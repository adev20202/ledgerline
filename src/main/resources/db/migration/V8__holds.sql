-- Funds reserved on an account. An active hold lowers the account's
-- available balance until it is captured, released or expires.
create table holds (
    id                    uuid primary key,
    tenant_id             uuid         not null references tenants (id),
    account_id            uuid         not null references accounts (id),
    amount_minor          bigint       not null check (amount_minor > 0),
    currency              varchar(3)   not null,
    status                varchar(16)  not null,
    reference             varchar(100),
    expires_at            timestamptz  not null,
    created_at            timestamptz  not null,
    resolved_at           timestamptz,
    captured_entry_id     uuid references journal_entries (id),
    captured_amount_minor bigint
);

create index ix_holds_account_active on holds (account_id) where status = 'ACTIVE';
create index ix_holds_due on holds (expires_at) where status = 'ACTIVE';
