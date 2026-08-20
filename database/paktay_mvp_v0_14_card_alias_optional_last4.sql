-- Paktay v0.14 — el nombre real de la tarjeta identifica los consumos entrantes.
-- last4 deja de ser obligatorio (las notificaciones no siempre lo incluyen), se agrega
-- alias opcional para mostrar y name_normalized para asociar consumos por nombre.
begin;

alter table cards alter column last4 drop not null;
alter table cards drop constraint if exists cards_last4_check;
alter table cards add constraint cards_last4_check
    check (last4 is null or last4 ~ '^[0-9]{4}$');

alter table cards add column if not exists alias varchar(80);
alter table cards drop constraint if exists cards_alias_check;
alter table cards add constraint cards_alias_check
    check (alias is null or btrim(alias) <> '');

alter table cards add column if not exists name_normalized varchar(80);
update cards
   set name_normalized = btrim(regexp_replace(regexp_replace(upper(translate(name,
        'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')), '[^A-Z0-9 ]', ' ', 'g'), '\s+', ' ', 'g'))
 where name_normalized is null;
alter table cards alter column name_normalized set not null;

-- La unicidad por últimos cuatro sólo aplica cuando existen; las tarjetas sin dígitos
-- conviven y se distinguen por su nombre real.
drop index if exists cards_active_identity_uq;
create unique index cards_active_identity_uq
    on cards (user_id, bank_id, last4) where status = 'ACTIVE' and last4 is not null;

create index if not exists cards_active_name_normalized_idx
    on cards (user_id, bank_id, name_normalized) where status = 'ACTIVE';

alter table pending_movements add column if not exists card_name_raw varchar(120);

comment on column cards.name is 'Nombre real de la tarjeta tal como llega en las notificaciones del banco (ej. TITANIUM Visa).';
comment on column cards.name_normalized is 'Nombre real normalizado (sin tildes, mayúsculas, espacios simples) usado para asociar consumos entrantes.';
comment on column cards.alias is 'Apodo opcional definido por el usuario únicamente para mostrar.';
comment on column cards.last4 is 'Últimos cuatro dígitos; opcionales porque no siempre pueden obtenerse. Nunca se almacena el número completo.';
comment on column pending_movements.card_name_raw is 'Nombre de tarjeta detectado en la notificación; puede ser nulo si no se logró extraer.';

commit;
