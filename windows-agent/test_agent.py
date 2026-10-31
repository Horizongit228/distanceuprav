"""Safety-focused smoke tests that run on the GitHub Windows runner.
No real shutdown, restart, or window termination is performed.
"""
import base64
from contextlib import ExitStack
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

import agent


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.patchers = ExitStack()
        base = Path(self.temp.name)
        for key, name in (
            ("DATA_DIR", base),
            ("CONFIG_FILE", base / "config.json"),
            ("CERT_FILE", base / "agent.crt"),
            ("KEY_FILE", base / "agent.key")):
            self.patchers.enter_context(patch.object(agent, key, name))

    def tearDown(self):
        self.patchers.close()
        self.temp.cleanup()

    def test_identity_persistent_and_pairing_format(self):
        token, fp = agent.load_identity()
        token2, fp2 = agent.load_identity()
        self.assertEqual(token, token2)
        self.assertEqual(fp, fp2)
        self.assertEqual(len(fp), 64)
        url = agent.pairing_uri("192.168.1.23", token, fp)
        self.assertTrue(url.startswith("distanceuprav://pair?data="))
        data = url.split("data=", 1)[1]
        obj = json.loads(base64.urlsafe_b64decode(data + "=" * (-len(data) % 4)))
        self.assertEqual(obj["ip"], "192.168.1.23")
        self.assertEqual(obj["token"], token)
        self.assertEqual(obj["fp"], fp)

    def test_https_requires_bearer_and_serves_status(self):
        token, _ = agent.load_identity()
        from http.server import ThreadingHTTPServer
        server = ThreadingHTTPServer(("127.0.0.1", 0), agent.Handler)
        server.auth_token = token
        server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(agent.CERT_FILE), str(agent.KEY_FILE))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        runner = threading.Thread(target=server.serve_forever, daemon=True)
        runner.start()
        url = "https://127.0.0.1:" + str(server.server_address[1]) + "/v1/status"
        context = ssl._create_unverified_context()  # ONLY inside this local loopback test
        try:
            with self.assertRaises(urllib.error.HTTPError) as err:
                urllib.request.urlopen(url, timeout=3, context=context)
            self.assertEqual(err.exception.code, 401)
            request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
            with urllib.request.urlopen(request, timeout=3, context=context) as response:
                data = json.load(response)
            self.assertEqual(data["version"], agent.VERSION)
            self.assertTrue(data["ok"])
        finally:
            server.shutdown()
            server.server_close()


    def test_physical_lan_is_preferred_over_virtual(self):
        class FakeCompleted:
            returncode = 0
            stdout = json.dumps([
                {"ip": "172.19.0.1", "interface": "vEthernet (WSL)",
                 "description": "Hyper-V Virtual Ethernet Adapter",
                 "physical": False, "gateway": False},
                {"ip": "192.168.0.105", "interface": "Ethernet",
                 "description": "Intel(R) Ethernet Controller",
                 "physical": True, "gateway": True},
            ])
        with patch.object(agent.subprocess, "run", return_value=FakeCompleted()):
            self.assertEqual(agent.choose_lan_ip(), "192.168.0.105")

    def test_invalid_lan_addresses(self):
        self.assertFalse(agent._valid_lan_ipv4("127.0.0.1"))
        self.assertFalse(agent._valid_lan_ipv4("169.254.1.2"))
        self.assertFalse(agent._valid_lan_ipv4("8.8.8.8"))
        self.assertTrue(agent._valid_lan_ipv4("192.168.0.105"))


    def test_window_tracker_records_opened_and_closed_events(self):
        tracker = agent.WindowTracker()
        first = {"hwnd": "42", "pid": 12, "title": "Editor", "process": "editor.exe", "elevated": False}
        next_window = {"hwnd": "43", "pid": 13, "title": "Notepad", "process": "notepad.exe", "elevated": False}
        tracker.update([first], now=100)
        self.assertEqual(tracker.snapshot()["recently_closed"], [])
        tracker.update([first, next_window], now=102)
        self.assertEqual(tracker.snapshot()["recently_opened"][0]["title"], "Notepad")
        tracker.update([next_window], now=104)
        snapshot = tracker.snapshot()
        self.assertEqual(snapshot["recently_closed"][0]["title"], "Editor")
        self.assertEqual(snapshot["recently_closed"][0]["time"], 104)
        self.assertEqual(snapshot["revision"], 2)

    def test_elevated_window_does_not_bypass_uac(self):
        target = {"hwnd": "a1", "pid": 1234, "title": "Administrator: CMD",
                  "process": "cmd.exe", "elevated": True}
        with patch.object(agent, "window_list", return_value=[target]):
            with patch.object(agent, "agent_elevated", return_value=False):
                with self.assertRaises(PermissionError) as context:
                    agent.close_window("a1", 1234)
                self.assertIn("Windows UAC", str(context.exception))

    def test_window_validation_rejects_malformed_input(self):
        with self.assertRaises(ValueError):
            agent.close_window("not-hex", 100)
        with self.assertRaises(ValueError):
            agent.close_window("1", "100")

    def test_power_does_not_force_close_applications(self):
        class FakeTimer:
            def __init__(self, delay, func, args):
                self.delay, self.func, self.args = delay, func, args
                self.daemon = False
            def start(self): pass
            def cancel(self): pass

        with patch.object(agent.threading, "Timer", FakeTimer):
            action = agent.schedule_power("restart")
            self.assertEqual(action["seconds"], 10)
            self.assertTrue(agent.cancel_power()["cancelled"])
            self.assertFalse(agent.cancel_power()["cancelled"])

        with patch.object(agent.subprocess, "Popen") as popen:
            agent.run_power("restart")
            argv = popen.call_args.args[0]
            self.assertEqual(argv, ["shutdown.exe", "/r", "/t", "0"])
            self.assertNotIn("/f", argv)


if __name__ == "__main__":
    unittest.main()
