-- Paktay V4: contexto del usuario, estados de gasto y tarjeta, y tablas preparatorias.
--
-- Día 3 (2026-09-23). Bloques:
--   1. Zona horaria y país del usuario.
--   2. Borrado lógico de tarjetas (estado DELETED).
--   3. Espacios Duo (tablas vacías, sin rutas todavía).
--   4. Estados de gasto (kind, status, anulación, asignación por regla, espacio).
--   5. Consentimientos y suscripciones (tablas vacías, sin rutas todavía).
--   6. Bancos por país y bancos propios del usuario.
--   7. Bitácora de auditoría: acciones nuevas y purga a 90 días.
--
-- Nota sobre enums: ALTER TYPE ... ADD VALUE puede ir dentro de la transacción de
-- Flyway (PostgreSQL 12+), pero el valor nuevo no se puede usar hasta que la
-- transacción confirme. Por eso ningún CHECK ni índice de esta migración escribe
-- el literal 'DELETED' ni las acciones nuevas de audit_action.

------------------------------------------------------------------------------
-- 1. Zona horaria y país del usuario
------------------------------------------------------------------------------
-- El mes del período y los filtros por fecha se calculan en la zona del usuario,
-- no en la del servidor. Ecuador es el mercado inicial, de ahí los valores por
-- defecto. La validez de la zona se comprueba en el backend (ZoneId de Java y
-- pg_timezone_names) porque un CHECK no puede consultar el catálogo de zonas.
alter table public.app_users
    add column if not exists timezone character varying(64) default 'America/Guayaquil' not null,
    add column if not exists country_code character(2) default 'EC' not null;

alter table public.app_users
    add constraint app_users_country_code_check check (country_code ~ '^[A-Z]{2}$');

comment on column public.app_users.timezone is 'Zona horaria IANA del usuario; define el mes calendario de sus períodos y filtros.';
comment on column public.app_users.country_code is 'País ISO 3166-1 alfa-2 del usuario; por defecto EC.';

------------------------------------------------------------------------------
-- 2. Borrado lógico de tarjetas
------------------------------------------------------------------------------
-- Eliminar una tarjeta ya no borra la fila: pasa a DELETED, conserva sus gastos
-- (que siguen contando en totales) y libera su nombre de Wallet (name = null).
alter type public.card_status add value if not exists 'DELETED';

-- cards_check exigía ACTIVE sin fecha o INACTIVE con fecha: DELETED lo violaría.
-- Se reescribe sin nombrar DELETED (ver nota de enums): todo estado distinto de
-- ACTIVE lleva deactivated_at.
alter table public.cards drop constraint if exists cards_check;
alter table public.cards
    add constraint cards_check check (
        ((status = 'ACTIVE'::public.card_status) and (deactivated_at is null))
        or ((status <> 'ACTIVE'::public.card_status) and (deactivated_at is not null))
    );

-- El nombre de Wallet es opcional desde v0.20 y el código ya escribe null (alta
-- sin nombre, DELETE /wallet-name), pero la base conservaba NOT NULL. Una tarjeta
-- eliminada también queda con name = null. cards_name_check (btrim(name) <> '')
-- deja pasar null, y el índice único cards_active_identity_uq sólo cubre
-- status = 'ACTIVE', así que ni los null ni las DELETED colisionan.
alter table public.cards alter column name drop not null;

comment on column public.cards.status is 'ACTIVE, INACTIVE o DELETED (borrado lógico); sólo ACTIVE recibe gastos nuevos.';
comment on column public.cards.name is 'Nombre que entrega Wallet/Shortcut; opcional, único por usuario entre tarjetas activas y nulo en tarjetas eliminadas.';
comment on column public.cards.deactivated_at is 'Fecha y hora de desactivación o eliminación lógica; nula sólo en tarjetas activas.';

