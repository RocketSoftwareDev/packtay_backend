-- Paktay v0.9 — tarjetas libres y gastos persistidos/idempotentes.
begin;

drop index if exists cards_active_identity_uq;

alter table cards add column if not exists name varchar(80);
update cards set name = alias where name is null and alias is not null;
update cards set name = 'Tarjeta • ' || last4 where name is null;
alter table cards alter column name set not null;

alter table cards add column if not exists color_dark char(7);
alter table cards add column if not exists color_light char(7);
update cards set color_dark = '#2563EB' where color_dark is null;
update cards set color_light = '#60A5FA' where color_light is null;
alter table cards alter column color_dark set not null;
alter table cards alter column color_light set not null;

alter table cards drop constraint if exists cards_color_dark_check;
alter table cards add constraint cards_color_dark_check check (color_dark ~ '^#[0-9A-Fa-f]{6}$');
alter table cards drop constraint if exists cards_color_light_check;
alter table cards add constraint cards_color_light_check check (color_light ~ '^#[0-9A-Fa-f]{6}$');
alter table cards drop constraint if exists cards_card_type_check;
alter table cards add constraint cards_card_type_check check (card_type in ('CREDIT', 'DEBIT'));

alter table cards drop column if exists card_product_id;
alter table cards drop column if exists network;
alter table cards drop column if exists alias;

create unique index if not exists cards_active_identity_uq
    on cards (user_id, bank_id, last4) where status = 'ACTIVE';

-- La clave que genera el móvil hace seguro reintentar un POST después de perder conexión.
alter table expenses add column if not exists idempotency_key uuid;
create unique index if not exists expenses_user_idempotency_uq
    on expenses (user_id, idempotency_key) where idempotency_key is not null;

comment on table cards is 'Tarjetas del usuario sin relación con productos o marcas predefinidas.';
comment on column cards.name is 'Nombre libre que muestra el front (por ejemplo Visa personal).';
comment on column cards.color_dark is 'Color hexadecimal para tema oscuro enviado por el usuario.';
comment on column cards.color_light is 'Color hexadecimal para tema claro enviado por el usuario.';
comment on column expenses.idempotency_key is 'UUID del cliente para evitar gastos duplicados al reintentar.';

commit;
