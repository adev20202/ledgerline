-- Append-only log of everything that changed in a tenant's ledger.
-- id gives a global order for projectors; tenant_sequence is what the
-- per-tenant event feed exposes to integrations.
create table ledger_events (
    id              bigint generated always as identity primary key,
    tenant_id       uuid        not null references tenants (id),
    tenant_sequence bigint      not null,
    event_type      varchar(40) not null,
    payload         jsonb       not null,
    occurred_at     timestamptz not null,
    constraint uq_ledger_events_tenant_seq unique (tenant_id, tenant_sequence)
);

-- Read model built from ledger_events by the balance projector.
create table account_balances (
    account_id         uuid primary key references accounts (id),
    tenant_id          uuid        not null references tenants (id),
    currency           varchar(3)  not null,
    debit_total_minor  bigint      not null default 0,
    credit_total_minor bigint      not null default 0,
    last_event_id      bigint      not null,
    updated_at         timestamptz not null
);

create index ix_account_balances_tenant on account_balances (tenant_id);

create table projection_checkpoints (
    name          varchar(64) primary key,
    last_event_id bigint      not null,
    updated_at    timestamptz not null
);
