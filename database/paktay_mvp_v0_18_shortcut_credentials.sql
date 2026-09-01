-- Paktay v0.18 — credenciales personales y revocables para Apple Shortcuts.
create table if not exists shortcut_credentials (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    token_hash char(64) not null unique,
    token_hint varchar(12) not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    last_used_at timestamptz,
    revoked_at timestamptz,
    check ((active and revoked_at is null) or (not active and revoked_at is not null))
);

create unique index if not exists shortcut_credentials_active_user_uq
    on shortcut_credentials (user_id) where active;
create index if not exists shortcut_credentials_user_idx
    on shortcut_credentials (user_id, created_at desc);

comment on table shortcut_credentials is 'Credenciales revocables que vinculan una instalación personal de Apple Shortcut con un usuario.';
comment on column shortcut_credentials.token_hash is 'SHA-256 hexadecimal del secreto; el bearer original sólo se entrega al crearlo.';
comment on column shortcut_credentials.token_hint is 'Últimos caracteres visibles para que el usuario reconozca la conexión sin revelar el secreto.';
