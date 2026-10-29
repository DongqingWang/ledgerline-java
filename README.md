# Ledgerline — Java

A Java 21 / Spring Boot 3.5 / Apache Ratis implementation of the platform in
[`../ledgerline-monorepo`](../ledgerline-monorepo). The existing Java Raft work
is retained and extended into a runnable Maven multi-module project. All backend
services are Java; the small browser console is static HTML/CSS/JavaScript served
by Spring Boot, and Python is used only for the local demo/test harness.

This is an educational distributed ledger, **not production financial software**.
It uses public architectural patterns (Raft, event sourcing, CQRS, double-entry
bookkeeping), not proprietary system code.

## Build and run

Requirements: **JDK 21+**, Maven 3.9+, Python 3 for the local demo. PostgreSQL
binaries (`initdb`, `pg_ctl`, `psql`) are needed only for `--with-db`. No database,
Docker, API credentials, or external FX provider is needed for the default demo.

```bash
cd /Users/dongqwang/ledgerline-java
mvn package
./scripts/local-demo.sh --no-build
```

The demo starts **three real Ratis nodes**, accounting, gateway, balance reader,
FX, orchestrator, and console. It creates four accounts, funds Alice, executes a
fee-split transfer twice to check idempotency, and checks projected balances.
Open **http://localhost:5180**. Ctrl-C stops only the processes this script started.
Logs and fresh, isolated Raft state are retained under `.run/demo-*`.
It refuses to start if a required port is occupied; it never kills other stacks.

### Full stack with a private PostgreSQL instance

```bash
./scripts/local-demo.sh --no-build --with-db
```

This starts a **new private PostgreSQL instance** on an available loopback port
under the run directory, creates its database and read-only role, and adds
persistence-sync, reporting, and MCP. It **does not modify your existing Postgres
server or databases**. Run this as a normal user, not root (`initdb` restriction).
The private instance uses local trust authentication for demo convenience.

### Automated live checks

```bash
./scripts/local-demo.sh --no-build --verify
./scripts/local-demo.sh --no-build --with-db --verify
./scripts/local-demo.sh --no-build --with-db --verify --resilience
./scripts/local-demo.sh --no-build --with-db --verify --resilience --migration
```

`--verify` exits after assertions and shuts down its children. The full check uses
real PostgreSQL, tests the database role's write denial, reconciles journals and
balances, and exercises MCP initialization, tool discovery, journal lookup, and
redaction. `--resilience` additionally stops the elected leader, submits through
the gateway after re-election, restarts the entire Raft cluster and balance cache,
and checks durable deduplication and identical event histories on all replicas.
`--migration` starts another private single-node target and checks successful
offline replay as well as an aborted cross-range migration with unchanged routing.
Unit tests use H2 only where a small SQL test is useful; H2 is never the
runtime ledger store.

The Maven suite includes a real single-node Ratis start/stop/recovery regression,
snapshot/dedup/event-history recovery, checked money arithmetic, journal rule and
JSON-boundary tests, HTTP failover and SSE retry tests, projection transaction
rollback, currency-scoped reconciliation, routing, and MCP protocol/redaction.

### Docker

```bash
docker compose up --build -d
docker compose logs -f
docker compose down
```

Docker starts the services but does not seed accounts. Use the HTTP examples
below to seed your own data. All host ports bind to loopback. Postgres is exposed
on **55432**, so an existing server on 5432 is not replaced. Demo passwords are
in `docker-compose.yml`; do not reuse them outside local development.
Raft and Postgres data persist in named volumes. Do not reuse a projection
database with a fresh/different Raft cluster: the cursor is scoped to one cluster
by deployment convention, not a persisted cluster identifier.

For a complete Docker end-to-end demo (build, start, wait for health checks,
seed accounts, fee-split transfer, idempotency, CQRS/Postgres reconciliation,
reporting CLI, MCP/redaction, and read-only database permission checks):

```bash
./scripts/docker-demo.sh --resilience
```

