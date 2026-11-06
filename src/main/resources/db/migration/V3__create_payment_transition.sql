create table payment_transition
(
    id           uuid primary key,
    payment_id   uuid        not null,
    from_status  varchar(32),
    to_status    varchar(32) not null,
    triggered_by varchar(32) not null,
    occurred_at  timestamptz not null,
    constraint fk_transition_payment foreign key (payment_id) references payment (id),
    constraint ck_transition_triggered_by check (triggered_by in ('INITIATE', 'VERIFY', 'WEBHOOK', 'RECONCILER', 'ADMIN'))
);

create index ix_transition_payment_occurred on payment_transition (payment_id, occurred_at);
