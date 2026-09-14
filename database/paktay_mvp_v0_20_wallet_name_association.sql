-- v0.20 · El nombre de Wallet se asocia, no se escribe.
--
-- Problema que cierra: `cards.name` era a la vez el nombre que el usuario
-- escribía al dar de alta la tarjeta y la clave con la que el atajo la busca
-- (`lower(btrim(name)) = lower(btrim(:cardName))`). El usuario escribe «Visa»,
-- Wallet manda «VISA MASTERCARD PLATINUM», y ese consumo no se asocia nunca.
-- Nadie acierta ese campo a la primera porque nadie sabe qué texto manda Wallet
-- hasta que llega el primer consumo.
--
-- A partir de aquí:
--   * `alias` es lo que el usuario escribe y lo único que se lee en la app.
--     Libre, puede repetirse entre bancos y dentro de uno.
--   * `name` es el nombre técnico de Wallet. NO se escribe a mano: se asocia con
--     PATCH /api/v1/user/cards/{id}/wallet-name cuando llega el primer consumo.
--     Es nulo mientras no se asocie.
--
-- El índice pasa de (user_id, bank_id, name) a (user_id, name). El atajo busca
-- por nombre SIN filtrar banco, así que dos tarjetas activas de bancos distintos
-- con el mismo nombre asociado devolvían dos filas y el consumo se cargaba a
-- cualquiera. Esto sustituye a la regla por banco de v0.19: lo que puede
-- repetirse entre bancos es el apodo, que es lo que el usuario lee.
begin;

alter table cards alter column name drop not null;

-- El check original prohibía el vacío y se creó sin nombre, así que su nombre
-- generado depende de la versión de PostgreSQL. Se busca por su definición en
-- vez de adivinarlo: una migración que falla por el nombre de un constraint es
-- una tarde perdida.
do $$
declare target text;
begin
    for target in
        select con.conname
          from pg_constraint con
          join pg_class rel on rel.oid = con.conrelid
         where rel.relname = 'cards'
           and con.contype = 'c'
           and pg_get_constraintdef(con.oid) ilike '%btrim(name)%'
    loop
        execute format('alter table cards drop constraint %I', target);
    end loop;
end $$;

alter table cards add constraint cards_name_check
    check (name is null or btrim(name) <> '');

drop index if exists cards_active_identity_uq;

create unique index cards_active_wallet_name_uq
    on cards (user_id, lower(btrim(name)))
    where status = 'ACTIVE' and name is not null;

comment on index cards_active_wallet_name_uq is
    'Un nombre de Wallet identifica una sola tarjeta activa del usuario. Sin banco: el atajo busca por nombre y no sabe de qué banco viene el consumo.';
comment on column cards.name is
    'Nombre que manda Wallet, asociado por el usuario cuando llega el primer consumo. Nulo mientras no se asocie. Nunca se escribe a mano.';
comment on column cards.alias is
    'Apodo del usuario. Es lo único que se lee en la app y puede repetirse.';

commit;
