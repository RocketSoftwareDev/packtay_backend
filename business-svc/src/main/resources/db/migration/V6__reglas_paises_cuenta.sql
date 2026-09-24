-- Paktay V6: día 6 (2026-09-24).
--
-- Bloques:
--   1. Países: catálogo país → moneda. El atajo manda el país donde estaba el
--      teléfono; si su moneda no es USD, la captura se queda en "Por revisar" hasta
--      que el usuario escriba lo que le cobró el banco en dólares.
--   2. Gastos en otra moneda: amount sigue siendo lo que cobró el banco en USD (lo
--      que suma en los totales) y original_amount/original_currency_code guardan el
--      monto de la compra como referencia. country_code es la pista del atajo.
--   3. Reglas por comercio (normalization_version = 2): la clave es el comercio
--      hasta el primer bloque con números, máximo dos palabras ("FYBECA 123 QUITO"
--      → "FYBECA"). Se migran las reglas v1 a v2.
--   4. Origen de Wallet: hasta hoy toda captura se guardaba como MANUAL. Las que
--      asignó una regla (assigned_by_rule) seguro vinieron de Wallet.
--   5. Duplicados: acción de auditoría DUPLICATE para el segundo envío de una misma
--      captura (mismo comercio, monto y tarjeta con menos de 60 s de diferencia).
--   6. Eliminación de cuenta: purge_user borra todo lo del usuario. Es la única vía
--      por la que un gasto o una fila reciente de auditoría se pueden borrar.
--
-- Nota sobre enums (igual que en V4 y V5): el valor DUPLICATE no se usa dentro de
-- esta migración.

------------------------------------------------------------------------------
-- 1. Países
------------------------------------------------------------------------------
create table if not exists public.countries (
    code character(2) not null primary key,
    name character varying(80) not null,
    currency_code character(3) not null references public.currencies(code),
    active boolean default true not null,
    constraint countries_code_check check (code ~ '^[A-Z]{2}$')
);

comment on table public.countries is 'País ISO 3166-1 alfa-2 y su moneda. Agregar un país es una migración nueva.';

insert into public.countries (code, name, currency_code) values
    ('EC', 'Ecuador', 'USD'),
    ('PA', 'Panamá', 'USD'),
    ('SV', 'El Salvador', 'USD'),
    ('US', 'Estados Unidos', 'USD'),
    ('CO', 'Colombia', 'COP'),
    ('PE', 'Perú', 'PEN'),
    ('MX', 'México', 'MXN'),
    ('GB', 'Reino Unido', 'GBP'),
    ('CA', 'Canadá', 'CAD'),
    ('ES', 'España', 'EUR'),
    ('FR', 'Francia', 'EUR'),
    ('DE', 'Alemania', 'EUR'),
    ('IT', 'Italia', 'EUR'),
    ('PT', 'Portugal', 'EUR'),
    ('NL', 'Países Bajos', 'EUR')
on conflict (code) do nothing;

------------------------------------------------------------------------------
-- 2. Gastos en otra moneda
------------------------------------------------------------------------------
alter table public.expenses
    add column if not exists original_amount numeric(14,2),
    add column if not exists original_currency_code character(3) references public.currencies(code),
    add column if not exists country_code character(2);

alter table public.expenses
    add constraint expenses_original_pair_check check (
        (original_amount is null and original_currency_code is null)
        or (original_amount > 0 and original_currency_code is not null)
    ),
    add constraint expenses_country_code_check check (country_code is null or country_code ~ '^[A-Z]{2}$');

comment on column public.expenses.original_amount is 'Monto de la compra en su moneda original, sólo como referencia; amount es lo que cobró el banco.';
comment on column public.expenses.original_currency_code is 'Moneda de original_amount.';
comment on column public.expenses.country_code is 'País donde estaba el teléfono al capturar (pista del atajo); puede ser null.';

------------------------------------------------------------------------------
-- 3. Reglas por comercio v2
------------------------------------------------------------------------------
-- Recibe el comercio ya normalizado (mayúsculas, sin tildes ni símbolos, un
-- espacio entre palabras) y devuelve la clave de la regla. Misma lógica que
-- ec.paktay.business.service.MerchantKey.
create or replace function public.merchant_rule_key(normalized text) returns text
    language plpgsql immutable
    as $$
declare
    words text[] := string_to_array(btrim(coalesce(normalized, '')), ' ');
    kept text[] := '{}';
    w text;
