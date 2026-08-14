begin;

create table if not exists card_products (
    id uuid primary key default gen_random_uuid(),
    bank_id uuid not null references banks(id),
    name varchar(120) not null,
    normalized_name varchar(120) not null,
    network varchar(20) not null check (network in ('VISA', 'MASTERCARD', 'DINERS', 'DISCOVER', 'AMEX', 'OTHER')),
    card_type varchar(20) not null check (card_type in ('CREDIT', 'DEBIT', 'PREPAID')),
    active boolean not null default true,
    display_order smallint not null default 1 check (display_order > 0),
    source_url varchar(500),
    verified_at date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (bank_id, normalized_name)
);
create index if not exists card_products_bank_active_idx on card_products (bank_id, active, display_order);

alter table cards add column if not exists card_product_id uuid references card_products(id);
alter table cards add column if not exists network varchar(20) not null default 'OTHER';
alter table cards add column if not exists card_type varchar(20) not null default 'CREDIT';

insert into card_products (bank_id, name, normalized_name, network, card_type, display_order, source_url, verified_at)
select b.id, p.name, p.normalized_name, p.network, 'CREDIT', p.display_order, p.source_url, date '2026-08-12'
from banks b
join (values
    ('BANCO PICHINCHA', 'Visa Banco Pichincha', 'VISA BANCO PICHINCHA', 'VISA', 1, 'https://www.pichincha.com/detalle-producto/compra-en-linea'),
    ('BANCO PICHINCHA', 'Mastercard Banco Pichincha', 'MASTERCARD BANCO PICHINCHA', 'MASTERCARD', 2, 'https://www.pichincha.com/detalle-producto/compra-en-linea'),
    ('BANCO DINERS CLUB', 'Diners Club Sphaera', 'DINERS CLUB SPHAERA', 'DINERS', 1, 'https://www.dinersclub.com.ec/tarjetas/diners-club'),
    ('BANCO DINERS CLUB', 'Diners Club Miles', 'DINERS CLUB MILES', 'DINERS', 2, 'https://www.dinersclub.com.ec/tarjetas/diners-club'),
    ('BANCO DINERS CLUB', 'Diners Club Internacional', 'DINERS CLUB INTERNACIONAL', 'DINERS', 3, 'https://www.dinersclub.com.ec/tarjetas/diners-club'),
    ('BANCO DINERS CLUB', 'Titanium Visa', 'TITANIUM VISA', 'VISA', 4, 'https://www.dinersclub.com.ec/tarjetas'),
    ('BANCO DINERS CLUB', 'Titanium Mastercard', 'TITANIUM MASTERCARD', 'MASTERCARD', 5, 'https://www.dinersclub.com.ec/tarjetas'),
    ('BANCO DINERS CLUB', 'Discover', 'DISCOVER', 'DISCOVER', 6, 'https://www.dinersclub.com.ec/tarjetas/discover/discover')
) as p(bank_normalized_name, name, normalized_name, network, display_order, source_url)
  on p.bank_normalized_name = b.normalized_name
on conflict (bank_id, normalized_name) do update set
    name = excluded.name, network = excluded.network, card_type = excluded.card_type,
    display_order = excluded.display_order, source_url = excluded.source_url,
    verified_at = excluded.verified_at, active = true, updated_at = now();

commit;
