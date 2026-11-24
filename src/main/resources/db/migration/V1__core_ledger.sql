create table tenants (
    id           uuid primary key,
    name         varchar(200) not null,
    slug         varchar(64)  not null unique,
    api_key_hash varchar(64)  not null unique,
    status       varchar(16)  not null,
    created_at   timestamptz  not null
);

create table accounts (
    id         uuid primary key,
    tenant_id  uuid         not null references tenants (id),
    code       varchar(32)  not null,
    name       varchar(200) not null,
    type       varchar(16)  not null,
    currency   varchar(3)   not null,
    status     varchar(16)  not null,
    created_at timestamptz  not null,
    closed_at  timestamptz,
    version    bigint       not null default 0,
    constraint uq_accounts_tenant_code unique (tenant_id, code)
);

create table journal_entries (
    id             uuid primary key,
    tenant_id      uuid         not null references tenants (id),
    reference      varchar(100),
    description    varchar(500) not null,
    effective_date date         not null,
    currency       varchar(3)   not null,
    status         varchar(16)  not null,
    reversal_of    uuid references journal_entries (id),
    reversed_by    uuid references journal_entries (id),
    posted_at      timestamptz  not null
);

create unique index uq_journal_entries_reference
    on journal_entries (tenant_id, reference) where reference is not null;
create index ix_journal_entries_tenant_date on journal_entries (tenant_id, effective_date);

create table journal_lines (
    id           uuid primary key,
    entry_id     uuid        not null references journal_entries (id),
    line_no      int         not null,
    account_id   uuid        not null references accounts (id),
    direction    varchar(6)  not null,
    amount_minor bigint      not null check (amount_minor > 0),
    memo         varchar(200),
    constraint uq_journal_lines_entry_line unique (entry_id, line_no)
);

create index ix_journal_lines_account on journal_lines (account_id);
