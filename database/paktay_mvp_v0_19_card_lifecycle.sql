-- v0.19 · Ciclo de vida de tarjetas: desactivar / activar / eliminar.
--
-- Regla de negocio nueva (2026-09-14):
--   * El nombre de la tarjeta identifica el plástico DENTRO de un banco. El mismo
--     nombre puede repetirse en bancos distintos ("Visa" en Pichincha y "Visa" en
--     Guayaquil), pero no dos veces en el mismo banco del mismo usuario.
--   * Sólo las tarjetas ACTIVAS compiten por el nombre: una desactivada libera su
--     nombre y, al reactivarla, el índice vuelve a exigir que no exista otra igual.
--   * Desactivar exige que la tarjeta no tenga consumos en los últimos 3 meses.
--   * Eliminar exige que la tarjeta no tenga ningún consumo en todo el historial.
--   Las dos últimas reglas viven en CardService; aquí sólo cambia la identidad.
begin;

drop index if exists cards_active_identity_uq;

create unique index cards_active_identity_uq
    on cards (user_id, bank_id, lower(btrim(name))) where status = 'ACTIVE';

comment on index cards_active_identity_uq is
    'Nombre único por usuario y banco, sólo entre tarjetas activas. Una tarjeta INACTIVE libera su nombre.';

-- El estado ya existía (card_status ACTIVE/INACTIVE + deactivated_at) desde v0.1;
-- a partir de v0.19 una tarjeta INACTIVE SÍ puede volver a ACTIVE desde la app.
comment on column cards.status is
    'ACTIVE: visible en selectores y acepta gastos. INACTIVE: sólo visible en Bancos y tarjetas, reactivable si su nombre sigue libre en el banco.';

commit;
