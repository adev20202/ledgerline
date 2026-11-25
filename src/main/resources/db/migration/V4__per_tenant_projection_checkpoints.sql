-- The balance projector used to read events by global id after a single
-- checkpoint. Ids are handed out at insert, not at commit, so an event that
-- committed after a higher id had already been projected was skipped for
-- good. Checkpoints are now per tenant and follow tenant_sequence, which
-- becomes visible in order (see V3).
create table projection_tenant_checkpoints (
    name          varchar(64) not null,
    tenant_id     uuid        not null references tenants (id),
    last_sequence bigint      not null,
    updated_at    timestamptz not null,
    primary key (name, tenant_id)
);

drop table projection_checkpoints;

-- Balances from the old projector may be missing events. Clear them so the
-- projector rebuilds every balance from the log after this migration.
delete from account_balances;
alter table account_balances rename column last_event_id to last_sequence;
