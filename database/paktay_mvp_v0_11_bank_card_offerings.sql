-- Paktay v0.11 — oferta de tarjetas por entidad y marca de crédito.
begin;

create table if not exists bank_card_offerings (
    id uuid primary key default gen_random_uuid(),
    bank_id uuid not null references banks(id),
    card_type varchar(10) not null check (card_type in ('DEBIT', 'CREDIT')),
    brand varchar(20) check (brand in ('VISA', 'MASTERCARD', 'DINERS', 'DISCOVER', 'AMEX')),
    source_url varchar(500),
    verified_at date not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    unique nulls not distinct (bank_id, card_type, brand),
    check ((card_type = 'DEBIT' and brand is null) or (card_type = 'CREDIT' and brand is not null))
);

alter table cards add column if not exists credit_brand varchar(20);
update cards set credit_brand = 'OTHER' where card_type = 'CREDIT' and credit_brand is null;
alter table cards drop constraint if exists cards_credit_brand_check;
alter table cards add constraint cards_credit_brand_check check (
    (card_type = 'DEBIT' and credit_brand is null) or
    (card_type = 'CREDIT' and credit_brand in ('VISA','MASTERCARD','DINERS','DISCOVER','AMEX','OTHER'))
);

insert into banks (name, normalized_name, logo_url) values
 ('Cooperativa JEP', 'COOPERATIVA JEP', 'https://www.google.com/s2/favicons?domain=coopjep.fin.ec&sz=128'),
 ('Cooperativa Jardín Azuayo', 'COOPERATIVA JARDIN AZUAYO', 'https://www.google.com/s2/favicons?domain=jardinazuayo.fin.ec&sz=128'),
 ('Cooperativa 29 de Octubre', 'COOPERATIVA 29 DE OCTUBRE', 'https://www.google.com/s2/favicons?domain=29deoctubre.fin.ec&sz=128'),
 ('Cooperativa Policía Nacional', 'COOPERATIVA POLICIA NACIONAL', 'https://www.google.com/s2/favicons?domain=cpn.fin.ec&sz=128')
on conflict (normalized_name) do update set name=excluded.name, logo_url=excluded.logo_url, active=true;

delete from bank_card_offerings;

insert into bank_card_offerings (bank_id, card_type, brand, source_url, verified_at)
select b.id, o.card_type, o.brand, o.source_url, date '2026-08-14'
from banks b join (values
 ('BANCO DINERS CLUB','CREDIT','DINERS','https://www.dinersclub.com.ec/tarjetas'),
 ('BANCO DINERS CLUB','CREDIT','DISCOVER','https://www.dinersclub.com.ec/tarjetas/discover'),
 ('BANCO DINERS CLUB','CREDIT','VISA','https://www.dinersclub.com.ec/tarjetas'),
 ('BANCO DINERS CLUB','CREDIT','MASTERCARD','https://www.dinersclub.com.ec/tarjetas'),
 ('BANCO PICHINCHA','DEBIT',null,'https://www.pichincha.com/portal/principal/personas/tarjetas/debito'),
 ('BANCO PICHINCHA','CREDIT','VISA','https://www.pichincha.com/portal/principal/personas/tarjetas/credito'),
 ('BANCO PICHINCHA','CREDIT','MASTERCARD','https://www.pichincha.com/portal/principal/personas/tarjetas/credito'),
 ('BANCO GUAYAQUIL','DEBIT',null,'https://www.bancoguayaquil.com/cuentas/tarjeta-debito/'),
 ('BANCO GUAYAQUIL','CREDIT','VISA','https://www.bancoguayaquil.com/tarjetas/'),
 ('BANCO GUAYAQUIL','CREDIT','MASTERCARD','https://www.bancoguayaquil.com/tarjetas/'),
 ('BANCO GUAYAQUIL','CREDIT','AMEX','https://www.bancoguayaquil.com/tarjetas/'),
 ('PRODUBANCO','DEBIT',null,'https://www.produbanco.com.ec/banca-personas/cuentas/'),
 ('PRODUBANCO','CREDIT','VISA','https://www.produbanco.com.ec/banca-personas/tarjetas/'),
 ('PRODUBANCO','CREDIT','MASTERCARD','https://www.produbanco.com.ec/banca-personas/tarjetas/'),
 ('BANCO BOLIVARIANO','DEBIT',null,'https://www.bolivariano.com/'),
 ('BANCO BOLIVARIANO','CREDIT','VISA','https://www.bolivariano.com/'),
 ('BANCO BOLIVARIANO','CREDIT','MASTERCARD','https://www.bolivariano.com/'),
 ('BANCO INTERNACIONAL','DEBIT',null,'https://www.bancointernacional.com.ec/'),
 ('BANCO INTERNACIONAL','CREDIT','VISA','https://www.bancointernacional.com.ec/'),
 ('BANCO INTERNACIONAL','CREDIT','MASTERCARD','https://www.bancointernacional.com.ec/'),
 ('BANCO DEL AUSTRO','DEBIT',null,'https://www.bancodelaustro.com/'),
 ('BANCO DEL AUSTRO','CREDIT','VISA','https://www.bancodelaustro.com/'),
 ('BANCO DEL AUSTRO','CREDIT','MASTERCARD','https://www.bancodelaustro.com/'),
 ('BANCO DEL PACIFICO','DEBIT',null,'https://www.bancodelpacifico.com/'),
 ('BANCO DEL PACIFICO','CREDIT','VISA','https://www.bancodelpacifico.com/'),
 ('BANCO DEL PACIFICO','CREDIT','MASTERCARD','https://www.bancodelpacifico.com/'),
 ('BANCO GENERAL RUMINAHUI','DEBIT',null,'https://www.bgr.com.ec/'),
 ('BANCO GENERAL RUMINAHUI','CREDIT','VISA','https://www.bgr.com.ec/'),
 ('BANCO DE LOJA','DEBIT',null,'https://www.bancodeloja.fin.ec/'),
 ('BANCO DE LOJA','CREDIT','VISA','https://www.bancodeloja.fin.ec/'),
 ('BANCO DE MACHALA','DEBIT',null,'https://www.bmachala.com/'),
 ('BANCO DE MACHALA','CREDIT','VISA','https://www.bmachala.com/'),
 ('BANCO SOLIDARIO','CREDIT','VISA','https://www.banco-solidario.com/'),
 ('BANCO PROCREDIT','DEBIT',null,'https://www.bancoprocredit.com.ec/'),
 ('COOPERATIVA JEP','DEBIT',null,'https://www.coopjep.fin.ec/'),
 ('COOPERATIVA JARDIN AZUAYO','DEBIT',null,'https://www.jardinazuayo.fin.ec/'),
 ('COOPERATIVA 29 DE OCTUBRE','DEBIT',null,'https://www.29deoctubre.fin.ec/'),
 ('COOPERATIVA POLICIA NACIONAL','DEBIT',null,'https://www.cpn.fin.ec/')
) o(bank_normalized_name,card_type,brand,source_url) on o.bank_normalized_name=b.normalized_name;

-- El selector muestra únicamente entidades con una oferta explícitamente configurada.
update banks b set active = exists(select 1 from bank_card_offerings o where o.bank_id=b.id and o.active);

comment on table bank_card_offerings is 'Tipos y marcas de tarjeta permitidos por entidad; débito no registra marca.';
comment on column cards.credit_brand is 'Marca seleccionada para crédito; siempre nula en débito.';
commit;
