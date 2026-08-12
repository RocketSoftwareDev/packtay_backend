-- Paktay — modelo físico PostgreSQL v0.1 (MVP)
--
-- Fuente funcional: C:\Users\User\Desktop\Paktay\docs\db\REQ-app-conciencia-financiera-v0.1.md
-- Identidad: Keycloak. business-svc es el único consumidor de esta base.
-- Este archivo no crea usuarios de base de datos ni políticas RLS: el móvil no se conecta
-- directamente a PostgreSQL. Ejecútelo con un rol propietario en una base PostgreSQL vacía.

begin;

create extension if not exists pgcrypto;

create type card_status as enum ('ACTIVE', 'INACTIVE');
create type category_origin as enum ('SYSTEM', 'CUSTOM');
create type expense_origin as enum ('MANUAL', 'AUTOMATIC');
create type pending_movement_status as enum ('PENDING', 'CONFIRMED', 'DISCARDED');
create type budget_scope as enum ('CARD', 'CATEGORY', 'CARD_CATEGORY');
create type installment_plan_status as enum ('ACTIVE', 'SETTLED');
create type installment_status as enum ('PENDING', 'PARTIALLY_PAID', 'PAID', 'SETTLED_EARLY');
create type installment_payment_type as enum ('INSTALLMENT', 'EARLY_SETTLEMENT');
create type audit_action as enum ('CREATE', 'UPDATE', 'DEACTIVATE', 'CONFIRM', 'DISCARD', 'CLOSE_PERIOD', 'EXPORT');

create table app_users (
    id uuid primary key, -- claim sub de Keycloak
    status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    deactivated_at timestamptz,
    created_at timestamptz not null default now(),
    check ((status = 'ACTIVE' and deactivated_at is null)
        or (status = 'INACTIVE' and deactivated_at is not null))
);

-- Catálogo ISO 4217. USD es la moneda base de Paktay porque Ecuador está dolarizado.
-- La tasa se almacena en cada gasto externo para que un reporte histórico no cambie si la
-- cotización posterior cambia. La integración automática de cotizaciones no es parte del MVP.
create table currencies (
    code char(3) primary key check (code ~ '^[A-Z]{3}$'),
    numeric_code char(3) not null unique check (numeric_code ~ '^[0-9]{3}$'),
    name varchar(80) not null,
    symbol varchar(8) not null,
    decimal_places smallint not null default 2 check (decimal_places between 0 and 4),
    active boolean not null default true,
    is_base_currency boolean not null default false
);
create unique index currencies_single_base_uq on currencies (is_base_currency) where is_base_currency;

create table banks (
    id uuid primary key default gen_random_uuid(),
    name varchar(120) not null,
    normalized_name varchar(120) not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    unique (normalized_name)
);

-- Catálogo global controlado por Paktay. No pertenece a ningún usuario.
create table system_categories (
    id uuid primary key default gen_random_uuid(),
    name varchar(80) not null,
    normalized_name varchar(80) not null,
    active boolean not null default true,
    display_order smallint not null check (display_order > 0),
    created_at timestamptz not null default now(),
    unique (normalized_name),
    unique (display_order),
    check (btrim(name) <> '')
);

-- Categorías operativas del usuario. Al registrarse se clona una por cada categoría del
-- sistema; las de origen SYSTEM pueden renombrarse o desactivarse sólo para ese usuario.
-- Las de origen CUSTOM son creadas por el usuario y no tienen system_category_id.
create table user_categories (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    system_category_id uuid references system_categories(id),
    origin category_origin not null,
    name varchar(80) not null,
    normalized_name varchar(80) not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, normalized_name),
    check ((origin = 'SYSTEM' and system_category_id is not null)
        or (origin = 'CUSTOM' and system_category_id is null)),
    check (btrim(name) <> '')
);
create unique index user_categories_system_uq on user_categories (user_id, system_category_id)
    where system_category_id is not null;

create table cards (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    bank_id uuid not null references banks(id),
    alias varchar(80) not null,
    last4 char(4) not null check (last4 ~ '^[0-9]{4}$'),
    default_currency_code char(3) not null default 'USD' references currencies(code),
    status card_status not null default 'ACTIVE',
    deactivated_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check ((status = 'ACTIVE' and deactivated_at is null)
        or (status = 'INACTIVE' and deactivated_at is not null)),
    check (btrim(alias) <> '')
);

-- Una tarjeta desactivada nunca se reactiva. El usuario puede registrar un plástico nuevo
-- con la misma combinación banco/últimos cuatro; sólo la combinación ACTIVA es única.
create unique index cards_active_identity_uq
    on cards (user_id, bank_id, last4) where status = 'ACTIVE';
create index cards_user_status_idx on cards (user_id, status);

-- Selección persistida del usuario: asocia un nombre de consumo llegado por Shortcut/Android
-- con una categoría. Es la memoria de clasificación para los próximos consumos similares.
create table user_consumption_selections (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    consumption_name varchar(180) not null,
    merchant_normalized varchar(180) not null,
    normalization_version smallint not null default 1 check (normalization_version > 0),
    category_id uuid not null references user_categories(id),
    active boolean not null default true,
    selection_count integer not null default 1 check (selection_count > 0),
    last_selected_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, merchant_normalized, normalization_version),
    check (btrim(merchant_normalized) <> '')
);

-- Todo evento entrante se conserva, incluidos los que no pudieron parsearse. El UUID llega
-- desde la app móvil y hace idempotente el reintento de un mismo Shortcut/notificación.
create table pending_movements (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    idempotency_key uuid not null,
    source varchar(16) not null check (source in ('IOS_SHORTCUT', 'ANDROID_NOTIFICATION')),
    raw_payload jsonb not null,
    raw_text text,
    parsed_amount numeric(12,2) check (parsed_amount > 0),
    parsed_currency_code char(3) not null default 'USD' references currencies(code),
    merchant_raw varchar(180),
    merchant_normalized varchar(180),
    normalization_version smallint,
    bank_id uuid references banks(id),
    last4 char(4) check (last4 is null or last4 ~ '^[0-9]{4}$'),
    occurred_at timestamptz,
    suggested_card_id uuid references cards(id),
    suggested_category_id uuid references user_categories(id),
    parse_error varchar(300),
    status pending_movement_status not null default 'PENDING',
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    unique (user_id, idempotency_key),
    check ((status = 'PENDING' and resolved_at is null)
        or (status in ('CONFIRMED', 'DISCARDED') and resolved_at is not null))
);
create index pending_movements_queue_idx on pending_movements (user_id, status, created_at);

