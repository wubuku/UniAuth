#!/usr/bin/env python3

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import secrets
import threading
from typing import Any


MAX_REQUEST_BYTES = 64 * 1024


class EmailStubServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], api_key: str):
        super().__init__(address, EmailStubHandler)
        self.api_key = api_key
        self._queue_id = 0
        self._queue_lock = threading.Lock()

    def next_queue_id(self) -> int:
        with self._queue_lock:
            self._queue_id += 1
            return self._queue_id


class EmailStubHandler(BaseHTTPRequestHandler):
    server: EmailStubServer

    def do_GET(self) -> None:
        if not self._authorize():
            return
        if self.path != "/api/email/health":
            self._json_response(404, {"error": "NOT_FOUND"})
            return
        self._json_response(200, {"status": "UP"})

    def do_POST(self) -> None:
        if not self._authorize():
            return
        if self.path not in {"/api/email/template", "/api/email/simple"}:
            self._json_response(404, {"error": "NOT_FOUND"})
            return

        payload = self._read_json_object()
        if payload is None:
            return

        recipient = payload.get("to")
        if not isinstance(recipient, str) or not recipient:
            self._json_response(400, {"success": False, "error": "INVALID_EMAIL"})
            return
        if recipient.startswith("rate-limited-"):
            self._json_response(429, {"success": False, "error": "RATE_LIMITED"})
            return
        if recipient.startswith("rejected-"):
            self._json_response(503, {"success": False, "error": "REJECTED"})
            return

        self._json_response(
            200,
            {"success": True, "queueId": self.server.next_queue_id()},
        )

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _authorize(self) -> bool:
        provided = self.headers.get("X-Email-Service-Key", "")
        if secrets.compare_digest(provided, self.server.api_key):
            return True
        self._json_response(401, {"error": "UNAUTHORIZED"})
        return False

    def _read_json_object(self) -> dict[str, Any] | None:
        body = self._read_request_body()
        if body is None:
            return None

        try:
            payload = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._json_response(400, {"success": False, "error": "INVALID_REQUEST"})
            return None
        if not isinstance(payload, dict):
            self._json_response(400, {"success": False, "error": "INVALID_REQUEST"})
            return None
        return payload

    def _read_request_body(self) -> bytes | None:
        content_length_header = self.headers.get("Content-Length")
        transfer_encoding = self.headers.get("Transfer-Encoding", "")
        if content_length_header is not None and transfer_encoding:
            self._invalid_request()
            return None

        if transfer_encoding:
            if transfer_encoding.lower().strip() != "chunked":
                self._invalid_request()
                return None
            return self._read_chunked_body()

        try:
            content_length = int(content_length_header or "0")
        except ValueError:
            self._invalid_request()
            return None
        if content_length <= 0 or content_length > MAX_REQUEST_BYTES:
            self._invalid_request()
            return None

        body = self.rfile.read(content_length)
        if len(body) != content_length:
            self._invalid_request()
            return None
        return body

    def _read_chunked_body(self) -> bytes | None:
        body = bytearray()
        while True:
            size_line = self.rfile.readline(128)
            if not size_line.endswith(b"\r\n"):
                self._invalid_request()
                return None
            size_token = size_line[:-2].split(b";", 1)[0].strip()
            try:
                chunk_size = int(size_token, 16)
            except ValueError:
                self._invalid_request()
                return None

            if chunk_size == 0:
                while True:
                    trailer = self.rfile.readline(8192)
                    if trailer == b"\r\n":
                        return bytes(body)
                    if not trailer or not trailer.endswith(b"\r\n"):
                        self._invalid_request()
                        return None

            if chunk_size < 0 or len(body) + chunk_size > MAX_REQUEST_BYTES:
                self._invalid_request()
                return None
            chunk = self.rfile.read(chunk_size)
            if len(chunk) != chunk_size or self.rfile.read(2) != b"\r\n":
                self._invalid_request()
                return None
            body.extend(chunk)

    def _invalid_request(self) -> None:
        self._json_response(400, {"success": False, "error": "INVALID_REQUEST"})

    def _json_response(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        print(f"{self.command} {self.path} {status}", flush=True)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def build_server(host: str, port: int, api_key: str) -> EmailStubServer:
    if not api_key:
        raise ValueError("api_key must not be empty")
    return EmailStubServer((host, port), api_key)


def main() -> None:
    parser = argparse.ArgumentParser(description="Disposable email REST contract stub")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", required=True, type=int)
    arguments = parser.parse_args()

    api_key = os.environ.get("EMAIL_STUB_API_KEY", "")
    server = build_server(arguments.host, arguments.port, api_key)
    print(f"READY {server.server_address[0]}:{server.server_address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
