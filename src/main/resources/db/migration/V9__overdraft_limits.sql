-- How far below zero an account's available balance may go, in minor units
-- on the account's normal side. Null means no limit.
alter table accounts add column overdraft_limit_minor bigint check (overdraft_limit_minor >= 0);
