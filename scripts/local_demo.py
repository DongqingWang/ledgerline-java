#!/usr/bin/env python3
"""Run an isolated stack; never kill unknown processes or reset an existing database."""
import argparse
import atexit
from decimal import Decimal, ROUND_HALF_UP, localcontext
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
PROCESSES = []
LOGS = []
SERVICES = {}
PGDATA = None


def request(port, path, body=None, *, parse_float=float):
    data = None if body is None else json.dumps(body).encode()
    req = Request(f"http://127.0.0.1:{port}{path}", data=data, headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=25) as response:
        return json.load(response, parse_float=parse_float)


def wait(port, path="/healthz", timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for name, process in PROCESSES:
            if process.poll() is not None:
                raise RuntimeError(f"{name} exited with code {process.returncode}; see logs")
        try:
            return request(port, path)
        except (URLError, TimeoutError, ConnectionError):
            time.sleep(0.25)
    raise RuntimeError(f"Timeout waiting for port {port}{path}")


def cleanup():
    for _, process in reversed(PROCESSES):
        if process.poll() is None:
            process.terminate()
    for _, process in reversed(PROCESSES):
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    if PGDATA is not None:
        subprocess.run(["pg_ctl", "-D", str(PGDATA), "-m", "fast", "stop"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for log in LOGS:
        log.close()


def launch(module, port, run_dir, env=None, name=None):
    name = name or module
    log = (run_dir / f"{name}.log").open("w")
    LOGS.append(log)
    # Keep running/restarted JVMs on immutable jars even if a developer rebuilds target/.
    jars = run_dir / "jars"
    jars.mkdir(exist_ok=True)
    jar = jars / f"{module}.jar"
    if not jar.exists():
        shutil.copy2(ROOT / module / "target" / f"{module}-0.1.0.jar", jar)
    command = ["java", "-Xms32m", "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar",
               str(jar), f"--server.port={port}"]
    merged = dict(os.environ, LEDGER_NODES="http://localhost:8001,http://localhost:8002,http://localhost:8003")
    merged.update(env or {})
    process = subprocess.Popen(command, cwd=run_dir, env=merged, stdout=log, stderr=subprocess.STDOUT)
    PROCESSES.append((name, process))
    SERVICES[name] = (module, port, run_dir, env, name)
    return process


def stop(name):
    for i, (service, process) in enumerate(PROCESSES):
        if service == name:
            PROCESSES.pop(i)
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            return
    raise RuntimeError(f"Unknown service {name}")


def restart(name):
    launch(*SERVICES[name])


def start_postgres(run_dir):
    global PGDATA
    for executable in ["initdb", "pg_ctl", "psql"]:
        if not shutil.which(executable):
            raise RuntimeError(f"--with-db requires PostgreSQL binaries ({executable} not found)")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    PGDATA = run_dir / "postgres"
    subprocess.run(["initdb", "-D", str(PGDATA), "--auth=trust", "--encoding=UTF8", "--no-locale"], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["pg_ctl", "-D", str(PGDATA), "-l", str(run_dir / "postgres.log"),
                    "-o", f"-h 127.0.0.1 -p {port} -k {run_dir}", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
    def sql(statement):
        subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(port), "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", statement], check=True, stdout=subprocess.DEVNULL)
    sql("CREATE ROLE ledgerline LOGIN; CREATE ROLE ledgerline_ai_readonly LOGIN;")
    sql("CREATE DATABASE ledgerline_java OWNER ledgerline;")
    return port


def seed():
    for account, unlimited in [("external-deposit", True), ("alice", False), ("bob", False), ("platform-fee-revenue", False)]:
        request(8080, "/v1/accounts", {"account_id": account, "currency": "USD", "unlimited": unlimited})
    request(8001, "/v1/transfers", {"dedup_id": "seed-alice", "from_account": "external-deposit", "to_account": "alice",
                                  "amount_minor": 100000, "currency": "USD", "gl_account": "1000", "cost_center": "CC-SEED"})
    transfer = {"dedup_id": "demo-payout", "from_account": "alice", "to_account": "bob", "amount_minor": 10000,
                "fee_minor": 250, "currency": "USD", "category": "seller_payout", "country": "US"}
    applied = request(8080, "/v1/transfers", transfer)
    duplicate = request(8080, "/v1/transfers", transfer)
    assert applied["status"] == "applied", applied
    assert duplicate["status"] == "duplicate", duplicate
    assert all(result["status"] == "duplicate" for result in duplicate["ledger_results"]), duplicate
    expected = {"external-deposit": -100000, "alice": 90000, "bob": 9750, "platform-fee-revenue": 250}
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        balances = request(8082, "/v1/balances")
        if {row["account_id"]: row["balance_minor"] for row in balances} == expected:
            break
        time.sleep(0.2)
    else:
        raise AssertionError(f"CQRS projection did not catch up: {balances}")
    for account, balance in expected.items():
        assert request(8080, f"/v1/accounts/{account}")["balance_minor"] == balance
    conversion = request(8083, "/v1/convert", {"base": "USD", "quote": "EUR", "amount_minor": 10000}, parse_float=Decimal)
    # Validate against this response's own exact rate, including when live refresh is enabled.
    with localcontext() as context:
        context.prec = 64
        expected_conversion = (Decimal(10000) * conversion["rate"]).quantize(Decimal(1), rounding=ROUND_HALF_UP)
    assert conversion["converted_amount_minor"] == expected_conversion, conversion
    assert request(8085, "/v1/routing")[0]["range_end"] == 4294967296
    # Validate a browser preflight, not just curl-style API access.
    req = Request("http://localhost:8080/v1/transfers", method="OPTIONS",
                  headers={"Origin": "http://localhost:5180", "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"})
    with urlopen(req, timeout=10) as response:
        assert response.headers["Access-Control-Allow-Origin"] == "http://localhost:5180"
    print("PASS: fee-split transfer, idempotency, exact balances, CQRS replay, FX, routing, and CORS", flush=True)


def verify_db():
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        report = request(8086, "/v1/reports/trial-balance")
        if report["rows"] and request(8084, "/healthz")["last_raft_index"] >= request(8082, "/healthz")["last_raft_index"]:
            break
        time.sleep(0.2)
    else:
        raise AssertionError("Database projection did not catch up")
    assert report["balanced"], report
    initialize = request(8087, "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "verify", "version": "1"}}})
    assert initialize["result"]["protocolVersion"] == "2025-03-26"
    listed = request(8087, "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    assert len(listed["result"]["tools"]) == 3
    result = request(8087, "/mcp", {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                   "params": {"name": "get_journal_entry", "arguments": {"transfer_dedup_id": "demo-payout-fee"}}})["result"]
    assert not result["isError"], result
    entries = json.loads(result["content"][0]["text"])["entries"]
    assert len(entries) == 2 and all("cost_center" not in row for row in entries), entries
    result = request(8087, "/mcp", {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                   "params": {"name": "search_transactions", "arguments": {"account_id": "alice", "limit": 9999}}})["result"]
    entries = json.loads(result["content"][0]["text"])["entries"]
    assert len(entries) <= 20 and all("cost_center" not in row and "..." in row["transfer_dedup_id"] for row in entries)
    print("PASS: real PostgreSQL projection, reconciliation, MCP initialize/list/call, redaction, and masking", flush=True)


def verify_resilience():
    leader = next(i for i in range(1, 4) if request(8000 + i, "/v1/healthz")["state"] == "LEADER")
    stop(f"raft-n{leader}")
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        elected = [i for i in range(1, 4) if i != leader and request(8000 + i, "/v1/healthz")["state"] == "LEADER"]
        if elected:
            break
        time.sleep(0.25)
    else:
        raise AssertionError("No replacement leader elected")
    transfer = {"dedup_id": "after-failover", "from_account": "external-deposit", "to_account": "bob", "amount_minor": 1,
                "currency": "USD", "category": "other", "country": "US"}
    assert request(8080, "/v1/transfers", transfer)["status"] == "applied"
    restart(f"raft-n{leader}")
    wait(8000 + leader, "/v1/leader")
    # Restart every Raft process with exactly the same log storage, then rebuild the read cache from zero.
    for i in range(1, 4):
        stop(f"raft-n{i}")
    for i in range(1, 4):
        restart(f"raft-n{i}")
    for i in range(1, 4):
        wait(8000 + i, "/v1/leader")
    stop("ledgerline-balance-reader")
    restart("ledgerline-balance-reader")
    wait(8082)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        values = request(8082, "/v1/balances")
        if {row["account_id"]: row["balance_minor"] for row in values}.get("bob") == 9751:
            break
        time.sleep(0.25)
    else:
        raise AssertionError("Replay after full cluster/cache restart failed")
    assert request(8080, "/v1/transfers", transfer)["status"] == "duplicate"
    assert request(8080, "/v1/accounts/bob")["balance_minor"] == 9751
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        histories = [request(8000 + i, "/v1/events") for i in range(1, 4)]
        if histories[0] == histories[1] == histories[2]:
            break
        time.sleep(0.25)
    else:
        raise AssertionError("Replica event histories differ")
    print("PASS: leader failover, gateway retry, full cluster restart, cache rebuild, durable dedup, identical replica histories", flush=True)


def verify_migration(run_dir):
    launch("ledgerline-raft", 9001, run_dir, {"LEDGER_NODE_ID": "m1", "LEDGER_RAFT_ADDRESS": "localhost:7101",
           "LEDGER_PEERS": "m1=localhost:7101", "LEDGER_STORAGE_DIR": str(run_dir / "migration-target")}, name="migration-target")
    wait(9001, "/v1/leader")
    def split(point):
        return request(8085, "/v1/split", {"split_point": point, "new_shard_ledger_nodes": ["http://localhost:9001"], "writes_quiesced": True})
    def fnv(value):
        result = 0x811c9dc5
        for byte in value.encode():
            result = ((result ^ byte) * 0x01000193) & 0xffffffff
        return result
    hashes = sorted(fnv(account) for account in ["external-deposit", "alice", "bob", "platform-fee-revenue"])
    try:
        split(hashes[1])
        raise AssertionError("Cross-range migration should have been rejected")
    except HTTPError as error:
        assert error.code == 409
        assert "Cross-shard" in json.load(error)["reason"]
    assert len(request(8085, "/v1/routing")) == 1
    assert request(9001, "/v1/events") == []
    # Moving the connected component in its entirety avoids losing cross-range history.
    result = split(1)
    assert result["status"] == "applied" and result["migrated_accounts"] == 4, result
    assert len(request(8085, "/v1/routing")) == 2
    for account in ["external-deposit", "alice", "bob", "platform-fee-revenue"]:
        assert request(8080, f"/v1/accounts/{account}") == request(9001, f"/v1/accounts/{account}")
    duplicate = request(9001, "/v1/transfers", {"dedup_id": "demo-payout-fee", "from_account": "alice", "to_account": "platform-fee-revenue",
                                               "amount_minor": 250, "currency": "USD", "gl_account": "4900", "cost_center": "CC-FEES-US"})
    assert duplicate["status"] == "duplicate"
    print("PASS: offline shard replay, preserved balances/currencies/unlimited flags/dedup, atomic routing swap, unsafe cross-range abort", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true", help="Use existing packaged jars")
    parser.add_argument("--with-db", action="store_true", help="Start a private, disposable PostgreSQL instance; never touches local databases")
    parser.add_argument("--verify", action="store_true", help="Run demo assertions and stop all child processes")
    parser.add_argument("--resilience", action="store_true", help="Also stop/restart owned nodes to test failover and durable replay")
    parser.add_argument("--migration", action="store_true", help="Also test offline migration to a separate private target cluster")
    args = parser.parse_args()
    if not args.no_build:
        subprocess.run(["mvn", "-B", "package"], cwd=ROOT, check=True)
    # Refuse to start over another stack. Do not terminate processes we did not create.
    ports = [8001, 8002, 8003, 7001, 7002, 7003, 8080, 8081, 8082, 8083, 8085, 5180]
    if args.with_db:
        ports += [8084, 8086, 8087]
    if args.migration:
        ports += [9001, 7101]
    for port in ports:
        with socket.socket() as sock:
            # TIME_WAIT sockets from a just-finished run are not live listeners.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"Port {port} is already in use; stop that stack yourself before running this demo")
    (ROOT / ".run").mkdir(exist_ok=True)
    run_dir = Path(tempfile.mkdtemp(prefix="demo-", dir=ROOT / ".run"))
    print(f"Logs and isolated state: {run_dir}", flush=True)
    atexit.register(cleanup)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    pg_port = start_postgres(run_dir) if args.with_db else None
    peers = ",".join(f"n{i}=localhost:{7000 + i}" for i in range(1, 4))
    for i in range(1, 4):
        launch("ledgerline-raft", 8000 + i, run_dir, {"LEDGER_NODE_ID": f"n{i}", "LEDGER_RAFT_ADDRESS": f"localhost:{7000 + i}",
               "LEDGER_PEERS": peers, "LEDGER_STORAGE_DIR": str(run_dir / f"n{i}")}, name=f"raft-n{i}")
    for i in range(1, 4):
        wait(8000 + i, "/v1/leader")
    for module, port in [("ledgerline-accounting-core", 8081), ("ledgerline-gateway", 8080),
                         ("ledgerline-balance-reader", 8082), ("ledgerline-fx", 8083),
                         ("ledgerline-orchestrator", 8085), ("ledgerline-admin-console", 5180)]:
        launch(module, port, run_dir)
        wait(port)
    if args.with_db:
        db_url = f"jdbc:postgresql://127.0.0.1:{pg_port}/ledgerline_java"
        launch("ledgerline-persistence-sync", 8084, run_dir, {"DB_URL": db_url, "DB_USER": "ledgerline", "DB_PASSWORD": ""})
        wait(8084)
        subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(pg_port), "-d", "ledgerline_java", "-v", "ON_ERROR_STOP=1", "-c",
                        "GRANT USAGE ON SCHEMA public TO ledgerline_ai_readonly; GRANT SELECT ON journal_entries,account_balances,sync_state TO ledgerline_ai_readonly;"],
                       check=True, stdout=subprocess.DEVNULL)
        for module, port in [("ledgerline-reporting", 8086), ("ledgerline-ai-gateway", 8087)]:
            launch(module, port, run_dir, {"DB_URL": db_url, "DB_USER": "ledgerline_ai_readonly", "DB_PASSWORD": ""})
            wait(port)
        # Verify the DB role is actually read-only, not just a JDBC hint.
        denied = subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(pg_port), "-U", "ledgerline_ai_readonly", "-d", "ledgerline_java",
                                 "-v", "ON_ERROR_STOP=1", "-c", "DELETE FROM account_balances WHERE false"], capture_output=True)
        assert denied.returncode != 0 and b"permission denied" in denied.stderr, denied.stderr
    seed()
    if args.with_db:
        verify_db()
    if args.resilience:
        verify_resilience()
        if args.with_db:
            verify_db()
    if args.migration:
        verify_migration(run_dir)
    print("Admin console: http://localhost:5180", flush=True)
    if not args.verify:
        print("Press Ctrl-C to stop. Isolated data and logs are retained in .run/ for inspection.", flush=True)
        while True:
            time.sleep(1)
            for name, process in PROCESSES:
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited unexpectedly")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
