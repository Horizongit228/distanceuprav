"""DistanceUprav Windows agent v0.4.0.

Run in the interactive Windows desktop session (NOT as a background Windows service).
- HTTPS with an installation-specific self-signed certificate pinned on Android
- Per-install 256-bit bearer token conveyed by QR on the PC itself
- Visible top-level windows only; close uses WM_CLOSE, never taskkill/TerminateProcess
- Shutdown/restart WITHOUT /f and with /t 0 (any positive /t implies /f!)
"""
import base64
import ctypes
from ctypes import wintypes
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

VERSION = "0.4.0"
PORT = 8765
DATA_DIR = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "DistanceUpravAgent"
CONFIG_FILE = DATA_DIR / "config.json"
CERT_FILE = DATA_DIR / "agent.crt"
KEY_FILE = DATA_DIR / "agent.key"
MAX_BODY = 2048
WM_CLOSE = 0x0010
lock = threading.RLock()
pending = {"timer": None, "kind": None, "until": None}


def load_identity():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not CONFIG_FILE.exists():
        CONFIG_FILE.write_text(json.dumps({
            "token": secrets.token_urlsafe(32)
        }), encoding="utf-8")
    cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    token = cfg.get("token", "")
    if not isinstance(token, str) or len(token) < 32:
        raise RuntimeError("Неверный токен в конфигурации")
    if not CERT_FILE.exists() or not KEY_FILE.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "DistanceUprav Local Agent")])
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(datetime.now(timezone.utc) - timedelta(days=1))
            .not_valid_after(datetime.now(timezone.utc) + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
        KEY_FILE.write_bytes(key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
        CERT_FILE.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    certificate = x509.load_pem_x509_certificate(CERT_FILE.read_bytes())
    fingerprint = certificate.fingerprint(hashes.SHA256()).hex().upper()
    return token, fingerprint


def choose_lan_ip():
    found = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(("8.8.8.8", 80))  # no packets are actually sent
            found.append(probe.getsockname()[0])
        finally:
            probe.close()
    except OSError:
        pass
    try:
        found += socket.gethostbyname_ex(socket.gethostname())[2]
    except OSError:
        pass
    for address in found:
        try:
            ip = ipaddress.ip_address(address)
            if isinstance(ip, ipaddress.IPv4Address) and ip.is_private and not ip.is_loopback:
                return address
        except ValueError:
            pass
    return "127.0.0.1"


def pairing_uri(ip, token, fingerprint):
    payload = {"t": "distanceuprav-agent", "v": 1, "ip": ip,
               "port": PORT, "token": token, "fp": fingerprint}
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return "distanceuprav://pair?data=" + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def window_list():
    if os.name != "nt":
        return []
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    cbtype = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]
    user32.EnumWindows.argtypes = [cbtype, wintypes.LPARAM]
    result = []

    @cbtype
    def enumerate_window(hwnd, extra):
        if not user32.IsWindowVisible(hwnd) or user32.GetWindow(hwnd, 4):  # GW_OWNER
            return True
        size = user32.GetWindowTextLengthW(hwnd)
        if size < 1 or size > 10000:
            return True
        buffer = ctypes.create_unicode_buffer(size + 1)
        user32.GetWindowTextW(hwnd, buffer, len(buffer))
        title = buffer.value.strip()
        if not title:
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if not pid.value or pid.value == os.getpid():
            return True
        name = "PID " + str(pid.value)
        # Query only PROCESS_QUERY_LIMITED_INFORMATION; no PROCESS_TERMINATE privilege.
        kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel32.OpenProcess.restype = wintypes.HANDLE
        kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel32.OpenProcess(0x1000, False, pid.value)
        if handle:
            try:
                path = ctypes.create_unicode_buffer(32768)
                chars = wintypes.DWORD(len(path))
                kernel32.QueryFullProcessImageNameW.argtypes = [
                    wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
                if kernel32.QueryFullProcessImageNameW(handle, 0, path, ctypes.byref(chars)):
                    name = os.path.basename(path.value)
            finally:
                kernel32.CloseHandle(handle)
        result.append({"hwnd": format(int(hwnd), "x"),
                       "pid": int(pid.value), "title": title[:250], "process": name[:100]})
        return True

    user32.EnumWindows(enumerate_window, 0)
    return result[:150]


def close_window(hwnd_text, expected_pid):
    if not isinstance(hwnd_text, str) or not re.fullmatch(r"[0-9a-fA-F]{1,16}", hwnd_text):
        raise ValueError("Некорректный идентификатор окна")
    if type(expected_pid) is not int or not (1 <= expected_pid < (1 << 31)):
        raise ValueError("Некорректный PID")
    # Revalidate BOTH HWND and PID; reuse of window handles is possible.
    candidate = next((w for w in window_list()
                      if w["hwnd"].lower() == hwnd_text.lower() and w["pid"] == expected_pid), None)
    if candidate is None:
        raise ValueError("Окно уже закрыто или изменилось")
    hwnd = int(hwnd_text, 16)
    user32 = ctypes.windll.user32
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    if not user32.PostMessageW(hwnd, WM_CLOSE, 0, 0):
        raise PermissionError("Не удалось отправить запрос закрытия; может потребоваться доступ")
    return {"accepted": True, "message": "Запрошено обычное закрытие окна. Если есть несохранённые данные, программа может показать диалог сохранения на ПК."}


def run_power(kind):
    with lock:
        pending.update(timer=None, kind=None, until=None)
    # /t 0 prevents the implicit /f that occurs for any positive timeout.
    # Apps with unsaved changes can block Windows logoff/shutdown and prompt the PC user.
    subprocess.Popen(["shutdown.exe", "/s" if kind == "shutdown" else "/r", "/t", "0"],
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def schedule_power(kind):
    with lock:
        if pending["timer"] is not None:
            raise ValueError("Уже запланировано действие с питанием")
        timer = threading.Timer(10.0, run_power, args=(kind,))
        timer.daemon = True
        pending.update(timer=timer, kind=kind, until=int(time.time()) + 10)
        timer.start()
        return {"scheduled": kind, "seconds": 10,
                "message": "Через 10 секунд Windows начнёт безопасное завершение. /f не используется; несохранённые приложения могут остановить действие."}


def cancel_power():
    with lock:
        if pending["timer"] is None:
            return {"cancelled": False, "message": "Отложенного действия нет"}
        pending["timer"].cancel()
        pending.update(timer=None, kind=None, until=None)
        return {"cancelled": True}


class Handler(BaseHTTPRequestHandler):
    server_version = "DistanceUpravAgent"
    sys_version = ""

    def log_message(self, fmt, *args):
        # Do not log secrets, headers, URLs, or user data.
        pass

    def reply(self, status, data):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        supplied = self.headers.get("Authorization", "")
        if not supplied.startswith("Bearer ") or not secrets.compare_digest(supplied[7:], self.server.auth_token):
            self.reply(401, {"error": "Требуется корректная привязка"})
            return False
        # Do not serve untrusted web pages even if credentials are somehow exposed.
        if self.headers.get("Origin"):
            self.reply(403, {"error": "Browser origins are not supported"})
            return False
        try:
            ip = ipaddress.ip_address(self.client_address[0])
            if not (ip.is_private or ip.is_loopback):
                self.reply(403, {"error": "Только локальная сеть"})
                return False
        except ValueError:
            self.reply(403, {"error": "Некорректный адрес клиента"})
            return False
        return True

    def do_GET(self):
        if not self.authorized():
            return
        path = urlsplit(self.path).path
        if path == "/v1/status":
            with lock:
                kind = pending["kind"]
                until = pending["until"]
            self.reply(200, {"ok": True, "version": VERSION, "computer": socket.gethostname(),
                             "pending_power": kind, "power_at": until})
        elif path == "/v1/apps":
            self.reply(200, {"apps": window_list()})
        else:
            self.reply(404, {"error": "Неизвестная команда"})

    def do_POST(self):
        if not self.authorized():
            return
        length = self.headers.get("Content-Length", "")
        if not length.isdecimal() or int(length) > MAX_BODY:
            self.reply(413, {"error": "Недопустимый размер запроса"})
            return
        try:
            data = json.loads(self.rfile.read(int(length)).decode("utf-8")) if int(length) else {}
            if not isinstance(data, dict):
                raise ValueError("Тело запроса должно быть JSON-объектом")
            path = urlsplit(self.path).path
            if path == "/v1/apps/close":
                answer = close_window(data.get("hwnd"), data.get("pid"))
            elif path == "/v1/power/shutdown":
                answer = schedule_power("shutdown")
            elif path == "/v1/power/restart":
                answer = schedule_power("restart")
            elif path == "/v1/power/cancel":
                answer = cancel_power()
            else:
                self.reply(404, {"error": "Неизвестная команда"})
                return
            self.reply(200, answer)
        except (ValueError, PermissionError) as e:
            self.reply(400, {"error": str(e)})
        except Exception:
            self.reply(500, {"error": "Ошибка выполнения команды"})


def start_server(token):
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.auth_token = token
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(str(CERT_FILE), str(KEY_FILE))
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main():
    if sys.platform != "win32":
        raise SystemExit("Агент запускается только на Windows 10/11")
    token, fingerprint = load_identity()
    server = start_server(token)
    ip = choose_lan_ip()
    url = pairing_uri(ip, token, fingerprint)

    import tkinter as tk
    from tkinter import messagebox
    import qrcode
    from PIL import ImageTk

    root = tk.Tk()
    root.title("DistanceUprav Agent 0.4.0")
    root.geometry("540x760")
    root.configure(bg="#111827")
    root.resizable(False, False)

    def label(value, size=11, color="#E5E7EB"):
        widget = tk.Label(root, text=value, font=("Segoe UI", size), fg=color,
                          bg="#111827", justify="center", wraplength=475)
        widget.pack(pady=7)
        return widget

    label("DistanceUprav • Windows Agent", 19, "#FFFFFF")
    label("Работает в активном сеансе Windows. Только домашняя сеть.", 10, "#A5B4CF")
    label("IP: " + ip + "     Порт: " + str(PORT), 12, "#99F6E4")

    qr = qrcode.QRCode(border=2, box_size=4, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(url)
    qr.make(fit=True)
    photo = ImageTk.PhotoImage(qr.make_image(fill_color="black", back_color="white").convert("RGB"))
    box = tk.Label(root, image=photo, bg="#111827")
    box.image = photo
    box.pack(pady=12)
    label("Отсканируй код кнопкой «Подключить агент» в Android.\n"
          "QR содержит секрет доступа — никому его не отправляй.", 10, "#FCD34D")
    label("Сертификат (SHA-256): " + fingerprint[:24] + "…", 9, "#A5B4CF")
    label("Закрытие окон — только мягкое, с системными диалогами сохранения.\n"
          "Выключение и перезапуск могут быть заблокированы несохранёнными файлами.",
          10, "#C4CDDE")

    def exit_agent():
        server.shutdown()
        root.destroy()

    tk.Button(root, text="Остановить агент", font=("Segoe UI", 11),
              command=exit_agent, bg="#374151", fg="white", relief="flat",
              padx=20, pady=9).pack(pady=11)
    root.protocol("WM_DELETE_WINDOW", exit_agent)
    root.mainloop()


if __name__ == "__main__":
    main()