------------------------------------------------------------------------------
-- 3. Espacios Duo (preparatorio: sin rutas todavía)
------------------------------------------------------------------------------
-- Se crean antes de expenses.space_id porque la FK los necesita.
create table if not exists public.spaces (
    id uuid default gen_random_uuid() not null primary key,
    kind character varying(8) default 'DUO' not null,
    name character varying(80),
    color_dark character(7),
    color_light character(7),
    status character varying(16) default 'ACTIVE' not null,
    created_by uuid not null references public.app_users(id),
    created_at timestamp with time zone default now(),
    constraint spaces_kind_check check ((kind)::text = 'DUO'),
    constraint spaces_status_check check ((status)::text in ('ACTIVE', 'DISSOLVED'))
);

comment on table public.spaces is 'Espacio compartido Duo (dos personas). Preparatorio: aún sin rutas.';

create table if not exists public.space_members (
    space_id uuid not null references public.spaces(id),
    user_id uuid not null references public.app_users(id),
    joined_at timestamp with time zone default now() not null,
    left_at timestamp with time zone,
    primary key (space_id, user_id)
);

comment on table public.space_members is 'Miembros de un espacio Duo; left_at nulo significa miembro activo.';

-- Un usuario pertenece como mucho a un Duo activo.
create unique index if not exists space_members_one_active_per_user_uq
    on public.space_members (user_id) where (left_at is null);

-- Un Duo tiene como mucho dos miembros activos. El bloqueo de la fila del espacio
-- serializa altas concurrentes en el mismo espacio.
create or replace function public.enforce_space_member_limit() returns trigger
    language plpgsql
    as $$
begin
    if new.left_at is null then
        perform 1 from spaces s where s.id = new.space_id for update;
        if (select count(*) from space_members m
             where m.space_id = new.space_id
               and m.left_at is null
               and m.user_id <> new.user_id) >= 2 then
            raise exception 'Un espacio Duo admite como máximo dos miembros activos';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists space_members_limit on public.space_members;
create trigger space_members_limit before insert or update on public.space_members
    for each row execute function public.enforce_space_member_limit();

-- Presupuestos de un espacio (nulo = presupuesto personal, como hasta hoy).
alter table public.user_budget_settings
    add column if not exists space_id uuid references public.spaces(id);
alter table public.user_category_budgets
    add column if not exists space_id uuid references public.spaces(id);

------------------------------------------------------------------------------
-- 4. Estados de gasto
------------------------------------------------------------------------------
-- kind: EXPENSE (consumo) o REFUND (registro compensatorio, día 4).
-- status: ACTIVE o VOIDED (anulado, día 4). Un anulado nunca vuelve a ACTIVE.
-- voided_by_expense_id: gasto que reemplaza al anulado, si lo hay.
-- assigned_by_rule: el teléfono asignó la tarjeta/categoría por una regla.
-- space_id: gasto marcado para un Duo (nulo = personal).
-- Mientras no existan rutas de anulación ni reembolsos, las sumas y conteos de
-- consumo sólo cuentan status = 'ACTIVE' and kind = 'EXPENSE'.
alter table public.expenses
    add column if not exists kind character varying(16) default 'EXPENSE' not null,
    add column if not exists status character varying(16) default 'ACTIVE' not null,
    add column if not exists voided_by_expense_id uuid references public.expenses(id),
    add column if not exists assigned_by_rule boolean default false not null,
    add column if not exists space_id uuid references public.spaces(id);

alter table public.expenses
    add constraint expenses_kind_check check ((kind)::text in ('EXPENSE', 'REFUND')),
    add constraint expenses_status_check check ((status)::text in ('ACTIVE', 'VOIDED'));

comment on column public.expenses.kind is 'EXPENSE (consumo) o REFUND (registro compensatorio).';
comment on column public.expenses.status is 'ACTIVE o VOIDED; un gasto anulado no vuelve a ACTIVE ni cuenta en totales.';
comment on column public.expenses.voided_by_expense_id is 'Gasto que reemplaza a este cuando fue anulado para corregirlo.';
comment on column public.expenses.assigned_by_rule is 'La tarjeta o categoría la asignó una regla del teléfono, no el usuario.';
comment on column public.expenses.space_id is 'Espacio Duo al que se marcó el gasto; nulo si es personal.';
comment on column public.expenses.card_id is 'Tarjeta usada en el gasto; editable, sólo hacia tarjetas activas del usuario.';

