-- Paktay V12: pagos recurrentes (día 8a, 2026-09-30).
--
-- Un pago recurrente es una plantilla que crea el usuario (Netflix $15,99 cada 15). No es
-- un gasto: cada vez que toca, el servidor deja un cobro pendiente (recurring_occurrences)
-- que aparece en «Por revisar». El gasto real sólo existe cuando el usuario lo confirma, y
-- queda enlazado con expenses.recurring_payment_id.
--
-- Reglas del dueño:
--   - Mensual o anual. Día: primero del mes (FIRST), un día elegido (DAY; si el mes no lo
--     tiene, el último) o fin de mes (LAST). Anual: además, el mes.
--   - Fecha de fin opcional por mes y año (end_month = primer día del último mes con cobro).
--   - Estados: ACTIVE, PAUSED (no genera cobros ni cuenta para el límite del plan Gratis),
--     CANCELLED (fin; se conserva para el historial).
--   - Un cobro pendiente se confirma (CONFIRMED, crea el gasto), se descarta sólo ese mes
--     (SKIPPED, «Este mes no se cobró») o se cancela junto con la serie (CANCELLED).

create table if not exists public.recurring_payments (
    id uuid default gen_random_uuid() not null primary key,
    user_id uuid not null references public.app_users(id) on delete cascade,
    name character varying(180) not null,
    amount numeric(12,2) not null,
    currency_code character(3) default 'USD' not null references public.currencies(code),
    card_id uuid not null references public.cards(id) on delete cascade,
    category_id uuid not null references public.user_categories(id) on delete cascade,
    frequency character varying(8) not null,
    day_rule character varying(8) not null,
    day_of_month smallint,
    month_of_year smallint,
    end_month date,
    -- Primer día que puede generar un cobro. Creado desde «Agregar gasto · Se repite» es
    -- mañana (el gasto de hoy ya se guardó); al reanudar una pausa, hoy (no se recuperan
    -- los meses pausados).
    starts_on date default current_date not null,
    status character varying(10) default 'ACTIVE' not null,
    created_at timestamp with time zone default now() not null,
    updated_at timestamp with time zone default now() not null,
    cancelled_at timestamp with time zone,
    constraint recurring_payments_name_check check (btrim(name) <> ''),
    constraint recurring_payments_amount_check check (amount > 0),
    constraint recurring_payments_frequency_check check (frequency in ('MONTHLY', 'YEARLY')),
    constraint recurring_payments_day_rule_check check (day_rule in ('FIRST', 'DAY', 'LAST')),
    constraint recurring_payments_day_check check (
        (day_rule = 'DAY' and day_of_month between 1 and 31)
        or (day_rule <> 'DAY' and day_of_month is null)
    ),
    constraint recurring_payments_month_check check (
        (frequency = 'YEARLY' and month_of_year between 1 and 12)
        or (frequency = 'MONTHLY' and month_of_year is null)
    ),
    constraint recurring_payments_end_month_check check (end_month is null or extract(day from end_month) = 1),
    constraint recurring_payments_status_check check (status in ('ACTIVE', 'PAUSED', 'CANCELLED'))
);

create index if not exists recurring_payments_user_idx on public.recurring_payments (user_id, status);

comment on table public.recurring_payments is 'Pagos recurrentes del usuario (plantillas). Cada cobro que toca queda en recurring_occurrences.';

create table if not exists public.recurring_occurrences (
    id uuid default gen_random_uuid() not null primary key,
    recurring_payment_id uuid not null references public.recurring_payments(id) on delete cascade,
    user_id uuid not null references public.app_users(id) on delete cascade,
    due_date date not null,
    expected_amount numeric(12,2) not null,
    status character varying(10) default 'PENDING' not null,
    expense_id uuid references public.expenses(id) on delete set null,
    created_at timestamp with time zone default now() not null,
    resolved_at timestamp with time zone,
    constraint recurring_occurrences_amount_check check (expected_amount > 0),
    constraint recurring_occurrences_status_check check (status in ('PENDING', 'CONFIRMED', 'SKIPPED', 'CANCELLED'))
);

-- Un solo cobro por recurrente y fecha: el trabajo diario puede correr varias veces.
create unique index if not exists recurring_occurrences_once_uq
    on public.recurring_occurrences (recurring_payment_id, due_date);
create index if not exists recurring_occurrences_pending_idx
    on public.recurring_occurrences (user_id, status, due_date);

comment on table public.recurring_occurrences is 'Cobros de un pago recurrente: PENDING en Por revisar hasta que el usuario lo confirma, lo descarta ese mes o cancela la serie.';

-- Avisos de la víspera ya enviados: uno por usuario y fecha de cobro, aunque sean varios.
create table if not exists public.recurring_reminders (
    user_id uuid not null references public.app_users(id) on delete cascade,
    due_date date not null,
    sent_at timestamp with time zone default now() not null,
    primary key (user_id, due_date)
);

alter table public.expenses
    add column if not exists recurring_payment_id uuid references public.recurring_payments(id) on delete set null;

comment on column public.expenses.recurring_payment_id is 'Pago recurrente que originó este gasto al confirmarse; null en el resto.';

-- purge_user: igual que V9, más los recurrentes antes que tarjetas y categorías (sus
-- llaves en cascada también los borrarían, pero así el orden no depende de eso).
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
    delete from recurring_reminders where user_id = target;
    delete from recurring_occurrences where user_id = target;
    delete from recurring_payments where user_id = target;
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