create table financial_periods (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    period_month date not null check (period_month = date_trunc('month', period_month)::date),
    closed_at timestamptz,
    created_at timestamptz not null default now(),
    unique (user_id, period_month)
);

-- Ingreso declarado: global o asociado a una tarjeta. No es obligatorio para registrar gastos.
create table monthly_incomes (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    period_id uuid not null references financial_periods(id),
    card_id uuid references cards(id),
    amount numeric(12,2) not null check (amount > 0),
    currency_code char(3) not null default 'USD' references currencies(code),
    exchange_rate_to_usd numeric(18,8) not null default 1 check (exchange_rate_to_usd > 0),
    amount_usd numeric(14,2) generated always as (round(amount * exchange_rate_to_usd, 2)) stored,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (period_id, card_id)
);
create unique index monthly_income_global_uq on monthly_incomes (period_id) where card_id is null;

-- Se guardan importes, no saldos derivados. El backend calcula el consumo sumando gastos.
create table budget_allocations (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    period_id uuid not null references financial_periods(id),
    scope budget_scope not null,
    card_id uuid references cards(id),
    category_id uuid references user_categories(id),
    amount numeric(12,2) not null check (amount >= 0),
    currency_code char(3) not null default 'USD' references currencies(code),
    exchange_rate_to_usd numeric(18,8) not null default 1 check (exchange_rate_to_usd > 0),
    amount_usd numeric(14,2) generated always as (round(amount * exchange_rate_to_usd, 2)) stored,
    alert_threshold numeric(4,3) not null default 0.900 check (alert_threshold > 0 and alert_threshold <= 1),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (
        (scope = 'CARD' and card_id is not null and category_id is null) or
        (scope = 'CATEGORY' and card_id is null and category_id is not null) or
        (scope = 'CARD_CATEGORY' and card_id is not null and category_id is not null)
    )
);
create unique index budget_card_uq on budget_allocations (period_id, card_id)
    where scope = 'CARD';
create unique index budget_category_uq on budget_allocations (period_id, category_id)
    where scope = 'CATEGORY';
create unique index budget_card_category_uq on budget_allocations (period_id, card_id, category_id)
    where scope = 'CARD_CATEGORY';

create table expenses (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    card_id uuid not null references cards(id),
    category_id uuid not null references user_categories(id),
    pending_movement_id uuid unique references pending_movements(id),
    origin expense_origin not null,
    amount numeric(12,2) not null check (amount > 0),
    currency_code char(3) not null default 'USD' references currencies(code),
    exchange_rate_to_usd numeric(18,8) not null default 1 check (exchange_rate_to_usd > 0),
    amount_usd numeric(14,2) generated always as (round(amount * exchange_rate_to_usd, 2)) stored,
    merchant_raw varchar(180) not null,
    merchant_normalized varchar(180),
    normalization_version smallint,
    occurred_at timestamptz not null,
    is_recurring boolean not null default false,
    recurrence_day smallint check (recurrence_day between 1 and 31),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check ((is_recurring and recurrence_day is not null) or (not is_recurring and recurrence_day is null)),
    check (btrim(merchant_raw) <> '')
);
create index expenses_user_date_idx on expenses (user_id, occurred_at);
create index expenses_category_date_idx on expenses (user_id, category_id, occurred_at);
create index expenses_card_date_idx on expenses (user_id, card_id, occurred_at);

-- V0.1 no calcula interés financiero: la tasa se conserva explícitamente en 0.0000.
-- La cantidad de cuotas es libre; el backend distribuye importe/numero y deja el residuo
-- de redondeo en la última cuota. Esto mantiene la suma exacta del plan.
create table installment_plans (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    expense_id uuid not null unique references expenses(id),
    original_amount numeric(12,2) not null check (original_amount > 0),
    interest_rate numeric(8,4) not null default 0 check (interest_rate = 0),
    installment_count integer not null check (installment_count > 0),
    first_due_date date not null,
    status installment_plan_status not null default 'ACTIVE',
    settled_at timestamptz,
    created_at timestamptz not null default now(),
    check ((status = 'ACTIVE' and settled_at is null)
        or (status = 'SETTLED' and settled_at is not null))
);

create table installments (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    installment_plan_id uuid not null references installment_plans(id),
    sequence_number integer not null check (sequence_number > 0),
    due_date date not null,
    scheduled_amount numeric(12,2) not null check (scheduled_amount > 0),
    status installment_status not null default 'PENDING',
    settled_at timestamptz,
    unique (installment_plan_id, sequence_number),
    check ((status in ('PAID', 'SETTLED_EARLY') and settled_at is not null)
        or (status in ('PENDING', 'PARTIALLY_PAID') and settled_at is null))
);
create index installments_due_idx on installments (due_date, status);

-- Comprobante de pago declarado por el usuario; no integra ningún procesador ni ejecuta un
-- cobro bancario. Puede distribuirse entre una o varias cuotas (liquidación anticipada).
create table installment_payments (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    installment_plan_id uuid not null references installment_plans(id),
    payment_type installment_payment_type not null default 'INSTALLMENT',
    total_amount numeric(12,2) not null check (total_amount > 0),
    paid_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);
create index installment_payments_plan_idx on installment_payments (installment_plan_id, paid_at);

