begin;

alter table system_categories add column if not exists code varchar(50);
alter table system_categories add column if not exists icon varchar(60);
alter table system_categories add column if not exists color_dark char(7);
alter table system_categories add column if not exists color_light char(7);
alter table user_categories add column if not exists code varchar(50);
alter table user_categories add column if not exists icon varchar(60);
alter table user_categories add column if not exists color_dark char(7);
alter table user_categories add column if not exists color_light char(7);
alter table user_categories add column if not exists sort_order smallint;

update system_categories
set code = lower(regexp_replace(normalized_name, '[^A-Z0-9]+', '-', 'g')),
    icon = 'circle-ellipsis', color_dark = '#8B93A3', color_light = '#5B6575'
where code is null;

alter table system_categories alter column code set not null;
alter table system_categories alter column icon set not null;
alter table system_categories alter column color_dark set not null;
alter table system_categories alter column color_light set not null;
alter table system_categories add constraint system_categories_code_uq unique (code);
alter table system_categories add constraint system_categories_color_dark_ck check (color_dark ~ '^#[0-9A-Fa-f]{6}$');
alter table system_categories add constraint system_categories_color_light_ck check (color_light ~ '^#[0-9A-Fa-f]{6}$');

update system_categories set display_order = display_order + 100;
update system_categories set active = false;

insert into system_categories (code, name, normalized_name, icon, color_dark, color_light, display_order) values
    ('arriendo', 'Arriendo o hipoteca', 'ARRIENDO O HIPOTECA', 'key', '#2DD4BF', '#0D9488', 1),
    ('servicios', 'Luz, agua y gas', 'LUZ AGUA Y GAS', 'lightbulb', '#FACC15', '#CA8A04', 2),
    ('supermercado', 'Supermercado y despensa', 'SUPERMERCADO Y DESPENSA', 'shopping-cart', '#A3E635', '#65A30D', 3),
    ('comida', 'Comida y restaurantes', 'COMIDA Y RESTAURANTES', 'utensils', '#F59E0B', '#D97706', 4),
    ('casa', 'Casa y hogar', 'CASA Y HOGAR', 'house', '#34D399', '#059669', 5),
    ('transporte', 'Transporte y taxis', 'TRANSPORTE Y TAXIS', 'car', '#38BDF8', '#0284C7', 6),
    ('gasolina', 'Gasolina y peajes', 'GASOLINA Y PEAJES', 'fuel', '#FB923C', '#EA580C', 7),
    ('telefono', 'Teléfono e internet', 'TELEFONO E INTERNET', 'wifi', '#818CF8', '#4F46E5', 8),
    ('salud', 'Salud y consultas', 'SALUD Y CONSULTAS', 'heart-pulse', '#FB7185', '#E11D48', 9),
    ('farmacia', 'Farmacia y medicinas', 'FARMACIA Y MEDICINAS', 'pill', '#F0ABFC', '#C026D3', 10),
    ('educacion', 'Educación y cursos', 'EDUCACION Y CURSOS', 'graduation-cap', '#3B82F6', '#1D4ED8', 11),
    ('subscripciones', 'Suscripciones y apps', 'SUSCRIPCIONES Y APPS', 'tv', '#A78BFA', '#7C5CE0', 12),
    ('entretenimiento', 'Entretenimiento y ocio', 'ENTRETENIMIENTO Y OCIO', 'gamepad-2', '#C084FC', '#9333EA', 13),
    ('ropa', 'Ropa', 'ROPA', 'shirt', '#F472B6', '#DB2777', 14),
    ('cuidado', 'Cuidado personal', 'CUIDADO PERSONAL', 'scissors', '#E0B085', '#B45309', 15),
    ('electronicos', 'Electrónicos', 'ELECTRONICOS', 'laptop', '#60A5FA', '#2563EB', 16),
    ('gimnasio', 'Gimnasio y deporte', 'GIMNASIO Y DEPORTE', 'dumbbell', '#22D3EE', '#0E7490', 17),
    ('mascotas', 'Mascotas', 'MASCOTAS', 'paw-print', '#FDBA74', '#C2410C', 18),
    ('viajes', 'Viajes', 'VIAJES', 'plane', '#5EEAD4', '#0F766E', 19),
    ('regalos', 'Regalos', 'REGALOS', 'gift', '#F9A8D4', '#BE185D', 20),
    ('deudas', 'Deudas y préstamos', 'DEUDAS Y PRESTAMOS', 'landmark', '#C4B5FD', '#6D28D9', 21),
    ('otros', 'Otros', 'OTROS', 'circle-ellipsis', '#8B93A3', '#5B6575', 22)
on conflict (code) do update set
    name = excluded.name, normalized_name = excluded.normalized_name, icon = excluded.icon,
    color_dark = excluded.color_dark, color_light = excluded.color_light,
    display_order = excluded.display_order, active = true;

update user_categories uc
set code = coalesce(sc.code, 'custom-' || left(replace(uc.id::text, '-', ''), 12)),
    icon = coalesce(sc.icon, 'circle-ellipsis'),
    color_dark = coalesce(sc.color_dark, '#8B93A3'),
    color_light = coalesce(sc.color_light, '#5B6575'),
    sort_order = coalesce(sc.display_order, 100)
from system_categories sc
where uc.system_category_id = sc.id;

-- La sentencia anterior no alcanza filas CUSTOM porque el LEFT JOIN parte del catálogo.
update user_categories
set code = 'custom-' || left(replace(id::text, '-', ''), 12),
    icon = 'circle-ellipsis', color_dark = '#8B93A3', color_light = '#5B6575', sort_order = 100
where code is null;

alter table user_categories alter column code set not null;
alter table user_categories alter column icon set not null;
alter table user_categories alter column color_dark set not null;
alter table user_categories alter column color_light set not null;
alter table user_categories alter column sort_order set not null;
alter table user_categories add constraint user_categories_user_code_uq unique (user_id, code);
alter table user_categories add constraint user_categories_color_dark_ck check (color_dark ~ '^#[0-9A-Fa-f]{6}$');
alter table user_categories add constraint user_categories_color_light_ck check (color_light ~ '^#[0-9A-Fa-f]{6}$');
alter table user_categories add constraint user_categories_sort_order_ck check (sort_order > 0);

create or replace function create_user_system_categories(p_user_id uuid)
returns void language plpgsql as $$
begin
    insert into user_categories
        (user_id, system_category_id, origin, code, name, normalized_name, icon, color_dark, color_light, sort_order)
    select p_user_id, sc.id, 'SYSTEM', sc.code, sc.name, sc.normalized_name,
           sc.icon, sc.color_dark, sc.color_light, sc.display_order
      from system_categories sc
     where sc.active
    on conflict do nothing;
end;
$$;

select create_user_system_categories(id) from app_users;

-- Oculta en el catálogo del usuario las copias de categorías globales retiradas.
update user_categories uc
set active = false
from system_categories sc
where uc.system_category_id = sc.id and not sc.active;

commit;
