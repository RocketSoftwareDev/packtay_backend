-- Día 8c · iniciar sesión con Apple y Google.
--
-- La identidad (el vínculo con Apple o Google) vive en Keycloak. Aquí solo se guarda
-- lo que Apple exige para cerrar la cuenta: el refresh token de Sign in with Apple,
-- que hay que revocar al eliminarla (App Store Review 5.1.1(v)). Google no lo exige
-- y no se guarda nada suyo.
--
-- La fila cae con app_users (on delete cascade), así que purge_user la borra sin
-- tocar la función; auth-svc la lee antes, para revocar en Apple.

create table if not exists public.social_provider_tokens (
    user_id uuid not null references public.app_users(id) on delete cascade,
    provider character varying(16) not null,
    refresh_token text not null,
    created_at timestamp with time zone default now() not null,
    updated_at timestamp with time zone default now() not null,
    constraint social_provider_tokens_pkey primary key (user_id, provider),
    constraint social_provider_tokens_provider_check check (provider in ('APPLE'))
);

comment on table public.social_provider_tokens is 'Refresh token de Sign in with Apple por usuario, solo para revocarlo al eliminar la cuenta.';