create table installment_payment_allocations (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    payment_id uuid not null references installment_payments(id),
    installment_id uuid not null references installments(id),
    amount numeric(12,2) not null check (amount > 0),
    created_at timestamptz not null default now(),
    unique (payment_id, installment_id)
);
create index installment_payment_allocations_installment_idx
    on installment_payment_allocations (installment_id);

create or replace function validate_installment_ownership()
returns trigger language plpgsql as $$
begin
    if tg_table_name = 'installment_plans' then
        if not exists (
            select 1 from expenses e
             where e.id = new.expense_id
               and e.user_id = new.user_id
               and e.amount = new.original_amount
        ) then
            raise exception 'El gasto no pertenece al usuario o el monto del plan no coincide con el gasto';
        end if;
    elsif tg_table_name = 'installments' then
        if not exists (select 1 from installment_plans p where p.id = new.installment_plan_id and p.user_id = new.user_id) then
            raise exception 'El plan no pertenece al usuario de la cuota';
        end if;
    elsif tg_table_name = 'installment_payments' then
        if not exists (select 1 from installment_plans p where p.id = new.installment_plan_id and p.user_id = new.user_id) then
            raise exception 'El plan no pertenece al usuario del pago';
        end if;
    elsif tg_table_name = 'installment_payment_allocations' then
        if not exists (
            select 1
              from installment_payments ip
              join installments i on i.id = new.installment_id
             where ip.id = new.payment_id
               and ip.user_id = new.user_id
               and i.user_id = new.user_id
               and i.installment_plan_id = ip.installment_plan_id
        ) then
            raise exception 'La asignación debe pertenecer al mismo usuario y plan de cuotas';
        end if;
    end if;
    return new;
end;
$$;
create trigger installment_plans_validate_ownership before insert or update on installment_plans
    for each row execute function validate_installment_ownership();
create trigger installments_validate_ownership before insert or update on installments
    for each row execute function validate_installment_ownership();
create trigger installment_payments_validate_ownership before insert or update on installment_payments
    for each row execute function validate_installment_ownership();
create trigger installment_payment_allocations_validate_ownership before insert or update on installment_payment_allocations
    for each row execute function validate_installment_ownership();

-- La suma asignada debe ser exactamente el comprobante y nunca puede superar una cuota.
create or replace function validate_payment_allocations()
returns trigger language plpgsql as $$
declare
    v_payment_id uuid := case when tg_op = 'DELETE' then old.payment_id else new.payment_id end;
    v_payment_total numeric(12,2);
    v_allocated_total numeric(12,2);
    v_plan_id uuid;
begin
    select total_amount, installment_plan_id into v_payment_total, v_plan_id
      from installment_payments where id = v_payment_id;
    if not found then
        return null; -- comprobante eliminado en cascada
    end if;
    select coalesce(sum(amount), 0) into v_allocated_total
      from installment_payment_allocations where payment_id = v_payment_id;
    if v_allocated_total <> v_payment_total then
        raise exception 'La suma de asignaciones debe coincidir con el total pagado';
    end if;
    if exists (
        select 1
          from installments i
          join installment_payment_allocations a on a.installment_id = i.id
         where i.installment_plan_id = v_plan_id
         group by i.id, i.scheduled_amount
        having sum(a.amount) > i.scheduled_amount
    ) then
        raise exception 'Un pago no puede exceder el valor programado de una cuota';
    end if;
    return null;
end;
$$;
create constraint trigger installment_payments_validate_allocations
    after insert or update on installment_payments
    deferrable initially deferred for each row execute function validate_payment_allocations();
create constraint trigger installment_payment_allocations_validate_totals
    after insert or update or delete on installment_payment_allocations
    deferrable initially deferred for each row execute function validate_payment_allocations();

-- Mantiene el estado derivado de cada cuota y del plan después de registrar sus asignaciones.
create or replace function sync_installment_payment_status()
returns trigger language plpgsql as $$
declare
    v_installment_id uuid := case when tg_op = 'DELETE' then old.installment_id else new.installment_id end;
    v_plan_id uuid;
    v_paid numeric(12,2);
    v_scheduled numeric(12,2);
    v_settled_early boolean;
begin
    select installment_plan_id, scheduled_amount into v_plan_id, v_scheduled
      from installments where id = v_installment_id;
    if not found then
        return null;
    end if;
    select coalesce(sum(amount), 0) into v_paid
      from installment_payment_allocations where installment_id = v_installment_id;
    select exists (
        select 1 from installment_payment_allocations a
        join installment_payments ip on ip.id = a.payment_id
         where a.installment_id = v_installment_id
           and ip.payment_type = 'EARLY_SETTLEMENT'
    ) into v_settled_early;
    update installments
       set status = case when v_paid = 0 then 'PENDING'::installment_status
                         when v_paid < v_scheduled then 'PARTIALLY_PAID'::installment_status
                         when v_settled_early then 'SETTLED_EARLY'::installment_status
                         else 'PAID'::installment_status end,
           settled_at = case when v_paid >= v_scheduled then now() else null end
     where id = v_installment_id;
    update installment_plans p
       set status = case when not exists (
                                select 1 from installments i
                                 where i.installment_plan_id = v_plan_id
                                   and i.status in ('PENDING', 'PARTIALLY_PAID')
                            ) then 'SETTLED'::installment_plan_status
                         else 'ACTIVE'::installment_plan_status end,
           settled_at = case when not exists (
                                select 1 from installments i
                                 where i.installment_plan_id = v_plan_id
                                   and i.status in ('PENDING', 'PARTIALLY_PAID')
                            ) then now() else null end
     where p.id = v_plan_id;
    return null;
end;
$$;
create trigger installment_payment_allocations_sync_status after insert or update or delete
    on installment_payment_allocations for each row execute function sync_installment_payment_status();

