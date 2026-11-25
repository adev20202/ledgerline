-- Lines carry their own currency so one entry can move money in several
-- currencies. Such an entry must balance in each currency; its
-- journal_entries.currency is null.
alter table journal_lines add column currency varchar(3);

update journal_lines l
set currency = e.currency
from journal_entries e
where e.id = l.entry_id;

alter table journal_lines alter column currency set not null;
alter table journal_entries alter column currency drop not null;
