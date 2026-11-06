create table payment
(
    id                      uuid primary key,
    reference               varchar(64)    not null,
    owner_id                varchar(64)    not null,
    email                   varchar(254)   not null,
    amount                  numeric(19, 2) not null,
    amount_minor            bigint         not null,
    currency                varchar(3)     not null,
    status                  varchar(32)    not null,
    provider                varchar(32)    not null,
    provider_transaction_id bigint,
    channel                 varchar(32),
    authorization_url       text,
    access_code             varchar(128),
    gateway_response        varchar(255),
    paid_at                 timestamptz,
    created_at              timestamptz    not null,
    updated_at              timestamptz    not null,
    version                 bigint         not null default 0,
    constraint uk_payment_reference unique (reference),
    constraint ck_payment_amount_positive check (amount > 0 and amount_minor > 0),
    constraint ck_payment_currency check (currency ~ '^[A-Z]{3}$'),
    constraint ck_payment_status check (status in
        ('INITIALIZING', 'PENDING', 'UNKNOWN', 'REQUIRES_REVIEW', 'SUCCESS', 'FAILED', 'EXPIRED'))
);

create index ix_payment_owner_created on payment (owner_id, created_at desc);
create index ix_payment_status_created on payment (status, created_at);
