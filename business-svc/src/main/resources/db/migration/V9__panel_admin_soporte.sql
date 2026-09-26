-- Paktay V9: panel de administración (2026-09-25).
--
-- Bloques:
--   1. normalize_email: la misma regla que el backend usa para comparar correos.
--   2. admin_audit: bitácora nueva del panel. Solo altas de usuario, acciones del
--      administrador y cuentas eliminadas; nada de gastos ni montos. audit_log (V1) deja
--      de recibir filas y se vacía sola con la purga de 90 días; se borra en otra versión.
--   3. Soporte: tickets del formulario público (con confirmación de correo), sus
--      mensajes (base para un chat futuro) y los bloqueos de correo, dominio o IP.
--   4. purge_user: además borra los eventos del panel y los tickets de la cuenta.

------------------------------------------------------------------------------
-- 1. Correos normalizados
------------------------------------------------------------------------------
-- Minúsculas, sin alias "+" y, en Gmail, sin puntos: troll+1@gmail.com, t.roll@gmail.com
-- y TROLL@googlemail.com son el mismo correo. SupportEmails.normalize hace lo mismo en Java.
create or replace function public.normalize_email(raw text) returns text
    language sql immutable
    as $$
    select case
        when raw is null then null
        when position('@' in raw) = 0 then lower(trim(raw))
        else (
            with parts as (
                select split_part(lower(trim(raw)), '@', 1) as local_part,
                       split_part(lower(trim(raw)), '@', 2) as domain_part
            ), cleaned as (
                select split_part(local_part, '+', 1) as local_part,
                       case when domain_part = 'googlemail.com' then 'gmail.com' else domain_part end as domain_part
                  from parts
            )
            select case when domain_part = 'gmail.com' then replace(local_part, '.', '') else local_part end
                   || '@' || domain_part
              from cleaned
        )
    end;
    $$;

comment on function public.normalize_email(text) is 'Correo comparable: minúsculas, sin alias + y sin puntos en Gmail.';

------------------------------------------------------------------------------
-- 2. Bitácora del panel
------------------------------------------------------------------------------
create table if not exists public.admin_audit (
    id uuid default gen_random_uuid() not null primary key,
    kind character varying(16) not null,
    action character varying(60) not null,
    actor_id uuid,
    actor_label character varying(320) not null,
    -- Usuario afectado, para que purge_user borre sus eventos. Null en acciones sobre
    -- catálogos y en el cierre de cuenta (que se guarda anónimo, después de la purga).
    subject_user_id uuid,
    subject_label character varying(320) not null,
    before_value jsonb,
    after_value jsonb,
    created_at timestamp with time zone default now() not null,
    constraint admin_audit_kind_check check ((kind)::text in ('USER_CREATED', 'ADMIN_ACTION', 'ACCOUNT_DELETED'))
);

create index if not exists admin_audit_created_idx on public.admin_audit (created_at desc);
create index if not exists admin_audit_subject_idx on public.admin_audit (subject_user_id) where subject_user_id is not null;

comment on table public.admin_audit is 'Bitácora del panel: altas, acciones del administrador y cierres de cuenta. Se conserva 90 días.';

create or replace function public.admin_audit_guard() returns trigger
    language plpgsql
    as $$
begin
    if tg_op = 'UPDATE' then
        raise exception 'admin_audit es append-only';
    end if;
    if old.created_at < now() - interval '90 days' then
        return old;
    end if;
    if old.subject_user_id is not null and current_setting('paktay.purge_user', true) = old.subject_user_id::text then
        return old;
    end if;
    raise exception 'admin_audit es append-only: sólo se purgan registros de más de 90 días';
end;
$$;

drop trigger if exists admin_audit_guard on public.admin_audit;
create trigger admin_audit_guard before update or delete on public.admin_audit
    for each row execute function public.admin_audit_guard();

------------------------------------------------------------------------------
-- 3. Soporte
------------------------------------------------------------------------------
create sequence if not exists public.support_ticket_seq;