`--resilience` additionally stops the elected leader and tests re-election,
gateway failover, full Raft restart, and rebuilding the balance cache. Omit it for
the shorter demo. Add `--no-build` to reuse built images. **Initial seeding requires
an empty ledger**; the script never resets data or removes volumes. To check a
previously seeded stack without posting anything new:

```bash
./scripts/docker-demo.sh --no-build --check
docker compose ps
docker compose stop
```

Containers remain running after successful verification so you can use the UI.
Diagnostics are saved under `.run/docker-*`. All application images include a
JRE-only HTTP health probe (no extra curl/Python runtime dependency). Container
health is liveness, not a guarantee of quorum or zero projection lag.

If startup reports `No space left on device`, check Docker's internal disk, not
just free space on the Mac. PostgreSQL needs initialization/WAL space and Ratis
requires a free-space safety margin. Increase Docker Desktop's disk limit or
review unused build artifacts/images before removing any; do not blindly prune
volumes or unrelated images. After resolving the space issue, retry with
`--no-build` to avoid another image build.

## Modules and endpoints

| Module | Default HTTP port | Main endpoints |
|---|---:|---|
| `ledgerline-common` | — | Shared HTTP, accounting contracts, event consumer |
| `ledgerline-raft` | 8001 (8002/8003 in demo) | `POST /v1/accounts`, `GET /v1/accounts/{id}`, `POST /v1/transfers`, `GET /v1/leader`, `GET /v1/healthz`, `GET /v1/events/stream?from_index=0`, `GET /v1/events` |
| `ledgerline-accounting-core` | 8081 | `POST /v1/compose` |
| `ledgerline-gateway` | 8080 | `POST /v1/transfers`, `POST /v1/accounts`, `GET /v1/accounts/{id}` |
| `ledgerline-balance-reader` | 8082 | `GET /v1/balances`, `GET /v1/balances/{id}` |
| `ledgerline-persistence-sync` | 8084 | Event → transactional Postgres projection |
| `ledgerline-fx` | 8083 | `GET /v1/rates/{base}/{quote}`, `POST /v1/convert` |
| `ledgerline-orchestrator` | 8085 | `GET /v1/routing`, `POST /v1/split` |
| `ledgerline-reporting` | 8086 | `GET /v1/reports/trial-balance`, CLI `--report` |
| `ledgerline-ai-gateway` | 8087 | MCP JSON-RPC `POST /mcp` |
| `ledgerline-admin-console` | 5180 | Browser UI at `/` |

Every non-Raft service also has `GET /healthz`. These endpoints indicate process
liveness (and projection cursor where available), not a quorum/readiness or
zero-lag guarantee. Amounts are signed 64-bit **integer minor units**; no floating
point is used for balances. Fractional minor-unit inputs are rejected rather than
truncated. JSON fields are `snake_case`. The demo browser uses JavaScript numbers;
use a 64-bit-safe API client for amounts outside JavaScript's safe integer range.

```text
gateway → accounting (classify + balance journal) → Ratis (majority commit)
                                                     ↓ committed SSE events
                                     balance-reader     persistence-sync → Postgres
                                                                                ↓
                                                                  reporting + read-only MCP
```

### Example requests

Create accounts (the Raft nodes accept writes through any reachable HTTP node):

```bash
curl -fsS localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"account_id":"external-deposit","currency":"USD","unlimited":true}'
curl -fsS localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"account_id":"alice","currency":"USD"}'
curl -fsS localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"account_id":"bob","currency":"USD"}'
curl -fsS localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"account_id":"platform-fee-revenue","currency":"USD"}'
curl -fsS localhost:8001/v1/transfers -H 'Content-Type: application/json' \
  -d '{"dedup_id":"seed-1","from_account":"external-deposit","to_account":"alice","amount_minor":100000,"currency":"USD"}'
curl -fsS localhost:8080/v1/transfers -H 'Content-Type: application/json' \
  -d '{"dedup_id":"payout-1","from_account":"alice","to_account":"bob","amount_minor":10000,"fee_minor":250,"currency":"USD","category":"seller_payout","country":"US"}'
curl -fsS localhost:8082/v1/balances
```

Repeating the identical transfer uses the same keys and returns `duplicate`
without moving money again. Never reuse a key for a different business operation:
as in the reference implementation, keys identify the original result, not a
request-payload hash.

