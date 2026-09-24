-- Paktay V5: anulación de gastos (día 4, 2026-09-24).
--
-- Bloques:
--   1. Acción de auditoría VOID (anular un gasto). Editar un gasto usa UPDATE,
--      que ya existía desde V1.
--   2. validate_expense_ownership: el requisito de tarjeta ACTIVE en el alta sólo
--      aplica a consumos (kind = 'EXPENSE'). El registro compensatorio (REFUND) de
--      una anulación va a la misma tarjeta del gasto original, que puede estar
--      INACTIVE o DELETED desde entonces; ese registro no es un gasto nuevo.
--
-- Nota sobre enums (igual que en V4): ALTER TYPE ... ADD VALUE puede ir dentro de
-- la transacción de Flyway, pero el valor nuevo no se puede usar hasta que la
-- transacción confirme. Esta migración no escribe el literal 'VOID' en ningún
-- otro lugar.

------------------------------------------------------------------------------
-- 1. Bitácora de auditoría
------------------------------------------------------------------------------
alter type public.audit_action add value if not exists 'VOID';

------------------------------------------------------------------------------
-- 2. Propiedad del gasto y tarjeta activa
------------------------------------------------------------------------------
-- Sin cambios respecto de V4 salvo la condición kind = 'EXPENSE' en la regla del
-- alta. La tarjeta y la categoría deben seguir perteneciendo al usuario en todos
-- los casos, y reasignar un gasto sigue exigiendo una tarjeta ACTIVE.
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
    if tg_op = 'INSERT' and new.kind = 'EXPENSE'
       and not exists (select 1 from cards c where c.id = new.card_id and c.status = 'ACTIVE') then
        raise exception 'No se puede registrar un gasto en una tarjeta inactiva';
    end if;
    if tg_op = 'UPDATE' and new.card_id <> old.card_id
       and not exists (select 1 from cards c where c.id = new.card_id and c.status = 'ACTIVE') then
        raise exception 'Sólo se puede asignar un gasto a una tarjeta activa';
    end if;
    return new;
end;
$$;
