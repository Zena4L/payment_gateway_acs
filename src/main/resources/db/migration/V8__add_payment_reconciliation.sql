alter table payment add column last_reconciled_at timestamptz;

create index ix_payment_reconcile on payment (updated_at) where status in ('INITIALIZING', 'PENDING', 'UNKNOWN');