## Configuration

All services accept Spring Boot command-line options and `SERVER_PORT`.

* Raft: `LEDGER_NODE_ID=n1`, `LEDGER_RAFT_ADDRESS=localhost:7001`,
  `LEDGER_PEERS=n1=localhost:7001,n2=localhost:7002,n3=localhost:7003`,
  `LEDGER_STORAGE_DIR=./data/n1`. The group UUID is fixed in `RaftConfig.java`;
  peer lists must match across a cluster. Independent clusters need disjoint
  peers/storage. Raft RPC ports differ from HTTP ports.
* Clients: `LEDGER_NODES=http://localhost:8001,http://localhost:8002,http://localhost:8003`.
  Writes fail over HTTP nodes and Ratis routes them to the leader. Reads select a
  reported leader but remain eventual (there is no read-index barrier).
* Gateway: `ACCOUNTING_URL=http://localhost:8081`.
* Accounting: `--ledger.rules=file:/path/to/rules.yaml` overrides the bundled
  ordered, first-match-wins rules (US payout, international payout, refund,
  unclassified catch-all). Fee-bearing contexts require `fee_credit` dimensions.
* FX: `--ledger.fx-seed=file:/path/to/rates.json`. Seed must be USD-anchored with
  `USD=1` and all positive rates. Cross-rates use `BigDecimal`; conversions round
  half up, reject overflow, and assume equal minor-unit scales across currencies.
  Live refresh is opt-in; see below.
* Postgres services: `DB_URL=jdbc:postgresql://localhost:5432/ledgerline_java`,
  `DB_USER`, `DB_PASSWORD`. Projection owns `journal_entries`, `account_balances`,
  and `sync_state`, compatible in naming with the Go project. Use a separate Java
  database, not the live Go projection database.
* Reporting/MCP default to `ledgerline_ai_readonly` with JDBC read-only connections.
  For a manually managed database, grant that role `CONNECT`, schema `USAGE`, and
  **SELECT only** on the projection tables after creating them. Do not grant DML,
  schema creation, or ownership. See `postgres-init/01-roles.sql` for Docker setup.

### Reporting CLI

```bash
DB_URL=jdbc:postgresql://localhost:55432/ledgerline_java \
  java -jar ledgerline-reporting/target/ledgerline-reporting-0.1.0.jar --report
```

Outputs a JSON trial balance grouped by currency/GL, checks transfer-level
debits/credits, and compares projected account balances with journal-derived
balances using a repeatable-read transaction. Exits 1 for discrepancies, 0 when
balanced; runtime/database failures also exit nonzero.

Optional `--from=YYYY-MM-DD` / `--to=YYYY-MM-DD` select inclusive **UTC dates**.
The upper bound is implemented as midnight of the following day, so subsecond
entries at the end of `--to` are included. Either bound may be omitted. Invalid
dates, reversed ranges, and invalid formats exit **2 before connecting to the DB**.

```bash
java -jar ledgerline-reporting/target/ledgerline-reporting-0.1.0.jar --report \
  --from=2026-10-01 --to=2026-10-31 --format=table
java -jar ledgerline-reporting/target/ledgerline-reporting-0.1.0.jar --report \
  --from=2026-10-01 --to=2026-10-31 --format=csv --output=trial-balance.csv
docker compose exec -T reporting java -jar /app/service.jar --report --format=csv
curl -fsS 'localhost:8086/v1/reports/trial-balance?from=2026-10-01&to=2026-10-31&format=json'
```

Formats are `json` (default), `csv`, and `table`, available in the CLI and HTTP
endpoint (`format` query parameter). `--output` writes UTF-8 to the requested file
(overwriting an existing file); otherwise stdout contains only the report.
Logs/reconciliation status go to stderr. CSV contains a header and rectangular
data rows, with standard quoting for commas/quotes/newlines and exact integer
minor-unit amounts; there is no non-CSV reconciliation footer. HTTP responses
include `X-Ledgerline-Reconciliation: pass|fail`.

