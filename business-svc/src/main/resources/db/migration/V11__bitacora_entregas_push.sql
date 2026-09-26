-- Bitácora propia de cada intento de entrega push. Firebase es solo el transporte.
create table if not exists public.push_delivery_log (
    id uuid default gen_random_uuid() not null primary key,
    user_id uuid not null references public.app_users(id) on delete cascade,
    device_id uuid references public.user_devices(id) on delete set null,
    kind character varying(32) not null,
    result character varying(16) not null,
    created_at timestamp with time zone default now() not null,
    constraint push_delivery_log_result_check check (result in ('SENT', 'FAILED', 'INVALID_TOKEN'))
);

create index if not exists push_delivery_log_created_idx on public.push_delivery_log (created_at desc);
create index if not exists push_delivery_log_user_idx on public.push_delivery_log (user_id, created_at desc);