-- El plan y sus cuotas deben nacer completos en la misma transacción: sin huecos, con el
-- número declarado por el usuario y con una suma idéntica al gasto original.
create or replace function validate_installment_plan_math()
returns trigger language plpgsql as $$
declare
    v_plan_id uuid;
    v_expected_count integer;
    v_original_amount numeric(12,2);
    v_actual_count integer;
    v_min_sequence integer;
    v_max_sequence integer;
    v_total numeric(12,2);
begin
    v_plan_id := case when tg_table_name = 'installment_plans' then new.id
                      when tg_op = 'DELETE' then old.installment_plan_id
                      else new.installment_plan_id end;
    select installment_count, original_amount
      into v_expected_count, v_original_amount
      from installment_plans where id = v_plan_id;
    if not found then
        return null; -- plan eliminado en cascada
    end if;
    select count(*), min(sequence_number), max(sequence_number), coalesce(sum(scheduled_amount), 0)
      into v_actual_count, v_min_sequence, v_max_sequence, v_total
      from installments where installment_plan_id = v_plan_id;
    if v_actual_count <> v_expected_count
       or v_min_sequence <> 1
       or v_max_sequence <> v_expected_count
       or v_total <> v_original_amount then
        raise exception 'Las cuotas deben ser consecutivas y sumar exactamente el monto original';
    end if;
    return null;
end;
$$;
create constraint trigger installment_plans_validate_math
    after insert or update on installment_plans
    deferrable initially deferred for each row execute function validate_installment_plan_math();
create constraint trigger installments_validate_math
    after insert or update or delete on installments
    deferrable initially deferred for each row execute function validate_installment_plan_math();

-- Se invoca al crear app_users. Las categorías quedan materializadas para permitir al usuario
-- renombrar o desactivar una predeterminada sin cambiar la categoría de otro usuario.
create or replace function create_user_system_categories(p_user_id uuid)
returns void language plpgsql as $$
begin
    insert into user_categories (user_id, system_category_id, origin, name, normalized_name)
    select p_user_id, sc.id, 'SYSTEM', sc.name, sc.normalized_name
      from system_categories sc
     where sc.active
    on conflict do nothing;
end;
$$;

create or replace function seed_user_system_categories()
returns trigger language plpgsql as $$
begin
    perform create_user_system_categories(new.id);
    return new;
end;
$$;
create trigger app_users_seed_system_categories after insert on app_users
    for each row execute function seed_user_system_categories();

-- Bitácora append-only. Las instantáneas JSONB permiten conservar el dato antes/después sin
-- depender de FKs que pudieran impedir una eventual purga de cuentas inactivas a los 6 meses.
create table audit_log (
    id uuid primary key default gen_random_uuid(),
    actor_user_id uuid references app_users(id),
    subject_user_id uuid not null references app_users(id),
    entity_type varchar(60) not null,
    entity_id uuid,
    action audit_action not null,
    before_value jsonb,
    after_value jsonb,
    created_at timestamptz not null default now()
);
create index audit_log_subject_created_idx on audit_log (subject_user_id, created_at desc);

create or replace function set_updated_at()
returns trigger language plpgsql as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create trigger user_categories_set_updated_at before update on user_categories
    for each row execute function set_updated_at();
create trigger cards_set_updated_at before update on cards
    for each row execute function set_updated_at();
create trigger user_consumption_selections_set_updated_at before update on user_consumption_selections
    for each row execute function set_updated_at();
create trigger incomes_set_updated_at before update on monthly_incomes
    for each row execute function set_updated_at();
create trigger budgets_set_updated_at before update on budget_allocations
    for each row execute function set_updated_at();
create trigger expenses_set_updated_at before update on expenses
    for each row execute function set_updated_at();

-- Impide que un gasto automático cambie monto o fecha. Para gastos manuales tampoco se puede
-- alterar la fecha, y el servicio debe rechazar cualquier cambio de gasto de un período cerrado.
create or replace function protect_expense_update()
returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Los gastos no se pueden eliminar';
    end if;
    if new.user_id <> old.user_id or new.card_id <> old.card_id or new.occurred_at <> old.occurred_at then
        raise exception 'El usuario, la tarjeta y la fecha de un gasto son inmutables';
    end if;
    if old.origin = 'AUTOMATIC' and new.amount <> old.amount then
        raise exception 'No se puede modificar el monto de un gasto automático';
    end if;
    if exists (
        select 1 from financial_periods p
         where p.user_id = old.user_id
           and p.period_month = date_trunc('month', old.occurred_at)::date
           and p.closed_at is not null
    ) then
        raise exception 'No se puede modificar un gasto de un período cerrado';
    end if;
    return new;
end;
$$;
create trigger expenses_protect_update before update on expenses
    for each row execute function protect_expense_update();
create trigger expenses_no_delete before delete on expenses
    for each row execute function protect_expense_update();

-- Las FKs simples validan existencia; esta validación evita combinar por error recursos de
-- distintos usuarios. En INSERT la tarjeta debe estar activa; desactivarla no altera gastos
-- históricos ya existentes.
create or replace function validate_expense_ownership()
returns trigger language plpgsql as $$
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
    return new;
end;
$$;
create trigger expenses_validate_ownership before insert or update on expenses
    for each row execute function validate_expense_ownership();

create or replace function validate_budget_ownership()
returns trigger language plpgsql as $$
begin
    if not exists (select 1 from financial_periods p where p.id = new.period_id and p.user_id = new.user_id) then
        raise exception 'El período no pertenece al usuario';
    end if;
    if new.card_id is not null and not exists (select 1 from cards c where c.id = new.card_id and c.user_id = new.user_id) then
        raise exception 'La tarjeta no pertenece al usuario del presupuesto';
    end if;
    if new.category_id is not null and not exists (select 1 from user_categories c where c.id = new.category_id and c.user_id = new.user_id) then
        raise exception 'La categoría no pertenece al usuario del presupuesto';
    end if;
    return new;
end;
$$;
create trigger budgets_validate_ownership before insert or update on budget_allocations
    for each row execute function validate_budget_ownership();