**Reconciliation scope:** trial-balance rows and per-transfer checks use the
selected period. The current account-balance projection still reconciles against
**all journal history**, not period movement. JSON includes `period`; table output
and HTTP header `X-Ledgerline-Projection-Scope: all-history` make this distinction
explicit. Filtering to a period does not disable the global projection check.

### Optional live FX refresh

The default remains bundled static seed rates and **no external HTTP requests**.
To enable periodic refresh from the same public USD endpoint as the Go version:

```bash
FX_REFRESH_ENABLED=true FX_REFRESH_INTERVAL=15m \
  java -jar ledgerline-fx/target/ledgerline-fx-0.1.0.jar
# Docker: recreate only FX with the opt-in environment value.
FX_REFRESH_ENABLED=true docker compose up -d --no-deps fx
```

Configuration (environment variable / Spring property):

| Environment | Spring property | Default |
|---|---|---|
| `FX_REFRESH_ENABLED` | `ledger.fx.refresh-enabled` | `false` |
| `FX_REFRESH_INTERVAL` | `ledger.fx.refresh-interval` | `15m` |
| `FX_REFRESH_ENDPOINT` | `ledger.fx.refresh-endpoint` | `https://open.er-api.com/v6/latest/USD` |
| `FX_REFRESH_TIMEOUT` | `ledger.fx.refresh-timeout` | `10s` |

Enabled refresh runs immediately in the background, then at fixed delay after
each attempt (no overlapping scheduled fetches). The endpoint must return
`{"result":"success","base_code":"USD","rates":{"USD":1,"EUR":0.92,...}}`.
Each complete table is validated before an atomic swap: HTTP/JSON failures,
timeouts, wrong base, missing USD anchor, empty tables, and nonpositive rates
leave **both the last good rates and their timestamp unchanged**. Transient
failures do not stop future attempts. Seed rates remain usable while the first
request runs or fails. `/healthz.refresh_enabled` reports the configured mode.
Rates/as-of values and each conversion use one consistent table snapshot.

### MCP

The Java version uses a **stateless Streamable HTTP subset**, not the Go version's
stdio transport. Point an HTTP-capable MCP client at `http://127.0.0.1:8087/mcp`.
Supported methods: `initialize` (protocol `2025-03-26`), `ping`, `tools/list`, and
`tools/call`; notifications receive HTTP 202. No sessions, subscriptions, server
SSE, arbitrary SQL, or write tools. A client opening `GET /mcp` receives 405.

Tools: `get_account_balance(account_id)`,
`get_journal_entry(transfer_dedup_id)`, `search_transactions(account_id, limit)`.
`cost_center` is omitted; broad searches also mask transfer IDs and cap results
at 20. Each dispatched call emits a structured audit record without raw account
IDs, transaction IDs, or database error details. MCP binds to loopback by default; Docker
overrides the container listener while publishing only to host loopback.

```bash
curl -fsS localhost:8087/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_account_balance","arguments":{"account_id":"alice"}}}'
```

### YAML AI redaction policy

`ledgerline-ai-gateway/src/main/resources/redaction.yaml` supplies the default
policy. Override it at startup using `REDACTION_POLICY=file:/path/to/policy.yaml`
or `--ledger.redaction-policy=file:/path/to/policy.yaml`. Example partial override:

```yaml
fields:
  cost_center: {classification: internal_sensitive, action: mask}
  amount_minor: {classification: internal_sensitive, action: redact}
```

Actions: `none` passes the original value, `redact` omits the field, and `mask`
returns an obscured string (including for numeric fields). `internal_sensitive`
with no action defaults to `redact`; `public` defaults to `none`. Short strings
are fully masked. Default cost-center masks keep 2 characters on each side;
other masks keep 4. Masking respects Unicode code points.

Partial files **overlay safe defaults**: omitting `cost_center` or
`transfer_dedup_id` does not accidentally expose them. An explicit `none` can
expose a field and must be reviewed accordingly. Unknown fields, invalid actions
or classifications, duplicate keys, malformed YAML, and unreadable policy files
**fail startup**, rather than silently relaxing protection. This deliberately
differs from Go's fallback behavior. Policy changes require a service restart.

