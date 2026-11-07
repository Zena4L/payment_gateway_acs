alter table payment_transition add column actor_key_id varchar(12);

alter table payment_transition add constraint ck_transition_actor_key_id
    check (actor_key_id is null or actor is not null);
