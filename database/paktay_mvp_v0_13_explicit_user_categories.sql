-- Paktay v0.13 — las categorías se agregan explícitamente por usuario.
begin;
create or replace function create_user_system_categories(p_user_id uuid)
returns void language plpgsql as $$
begin
    -- Intencionalmente vacío: POST /api/v1/user/categories/from-system/{id}
    -- materializa únicamente las subcategorías elegidas por el usuario.
    return;
end;
$$;
comment on function create_user_system_categories(uuid) is 'Compatibilidad con instalaciones anteriores; no clona el catálogo automáticamente.';
commit;
