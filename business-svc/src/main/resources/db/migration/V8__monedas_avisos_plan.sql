-- Paktay V8: día 7 (2026-09-25).
--
-- Bloques:
--   1. Monedas: la app tenía BRL y CLP escritas en su código y la base no; se agregan
--      (con Brasil y Chile) para que el catálogo del servidor sea la única lista.
--   2. Avisos push (Firebase): token por dispositivo y bitácora de avisos enviados, para
--      no avisar dos veces el mismo umbral en el mismo mes.

------------------------------------------------------------------------------
-- 1. Monedas y países
------------------------------------------------------------------------------
insert into public.currencies (code, numeric_code, name, symbol, decimal_places, active, is_base_currency) values
    ('BRL', '986', 'Real brasileño', 'R$', 2, true, false),
    ('CLP', '152', 'Peso chileno', 'CLP$', 0, true, false)
on conflict do nothing;

insert into public.countries (code, name, currency_code) values
    ('BR', 'Brasil', 'BRL'),
    ('CL', 'Chile', 'CLP')
on conflict (code) do nothing;

------------------------------------------------------------------------------
-- 2. Avisos push
------------------------------------------------------------------------------
alter table public.user_devices
    add column if not exists push_token character varying(4096),
    add column if not exists push_token_updated_at timestamp with time zone;

comment on column public.user_devices.push_token is 'Token de Firebase Cloud Messaging del dispositivo; null si no aceptó avisos o cerró sesión.';

-- Un aviso por usuario, tipo, destino (categoría o tarjeta), mes y umbral. El índice
-- único es lo que garantiza que el 90 % de «Salud» avise una sola vez en septiembre
-- aunque lleguen dos gastos a la vez.
create table if not exists public.notification_log (
    id uuid default gen_random_uuid() not null primary key,
    user_id uuid not null references public.app_users(id) on delete cascade,
    kind character varying(24) not null,
    target_id uuid not null,
    period_month date not null,
    threshold smallint not null,
    sent_at timestamp with time zone default now() not null,
    delivered boolean default false not null,
    constraint notification_log_kind_check check ((kind)::text in ('CATEGORY_BUDGET', 'CARD_LIMIT')),
    constraint notification_log_threshold_check check (threshold in (90, 100))
);

create unique index if not exists notification_log_once_uq
    on public.notification_log (user_id, kind, target_id, period_month, threshold);

comment on table public.notification_log is 'Avisos de presupuesto ya enviados (o intentados) por mes y umbral.';
