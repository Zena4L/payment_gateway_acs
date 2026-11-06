create table api_client_key
(
    id         uuid primary key,
    key_id     varchar(12) not null,
    key_hash   varchar(64) not null,
    owner_id   varchar(64) not null,
    role       varchar(16) not null,
    created_at timestamptz not null,
    revoked_at timestamptz,
    constraint uk_api_client_key_key_id unique (key_id),
    constraint ck_api_client_key_role check (role in ('CLIENT', 'ADMIN'))
);
