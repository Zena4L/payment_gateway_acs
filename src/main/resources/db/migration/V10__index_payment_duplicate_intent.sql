create index ix_payment_duplicate_intent on payment (owner_id, email, amount_minor, currency, created_at);
