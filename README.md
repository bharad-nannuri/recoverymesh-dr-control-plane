# RecoveryMesh

**RecoveryMesh** is an interactive database disaster-recovery control plane built as a portfolio lab for high-availability engineering.

It lets a user submit real application transactions, observe writes on a PRIMARY PostgreSQL node, inject a primary database outage, continue traffic through a SECONDARY node, reconcile data when PRIMARY returns, and perform a controlled failback.

## Why this project exists

Most DR demos stop at architecture diagrams. RecoveryMesh makes the recovery path observable and testable:

1. Submit or generate live shipment transactions.
2. Watch PRIMARY accept writes while SECONDARY receives standby copies.
3. Inject a PRIMARY connectivity failure.
4. Observe queued writes during the failover window.
5. Watch SECONDARY become active and continue accepting traffic.
6. Restore PRIMARY and reconcile missing records.
7. Fail traffic back only after both sides are synchronized.

The dashboard exposes recovery-oriented signals including active site, database health, replication lag, queued writes, failover count, record counts, synchronization state and measured RTO.

## Architecture

```text
                    Browser / API client
                            |
                            v
                    +----------------+
                    | Recovery Router|
                    +--------+-------+
                             |
                 active-site routing
                    /                \
                   v                  v
          +----------------+   +----------------+
          | PRIMARY        |   | SECONDARY      |
          | PostgreSQL     |-->| PostgreSQL     |
          | :5432          |   | :5433          |
          +----------------+   +----------------+
                 ^                   |
                 +---- reconcile ----+
```

The public portfolio deployment intentionally co-locates both PostgreSQL nodes in one ephemeral Cloudflare Container to keep demo cost low. In a production architecture the nodes would be placed in independent failure domains, and database-native replication/failover tooling would replace the app-level DR simulation used by this lab.

## Failure model

The **Fail PRIMARY** control injects a database connectivity fault at the application routing layer. This deliberately makes PRIMARY unavailable to the application without destroying its data. During the failover window new writes are queued. Once SECONDARY is promoted, queued writes are drained there and new requests continue normally.

When PRIMARY is restored, RecoveryMesh computes the union of transaction IDs across both nodes and upserts missing rows to both sides. Controlled failback is blocked until the databases are healthy and synchronized.

This is a reliability lab, not a claim that application-level copying is a replacement for PostgreSQL streaming replication, managed Multi-AZ databases, Patroni, Pacemaker or cloud-native DR services.

## Tech stack

- Java 21
- Spring Boot 3
- Spring JDBC / HikariCP
- PostgreSQL (PRIMARY + SECONDARY)
- Vanilla HTML/CSS/JavaScript dashboard
- Docker / Docker Compose
- Cloudflare Workers + Containers configuration for the public demo
- GitHub Actions

## Run locally

```bash
docker compose up --build
```

Open `http://localhost:8080`.

Recommended demo flow:

```text
Generate 50
   -> verify PRIMARY/SECONDARY counts converge
Fail PRIMARY
   -> submit or generate more traffic
   -> watch SECONDARY become ACTIVE / DR
Restore PRIMARY
   -> reconcile missing records
Controlled Failback
   -> return traffic to PRIMARY
```

## API

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/status` | Current DR state and metrics |
| GET | `/api/transactions` | Transaction presence across both databases |
| GET | `/api/events` | Incident/recovery timeline |
| POST | `/api/transactions` | Submit a shipment transaction |
| POST | `/api/traffic/generate?count=50` | Generate test traffic |
| POST | `/api/control/fail-primary` | Inject PRIMARY outage |
| POST | `/api/control/restore-primary` | Restore PRIMARY connectivity |
| POST | `/api/control/reconcile` | Reconcile database contents |
| POST | `/api/control/failback` | Controlled return to PRIMARY |
| POST | `/api/control/reset` | Reset the demo |

## Portfolio story

RecoveryMesh demonstrates the concerns behind production high availability and DR rather than only the happy-path application:

- active/standby routing
- failure detection and promotion
- write protection during failover
- RTO measurement
- replication lag / RPO exposure
- data reconciliation
- controlled failback
- incident timeline and operational visibility
