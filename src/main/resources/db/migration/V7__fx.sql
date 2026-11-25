-- 1 base_currency = rate quote_currency, from effective_date until the next
-- rate for the same pair. Rates are never changed once stored, since past
-- conversions used them.
create table fx_rates (
    tenant_id      uuid           not null references tenants (id),
    base_currency  varchar(3)     not null,
    quote_currency varchar(3)     not null,
    effective_date date           not null,
    rate           numeric(24, 12) not null check (rate > 0),
    created_at     timestamptz    not null,
    primary key (tenant_id, base_currency, quote_currency, effective_date),
    check (base_currency <> quote_currency)
);

-- The account in each currency that takes the other side of a conversion,
-- so every currency in a conversion entry balances.
create table fx_clearing_accounts (
    tenant_id  uuid       not null references tenants (id),
    currency   varchar(3) not null,
    account_id uuid       not null references accounts (id),
    primary key (tenant_id, currency)
);

create table fx_conversions (
    entry_id            uuid primary key references journal_entries (id),
    tenant_id           uuid            not null references tenants (id),
    source_currency     varchar(3)      not null,
    target_currency     varchar(3)      not null,
    source_amount_minor bigint          not null,
    target_amount_minor bigint          not null,
    rate                numeric(24, 12) not null,
    rate_date           date            not null,
    inverted            boolean         not null
);
