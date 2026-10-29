#!/usr/bin/env python3
"""Exercise the parity features in packaged Java services against the seeded Docker demo DB.

Uses dynamically assigned loopback ports and SELECT-only DB credentials. Does not
stop Docker services or post ledger transfers. FX network calls go only to a local fixture.
"""
import argparse
import atexit
import csv
import http.server
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.error import HTTPError
from urllib.request import urlopen

import local_demo as demo

ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def eventually(check, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.1)
    raise AssertionError("Timed out waiting for feature assertion")


class RateFixture:
    def __init__(self):
        self.lock = threading.Lock()
        self.status = 200
        self.payload = {"result": "success", "base_code": "USD", "rates": {"USD": 1, "EUR": 0.8}}
        self.requests = 0
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                with fixture.lock:
                    status, payload = fixture.status, json.dumps(fixture.payload).encode()
                    fixture.requests += 1
                self.send_response(status)
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def set(self, status, payload):
        with self.lock:
            self.status, self.payload = status, payload
            return self.requests

    def count(self):
        with self.lock:
            return self.requests

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--db-url", default=os.environ.get("DB_URL", "jdbc:postgresql://localhost:55432/ledgerline_java"))
    parser.add_argument("--db-user", default=os.environ.get("DB_USER", "ledgerline_ai_readonly"))
    parser.add_argument("--db-password", default=os.environ.get("DB_PASSWORD", "ledgerline_readonly"))
    args = parser.parse_args()
    if not args.no_build:
        subprocess.run(["mvn", "-B", "package"], cwd=ROOT, check=True)
    (ROOT / ".run").mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="features-", dir=ROOT / ".run"))
    print(f"Feature verification logs: {directory}", flush=True)
    atexit.register(demo.cleanup)
    fixture = RateFixture()
    try:
        fx = port()
        demo.launch("ledgerline-fx", fx, directory, {"FX_REFRESH_ENABLED": "true", "FX_REFRESH_INTERVAL": "200ms",
            "FX_REFRESH_TIMEOUT": "2s", "FX_REFRESH_ENDPOINT": f"http://127.0.0.1:{fixture.server.server_port}/rates"})
        demo.wait(fx)
        eventually(lambda: demo.request(fx, "/v1/rates/USD/EUR")["rate"] == 0.8)
        assert demo.request(fx, "/healthz")["refresh_enabled"] is True
        for status, payload in [(503, {}), (200, {"result": "success", "base_code": "USD", "rates": {"USD": 2, "EUR": 0.5}})]:
            previous = fixture.set(status, payload)
            eventually(lambda: fixture.count() >= previous + 2)
            snapshot = demo.request(fx, "/v1/rates/USD/EUR")
            previous = fixture.count()
            eventually(lambda: fixture.count() >= previous + 2)
            assert demo.request(fx, "/v1/rates/USD/EUR") == snapshot
            assert snapshot["rate"] == 0.8
        fixture.set(200, {"result": "success", "base_code": "USD", "rates": {"USD": 1, "EUR": 0.85}})
        eventually(lambda: demo.request(fx, "/v1/rates/USD/EUR")["rate"] == 0.85)
        assert demo.request(fx, "/v1/convert", {"base": "USD", "quote": "EUR", "amount_minor": 100})["converted_amount_minor"] == 85
        print("PASS: live HTTP FX refresh, failed fetch/validation retain rates and timestamp, recovery, exact conversion", flush=True)

        db = {"DB_URL": args.db_url, "DB_USER": args.db_user, "DB_PASSWORD": args.db_password}
        policy = directory / "policy.yaml"
        policy.write_text("""fields:
  cost_center: {classification: internal_sensitive, action: mask}
  amount_minor: {classification: internal_sensitive, action: redact}
  account_id: {classification: internal_sensitive, action: mask}
""")
        ai = port()
        demo.launch("ledgerline-ai-gateway", ai, directory, dict(db, REDACTION_POLICY=policy.as_uri()))
        demo.wait(ai)
        result = demo.request(ai, "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
            "name": "get_journal_entry", "arguments": {"transfer_dedup_id": "demo-payout-fee"}}})["result"]
        assert not result["isError"], result
        entries = json.loads(result["content"][0]["text"])["entries"]
        assert len(entries) == 2, entries
        assert all("amount_minor" not in row and "..." in row["cost_center"] for row in entries), entries
        result = demo.request(ai, "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
            "name": "get_account_balance", "arguments": {"account_id": "alice"}}})["result"]
        assert json.loads(result["content"][0]["text"])["account_id"] == "*****", result
        print("PASS: external YAML policy affects real MCP journal/balance queries; omitted sensitive defaults stay protected", flush=True)

        reporting = port()
        demo.launch("ledgerline-reporting", reporting, directory, db)
        demo.wait(reporting)
        report = demo.request(reporting, "/v1/reports/trial-balance")
        assert report["balanced"] and report["rows"], report
        empty = demo.request(reporting, "/v1/reports/trial-balance?from=1900-01-01&to=1900-01-01")
        assert empty["balanced"] and empty["rows"] == [], empty
        for format, expected in [("csv", "currency,gl_account,"), ("table", "RECONCILIATION: PASS")]:
            with urlopen(f"http://127.0.0.1:{reporting}/v1/reports/trial-balance?format={format}", timeout=10) as response:
                assert expected in response.read().decode()
                assert response.headers["X-Ledgerline-Reconciliation"] == "pass"
        try:
            demo.request(reporting, "/v1/reports/trial-balance?from=2026-02-30")
            raise AssertionError("Invalid report date was accepted")
        except HTTPError as error:
            assert error.code == 400
        jar = directory / "jars/ledgerline-reporting.jar"
        command = ["java", "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar", str(jar), "--report"]
        env = dict(os.environ, **db)
        output = directory / "trial.csv"
        csv_run = subprocess.run(command + ["--format=csv", f"--output={output}"], env=env, capture_output=True, text=True, timeout=30)
        assert csv_run.returncode == 0 and not csv_run.stdout, csv_run.stderr
        assert len(list(csv.reader(io.StringIO(output.read_text())))) == len(report["rows"]) + 1
        json_run = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
        assert json_run.returncode == 0 and json.loads(json_run.stdout)["balanced"], json_run.stderr
        invalid = subprocess.run(command + ["--from=bad"], env=dict(env, DB_URL="jdbc:postgresql://invalid.invalid/no_db"), capture_output=True, text=True, timeout=15)
        assert invalid.returncode == 2 and not invalid.stdout, invalid.stderr
        print("PASS: real PostgreSQL date-filtered reports, JSON/CSV/table HTTP, CLI CSV file export, clean stdout, invalid-date exit code", flush=True)
    finally:
        demo.cleanup()
        fixture.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
