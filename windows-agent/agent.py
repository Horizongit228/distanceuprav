"""DistanceUprav Windows agent v0.4.0.

Run in the interactive Windows desktop session (NOT as a background Windows service).
- HTTPS with an installation-specific self-signed certificate pinned on Android
- Per-install 256-bit bearer token conveyed by QR on the PC itself
- Visible top-level windows only; close uses WM_CLOSE, never taskkill/TerminateProcess
- Shutdown/restart WITHOUT /f and with /t 0 (any positive /t implies /f!)
"""
import base64
from collections import deque
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

VERSION = "0.4.2"
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


def _valid_lan_ipv4(address):
    try:
        ip = ipaddress.IPv4Address(address)
        return ip.is_private and not ip.is_loopback and not ip.is_link_local and not ip.is_multicast
    except ValueError:
        return False


def _score_adapter(row):
    """Physical NIC and default gateway take priority over Docker/WSL adapters."""
    combined = (str(row.get("interface", "")) + " " + str(row.get("description", ""))).lower()
    virtual = any(word in combined for word in (
        "vethernet", "hyper-v", "wsl", "docker", "virtual", "vmware",
        "virtualbox", "loopback", "tailscale", "zerotier", "vpn", "tunnel"))
    ip = ipaddress.IPv4Address(row["ip"])
    return ((100 if row.get("physical") else 0)
            + (40 if row.get("gateway") else 0)
            + (20 if ip in ipaddress.IPv4Network("192.168.0.0/16") else 0)
            + (10 if ip in ipaddress.IPv4Network("10.0.0.0/8") else 0)
            - (200 if virtual else 0))


def lan_addresses():
    """Discover Windows IPv4 interfaces, preferring physical home LAN over vEthernet."""
    items = []
    ps = r"""
$ErrorActionPreference = 'SilentlyContinue'
$physical = @(Get-NetAdapter -Physical | ForEach-Object { $_.ifIndex })
$rows = @(
    Get-NetIPConfiguration -All | ForEach-Object {
        $cfg = $_
        if ($cfg.NetAdapter -and $cfg.NetAdapter.Status -eq 'Up') {
            foreach ($address in @($cfg.IPv4Address)) {
                if ($address -and $address.IPAddress) {
                    [pscustomobject]@{
                        ip = [string]$address.IPAddress
                        interface = [string]$cfg.InterfaceAlias
                        description = [string]$cfg.InterfaceDescription
                        physical = ($cfg.InterfaceIndex -in $physical)
                        gateway = ($null -ne $cfg.IPv4DefaultGateway)
                    }
                }
            }
        }
    }
)
ConvertTo-Json -InputObject $rows -Compress -Depth 4
"""
    try:
        completed = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps],
            capture_output=True, text=True, timeout=12,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if completed.returncode == 0 and completed.stdout.strip():
            output = json.loads(completed.stdout.strip())
            for row in (output if isinstance(output, list) else [output]):
                if isinstance(row, dict) and _valid_lan_ipv4(row.get("ip", "")):
                    items.append(row)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        pass

    # Fallback if Windows networking cmdlets fail. Lower priority than physical NICs.
    addresses = []
    try:
        addresses.extend(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))  # no packet is sent
            addresses.append(probe.getsockname()[0])
    except OSError:
        pass
    for address in addresses:
        if _valid_lan_ipv4(address) and not any(row["ip"] == address for row in items):
            items.append({"ip": address, "interface": "Другой интерфейс",
                          "description": "Определён системой",
                          "physical": False, "gateway": False})
    items.sort(key=lambda row: (-_score_adapter(row), row["ip"]))
    return items


def choose_lan_ip():
    addresses = lan_addresses()
    return addresses[0]["ip"] if addresses else "127.0.0.1"


def pairing_uri(ip, token, fingerprint):
    payload = {"t": "distanceuprav-agent", "v": 1, "ip": ip,
               "port": PORT, "token": token, "fp": fingerprint}
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return "distanceuprav://pair?data=" + base64.urlsafe_b64encode(raw).decode().rstrip("=")



def agent_elevated():
    """The agent never elevates itself or bypasses Windows UAC."""
    if os.name != "nt":
        return False
    return bool(ctypes.windll.shell32.IsUserAnAdmin())


