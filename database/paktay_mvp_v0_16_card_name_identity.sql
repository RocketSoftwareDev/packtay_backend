-- Paktay v0.16 — identifica tarjetas por el nombre que entrega el Shortcut.
begin;

alter table cards add column if not exists alias varchar(80);
alter table cards alter column last4 drop not null;
alter table cards drop constraint if exists cards_last4_check;
alter table cards add constraint cards_last4_check check (last4 is null or last4 ~ '^[0-9]{4}$');
alter table cards drop constraint if exists cards_alias_check;
alter table cards add constraint cards_alias_check check (alias is null or btrim(alias) <> '');

drop index if exists cards_active_identity_uq;

-- Los datos anteriores usaban name como alias y podían repetirlo. Conserva el
-- primer nombre y distingue sólo los duplicados históricos antes de imponer la
-- nueva identidad estable por nombre.
with duplicated_names as (
    select id,
           row_number() over (
               partition by user_id, lower(btrim(name))
               order by created_at, id
           ) as occurrence
      from cards
     where status = 'ACTIVE'
)
update cards c
   set name = left(btrim(c.name), 69) || ' #' || left(c.id::text, 8),
       updated_at = now()
  from duplicated_names d
 where c.id = d.id
   and d.occurrence > 1;

create unique index cards_active_identity_uq
    on cards (user_id, lower(btrim(name))) where status = 'ACTIVE';

alter table pending_movements add column if not exists card_name varchar(80);

comment on column cards.name is 'Nombre estable que entrega Wallet/Shortcut y se usa para identificar la tarjeta.';
comment on column cards.alias is 'Apodo visual opcional elegido por el usuario; no participa en la identificación.';
comment on column cards.last4 is 'Últimos cuatro dígitos opcionales; nunca se almacena el número completo.';
comment on column pending_movements.card_name is 'Nombre de tarjeta recibido del Shortcut y usado para sugerir una tarjeta existente.';

commit;
