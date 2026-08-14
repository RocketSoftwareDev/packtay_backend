begin;

alter table banks add column if not exists logo_url varchar(1000);

create table if not exists card_networks (
    code varchar(20) primary key,
    name varchar(40) not null,
    logo_url varchar(1000) not null,
    active boolean not null default true,
    check (code in ('VISA', 'MASTERCARD', 'DINERS', 'DISCOVER', 'AMEX', 'OTHER'))
);

update banks b
set logo_url = logos.logo_url
from (values
    ('BANCO AMAZONAS', 'https://www.google.com/s2/favicons?domain=bancoamazonas.com&sz=128'),
    ('PRODUBANCO', 'https://www.google.com/s2/favicons?domain=produbanco.com.ec&sz=128'),
    ('BANCO DEL AUSTRO', 'https://www.google.com/s2/favicons?domain=bancodelaustro.com&sz=128'),
    ('BANCO SOLIDARIO', 'https://www.google.com/s2/favicons?domain=banco-solidario.com&sz=128'),
    ('BANCO GUAYAQUIL', 'https://www.google.com/s2/favicons?domain=bancoguayaquil.com&sz=128'),
    ('BANCO SUDAMERICANO', 'https://www.google.com/s2/favicons?domain=bancosudamericano.com.ec&sz=128'),
    ('BANCO BOLIVARIANO', 'https://www.google.com/s2/favicons?domain=bolivariano.com&sz=128'),
    ('BANCO COOPNACIONAL', 'https://www.google.com/s2/favicons?domain=coopnacional.com&sz=128'),
    ('BANCO COMERCIAL MANABI', 'https://www.google.com/s2/favicons?domain=bancodelmanabi.com&sz=128'),
    ('BANCO PROCREDIT', 'https://www.google.com/s2/favicons?domain=bancoprocredit.com.ec&sz=128'),
    ('BANCO DEL LITORAL', 'https://www.google.com/s2/favicons?domain=bancodellitoral.com&sz=128'),
    ('BANCO CAPITAL', 'https://www.google.com/s2/favicons?domain=bancocapital.com.ec&sz=128'),
    ('BANCO GENERAL RUMINAHUI', 'https://www.google.com/s2/favicons?domain=bgr.com.ec&sz=128'),
    ('BANCO DELBANK', 'https://www.google.com/s2/favicons?domain=delbank.fin.ec&sz=128'),
    ('BANCO INTERNACIONAL', 'https://www.google.com/s2/favicons?domain=bancointernacional.com.ec&sz=128'),
    ('BANCO ATLANTIDA', 'https://www.google.com/s2/favicons?domain=bancoatlantida.com.ec&sz=128'),
    ('BANCO DE LOJA', 'https://www.google.com/s2/favicons?domain=bancodeloja.fin.ec&sz=128'),
    ('CODESARROLLO', 'https://www.google.com/s2/favicons?domain=bancodesarrollo.fin.ec&sz=128'),
    ('BANCO DE MACHALA', 'https://www.google.com/s2/favicons?domain=bmachala.com&sz=128'),
    ('BANCO VISIONFUND ECUADOR', 'https://www.google.com/s2/favicons?domain=visionfund.ec&sz=128'),
    ('BANCO DEL PACIFICO', 'https://www.google.com/s2/favicons?domain=bancodelpacifico.com&sz=128'),
    ('BANCO DINERS CLUB', 'https://www.google.com/s2/favicons?domain=dinersclub.com.ec&sz=128'),
    ('BANCO PICHINCHA', 'https://www.google.com/s2/favicons?domain=pichincha.com&sz=128'),
    ('CITIBANK', 'https://www.google.com/s2/favicons?domain=citibank.com&sz=128'),
    ('BANECUADOR', 'https://www.google.com/s2/favicons?domain=banecuador.fin.ec&sz=128')
) as logos(normalized_name, logo_url)
where b.normalized_name = logos.normalized_name;

insert into card_networks (code, name, logo_url) values
    ('VISA', 'Visa', 'https://www.google.com/s2/favicons?domain=visa.com&sz=128'),
    ('MASTERCARD', 'Mastercard', 'https://www.google.com/s2/favicons?domain=mastercard.com&sz=128'),
    ('DINERS', 'Diners Club', 'https://www.google.com/s2/favicons?domain=dinersclub.com&sz=128'),
    ('DISCOVER', 'Discover', 'https://www.google.com/s2/favicons?domain=discover.com&sz=128'),
    ('AMEX', 'American Express', 'https://www.google.com/s2/favicons?domain=americanexpress.com&sz=128'),
    ('OTHER', 'Otra', 'https://www.google.com/s2/favicons?domain=emvco.com&sz=128')
on conflict (code) do update set name = excluded.name, logo_url = excluded.logo_url, active = true;

comment on column banks.logo_url is 'URL HTTPS de una imagen PNG con el logo del banco para mostrar en el frontend.';
comment on table card_networks is 'Catálogo de marcas o redes de tarjeta y sus recursos visuales.';
comment on column card_networks.logo_url is 'URL HTTPS de una imagen PNG con el logo de la marca de tarjeta.';

commit;