create or replace function validate_category_rule_ownership()
returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from user_categories c
         where c.id = new.category_id and c.user_id = new.user_id
    ) then
        raise exception 'La categoría no pertenece al usuario de la selección';
    end if;
    return new;
end;
$$;
create trigger user_consumption_selections_validate_ownership before insert or update on user_consumption_selections
    for each row execute function validate_category_rule_ownership();

create or replace function validate_pending_suggestions_ownership()
returns trigger language plpgsql as $$
begin
    if new.suggested_card_id is not null and not exists (
        select 1 from cards c where c.id = new.suggested_card_id and c.user_id = new.user_id
    ) then
        raise exception 'La tarjeta sugerida no pertenece al usuario';
    end if;
    if new.suggested_category_id is not null and not exists (
        select 1 from user_categories c where c.id = new.suggested_category_id and c.user_id = new.user_id
    ) then
        raise exception 'La categoría sugerida no pertenece al usuario';
    end if;
    return new;
end;
$$;
create trigger pending_movements_validate_suggestions before insert or update on pending_movements
    for each row execute function validate_pending_suggestions_ownership();

create or replace function reject_audit_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'audit_log es append-only';
end;
$$;
create trigger audit_log_no_update before update on audit_log
    for each row execute function reject_audit_mutation();
create trigger audit_log_no_delete before delete on audit_log
    for each row execute function reject_audit_mutation();

-- Información útil para reportes. No guarda resultados para no desincronizarlos de los gastos.
create view v_monthly_expense_summary as
select x.user_id,
       x.period_month,
       x.category_id,
       x.card_id,
       sum(x.amount) as spent_amount,
       count(*) as expense_count
  from (
        -- Gasto normal: consume el presupuesto del mes de compra.
        select e.user_id, date_trunc('month', e.occurred_at)::date as period_month,
               e.category_id, e.card_id, e.amount_usd as amount
          from expenses e
         where not exists (select 1 from installment_plans p where p.expense_id = e.id)
        union all
        -- Gasto diferido: cada cuota consume el presupuesto de su mes programado.
        select e.user_id, date_trunc('month', i.due_date)::date as period_month,
               e.category_id, e.card_id,
               round(i.scheduled_amount * e.exchange_rate_to_usd, 2) as amount
          from installments i
          join installment_plans p on p.id = i.installment_plan_id
          join expenses e on e.id = p.expense_id
       ) x
 group by x.user_id, x.period_month, x.category_id, x.card_id;

-- Categorías iniciales: el backend las inserta para cada usuario en el alta.
-- Comida, Ropa, Electrónicos, Casa, Suscripciones, Transporte, Salud, Entretenimiento, Otros.
-- Se recomienda que business-svc ejecute la confirmación de un pendiente de forma atómica:
-- tarjeta opcional + selección de categoría opcional + gasto + estado CONFIRMED + auditoría.

-- Monedas inicialmente habilitadas. USD es la única base de reportes/presupuestos en Ecuador;
-- las demás permiten guardar consumos externos junto con la tasa usada en el momento del registro.
insert into currencies (code, numeric_code, name, symbol, decimal_places, is_base_currency) values
    ('USD', '840', 'Dólar estadounidense', '$', 2, true),
    ('EUR', '978', 'Euro', '€', 2, false),
    ('COP', '170', 'Peso colombiano', 'COP$', 2, false),
    ('PEN', '604', 'Sol peruano', 'S/', 2, false),
    ('MXN', '484', 'Peso mexicano', 'MX$', 2, false),
    ('GBP', '826', 'Libra esterlina', '£', 2, false),
    ('CAD', '124', 'Dólar canadiense', 'CA$', 2, false)
on conflict (code) do update set
    numeric_code = excluded.numeric_code,
    name = excluded.name,
    symbol = excluded.symbol,
    decimal_places = excluded.decimal_places,
    active = true;

insert into system_categories (name, normalized_name, display_order) values
    ('Comida', 'COMIDA', 1),
    ('Ropa', 'ROPA', 2),
    ('Electrónicos', 'ELECTRONICOS', 3),
    ('Casa', 'CASA', 4),
    ('Suscripciones', 'SUSCRIPCIONES', 5),
    ('Transporte', 'TRANSPORTE', 6),
    ('Salud', 'SALUD', 7),
    ('Entretenimiento', 'ENTRETENIMIENTO', 8),
    ('Otros', 'OTROS', 9)
on conflict (normalized_name) do update set
    name = excluded.name,
    display_order = excluded.display_order,
    active = true;

-- Catálogo inicial de bancos supervisados en Ecuador. Se deriva del Catastro Público de la
-- Superintendencia de Bancos; debe revisarse periódicamente antes de un despliegue productivo.
insert into banks (name, normalized_name) values
    ('Banco Amazonas S.A.', 'BANCO AMAZONAS'),
    ('Banco de la Producción S.A. (Produbanco)', 'PRODUBANCO'),
    ('Banco del Austro S.A.', 'BANCO DEL AUSTRO'),
    ('Banco Solidario S.A.', 'BANCO SOLIDARIO'),
    ('Banco Guayaquil S.A.', 'BANCO GUAYAQUIL'),
    ('Banco Sudamericano S.A.', 'BANCO SUDAMERICANO'),
    ('Banco Bolivariano C.A.', 'BANCO BOLIVARIANO'),
    ('Banco Coopnacional S.A.', 'BANCO COOPNACIONAL'),
    ('Banco Comercial Manabí S.A.', 'BANCO COMERCIAL MANABI'),
    ('Banco ProCredit S.A.', 'BANCO PROCREDIT'),
    ('Banco del Litoral S.A.', 'BANCO DEL LITORAL'),
    ('Banco Capital S.A.', 'BANCO CAPITAL'),
    ('Banco General Rumiñahui S.A.', 'BANCO GENERAL RUMINAHUI'),
    ('Banco Delbank S.A.', 'BANCO DELBANK'),
    ('Banco Internacional S.A.', 'BANCO INTERNACIONAL'),
    ('Banco Atlántida S.A.', 'BANCO ATLANTIDA'),
    ('Banco de Loja S.A.', 'BANCO DE LOJA'),
    ('Banco Desarrollo de los Pueblos S.A. Codesarrollo', 'CODESARROLLO'),
    ('Banco de Machala S.A.', 'BANCO DE MACHALA'),
    ('Banco VisionFund Ecuador S.A.', 'BANCO VISIONFUND ECUADOR'),
    ('Banco del Pacífico S.A.', 'BANCO DEL PACIFICO'),
    ('Banco Diners Club del Ecuador S.A.', 'BANCO DINERS CLUB'),
    ('Banco Pichincha C.A.', 'BANCO PICHINCHA'),
    ('Citibank N.A. Sucursal Ecuador', 'CITIBANK'),
    ('Banco de Desarrollo del Ecuador B.P.', 'BANCO DE DESARROLLO DEL ECUADOR'),
    ('Corporación Financiera Nacional B.P.', 'CORPORACION FINANCIERA NACIONAL'),
    ('Banco del Instituto Ecuatoriano de Seguridad Social - BIESS', 'BIESS'),
    ('BanEcuador B.P.', 'BANECUADOR')
