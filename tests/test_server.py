"""Network regression tests. No real keyboard events are sent."""

import http.client
import json
from pathlib import Path
import socket
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.app = server.PageFlipApp(dry_run=True, token="123456")
        handler = type("TestHandler", (server.Handler,), {"app": self.app})
        self.httpd = server.FastHTTPServer(("127.0.0.1", 0), handler)
        self.port = self.httpd.server_port
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, data=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        try:
            body = json.dumps(data) if data is not None else None
            conn.request(method, path, body, headers or {})
            response = conn.getresponse()
            raw = response.read()
            return response.status, response.headers, json.loads(raw) if raw else None
        finally:
            conn.close()

    def command(self, data):
        return self.request("POST", "/api/cmd", data, {"X-PF-Token": "123456"})

    def test_pair_allows_loopback_hosts_and_matching_origin(self):
        for host in ("127.0.0.1", "localhost"):
            authority = f"{host}:{self.port}"
            with self.subTest(host=host):
                status, headers, data = self.request("GET", "/api/pair", headers={
                    "Host": authority, "Origin": f"http://{authority}"})
                self.assertEqual(status, 200)
                self.assertEqual(data["token"], "123456")
                self.assertNotIn("Access-Control-Allow-Origin", headers)

    def test_pair_rejects_rebinding_host_even_with_matching_origin(self):
        authority = f"attacker.example:{self.port}"
        status, headers, data = self.request("GET", "/api/pair", headers={
            "Host": authority, "Origin": f"http://{authority}"})
        self.assertEqual(status, 403)
        self.assertNotIn("token", data)
        self.assertNotIn("Access-Control-Allow-Origin", headers)

    def test_pair_rejects_wrong_port_and_cross_origin(self):
        for headers in ({"Host": "localhost:1"},
                        {"Host": f"localhost:{self.port}", "Origin": "http://evil.example"},
                        {"Host": f"evil@localhost:{self.port}"},
                        {"Host": f"localhost:{self.port}/path"}):
            with self.subTest(headers=headers):
                status, _, data = self.request("GET", "/api/pair", headers=headers)
                self.assertEqual(status, 403)
                self.assertNotIn("token", data)

    def test_commands_require_auth_and_head_never_fires(self):
        status, _, _ = self.request("POST", "/api/cmd", {"action": "next"})
        self.assertEqual(status, 403)
        status, _, _ = self.request("GET", "/next")
        self.assertEqual(status, 403)
        status, _, _ = self.request("HEAD", "/next?t=123456")
        self.assertEqual(status, 405)
        self.assertEqual(self.app.total, 0)

    def test_non_ascii_pairing_attempt_is_rejected_without_crashing(self):
        status, _, data = self.request("GET", "/api/verify?t=%E4%B8%AD%E6%96%87")
        self.assertEqual(status, 403)
        self.assertFalse(data["ok"])

    def test_out_of_range_json_numbers_do_not_crash_handler(self):
        with mock.patch.object(self.app, "fire", return_value=True) as fire:
            status, _, data = self.command({"action": "next", "repeat": float("inf"),
                                            "s": float("inf")})
            self.assertEqual(status, 200)
            self.assertTrue(data["ok"])
            fire.assert_called_once_with("next", 1)

    def test_rejected_post_closes_unread_body_connection(self):
        for path, headers, status in (
            ("/api/cmd", {}, 403),
            ("/missing", {}, 404),
            ("/api/cmd", {"X-PF-Token": "123456", "Content-Length": "bad"}, 400),
            ("/api/cmd", {"X-PF-Token": "123456", "Content-Length": "4097"}, 400),
        ):
            with self.subTest(path=path, headers=headers):
                conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
                try:
                    conn.request("POST", path, '{"action":"next"}', headers)
                    response = conn.getresponse()
                    response.read()
                    self.assertEqual(response.status, status)
                    self.assertTrue(response.will_close)
                    # http.client safely reconnects after the rejected request.
                    conn.request("GET", "/api/verify?t=123456")
                    response = conn.getresponse()
                    self.assertEqual(response.status, 200)
                    self.assertTrue(json.loads(response.read())["ok"])
                finally:
                    conn.close()

    def test_http_retry_replays_failure_without_executing_again(self):
        data = {"action": "next", "c": "failure-client", "s": 1}
        with mock.patch.object(self.app, "fire", return_value=False) as fire:
            self.assertFalse(self.command(data)[2]["ok"])
            retry = self.command(data)[2]
            self.assertFalse(retry["ok"])
            self.assertTrue(retry["dup"])
            fire.assert_called_once_with("next", 1)

    def test_udp_then_http_retry_does_not_turn_two_pages(self):
        udp = server.UdpServer(self.app, 0)
        udp.start()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(3)
        try:
            destination = ("127.0.0.1", udp.sock.getsockname()[1])
            sock.sendto(b"PFD1 123456 test-client 7 next", destination)
            self.assertEqual(sock.recvfrom(512)[0], b"PFD1 ack 7 ok")
            retry = self.command({"action": "next", "c": "test-client", "s": 7})[2]
            self.assertTrue(retry["ok"])
            self.assertTrue(retry["dup"])
            self.assertEqual(self.app.total, 1)
        finally:
            sock.close()
            udp.stop()
            udp.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