begin
    foreach w in array words loop
        exit when w ~ '[0-9]';
        if w <> '' then kept := kept || w; end if;
        exit when array_length(kept, 1) = 2;
    end loop;
    if coalesce(array_length(kept, 1), 0) = 0 then
        -- Empieza por un bloque con números ("7ELEVEN 123"): se usan sus letras.
        foreach w in array words loop
            w := regexp_replace(w, '[0-9]', '', 'g');
            if w <> '' then kept := kept || w; end if;
            exit when array_length(kept, 1) = 2;
        end loop;
    end if;
    if coalesce(array_length(kept, 1), 0) = 0 then
        return nullif(btrim(coalesce(normalized, '')), '');
    end if;
    return array_to_string(kept, ' ');
end;
$$;

insert into public.user_consumption_selections (user_id, consumption_name, merchant_normalized,
    normalization_version, category_id, active, selection_count, last_selected_at)
select user_id, consumption_name, rule_key, 2, category_id, true, total, last_selected_at
  from (
    select s.user_id, s.consumption_name, public.merchant_rule_key(s.merchant_normalized) as rule_key,
           s.category_id, s.last_selected_at,
           sum(s.selection_count) over (partition by s.user_id, public.merchant_rule_key(s.merchant_normalized)) as total,
           row_number() over (partition by s.user_id, public.merchant_rule_key(s.merchant_normalized)
                              order by s.last_selected_at desc) as rn
      from public.user_consumption_selections s
      join public.user_categories uc on uc.id = s.category_id and uc.active
     where s.normalization_version = 1 and s.active
  ) v1
 where rn = 1 and rule_key is not null
on conflict (user_id, merchant_normalized, normalization_version) do nothing;

delete from public.user_consumption_selections where normalization_version = 1;

comment on table public.user_consumption_selections is 'Reglas comercio → categoría (v2: clave de merchant_rule_key). Las crean las capturas de Wallet asignadas en Por revisar y los cambios de categoría de un gasto de Wallet.';

------------------------------------------------------------------------------
-- 4. Origen de las capturas de Wallet guardadas como MANUAL
------------------------------------------------------------------------------
with fixed as (
    update public.expenses e set origin = 'AUTOMATIC'
     where e.origin = 'MANUAL' and e.kind = 'EXPENSE' and e.assigned_by_rule
       and not exists (select 1 from public.financial_periods p
                        where p.user_id = e.user_id and p.closed_at is not null
                          and p.period_month = date_trunc('month', e.occurred_at)::date)
    returning e.voided_by_expense_id
)
update public.expenses r set origin = 'AUTOMATIC'
  from fixed where r.id = fixed.voided_by_expense_id and r.origin = 'MANUAL';

------------------------------------------------------------------------------
-- 5. Auditoría de duplicados
------------------------------------------------------------------------------
alter type public.audit_action add value if not exists 'DUPLICATE';

------------------------------------------------------------------------------
-- 6. Eliminación de cuenta
------------------------------------------------------------------------------
create or replace function public.protect_expense_update() returns trigger
    language plpgsql
    as $$
begin
    if tg_op = 'DELETE' then
        if current_setting('paktay.purge_user', true) = old.user_id::text then
            return old;
        end if;
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

create or replace function public.reject_recent_audit_delete() returns trigger
    language plpgsql
    as $$
begin
    if old.created_at < now() - interval '90 days' then
        return old;
    end if;
    if current_setting('paktay.purge_user', true) in (old.subject_user_id::text, old.actor_user_id::text) then
        return old;
    end if;
    raise exception 'audit_log es append-only: sólo se purgan registros de más de 90 días';
end;
$$;

-- Identificadores de cuentas eliminadas, sin ningún otro dato. El token de acceso
-- sigue siendo válido unos minutos después de borrar el usuario en Keycloak; con
-- esta lista business-svc no vuelve a crear app_users si llega una petición tardía.
create table if not exists public.deleted_accounts (
    user_id uuid not null primary key,
    deleted_at timestamp with time zone default now() not null
);

comment on table public.deleted_accounts is 'Cuentas eliminadas (sólo el id): impide recrear app_users con un token que aún no vence.';

-- Borra todo lo del usuario. subscription_event no tiene llave hacia app_users y
-- se conserva: es el registro de compras de planes. Idempotente: con un usuario
-- que ya no existe no hace nada.
create or replace function public.purge_user(target uuid) returns void
    language plpgsql
    as $$
begin
    perform set_config('paktay.purge_user', target::text, true);
    delete from audit_log where subject_user_id = target or actor_user_id = target;
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

comment on function public.purge_user(uuid) is 'Eliminación de cuenta: borra todos los datos del usuario salvo subscription_event.';
