-- Contraseña temporal enviada por el administrador (auth-svc, TemporaryPasswordService).
--
-- El admin pide "enviar contraseña temporal": auth-svc la genera, la pone en Keycloak como
-- contraseña normal (no "temporal" de Keycloak: con direct grant esa marca deja la cuenta sin
-- poder entrar) y la manda por correo. Esta fila recuerda que hay que cambiarla: el login del
-- móvil responde password_change_required = true y la app obliga a elegir una nueva. Vence a
-- las 24 horas; vencida, el login se rechaza y queda la recuperación por PIN.
--
-- Se borra sola con la cuenta (purge_user borra app_users).
create table public.password_temporary (
    user_id    uuid primary key references public.app_users (id) on delete cascade,
    expires_at timestamp with time zone not null,
    created_by uuid,
    created_at timestamp with time zone not null default now()
);
