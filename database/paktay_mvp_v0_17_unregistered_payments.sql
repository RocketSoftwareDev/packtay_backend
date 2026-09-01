-- Paktay v0.17 — bandeja independiente de pagos todavía no vinculados.
begin;

create table if not exists unregistered_payments (
    id bigserial primary key,
    amount numeric(12,2) not null check (amount > 0),
    merchant varchar(180) not null check (btrim(merchant) <> ''),
    card_name varchar(120) not null check (btrim(card_name) <> ''),
    device_id varchar(255),
    created_at timestamptz not null default now(),
    check (device_id is null or btrim(device_id) <> '')
);

create index if not exists unregistered_payments_created_idx
    on unregistered_payments (created_at desc);

comment on table unregistered_payments is 'Pagos recibidos desde el celular que todavía no están vinculados con usuario ni tarjeta registrada.';
comment on column unregistered_payments.id is 'Identificador numérico secuencial asignado por PostgreSQL.';
comment on column unregistered_payments.device_id is 'Identificador opcional del dispositivo, reservado para una integración futura.';

commit;
