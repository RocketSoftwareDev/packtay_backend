-- Día 9 · categorías: reactivar, eliminar y la reservada «Sin categoría» (bug de testers).
--
--   1. user_categories.reserved: la categoría «Sin categoría» de cada usuario. Recibe los
--      gastos viejos de una categoría eliminada (expenses.category_id es NOT NULL). No se
--      edita, no se elimina, no se presupuesta ni se asigna a gastos nuevos.
--   2. ensure_reserved_category(user): la crea si falta. Un trigger la crea con cada
--      app_users nuevo y aquí se crea para todos los que ya existen.
--   3. protect_expense_update: igual que V7, más una salida para mover los gastos de una
--      categoría eliminada aunque sean de un período cerrado (paktay.category_move).

alter table public.user_categories
    add column if not exists reserved boolean default false not null;

comment on column public.user_categories.reserved is
    'Categoría reservada «Sin categoría» del usuario: recibe los gastos de categorías eliminadas.';

create unique index if not exists user_categories_reserved_uq
    on public.user_categories (user_id) where reserved;

-- code y normalized_name internos (no visibles): no chocan con nada que el usuario cree.
create or replace function public.ensure_reserved_category(target uuid) returns uuid
    language plpgsql
    as $$
declare
    v_id uuid;
begin
    select id into v_id from user_categories where user_id = target and reserved;
    if v_id is not null then
        return v_id;
    end if;
    insert into user_categories
        (user_id, origin, code, name, alias, normalized_name, icon, color_dark, color_light, sort_order, reserved)
    values
        (target, 'CUSTOM', '__sin_categoria__', 'Sin categoría', 'Sin categoría', '__SIN_CATEGORIA__',
         'inbox', '#8C837B', '#6B635C', 32767, true)
    on conflict do nothing
    returning id into v_id;
    if v_id is null then
        select id into v_id from user_categories where user_id = target and reserved;
    end if;
    return v_id;
end;
$$;

comment on function public.ensure_reserved_category(uuid) is
    'Devuelve (y crea si falta) la categoría reservada «Sin categoría» del usuario.';

create or replace function public.app_users_reserved_category() returns trigger
    language plpgsql
    as $$
begin
    perform public.ensure_reserved_category(new.id);
    return new;
end;
$$;

drop trigger if exists app_users_reserved_category on public.app_users;
create trigger app_users_reserved_category
    after insert on public.app_users
    for each row execute function public.app_users_reserved_category();

select public.ensure_reserved_category(u.id) from public.app_users u;

create or replace function public.protect_expense_update() returns trigger
    language plpgsql
    as $$
declare
    v_timezone text;
begin
    if tg_op = 'DELETE' then
        if current_setting('paktay.purge_user', true) = old.user_id::text then
            return old;
        end if;
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
    -- Día 9 · eliminar una categoría mueve sus gastos a «Sin categoría»: solo cambia
    -- category_id y vale también en períodos cerrados.
    if current_setting('paktay.category_move', true) = old.user_id::text
       and new.amount = old.amount and new.status = old.status and new.card_id = old.card_id then
        return new;
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
