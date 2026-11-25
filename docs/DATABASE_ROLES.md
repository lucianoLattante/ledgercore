# Database roles

Use a dedicated PostgreSQL database with the default public schema. Provision a migration login that owns the LedgerCore objects or can create them, and a separate login named ledgercore_runtime with only database CONNECT and schema USAGE. The runtime login must not own the database or its objects or inherit the migration role. On existing installations, review object ownership and effective role grants before switching users. For PostgreSQL databases upgraded from older versions, check that PUBLIC has no CREATE grant on the application schema.

## Migrate with a separate process

Supply the Flyway CLI with FLYWAY_URL, FLYWAY_USER and FLYWAY_PASSWORD in the migration process only. Use the Flyway major version resolved by the project's POM. From the repository root, run:

~~~sh
flyway -locations=filesystem:src/main/resources/db/migration migrate
PGSERVICE=ledgercore_migrate psql -X -w --set ON_ERROR_STOP=1 --single-transaction \
  --file src/main/resources/db/permissions/runtime.sql
~~~

Configure the PostgreSQL service ledgercore_migrate to use the same database and migration login. Keep passwords in the operator's secret store or local protected connection configuration. The grant script applies after migration and can be reapplied. Its table grants are explicit, so review it when adding a migration. To provision an isolated demo, include the additional Flyway location filesystem:src/main/resources/db/demo before the API starts.

Start the API with LEDGERCORE_FLYWAY_ENABLED=false, LEDGERCORE_DB_USER=ledgercore_runtime and the corresponding LEDGERCORE_DB_URL and LEDGERCORE_DB_PASSWORD. Omit the migration credentials from the API process. The separate-account setup applies to the database connection. The API authentication remains demo-only.

## Effective permissions

The API can read ledger data and append transfer claims and postings. PostgreSQL also requires UPDATE on at least one column of ledger_account for SELECT FOR NO KEY UPDATE. The runtime role receives UPDATE on opening_balance only, and Flyway migration V3 installs a trigger that rejects any actual change to that value. The role can issue a same-value update to that column and take row locks. Keep the trigger and the migration ownership boundary in place. The role cannot provision accounts, change posted transfers, read Flyway history or issue schema DDL with the stated setup.

The PostgreSQL Testcontainers suite migrates and grants under separate logins, then runs the existing HTTP, reconciliation and race cases with the restricted API data source. A focused test checks the lock and exact SQLSTATEs for rejected operations. The fixture migration login provisions test rows and tests database triggers separately.
