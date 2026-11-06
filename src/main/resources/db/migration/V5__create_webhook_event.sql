create table webhook_event
(
    id                      uuid primary key,
    dedup_key               varchar(128),
    event_type              varchar(64) not null,
    reference               varchar(64),
    provider_transaction_id bigint,
    amount_minor            bigint,
    currency                varchar(3),
    processing_state        varchar(16) not null,
    diagnostic              varchar(255),
    received_at             timestamptz not null,
    processed_at            timestamptz,
    constraint ck_webhook_event_processing_state check (processing_state in
        ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'IGNORED', 'REJECTED', 'FAILED'))
);

create unique index uk_webhook_event_dedup on webhook_event (dedup_key) where processing_state <> 'DUPLICATE';
create index ix_webhook_event_reference on webhook_event (reference);
