# LedgerCore

LedgerCore is a Java 21 and Spring Boot portfolio API for simulated, same-currency transfers. The initial increment posts a balanced journal, handles retries and prevents overdrafts under concurrent requests. A local demo uses seeded balances and two synthetic users.

## Architecture

- `api` defines the HTTP contract, demo authentication and error responses.
- `ledger` owns the transfer transaction, account access checks and PostgreSQL queries.
- Flyway manages the schema. Each immutable transfer row produces two equal and opposite journal entries. The API calculates balances from opening balances and the journal.

See [ADR 001](docs/adr-001-transfer-journal.md) for locking, idempotency and tradeoffs.

## Run the local demo

Install Java 21, Maven 3.6.3+ and Docker Compose. The API binds to `127.0.0.1:8080` and PostgreSQL to `127.0.0.1:5439`. Use a disposable database and generate fresh local passwords.

```bash
export LEDGERCORE_DB_USER=ledgercore
export LEDGERCORE_DB_PASSWORD="$(openssl rand -hex 24)"
export LEDGERCORE_ALICE_PASSWORD="$(openssl rand -hex 24)"
export LEDGERCORE_BOB_PASSWORD="$(openssl rand -hex 24)"
docker compose up -d --wait
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

In a second shell, run the requests below using the same demo password variables. In PowerShell, set environment variables with `$env:VARIABLE = ...` and use `curl.exe`. Start from a fresh Compose database for the stated opening balances.

```bash
curl -i --user "alice:${LEDGERCORE_ALICE_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-transfer-001' \
  -d '{"debitAccountId":"00000000-0000-0000-0000-000000000001","creditAccountId":"00000000-0000-0000-0000-000000000002","currency":"ARS","amount":25.50}' \
  http://127.0.0.1:8080/api/transfers
```

The first request returns `201` and a transfer ID. The same request returns `200`, the same transfer and `Idempotency-Replayed: true`. A change to the amount or either account with the same actor and key returns `409 idempotency_conflict`.

```bash
curl --user "alice:${LEDGERCORE_ALICE_PASSWORD}" \
  http://127.0.0.1:8080/api/accounts/00000000-0000-0000-0000-000000000001
```

Alice's balance is `74.50 ARS` after one successful transfer of `25.50 ARS`. Each demo user can read their own accounts. Only the owner of the debit account can initiate a transfer. The API returns `404` for unavailable accounts, `422` for same-account or currency violations, and `409 insufficient_funds` for overdrafts.

Press Ctrl+C to stop the app and run `docker compose down` to stop PostgreSQL. Compose keeps the demo volume between runs.

## Tests

```bash
mvn -B --no-transfer-progress verify
```

The Failsafe integration suite starts disposable PostgreSQL 17 with Testcontainers. It covers balanced and immutable postings, ownership, input validation, concurrent debits, same-key retries, opposite transfers and rollback on a database write failure.

## Scope

The initial API uses decimal amounts with two fractional digits and same-currency transfers. Reversals, account provisioning, external identity, real payment providers and deployment are outside this increment. The demo uses local HTTP Basic authentication and fixed seed balances. Outside the `demo` profile the HTTP routes are denied.
