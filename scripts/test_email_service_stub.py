#!/usr/bin/env python3

from http.client import HTTPConnection
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parent))
from email_service_stub import build_server  # noqa: E402


class EmailServiceStubContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.api_key = "stub-contract-key"
        cls.server = build_server("127.0.0.1", 0, cls.api_key)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.host, cls.port = cls.server.server_address

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_health_requires_the_configured_api_key(self) -> None:
        status, body = self.request("GET", "/api/email/health")
        self.assertEqual(401, status)
        self.assertEqual("UNAUTHORIZED", body["error"])

        status, body = self.request(
            "GET",
            "/api/email/health",
            api_key=self.api_key,
        )
        self.assertEqual(200, status)
        self.assertEqual({"status": "UP"}, body)

    def test_repeated_api_key_headers_are_rejected(self) -> None:
        for api_keys in (
            (self.api_key, self.api_key),
            (self.api_key, "wrong"),
            ("wrong", self.api_key),
        ):
            with self.subTest(api_keys=api_keys):
                connection = HTTPConnection(self.host, self.port, timeout=5)
                try:
                    connection.putrequest("GET", "/api/email/health")
                    for api_key in api_keys:
                        connection.putheader("X-Email-Service-Key", api_key)
                    connection.endheaders()
                    response = connection.getresponse()
                    body = json.loads(response.read())
                finally:
                    connection.close()

                self.assertEqual(401, response.status)
                self.assertEqual("UNAUTHORIZED", body["error"])

    def test_responses_disable_cache_and_content_sniffing(self) -> None:
        status, body, headers = self.request_with_headers(
            "GET",
            "/api/email/health",
            api_key=self.api_key,
        )
        self.assertEqual(200, status)
        self.assertEqual({"status": "UP"}, body)
        self.assertEqual("no-store", headers["cache-control"])
        self.assertEqual("no-cache", headers["pragma"])
        self.assertEqual("nosniff", headers["x-content-type-options"])

        status, body, headers = self.request_with_headers(
            "GET",
            "/api/email/not-found",
            api_key=self.api_key,
        )
        self.assertEqual(404, status)
        self.assertEqual("NOT_FOUND", body["error"])
        self.assertEqual("no-store", headers["cache-control"])
        self.assertEqual("no-cache", headers["pragma"])
        self.assertEqual("nosniff", headers["x-content-type-options"])

        status, body, headers = self.request_with_headers(
            "POST",
            "/api/email/template",
            {},
            self.api_key,
        )
        self.assertEqual(400, status)
        self.assertEqual("INVALID_EMAIL", body["error"])
        self.assertEqual("no-store", headers["cache-control"])
        self.assertEqual("no-cache", headers["pragma"])
        self.assertEqual("nosniff", headers["x-content-type-options"])

        status, body, headers = self.request_with_headers(
            "GET",
            "/api/email/health",
        )
        self.assertEqual(401, status)
        self.assertEqual("UNAUTHORIZED", body["error"])
        self.assertEqual("no-store", headers["cache-control"])
        self.assertEqual("no-cache", headers["pragma"])
        self.assertEqual("nosniff", headers["x-content-type-options"])

    def test_template_request_returns_an_opaque_queue_id(self) -> None:
        status, body = self.request(
            "POST",
            "/api/email/template",
            {
                "to": "accepted@example.test",
                "subject": "Verify",
                "templateName": "email/email-verify",
                "variables": {"verificationCode": "123456"},
                "emailType": "VERIFICATION",
            },
            self.api_key,
        )
        self.assertEqual(200, status)
        self.assertTrue(body["success"])
        self.assertIsInstance(body["queueId"], int)
        self.assertNotIn("variables", body)
        self.assertNotIn("verificationCode", json.dumps(body))

    def test_optional_capture_file_records_an_accepted_template_request(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            capture_file = Path(directory) / "mailbox.jsonl"
            capture_file.write_text("stale data\n", encoding="utf-8")
            capture_file.chmod(0o644)
            server = build_server(
                "127.0.0.1",
                0,
                self.api_key,
                capture_file,
            )
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            host, port = server.server_address
            previous_host, previous_port = self.host, self.port
            self.host, self.port = host, port
            try:
                status, body = self.request(
                    "POST",
                    "/api/email/template",
                    {
                        "to": "capture@example.test",
                        "subject": "Verify",
                        "templateName": "email/email-verify",
                        "variables": {"verificationCode": "654321"},
                        "emailType": "VERIFICATION",
                    },
                    self.api_key,
                )
            finally:
                self.host, self.port = previous_host, previous_port
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)

            self.assertEqual(200, status)
            captured = json.loads(capture_file.read_text(encoding="utf-8"))
            self.assertEqual(body["queueId"], captured["queueId"])
            self.assertEqual("capture@example.test", captured["to"])
            self.assertEqual("email/email-verify", captured["templateName"])
            self.assertEqual(
                "654321",
                captured["variables"]["verificationCode"],
            )
            self.assertEqual(0o600, capture_file.stat().st_mode & 0o777)

    def test_rejected_recipient_returns_a_failed_acceptance(self) -> None:
        status, body = self.request(
            "POST",
            "/api/email/template",
            {"to": "rejected-contract@example.test"},
            self.api_key,
        )
        self.assertEqual(503, status)
        self.assertFalse(body["success"])

    def test_rate_limited_recipient_returns_429(self) -> None:
        status, body = self.request(
            "POST",
            "/api/email/template",
            {"to": "rate-limited-contract@example.test"},
            self.api_key,
        )
        self.assertEqual(429, status)
        self.assertEqual("RATE_LIMITED", body["error"])

    def test_malformed_json_fails_closed(self) -> None:
        connection = HTTPConnection(self.host, self.port, timeout=5)
        try:
            connection.request(
                "POST",
                "/api/email/template",
                body=b"{",
                headers={
                    "Content-Type": "application/json",
                    "Content-Length": "1",
                    "X-Email-Service-Key": self.api_key,
                },
            )
            response = connection.getresponse()
            body = json.loads(response.read())
        finally:
            connection.close()

        self.assertEqual(400, response.status)
        self.assertEqual("INVALID_REQUEST", body["error"])

    def test_chunked_template_request_matches_the_runtime_client_contract(self) -> None:
        payload = json.dumps({
            "to": "chunked@example.test",
            "subject": "Verify",
            "templateName": "email/email-verify",
            "variables": {"verificationCode": "123456"},
            "emailType": "VERIFICATION",
        }).encode("utf-8")
        connection = HTTPConnection(self.host, self.port, timeout=5)
        try:
            connection.request(
                "POST",
                "/api/email/template",
                body=[payload[:20], payload[20:]],
                headers={
                    "Content-Type": "application/json",
                    "X-Email-Service-Key": self.api_key,
                },
                encode_chunked=True,
            )
            response = connection.getresponse()
            body = json.loads(response.read())
        finally:
            connection.close()

        self.assertEqual(200, response.status)
        self.assertTrue(body["success"])

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        api_key: str | None = None,
    ) -> tuple[int, dict[str, object]]:
        status, response_body, _ = self.request_with_headers(
            method,
            path,
            payload,
            api_key,
        )
        return status, response_body

    def request_with_headers(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        api_key: str | None = None,
    ) -> tuple[int, dict[str, object], dict[str, str]]:
        connection = HTTPConnection(self.host, self.port, timeout=5)
        headers: dict[str, str] = {}
        body = None
        if api_key is not None:
            headers["X-Email-Service-Key"] = api_key
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
            headers["Content-Length"] = str(len(body))
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            response_body = json.loads(response.read())
            response_headers = {
                key.lower(): value
                for key, value in response.getheaders()
            }
            return response.status, response_body, response_headers
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
