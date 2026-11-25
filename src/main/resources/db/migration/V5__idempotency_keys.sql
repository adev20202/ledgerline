-- Stored responses for requests sent with an Idempotency-Key header. The row
-- is written in the same transaction as the change, so a failed request
-- leaves no row and can be retried with the same key.
create table idempotency_keys (
    tenant_id       uuid         not null references tenants (id),
    idempotency_key varchar(200) not null,
    request_hash    varchar(64)  not null,
    response_status int,
    response_body   jsonb,
    created_at      timestamptz  not null,
    expires_at      timestamptz  not null,
    primary key (tenant_id, idempotency_key)
);

create index ix_idempotency_keys_expires on idempotency_keys (expires_at);
