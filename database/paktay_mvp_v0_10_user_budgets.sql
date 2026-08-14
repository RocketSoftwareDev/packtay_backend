-- Paktay v0.10 — presupuesto global, selección de categorías y alias visual.
begin;

alter table user_categories add column if not exists alias varchar(80);
update user_categories set alias = name where alias is null;
alter table user_categories alter column alias set not null;
alter table user_categories drop constraint if exists user_categories_alias_check;
alter table user_categories add constraint user_categories_alias_check check (btrim(alias) <> '');

-- La función ya existía en instalaciones previas y debe incluir la nueva columna.
create or replace function create_user_system_categories(p_user_id uuid)
returns void language plpgsql as $$
begin
    insert into user_categories
        (user_id, system_category_id, origin, code, name, alias, normalized_name,
         icon, color_dark, color_light, sort_order)
    select p_user_id, sc.id, 'SYSTEM', sc.code, sc.name, sc.name, sc.normalized_name,
           sc.icon, sc.color_dark, sc.color_light, sc.display_order
      from system_categories sc
     where sc.active
    on conflict do nothing;
end;
$$;

create table if not exists user_budget_settings (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    period_id uuid not null references financial_periods(id),
    global_amount numeric(12,2) check (global_amount > 0),
    currency_code char(3) not null default 'USD' references currencies(code),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, period_id)
);

create table if not exists user_category_budgets (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    period_id uuid not null references financial_periods(id),
    category_id uuid not null references user_categories(id),
    individual_amount numeric(12,2) check (individual_amount > 0),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, period_id, category_id)
);

create index if not exists user_category_budgets_active_idx
    on user_category_budgets (user_id, period_id, active);

comment on table user_budget_settings is 'Presupuesto global mensual compartido por las categorías que el usuario seleccionó.';
comment on table user_category_budgets is 'Categorías incluidas en presupuesto; individual_amount reemplaza el global sólo para esa categoría.';
comment on column user_categories.alias is 'Nombre visual editable por el usuario sin modificar el nombre base del catálogo.';

commit;
