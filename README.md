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
- **Journal entries** must balance in every currency they touch: per
  currency, total debits equal total credits, with at least two lines in all.
  Each line is in its account's currency. An entry sent with a `currency`
  must stay in that one currency. Amounts are integers in the
  currency's minor unit (cents for USD). Entries are never edited or deleted;
  a mistake is fixed by posting a reversal.
- **FX.** Each tenant stores dated rates (`1 base = rate quote`) and one FX
  clearing account per currency. A conversion moves an amount from an account
  in one currency to an account in another at the rate in effect on a date
  (or the inverse of the opposite pair), rounding half-even to the target's
  minor unit. It posts four lines, so both currencies balance, and records
  the rate used in `fx_conversions`.
- **Holds** reserve part of an account's balance for up to 30 days. The
  balance endpoint reports `heldMinor` and `availableMinor`. A hold is
  captured into a journal entry (a partial capture releases the rest),
  released, or expires; it stops counting at its expiry time even before the
  expiry job records it. Only one of capture, release and expiry can win.
- **Event log** (`ledger_events`). Posting an entry or opening/closing an
  account appends an event in the same transaction. Each tenant's events are
  numbered 1, 2, 3, ... (`tenant_sequence`), and `GET /api/events` lets an
  integration follow them.
- **Balances** (`account_balances`) are filled in by `BalanceProjector`, which
  polls the event log every `ledger.projection.poll-interval-ms`. It keeps one
  checkpoint per tenant (`projection_tenant_checkpoints`, a tenant sequence)
  and locks it while applying a batch, so more than one instance can run it.
  Balances and the trial balance can trail the journal by one poll.

```
src/main/java/io/ledgerline
  account/     accounts, account API
  journal/     posting, reversals, entry and statement queries
  event/       event log, event feed API
  fx/          FX rates, clearing accounts, conversions
  hold/        holds, capture, release, expiry job
  idempotency/ Idempotency-Key handling
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

`POST /api/accounts`, `POST /api/entries`, `POST /api/entries/{id}/reversal`
`POST /api/fx/conversions`, `POST /api/holds` and `POST /api/holds/{id}/capture`
accept an optional `Idempotency-Key` header. A retry with the same key and body
gets the first response back, marked `Idempotent-Replayed: true`, and nothing
is written twice, even when the two requests arrive at the same time. The same
key with a different body is a `409`. Failed requests are not stored, so they
can be retried with the same key. Keys are kept for `ledger.idempotency.ttl`
(default 24 hours).

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
| POST | `/api/fx/rates` | store a rate for a currency pair and date |
| GET | `/api/fx/rates?base=&quote=&on=` | the rate in effect on a date |
| PUT | `/api/fx/clearing-accounts/{currency}` | set the clearing account for a currency |
| GET | `/api/fx/clearing-accounts` | list clearing accounts |
| POST | `/api/fx/conversions` | convert between two accounts in different currencies |
| POST | `/api/holds` | place a hold |
| GET | `/api/holds?accountId=&status=` | list an account's holds |
| GET | `/api/holds/{id}` | get a hold |
| POST | `/api/holds/{id}/capture` | capture all or part of a hold |
| POST | `/api/holds/{id}/release` | release a hold |
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
