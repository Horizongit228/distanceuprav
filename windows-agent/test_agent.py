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
