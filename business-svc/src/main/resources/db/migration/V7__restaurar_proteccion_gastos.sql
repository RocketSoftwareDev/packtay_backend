-- Paktay V7: corrige protect_expense_update (2026-09-24).
--
-- V6 reescribió la función partiendo de la versión de V1 para dejar pasar la
-- purga de una cuenta, y con eso perdió tres reglas que V4 había puesto:
--   - la tarjeta de un gasto se puede cambiar (el backend sólo lo permite en
--     gastos MANUAL, y validate_expense_ownership exige que sea ACTIVA);
--   - un gasto anulado no vuelve a ACTIVE;
--   - el período cerrado se calcula con la zona horaria del usuario.
-- Aquí vuelve la versión de V4 más la salida de la purga de V6.
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