The policy applies to the tools' allowed output fields, including scoped balance
lookups; it cannot expose arbitrary database columns, widen SQL scope, remove the
20-row search cap, or grant database writes. Audit records contain the actual
fields redacted/masked by each call. In Docker, mount a custom policy read-only
and set `REDACTION_POLICY` to its **container** path, e.g. via a Compose override:

```yaml
services:
  ai-gateway:
    volumes: ["./my-policy.yaml:/app/config/redaction.yaml:ro"]
    environment:
      REDACTION_POLICY: file:/app/config/redaction.yaml
```

### Verifying the added parity features

With the seeded Docker demo running, this starts the **newly packaged Java jars**
on temporary loopback ports against its read-only PostgreSQL role. It uses a
controlled local HTTP FX source, so no public provider is required:

```bash
mvn package
python3 scripts/verify_features.py --no-build
```

It checks FX refresh/failure retention/recovery, actual YAML-controlled MCP
responses, date-filtered reports, all output formats, CSV file export, clean CLI
stdout, and invalid-date exit codes. It does not write ledger data, stop Docker
services, or reset any database. Child Java processes are stopped afterward;
diagnostics stay under `.run/features-*`.

For a low-disk development image update, reuse the locally tested Maven jars
instead of creating another Maven dependency cache inside Docker:

```bash
python3 scripts/docker_prebuilt.py fx ai-gateway reporting
docker compose up -d --no-deps --no-build --wait fx ai-gateway reporting
./scripts/docker-demo.sh --no-build --check
```

This optional `prebuilt` target requires JDK 21+ locally, compiles the small
health probe with `--release 21`, and never deletes images or volumes. The normal
`docker compose build` path remains a full containerized Maven build.

### Offline shard-split demonstration

Start a separate empty target cluster, stop **all** writers, and call:

```json
{"split_point":2147483648,"new_shard_ledger_nodes":["http://localhost:9001"],"writes_quiesced":true}
```

`POST /v1/split` hashes accounts using FNV-1a, replays a bounded event-history
snapshot into the target (preserving currencies/unlimited flags), verifies
balances and unchanged source history, then replaces the in-memory routing table.
It **aborts** on cross-range transfer history instead of silently omitting those
transfers. Routing is demo metadata, not consulted by the gateway. No cluster
provisioning, distributed writer fencing, durable routing, automated rollback of
a partially loaded target, or online cutover is implemented. An aborted target
may be partially populated and must be replaced with a fresh target before retry.

## Correctness boundaries and differences from the reference

* Preserves the existing Spring/Ratis implementation rather than embedding Go.
  Self-transfers are rejected. Checked arithmetic rejects balance overflow before
  either account changes. Event timestamps are assigned in commands, so replicas
  publish the same event values. Invalid input is also validated in the FSM.
* A Raft transfer atomically debits/credits two accounts. Gateway fee splits are
  **two independent commits**, with deterministic `-net`/`-fee` dedup keys. An
  intermediate failure can leave only the net leg applied; retry with the exact
  same business request to finish. An all-fee transfer skips the zero-value leg.
  There is no reservation or atomic batch protocol.
* One GL/cost-center pair per engine transfer. No-fee transfers use the debit
  dimensions; fee-split legs use their credit dimensions, matching the Go mapping.
  This is not per-leg GL attribution in the durable projection.
* SSE consumers reconnect from the next committed projection index, accept gaps
  (Raft also logs non-business entries), and do not advance on projection failure.
  Postgres journal rows, both balance deltas, and cursor commit in one transaction.
* Full event history and dedup state are retained in memory and snapshots at demo
  scale. Snapshot recovery retains replay history. SSE fan-out is synchronous:
  slow subscribers can delay apply; this needs bounded asynchronous delivery and
  durable replay infrastructure before production. No automated snapshot
  compaction policy is enabled by this project.
* API reads have **no linearizability guarantee**. The cached reader is explicitly
  eventual. No auth, TLS, tenant isolation, quotas, production migrations,
  or full MCP transport parity is provided. CORS allows
  only the local demo console origins. Never expose the stack publicly.