-- Reglas de modificación (decisión del dueño 2026-09-23):
--   - los gastos no se borran;
--   - usuario y fecha son inmutables; tarjeta y categoría se pueden cambiar;
--   - el monto de un gasto AUTOMATIC es inmutable;
--   - ACTIVE -> VOIDED permitido, VOIDED -> ACTIVE nunca;
--   - no se toca un gasto de un período cerrado. El mes del gasto se calcula en la
--     zona horaria del usuario, igual que en el backend.
create or replace function public.protect_expense_update() returns trigger
    language plpgsql
    as $$
declare
    v_timezone text;
begin
    if tg_op = 'DELETE' then
        raise exception 'Los gastos no se pueden eliminar';
    end if;
    if new.user_id <> old.user_id or new.occurred_at <> old.occurred_at then
        raise exception 'El usuario y la fecha de un gasto son inmutables';
    end if;
    if old.origin = 'AUTOMATIC' and new.amount <> old.amount then
        raise exception 'No se puede modificar el monto de un gasto automático';
    end if;
    if old.status = 'VOIDED' and new.status <> 'VOIDED' then
        raise exception 'Un gasto anulado no se puede reactivar';
    end if;
    select u.timezone into v_timezone from app_users u where u.id = old.user_id;
    if exists (
        select 1 from financial_periods p
         where p.user_id = old.user_id
           and p.period_month = date_trunc('month', old.occurred_at at time zone coalesce(v_timezone, 'America/Guayaquil'))::date
           and p.closed_at is not null
    ) then
        raise exception 'No se puede modificar un gasto de un período cerrado';
    end if;
    return new;
end;
$$;

-- Propiedad del gasto. Además de la regla del alta (sólo tarjetas activas), ahora
-- que card_id es editable, cambiar la tarjeta sólo se permite hacia una ACTIVA:
-- una INACTIVE o DELETED no recibe gastos, ni nuevos ni reasignados.
create or replace function public.validate_expense_ownership() returns trigger
    language plpgsql
    as $$
begin
    if not exists (select 1 from cards c where c.id = new.card_id and c.user_id = new.user_id) then
        raise exception 'La tarjeta no pertenece al usuario del gasto';
    end if;
    if not exists (select 1 from user_categories c where c.id = new.category_id and c.user_id = new.user_id) then
        raise exception 'La categoría no pertenece al usuario del gasto';
    end if;
    if tg_op = 'INSERT' and not exists (select 1 from cards c where c.id = new.card_id and c.status = 'ACTIVE') then
        raise exception 'No se puede registrar un gasto en una tarjeta inactiva';
    end if;
    if tg_op = 'UPDATE' and new.card_id <> old.card_id
       and not exists (select 1 from cards c where c.id = new.card_id and c.status = 'ACTIVE') then
        raise exception 'Sólo se puede asignar un gasto a una tarjeta activa';
    end if;
    return new;
end;
$$;

------------------------------------------------------------------------------
-- 5. Consentimientos y suscripciones (preparatorio: sin rutas todavía)
------------------------------------------------------------------------------
create table if not exists public.user_consent (
    user_id uuid not null references public.app_users(id) on delete cascade,
    document character varying(32) not null,
    version character varying(16) not null,
    accepted_at timestamp with time zone default now() not null,
    primary key (user_id, document, version),
    constraint user_consent_document_check check ((document)::text in ('terms', 'privacy', 'duo_sharing'))
);

comment on table public.user_consent is 'Aceptación de términos, privacidad y compartir en Duo, por versión del documento.';

-- Durante la beta todos son testers con PRO; las tiendas llegan con RevenueCat.
create table if not exists public.user_subscription (
    user_id uuid not null primary key references public.app_users(id) on delete cascade,
    plan character varying(8) default 'PRO' not null,
    source character varying(16) default 'TESTER' not null,
    store_product_id character varying(100),
    status character varying(16) default 'ACTIVE' not null,
    current_period_end timestamp with time zone,
    updated_at timestamp with time zone default now() not null,
    constraint user_subscription_plan_check check ((plan)::text in ('FREE', 'PRO', 'DUO')),
    constraint user_subscription_source_check check ((source)::text in ('TESTER', 'APP_STORE', 'PLAY_STORE'))
);