on conflict (normalized_name) do update set name = excluded.name, active = true;

-- Diccionario del esquema: visible en Supabase Studio y herramientas PostgreSQL.
comment on table app_users is 'Usuario local de Paktay identificado por el claim sub de Keycloak; no almacena credenciales ni JWT.';
comment on column app_users.id is 'UUID sub emitido por Keycloak.';
comment on column app_users.status is 'Estado de acceso administrado por el backend: ACTIVE o INACTIVE.';
comment on column app_users.deactivated_at is 'Fecha y hora de desactivación de la cuenta.';
comment on column app_users.created_at is 'Fecha y hora de creación del registro local.';

comment on table currencies is 'Catálogo de monedas ISO 4217 disponibles para registrar importes.';
comment on column currencies.code is 'Código alfabético ISO 4217, por ejemplo USD.';
comment on column currencies.numeric_code is 'Código numérico ISO 4217.';
comment on column currencies.name is 'Nombre legible de la moneda.';
comment on column currencies.symbol is 'Símbolo usado para mostrar la moneda.';
comment on column currencies.decimal_places is 'Cantidad de decimales admitidos por la moneda.';
comment on column currencies.active is 'Indica si la moneda puede usarse en nuevos registros.';
comment on column currencies.is_base_currency is 'Indica la moneda base única de reportes y presupuestos: USD.';

comment on table banks is 'Catálogo de bancos autorizados y disponibles para asociar tarjetas.';
comment on column banks.id is 'Identificador interno del banco.';
comment on column banks.name is 'Nombre legal o comercial mostrado al usuario.';
comment on column banks.normalized_name is 'Nombre normalizado único para integración y búsquedas.';
comment on column banks.active is 'Indica si el banco está disponible para nuevas tarjetas.';
comment on column banks.created_at is 'Fecha y hora de alta del banco en el catálogo.';

comment on table system_categories is 'Catálogo global de categorías predeterminadas administrado exclusivamente por Admin.';
comment on column system_categories.id is 'Identificador interno de la categoría predeterminada.';
comment on column system_categories.name is 'Nombre visible de la categoría predeterminada.';
comment on column system_categories.normalized_name is 'Nombre único normalizado para búsquedas y duplicados.';
comment on column system_categories.active is 'Indica si se copia a usuarios nuevos y se ofrece para uso.';
comment on column system_categories.display_order is 'Orden de visualización dentro del catálogo.';
comment on column system_categories.created_at is 'Fecha y hora de creación en el catálogo global.';

comment on table user_categories is 'Categorías que un usuario usa en sus gastos: copias de sistema o categorías propias.';
comment on column user_categories.id is 'Identificador interno de la categoría del usuario.';
comment on column user_categories.user_id is 'Usuario propietario de la categoría.';
comment on column user_categories.system_category_id is 'Categoría global de origen; es nulo en categorías propias.';
comment on column user_categories.origin is 'Origen SYSTEM para copia predeterminada o CUSTOM para creación del usuario.';
comment on column user_categories.name is 'Nombre visible, editable por el usuario.';
comment on column user_categories.normalized_name is 'Nombre normalizado único dentro del usuario.';
comment on column user_categories.active is 'Indica si puede asignarse a nuevos gastos; no borra historial.';
comment on column user_categories.created_at is 'Fecha y hora de creación de la categoría del usuario.';
comment on column user_categories.updated_at is 'Fecha y hora de última modificación.';

comment on table cards is 'Tarjetas registradas por el usuario; solo se conserva banco, alias y últimos cuatro dígitos.';
comment on column cards.id is 'Identificador interno de la tarjeta.';
comment on column cards.user_id is 'Usuario propietario de la tarjeta.';
comment on column cards.bank_id is 'Banco emisor seleccionado del catálogo.';
comment on column cards.alias is 'Nombre amigable de la tarjeta para el usuario.';
comment on column cards.last4 is 'Últimos cuatro dígitos; nunca se almacena el número completo.';
comment on column cards.default_currency_code is 'Moneda predeterminada propuesta al registrar gastos de la tarjeta.';
comment on column cards.status is 'Estado ACTIVE o INACTIVE; una tarjeta inactiva no recibe gastos nuevos.';
comment on column cards.deactivated_at is 'Fecha y hora de desactivación; no permite reactivación.';
comment on column cards.created_at is 'Fecha y hora de registro de la tarjeta.';
comment on column cards.updated_at is 'Fecha y hora de última modificación permitida.';

