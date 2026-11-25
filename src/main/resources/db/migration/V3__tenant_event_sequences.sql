-- One counter row per tenant. Taking the next number locks the row until the
-- transaction ends, so concurrent appends for one tenant queue up instead of
-- all reading the same max(tenant_sequence) and colliding on the unique key.
-- Because the lock is held to commit, a tenant's events also become visible
-- in sequence order.
create table tenant_event_sequences (
    tenant_id     uuid primary key references tenants (id),
    last_sequence bigint not null
);

insert into tenant_event_sequences (tenant_id, last_sequence)
select tenant_id, max(tenant_sequence)
from ledger_events
group by tenant_id;
