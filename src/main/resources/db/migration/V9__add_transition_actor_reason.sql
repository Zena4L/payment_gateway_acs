alter table payment_transition add column actor varchar(64), add column reason varchar(255);

alter table payment_transition add constraint ck_transition_admin_details
    check (triggered_by <> 'ADMIN' or (actor is not null and reason is not null));
