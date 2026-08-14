begin;

alter table app_users
    add column if not exists is_automatic boolean not null default false;

comment on column app_users.is_automatic is
    'Preferencia que habilita o deshabilita el procesamiento automático para el usuario.';

commit;