comment on table user_consumption_selections is 'Elección persistida que relaciona el nombre de un consumo recibido con una categoría del mismo usuario.';
comment on column user_consumption_selections.id is 'Identificador interno de la selección.';
comment on column user_consumption_selections.user_id is 'Usuario propietario de la selección.';
comment on column user_consumption_selections.consumption_name is 'Nombre original del consumo, por ejemplo STARBUCKS QUICENTRO.';
comment on column user_consumption_selections.merchant_normalized is 'Nombre normalizado usado para reconocer consumos similares.';
comment on column user_consumption_selections.normalization_version is 'Versión del algoritmo de normalización aplicado.';
comment on column user_consumption_selections.category_id is 'Categoría seleccionada por el usuario para ese consumo.';
comment on column user_consumption_selections.active is 'Indica si la sugerencia automática sigue vigente.';
comment on column user_consumption_selections.selection_count is 'Número de veces que la selección ha sido utilizada.';
comment on column user_consumption_selections.last_selected_at is 'Fecha y hora del último uso de la selección.';
comment on column user_consumption_selections.created_at is 'Fecha y hora de creación de la selección.';
comment on column user_consumption_selections.updated_at is 'Fecha y hora de última modificación.';

comment on table pending_movements is 'Bandeja persistente de eventos recibidos por Shortcut o Android antes de confirmar o descartar el gasto.';
comment on column pending_movements.id is 'Identificador interno del movimiento pendiente.';
comment on column pending_movements.user_id is 'Usuario dueño del evento recibido.';
comment on column pending_movements.idempotency_key is 'UUID único enviado por el móvil para evitar duplicar el mismo evento.';
comment on column pending_movements.source is 'Canal de origen: IOS_SHORTCUT o ANDROID_NOTIFICATION.';
comment on column pending_movements.raw_payload is 'Carga original recibida del canal, preservada para trazabilidad.';
comment on column pending_movements.raw_text is 'Texto original de la notificación o Shortcut cuando exista.';
comment on column pending_movements.parsed_amount is 'Monto extraído; puede ser nulo si el evento no se pudo parsear.';
comment on column pending_movements.parsed_currency_code is 'Moneda extraída o propuesta para el movimiento.';
comment on column pending_movements.merchant_raw is 'Nombre de comercio extraído sin normalizar.';
comment on column pending_movements.merchant_normalized is 'Nombre de comercio normalizado para buscar selecciones previas.';
comment on column pending_movements.normalization_version is 'Versión de la normalización aplicada al comercio.';
comment on column pending_movements.bank_id is 'Banco detectado; puede ser nulo si no se logró identificar.';
comment on column pending_movements.last4 is 'Últimos cuatro dígitos detectados; puede ser nulo si no se logró extraer.';
comment on column pending_movements.occurred_at is 'Fecha y hora detectada del consumo.';
comment on column pending_movements.suggested_card_id is 'Tarjeta del usuario propuesta por banco y últimos cuatro.';
comment on column pending_movements.suggested_category_id is 'Categoría propuesta por una selección previa del usuario.';
comment on column pending_movements.parse_error is 'Razón técnica cuando el evento no pudo parsearse completamente.';
comment on column pending_movements.status is 'Estado PENDING, CONFIRMED o DISCARDED.';
comment on column pending_movements.resolved_at is 'Fecha y hora de confirmación o descarte.';
comment on column pending_movements.created_at is 'Fecha y hora de recepción del evento.';

comment on table financial_periods is 'Períodos contables mensuales del usuario usados por ingresos, presupuestos y cierres.';
comment on column financial_periods.id is 'Identificador interno del período.';
comment on column financial_periods.user_id is 'Usuario propietario del período mensual.';
comment on column financial_periods.period_month is 'Primer día del mes que representa el período.';
comment on column financial_periods.closed_at is 'Fecha y hora de cierre; impide editar gastos de ese mes.';
comment on column financial_periods.created_at is 'Fecha y hora de creación del período.';

comment on table monthly_incomes is 'Ingresos mensuales declarados por el usuario, globales o vinculados a una tarjeta.';
comment on column monthly_incomes.id is 'Identificador interno del ingreso.';
comment on column monthly_incomes.user_id is 'Usuario propietario del ingreso.';
comment on column monthly_incomes.period_id is 'Período mensual al que pertenece el ingreso.';
comment on column monthly_incomes.card_id is 'Tarjeta asociada al ingreso; nulo para ingreso global.';
comment on column monthly_incomes.amount is 'Monto original declarado por el usuario.';
comment on column monthly_incomes.currency_code is 'Moneda del monto original.';
comment on column monthly_incomes.exchange_rate_to_usd is 'Tasa histórica de una unidad de la moneda a USD.';
comment on column monthly_incomes.amount_usd is 'Equivalente en USD calculado y almacenado por PostgreSQL.';
comment on column monthly_incomes.created_at is 'Fecha y hora de creación.';
comment on column monthly_incomes.updated_at is 'Fecha y hora de última modificación.';

comment on table budget_allocations is 'Presupuestos informativos mensuales por tarjeta, categoría o combinación tarjeta-categoría.';
comment on column budget_allocations.id is 'Identificador interno de la asignación presupuestaria.';
comment on column budget_allocations.user_id is 'Usuario propietario del presupuesto.';
comment on column budget_allocations.period_id is 'Período mensual del presupuesto.';
comment on column budget_allocations.scope is 'Alcance CARD, CATEGORY o CARD_CATEGORY.';
comment on column budget_allocations.card_id is 'Tarjeta objetivo; obligatoria según el alcance.';
comment on column budget_allocations.category_id is 'Categoría objetivo; obligatoria según el alcance.';
comment on column budget_allocations.amount is 'Monto planeado en la moneda seleccionada.';
comment on column budget_allocations.currency_code is 'Moneda del presupuesto.';
comment on column budget_allocations.exchange_rate_to_usd is 'Tasa histórica aplicada para reportar el presupuesto en USD.';
comment on column budget_allocations.amount_usd is 'Equivalente en USD calculado y almacenado por PostgreSQL.';
comment on column budget_allocations.alert_threshold is 'Porcentaje consumido que dispara una alerta, por defecto 90 por ciento.';
comment on column budget_allocations.active is 'Indica si el presupuesto participa en cálculos y alertas.';
comment on column budget_allocations.created_at is 'Fecha y hora de creación.';
comment on column budget_allocations.updated_at is 'Fecha y hora de última modificación.';