create table if not exists public.support_tickets (
    id uuid default gen_random_uuid() not null primary key,
    -- Código que se devuelve al formulario público (PK-0001).
    code character varying(16) default ('PK-' || lpad(nextval('public.support_ticket_seq')::text, 4, '0')) not null unique,
    email character varying(320) not null,
    email_normalized character varying(320) not null,
    reason character varying(2000) not null,
    status character varying(24) default 'PENDING_VERIFICATION' not null,
    -- SHA-256 del token del enlace de confirmación; el token en claro solo viaja por correo.
    verify_token_hash character(64),
    verify_expires_at timestamp with time zone,
    client_ip character varying(64),
    user_agent character varying(300),
    unread boolean default true not null,
    created_at timestamp with time zone default now() not null,
    verified_at timestamp with time zone,
    resolved_at timestamp with time zone,
    updated_at timestamp with time zone default now() not null,
    constraint support_tickets_status_check check ((status)::text in ('PENDING_VERIFICATION', 'NEW', 'IN_PROGRESS', 'RESOLVED'))
);

create index if not exists support_tickets_status_idx on public.support_tickets (status, created_at desc);
create index if not exists support_tickets_email_idx on public.support_tickets (email_normalized);
create unique index if not exists support_tickets_token_uq on public.support_tickets (verify_token_hash) where verify_token_hash is not null;

comment on table public.support_tickets is 'Tickets del formulario público. Solo los de correo confirmado aparecen en el panel.';

create table if not exists public.support_messages (
    id uuid default gen_random_uuid() not null primary key,
    ticket_id uuid not null references public.support_tickets(id) on delete cascade,
    author character varying(8) not null,
    author_label character varying(320) not null,
    body character varying(4000) not null,
    created_at timestamp with time zone default now() not null,
    constraint support_messages_author_check check ((author)::text in ('USER', 'ADMIN', 'NOTE', 'SYSTEM'))
);

create index if not exists support_messages_ticket_idx on public.support_messages (ticket_id, created_at);

comment on table public.support_messages is 'Conversación de un ticket: mensaje del usuario, respuestas, notas internas (NOTE) y eventos (SYSTEM).';

create table if not exists public.blocked_identities (
    id uuid default gen_random_uuid() not null primary key,
    type character varying(8) not null,
    -- Correo normalizado, dominio en minúsculas o IP.
    value character varying(320) not null,
    scopes character varying(16)[] not null,
    reason character varying(16) not null,
    automatic boolean default false not null,
    created_by character varying(320) not null,
    created_at timestamp with time zone default now() not null,
    constraint blocked_identities_type_check check ((type)::text in ('EMAIL', 'DOMAIN', 'IP')),
    constraint blocked_identities_reason_check check ((reason)::text in ('FAKE_EMAIL', 'OFFENSIVE', 'SPAM', 'OTHER')),
    constraint blocked_identities_scopes_check check (scopes <@ array['TICKETS', 'REGISTRATION', 'ACCOUNT']::character varying[] and cardinality(scopes) > 0),
    constraint blocked_identities_value_uq unique (type, value)
);

comment on table public.blocked_identities is 'Correos, dominios e IP bloqueados para soporte, registro y cuenta.';

------------------------------------------------------------------------------
-- 4. purge_user
------------------------------------------------------------------------------
-- Igual que V6 más los eventos del panel del usuario y sus tickets (por correo, que es
-- lo único que los une a la cuenta). Los bloqueos se conservan: son decisión del admin.
create or replace function public.purge_user(target uuid) returns void
    language plpgsql
    as $$
declare
    target_email text;
begin
    perform set_config('paktay.purge_user', target::text, true);
    select public.normalize_email(email) into target_email from app_users where id = target;
    delete from audit_log where subject_user_id = target or actor_user_id = target;
    delete from admin_audit where subject_user_id = target;
    if target_email is not null then
        delete from support_tickets where email_normalized = target_email;
    end if;
    update expenses set space_id = null
     where user_id <> target and space_id in (select id from spaces where created_by = target);
    delete from expenses where user_id = target;
    delete from budget_allocations where user_id = target;
    delete from user_category_budgets where user_id = target;
    delete from user_budget_settings where user_id = target;
    delete from financial_periods where user_id = target;
    delete from user_consumption_selections where user_id = target;
    delete from cards where user_id = target;
    delete from banks where user_id = target;
    delete from user_categories where user_id = target;
    delete from space_members where user_id = target
        or space_id in (select id from spaces where created_by = target);
    delete from spaces where created_by = target;
    delete from password_pins where user_id = target::text;
    delete from app_users where id = target;
    insert into deleted_accounts (user_id) values (target) on conflict (user_id) do nothing;
    perform set_config('paktay.purge_user', '', true);
end;
$$;

comment on function public.purge_user(uuid) is 'Eliminación de cuenta: borra todos los datos del usuario (y sus tickets) salvo subscription_event.';