comment on table public.user_subscription is 'Plan vigente del usuario. Preparatorio: los entitlements se calculan desde el día 7.';

-- Eventos recibidos de la tienda o de RevenueCat; event_id da idempotencia.
create table if not exists public.subscription_event (
    event_id character varying(100) not null primary key,
    user_id uuid,
    type character varying(40) not null,
    payload jsonb not null,
    received_at timestamp with time zone default now() not null
);

comment on table public.subscription_event is 'Webhooks de suscripción recibidos, tal cual llegaron; event_id evita procesarlos dos veces.';

------------------------------------------------------------------------------
-- 6. Bancos por país y bancos propios del usuario (sólo esquema)
------------------------------------------------------------------------------
-- Los bancos actuales son de Ecuador y del sistema. Un banco CUSTOM lo crea un
-- usuario y sólo lo ve él.
alter table public.banks
    add column if not exists country_code character(2) default 'EC' not null,
    add column if not exists origin character varying(8) default 'SYSTEM' not null,
    add column if not exists user_id uuid references public.app_users(id);

alter table public.banks
    add constraint banks_country_code_check check (country_code ~ '^[A-Z]{2}$'),
    add constraint banks_origin_check check ((origin)::text in ('SYSTEM', 'CUSTOM')),
    add constraint banks_origin_user_check check (
        ((origin)::text = 'SYSTEM' and user_id is null)
        or ((origin)::text = 'CUSTOM' and user_id is not null)
    );

-- banks_normalized_name_key era único global: dos usuarios no podrían crear cada
-- uno su "Banco X". Se separa en único entre bancos del sistema y único por
-- usuario entre bancos propios.
alter table public.banks drop constraint if exists banks_normalized_name_key;
create unique index if not exists banks_system_normalized_name_uq
    on public.banks (normalized_name) where ((origin)::text = 'SYSTEM');
create unique index if not exists banks_custom_user_normalized_name_uq
    on public.banks (user_id, normalized_name) where ((origin)::text = 'CUSTOM');

comment on column public.banks.normalized_name is 'Nombre normalizado; único entre bancos del sistema y, para bancos propios, único por usuario.';
comment on column public.banks.country_code is 'País ISO 3166-1 alfa-2 del banco.';
comment on column public.banks.origin is 'SYSTEM (catálogo) o CUSTOM (creado por un usuario).';
comment on column public.banks.user_id is 'Dueño del banco CUSTOM; nulo en bancos del sistema.';

------------------------------------------------------------------------------
-- 7. Bitácora de auditoría
------------------------------------------------------------------------------
-- Acciones nuevas que registra el backend: reactivar y eliminar tarjeta, asociar
-- y quitar el nombre de Wallet. CREATE y DEACTIVATE ya existían.
alter type public.audit_action add value if not exists 'ACTIVATE';
alter type public.audit_action add value if not exists 'DELETE';
alter type public.audit_action add value if not exists 'LINK';
alter type public.audit_action add value if not exists 'UNLINK';

-- La bitácora sigue sin admitir UPDATE (audit_log_no_update no cambia). El DELETE
-- sólo se admite para filas de más de 90 días, que es lo que purga el job diario
-- del backend (AuditService.purgeExpired, 03:30). Lo reciente sigue intocable.
create or replace function public.reject_recent_audit_delete() returns trigger
    language plpgsql
    as $$
begin
    if old.created_at < now() - interval '90 days' then
        return old;
    end if;
    raise exception 'audit_log es append-only: sólo se purgan registros de más de 90 días';
end;
$$;

drop trigger if exists audit_log_no_delete on public.audit_log;
create trigger audit_log_no_delete before delete on public.audit_log
    for each row execute function public.reject_recent_audit_delete();

comment on table public.audit_log is 'Bitácora append-only de acciones relevantes; no admite actualización y sólo se purgan filas de más de 90 días.';