comment on table expenses is 'Gastos confirmados del usuario, manuales o automáticos; nunca se eliminan.';
comment on column expenses.id is 'Identificador interno del gasto.';
comment on column expenses.user_id is 'Usuario propietario del gasto.';
comment on column expenses.card_id is 'Tarjeta usada en el gasto.';
comment on column expenses.category_id is 'Categoría asignada al gasto.';
comment on column expenses.pending_movement_id is 'Movimiento de origen cuando el gasto llegó automáticamente.';
comment on column expenses.origin is 'Origen MANUAL o AUTOMATIC; el monto automático es inmutable.';
comment on column expenses.amount is 'Monto original del consumo.';
comment on column expenses.currency_code is 'Moneda del consumo.';
comment on column expenses.exchange_rate_to_usd is 'Tasa histórica usada para convertir el gasto a USD.';
comment on column expenses.amount_usd is 'Equivalente en USD calculado y almacenado por PostgreSQL.';
comment on column expenses.merchant_raw is 'Comercio o descripción capturada originalmente.';
comment on column expenses.merchant_normalized is 'Comercio normalizado para sugerir categorías.';
comment on column expenses.normalization_version is 'Versión de normalización aplicada al comercio.';
comment on column expenses.occurred_at is 'Fecha y hora del consumo; no es editable.';
comment on column expenses.is_recurring is 'Indica si el usuario marcó el gasto como recurrente.';
comment on column expenses.recurrence_day is 'Día mensual propuesto para la recurrencia, entre 1 y 31.';
comment on column expenses.created_at is 'Fecha y hora de creación del gasto.';
comment on column expenses.updated_at is 'Fecha y hora de última modificación permitida.';

comment on table installment_plans is 'Plan sin interés que divide un gasto confirmado en cuotas mensuales.';
comment on column installment_plans.id is 'Identificador interno del plan.';
comment on column installment_plans.user_id is 'Usuario propietario del plan.';
comment on column installment_plans.expense_id is 'Gasto original diferido; solo puede tener un plan.';
comment on column installment_plans.original_amount is 'Monto original del gasto, igual al monto del gasto asociado.';
comment on column installment_plans.interest_rate is 'Tasa de interés; en v0.1 debe ser cero.';
comment on column installment_plans.installment_count is 'Cantidad de cuotas declarada por el usuario, sin máximo.';
comment on column installment_plans.first_due_date is 'Fecha de la primera cuota programada.';
comment on column installment_plans.status is 'Estado ACTIVE o SETTLED del plan.';
comment on column installment_plans.settled_at is 'Fecha y hora en que todas las cuotas quedaron pagadas o liquidadas.';
comment on column installment_plans.created_at is 'Fecha y hora de creación del plan.';

comment on table installments is 'Cuotas programadas de un plan; una fila representa una obligación mensual.';
comment on column installments.id is 'Identificador interno de la cuota.';
comment on column installments.user_id is 'Usuario propietario de la cuota.';
comment on column installments.installment_plan_id is 'Plan de cuotas al que pertenece.';
comment on column installments.sequence_number is 'Número consecutivo de cuota dentro del plan.';
comment on column installments.due_date is 'Fecha programada de la cuota.';
comment on column installments.scheduled_amount is 'Monto programado de la cuota.';
comment on column installments.status is 'Estado PENDING, PARTIALLY_PAID, PAID o SETTLED_EARLY.';
comment on column installments.settled_at is 'Fecha y hora en que la cuota quedó cubierta.';

comment on table installment_payments is 'Comprobantes de pagos manuales declarados contra un plan de cuotas.';
comment on column installment_payments.id is 'Identificador interno del comprobante de pago.';
comment on column installment_payments.user_id is 'Usuario que declara el pago.';
comment on column installment_payments.installment_plan_id is 'Plan de cuotas que recibe el pago.';
comment on column installment_payments.payment_type is 'Pago normal de cuota o liquidación anticipada.';
comment on column installment_payments.total_amount is 'Monto total declarado en el comprobante.';
comment on column installment_payments.paid_at is 'Fecha y hora declarada del pago.';
comment on column installment_payments.created_at is 'Fecha y hora de registro en Paktay.';

comment on table installment_payment_allocations is 'Distribución de un comprobante de pago entre una o varias cuotas.';
comment on column installment_payment_allocations.id is 'Identificador interno de la asignación.';
comment on column installment_payment_allocations.user_id is 'Usuario propietario de la asignación.';
comment on column installment_payment_allocations.payment_id is 'Comprobante de pago del que proviene el monto.';
comment on column installment_payment_allocations.installment_id is 'Cuota a la que se aplica el monto.';
comment on column installment_payment_allocations.amount is 'Valor del comprobante aplicado a esta cuota.';
comment on column installment_payment_allocations.created_at is 'Fecha y hora de creación de la asignación.';

comment on table audit_log is 'Bitácora append-only de acciones relevantes; no permite actualización ni eliminación.';
comment on column audit_log.id is 'Identificador interno de la entrada de auditoría.';
comment on column audit_log.actor_user_id is 'Usuario que ejecutó la acción; puede ser nulo para procesos técnicos.';
comment on column audit_log.subject_user_id is 'Usuario dueño de los datos afectados.';
comment on column audit_log.entity_type is 'Nombre lógico de la entidad afectada.';
comment on column audit_log.entity_id is 'Identificador de la entidad afectada cuando exista.';
comment on column audit_log.action is 'Tipo de operación realizada.';
comment on column audit_log.before_value is 'Instantánea JSON anterior a una modificación.';
comment on column audit_log.after_value is 'Instantánea JSON posterior a una modificación.';
comment on column audit_log.created_at is 'Fecha y hora inmutable del evento de auditoría.';

commit;
