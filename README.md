# ledgerline

A multi-tenant double-entry ledger service. Each tenant (a company using the
service) keeps its own chart of accounts and posts balanced journal entries
through a REST API. Every change is written to an append-only event log, and
account balances are a read model built from that log.

Built with Java 21, Spring Boot 3.4, Spring Data JPA, Flyway and PostgreSQL.

## How it works

- **Accounts** have a type (asset, liability, equity, income, expense), a
  currency and a code that is unique per tenant. Only accounts with a zero
  balance can be closed.
- **Journal entries** are single-currency and must balance: total debits equal
  total credits, with at least two lines. Amounts are integers in the
  currency's minor unit (cents for USD). Entries are never edited or deleted;
  a mistake is fixed by posting a reversal.
- **Event log** (`ledger_events`). Posting an entry or opening/closing an
  account appends an event in the same transaction. Each tenant's events are
  numbered 1, 2, 3, ... (`tenant_sequence`), and `GET /api/events` lets an
  integration follow them.
- **Balances** (`account_balances`) are filled in by `BalanceProjector`, which
  polls the event log every `ledger.projection.poll-interval-ms` and keeps
  its position in `projection_checkpoints`. Balances and the trial balance can
  trail the journal by one poll.

```
src/main/java/io/ledgerline
  account/     accounts, account API
  journal/     posting, reversals, entry and statement queries
  event/       event log, event feed API
  projection/  balance projector, balances, trial balance report
  tenant/      tenants, API keys, admin API
  web/         API key filter, error mapping
  common/      shared types and exceptions
```

## Requirements

- Java 21
- Maven 3.8 or newer
- PostgreSQL 16

## Database setup

```sh
sudo -u postgres psql <<'SQL'
create user ledger with password 'ledger';
create database ledger owner ledger;
create database ledger_test owner ledger;
SQL
```

Flyway creates the tables on startup. Connection settings come from
`LEDGER_DB_URL`, `LEDGER_DB_USER` and `LEDGER_DB_PASSWORD` (defaults:
`jdbc:postgresql://localhost:5432/ledger`, `ledger`, `ledger`).

## Run

```sh
export LEDGER_ADMIN_TOKEN=some-long-secret
mvn spring-boot:run
```

## Test

The integration tests need the `ledger_test` database. Override its location
with `LEDGER_TEST_DB_URL`, `LEDGER_TEST_DB_USER` and `LEDGER_TEST_DB_PASSWORD`.

```sh
mvn test
```

In tests the projector does not run on a timer; tests call
`BalanceProjector.runOnce()` themselves.

## API

Admin endpoints need `X-Admin-Token`. Everything under `/api` needs a tenant's
key in `X-Api-Key`. Errors are RFC 9457 problem details; ledger rule
violations are `422` with a `code` field such as `unbalanced` or
`currency_mismatch`.

| Method | Path | |
| --- | --- | --- |
| POST | `/admin/tenants` | create a tenant, returns its API key once |
| POST | `/admin/tenants/{id}/rotate-key` | issue a new key, the old one stops working |
| POST | `/admin/tenants/{id}/suspend` | block all API access for a tenant |
| POST | `/api/accounts` | open an account |
| GET | `/api/accounts?type=` | list accounts |
| GET | `/api/accounts/{id}` | get an account |
| POST | `/api/accounts/{id}/close` | close a zero-balance account |
| GET | `/api/accounts/{id}/balance` | projected balance |
| GET | `/api/accounts/{id}/statement?from=&to=` | lines posted to the account |
| POST | `/api/entries` | post a journal entry |
| GET | `/api/entries?from=&to=` | list entries by effective date |
| GET | `/api/entries/{id}` | get an entry |
| POST | `/api/entries/{id}/reversal` | reverse an entry |
| GET | `/api/reports/trial-balance?currency=` | trial balance for one currency |
| GET | `/api/events?afterSequence=&limit=` | the tenant's event feed |

Example:

```sh
curl -s -X POST localhost:8080/admin/tenants \
  -H "X-Admin-Token: $LEDGER_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Acme Ltd","slug":"acme"}'

curl -s -X POST localhost:8080/api/entries \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"reference":"INV-1001","description":"Invoice 1001","currency":"USD",
       "lines":[{"accountId":"<cash id>","direction":"DEBIT","amountMinor":12500},
                {"accountId":"<revenue id>","direction":"CREDIT","amountMinor":12500}]}'
```
