# LedgerCore

LedgerCore is a Java 21 and Spring Boot portfolio API for simulated, same-currency transfers. The initial increment posts a balanced journal, handles retries and prevents overdrafts under concurrent requests. A local demo uses seeded balances and two synthetic users.

## Architecture

- `api` defines the HTTP contract, demo authentication and error responses.
- `ledger` owns the transfer transaction, account access checks and PostgreSQL queries.
- Flyway manages the schema. Each immutable transfer row produces two equal and opposite journal entries. The API calculates balances from opening balances and the journal.

See [ADR 001](docs/adr-001-transfer-journal.md) for locking, idempotency and tradeoffs.

## Run the local demo

Install Java 21, Maven 3.6.3+ and Docker Compose. The API binds to `127.0.0.1:8080` and PostgreSQL to `127.0.0.1:5439`. Generate an ignored `.env` once and keep it with the demo database volume. PostgreSQL initializes its database credentials when it creates the volume.

In Bash with OpenSSL:

```bash
if [ ! -e .env ]; then
  (
    umask 077
    set -C
    {
      printf 'LEDGERCORE_DB_USER=ledgercore\n'
      printf 'LEDGERCORE_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
      printf 'LEDGERCORE_ALICE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
      printf 'LEDGERCORE_BOB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    } > .env
  )
fi
set -a
. ./.env
set +a
docker compose up -d --wait
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

In PowerShell:

```powershell
if (!(Test-Path -LiteralPath .env)) {
    @(
        'LEDGERCORE_DB_USER=ledgercore'
        "LEDGERCORE_DB_PASSWORD=$([guid]::NewGuid().ToString('N'))"
        "LEDGERCORE_ALICE_PASSWORD=$([guid]::NewGuid().ToString('N'))"
        "LEDGERCORE_BOB_PASSWORD=$([guid]::NewGuid().ToString('N'))"
    ) | Set-Content -LiteralPath .env -Encoding ascii
}
(ConvertFrom-StringData (Get-Content -LiteralPath .env -Raw)).GetEnumerator() | ForEach-Object {
    [Environment]::SetEnvironmentVariable($_.Key, $_.Value)
}
docker compose up -d --wait
mvn spring-boot:run '-Dspring-boot.run.profiles=demo'
```

Open a second shell in the repository and load `.env` using the same shell-specific commands before running the requests. The examples below use Bash. To call the API from PowerShell, use `Invoke-RestMethod` with a Basic credential and the same request headers and JSON. The stated balances assume a fresh Compose database.

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

Press Ctrl+C to stop the app and run `docker compose down` to stop PostgreSQL. Compose keeps the demo volume between runs. On restart, reuse the `.env` that initialized the volume.

## Reverse a transfer

The original recipient can post one full reversal if their account has enough funds. Replace `TRANSFER_ID` with the ID returned by the original request:

```bash
curl -i --request POST --user "bob:${LEDGERCORE_BOB_PASSWORD}" \
  -H 'Idempotency-Key: demo-reversal-001' \
  http://127.0.0.1:8080/api/transfers/TRANSFER_ID/reversal
```

The first request returns `201` and a new posting with `reversesTransferId`. A replay by the same user with the same key returns `200` and the same posting. An attempt with a different key after a successful reversal returns `409 transfer_already_reversed`. If the recipient lacks funds, the API returns `409 insufficient_funds` and the key can be used again after the balance changes. Unavailable transfers, including reversals and transfers addressed to other users, return `404`.

The reversal debits the original recipient and credits the original sender. Both postings remain in the journal. For the stated demo balances, reversing the transfer returns Alice to `100.00 ARS` and Bob to `50.00 ARS`.

## Movements and reconciliation

Use the same local demo credentials and an account UUID from the demo above.

```sh
curl --user "alice:${LEDGERCORE_ALICE_PASSWORD}" \
  'http://127.0.0.1:8080/api/accounts/00000000-0000-0000-0000-000000000001/movements?limit=2'
curl --user "alice:${LEDGERCORE_ALICE_PASSWORD}" \
  'http://127.0.0.1:8080/api/accounts/00000000-0000-0000-0000-000000000001/reconciliation'
```

The movement API returns signed decimal deltas and timestamps in descending posting order, using transfer ID as the tie-breaker. If `nextBefore` is present, give it as the next request's `before` value. Limits range from 1 to 100. A cursor must refer to a movement on the same account. Both routes return 404 for other owners. Each page uses its own read snapshot.

Reconciliation returns the persisted opening balance, independent totals over postings and the journal view, row counts and a consistency flag. Both totals use the same database snapshot. The opening rows in the demo come from [the seed script](src/main/resources/db/demo/R__seed_demo_accounts.sql). Migration V3 freezes each existing opening amount. It cannot infer the provenance of accounts provisioned outside the demo. Use compensating postings to correct posted transfers.

## Tests

```bash
mvn -B --no-transfer-progress verify
```

The Failsafe integration suite starts disposable PostgreSQL 17 with Testcontainers. It covers balanced and immutable postings, ownership, input validation, concurrent debits, same-key retries, opposite transfers, reversal races and rollback on a database write failure.

## Scope

The API uses decimal amounts with two fractional digits and same-currency transfers. Reversals apply to the full amount and require the original recipient. Account provisioning, external identity, real payment providers and deployment are outside the demo scope. The demo uses local HTTP Basic authentication and fixed seed balances. Outside the `demo` profile the HTTP routes are denied.
