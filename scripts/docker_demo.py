#!/usr/bin/env python3
"""Build/start the Docker stack and verify it without deleting or resetting volumes."""
import argparse
import csv
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from urllib.request import urlopen

import local_demo as demo

ROOT = Path(__file__).resolve().parents[1]


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, **kwargs)


def save_diagnostics(directory):
    with (directory / "containers.jsonl").open("w") as output:
        compose("ps", "-a", "--format", "json", stdout=output)
    with (directory / "containers.log").open("w") as output:
        compose("logs", "--no-color", "--tail", "150", stdout=output, stderr=subprocess.STDOUT)


def verify_projection():
    demo.verify_db()
    # Check every cached account against the authoritative engine read path.
    balances = demo.request(8082, "/v1/balances")
    assert balances and sum(row["balance_minor"] for row in balances) == 0, balances
    for row in balances:
        account = demo.request(8080, "/v1/accounts/" + row["account_id"])
        assert account["balance_minor"] == row["balance_minor"], (row, account)
    denied = compose("exec", "-T", "-e", "PGPASSWORD=ledgerline_readonly", "postgres", "psql", "-h", "127.0.0.1",
                     "-U", "ledgerline_ai_readonly", "-d", "ledgerline_java", "-v", "ON_ERROR_STOP=1", "-c",
                     "DELETE FROM account_balances WHERE false", capture_output=True, text=True)
    assert denied.returncode != 0 and "permission denied" in denied.stderr, denied.stderr
    report = compose("exec", "-T", "reporting", "java", "-jar", "/app/service.jar", "--report", capture_output=True, text=True)
    if report.returncode != 0:
        raise RuntimeError("Reporting CLI failed: " + report.stdout + report.stderr)
    reports = [json.loads(line) for line in report.stdout.splitlines() if line.startswith('{"balanced"')]
    assert reports and reports[-1]["balanced"], report.stdout
    print("PASS: Docker reporting CLI, engine/cache agreement, zero-sum balances, and database-enforced read-only role", flush=True)
    period = demo.request(8086, "/v1/reports/trial-balance?from=1900-01-01&to=1900-01-01")
    assert period["balanced"] and period["rows"] == [], period
    for format, expected in [("csv", "currency,gl_account,"), ("table", "RECONCILIATION: PASS")]:
        with urlopen(f"http://127.0.0.1:8086/v1/reports/trial-balance?format={format}", timeout=10) as response:
            assert expected in response.read().decode()
            assert response.headers["X-Ledgerline-Projection-Scope"] == "all-history"
    csv_report = compose("exec", "-T", "reporting", "java", "-jar", "/app/service.jar", "--report", "--format=csv", capture_output=True, text=True)
    assert csv_report.returncode == 0, csv_report.stderr
    assert len(list(csv.reader(io.StringIO(csv_report.stdout)))) == len(reports[-1]["rows"]) + 1
    print("PASS: Docker date-filtered reports, CSV/table HTTP exports, and clean CSV reporting CLI", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true", help="Use images already built locally")
    parser.add_argument("--check", action="store_true", help="Check a previously seeded demo without creating accounts or transfers")
    parser.add_argument("--resilience", action="store_true", help="Also stop the leader and restart the Raft cluster/cache (fresh demo only)")
    args = parser.parse_args()
    if args.check and args.resilience:
        parser.error("--resilience is supported only when seeding a fresh demo")
    (ROOT / ".run").mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="docker-", dir=ROOT / ".run"))
    print(f"Docker diagnostics: {directory}", flush=True)
    try:
        command = ["up", "-d", "--wait", "--wait-timeout", "240"]
        if not args.no_build:
            command.append("--build")
        compose(*command, check=True)
        for i in range(1, 4):
            demo.wait(8000 + i, "/v1/leader", timeout=180)
        for port in [8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 5180]:
            demo.wait(port, timeout=180)
        if not args.check:
            if demo.request(8001, "/v1/events"):
                raise RuntimeError("Ledger is not empty. Refusing to reset data. Use --check for an already seeded demo, or a separately configured fresh deployment.")
            demo.seed()
        verify_projection()
        if args.resilience:
            # Reuse the same assertions as the local-process run, but control containers.
            demo.stop = lambda name: compose("stop", "-t", "10", name.replace("ledgerline-", ""), check=True)
            demo.restart = lambda name: compose("start", name.replace("ledgerline-", ""), check=True)
            try:
                demo.verify_resilience()
            finally:
                # Restore deliberately stopped containers even if an assertion fails.
                compose("start", "raft-n1", "raft-n2", "raft-n3", "balance-reader", check=False)
            verify_projection()
        compose("up", "-d", "--wait", "--wait-timeout", "120", check=True)
        print("PASS: Docker end-to-end demo. Containers and data are retained.", flush=True)
        print("Admin console: http://localhost:5180\nStop without deleting data: docker compose stop", flush=True)
    finally:
        save_diagnostics(directory)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