def process_elevated(pid):
    """Read the window process UAC elevation state without termination rights."""
    if os.name != "nt":
        return None
    kernel32 = ctypes.windll.kernel32
    advapi32 = ctypes.windll.advapi32
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    advapi32.OpenProcessToken.argtypes = [
        wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    advapi32.OpenProcessToken.restype = wintypes.BOOL
    advapi32.GetTokenInformation.argtypes = [
        wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
        wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    advapi32.GetTokenInformation.restype = wintypes.BOOL
    process = kernel32.OpenProcess(0x1000, False, pid)  # QUERY_LIMITED_INFORMATION
    if not process:
        return None
    token = wintypes.HANDLE()
    try:
        if not advapi32.OpenProcessToken(process, 0x0008, ctypes.byref(token)):
            return None
        elevated = wintypes.DWORD()
        size = wintypes.DWORD()
        # TokenElevation (20); TOKEN_ELEVATION.TokenIsElevated is DWORD.
        if not advapi32.GetTokenInformation(token, 20, ctypes.byref(elevated),
                                             ctypes.sizeof(elevated), ctypes.byref(size)):
            return None
        return bool(elevated.value)
    finally:
        if token:
            kernel32.CloseHandle(token)
        kernel32.CloseHandle(process)


class WindowTracker:
    """In-memory 2-second snapshots; closed history is not persisted to disk."""
    def __init__(self):
        self._lock = threading.RLock()
        self._current = {}
        self._history = deque(maxlen=50)
        self._started = False
        self._revision = 0

    def update(self, windows, now=None):
        stamp = int(time.time()) if now is None else now
        current = {(w["hwnd"], w["pid"]): dict(w) for w in windows}
        with self._lock:
            if self._started:
                for key, win in self._current.items():
                    if key not in current:
                        self._history.appendleft({**win, "event": "closed", "time": stamp})
                        self._revision += 1
                for key, win in current.items():
                    if key not in self._current:
                        self._history.appendleft({**win, "event": "opened", "time": stamp})
                        self._revision += 1
            self._started = True
            self._current = current

    def snapshot(self):
        with self._lock:
            return {
                "apps": list(self._current.values())[:150],
                "recently_closed": [
                    dict(e) for e in self._history if e["event"] == "closed"
                ][:20],
                "recently_opened": [
                    dict(e) for e in self._history if e["event"] == "opened"
                ][:20],
                "revision": self._revision,
                "refresh_seconds": 2,
            }


window_tracker = WindowTracker()


def monitor_windows():
    while True:
        try:
            window_tracker.update(window_list())
        except Exception:
            pass
        time.sleep(2)


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
        elevated = process_elevated(pid.value)
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
                       "pid": int(pid.value), "title": title[:250], "process": name[:100],
                       "elevated": elevated})
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
    if candidate.get("elevated") is True and not agent_elevated():
        raise PermissionError("Окно запущено от администратора. Windows UAC запрещает обычному агенту закрывать его. Для этой задачи потребуется вручную запустить агент с правами администратора на самом ПК.")
    hwnd = int(hwnd_text, 16)
    user32 = ctypes.windll.user32
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    if not user32.PostMessageW(hwnd, WM_CLOSE, 0, 0):
        raise PermissionError("Не удалось отправить запрос закрытия; может потребоваться доступ")
    return {"accepted": True, "message": "Запрос закрытия отправлен. Ожидай обновления списка; если окно осталось открытым, проверь диалог сохранения на ПК."}


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
                             "agent_elevated": agent_elevated(),
                             "pending_power": kind, "power_at": until})
        elif path == "/v1/apps":
            self.reply(200, window_tracker.snapshot())
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
    window_tracker.update(window_list())
    threading.Thread(target=monitor_windows, daemon=True).start()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main():
    if sys.platform != "win32":
        raise SystemExit("Агент запускается только на Windows 10/11")
    token, fingerprint = load_identity()
    server = start_server(token)
    choices = lan_addresses()

    import tkinter as tk
    from tkinter import ttk, messagebox
    import qrcode
    from PIL import ImageTk

    root = tk.Tk()
    root.title("DistanceUprav Agent 0.4.2")
    root.geometry("550x785")
    root.configure(bg="#111827")
    root.resizable(False, False)

    def label(value, size=11, color="#E5E7EB"):
        item = tk.Label(root, text=value, font=("Segoe UI", size), fg=color,
                        bg="#111827", wraplength=510, justify="center")
        item.pack(pady=5)
        return item

    label("DistanceUprav • Windows Agent", 18, "#FFFFFF")
    label("Выбери сетевой адрес ПК из той же сети, что и телефон.", 10, "#A5B4CF")
    label("Например, для телефона 192.168.0.36 адрес ПК обычно 192.168.0.x.",
          10, "#FCD34D")

    ip_by_option = {
        "{} — {}".format(row["ip"], row.get("interface", "Сеть")): row["ip"]
        for row in choices
    }
    selected = tk.StringVar(value=next(iter(ip_by_option), "Нет сетевых адаптеров"))
    picker = ttk.Combobox(root, textvariable=selected,
                          values=list(ip_by_option), state="readonly", width=53)
    picker.pack(pady=(8, 4))

    manual_frame = tk.Frame(root, bg="#111827")
    manual_frame.pack(pady=(4, 8))
    manual_ip = tk.StringVar(value=next(iter(ip_by_option.values()), ""))
    entry = tk.Entry(manual_frame, textvariable=manual_ip, font=("Segoe UI", 11),
                     width=19)
    entry.pack(side="left", padx=5)

    ip_label = label("IP: не выбран", 12, "#99F6E4")
    warning = label("QR-код пока не готов.", 10, "#FCD34D")
    qr_image = tk.Label(root, bg="#111827")
    qr_image.pack(pady=(0, 6))

    def update_qr(ip):
        if not _valid_lan_ipv4(ip):
            warning.configure(text="Укажи локальный IPv4 компьютера.")
            qr_image.configure(image="")
            qr_image.image = None
            return
        ip_label.configure(text="Адрес агента: {}   •   порт {}".format(ip, PORT))
        uri = pairing_uri(ip, token, fingerprint)
        qr = qrcode.QRCode(border=2, box_size=4,
                          error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(uri)
        qr.make(fit=True)
        photo = ImageTk.PhotoImage(
            qr.make_image(fill_color="black", back_color="white").convert("RGB"))
        qr_image.configure(image=photo)
        qr_image.image = photo
        warning.configure(text="Новый QR готов. Переподключи агент в Android.",
                          fg="#86EFAC")

    def choose(event=None):
        ip = ip_by_option.get(selected.get(), "")
        if ip:
            manual_ip.set(ip)
            update_qr(ip)

    def manual_apply():
        ip = manual_ip.get().strip()
        if not _valid_lan_ipv4(ip):
            messagebox.showerror("Неправильный IP", "Нужен локальный IPv4 адрес ПК.")
            return
        if choices and ip not in (row["ip"] for row in choices):
            ok = messagebox.askyesno(
                "IP не обнаружен",
                "Этот адрес не найден среди сетевых интерфейсов ПК. "
                "Продолжить только если это действительно IPv4 компьютера?")
            if not ok:
                return
        update_qr(ip)

    tk.Button(manual_frame, text="Применить IP", command=manual_apply,
              bg="#374151", fg="white", relief="flat", padx=9, pady=5).pack(side="left")
    picker.bind("<<ComboboxSelected>>", choose)
    choose()

    label("Секретный QR показывай только своему телефону. "
          "При выборе другого IP не нужно переустанавливать Windows-агент.",
          10, "#FCD34D")
    label("Сертификат SHA-256: " + fingerprint[:24] + "…",
          9, "#A5B4CF")
    label("Окна закрываются мягко. Несохранённые файлы могут требовать "
          "подтверждения на самом компьютере.", 10, "#C4CDDE")

    def stop():
        server.shutdown()
        root.destroy()

    tk.Button(root, text="Остановить агент", font=("Segoe UI", 11),
              command=stop, bg="#374151", fg="white",
              relief="flat", padx=20, pady=9).pack(pady=9)
    root.protocol("WM_DELETE_WINDOW", stop)
    root.mainloop()


if __name__ == "__main__":
    main()
