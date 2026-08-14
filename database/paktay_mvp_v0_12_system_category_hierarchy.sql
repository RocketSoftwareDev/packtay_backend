-- Paktay v0.12 — agrupación de categorías predeterminadas para búsqueda visual.
begin;
alter table system_categories add column if not exists parent_code varchar(50);
alter table system_categories add column if not exists parent_name varchar(80);
update system_categories set
 parent_code = case when code in ('arriendo','servicios','casa','telefono') then 'hogar' when code in ('supermercado','comida') then 'alimentacion' when code in ('transporte','gasolina','viajes') then 'movilidad-viajes' when code in ('salud','farmacia','cuidado','gimnasio') then 'bienestar' when code in ('educacion','electronicos') then 'educacion-tecnologia' when code in ('subscripciones','entretenimiento') then 'ocio-digital' when code in ('ropa','mascotas','regalos') then 'estilo-familia' when code='deudas' then 'finanzas' else 'otros' end,
 parent_name = case when code in ('arriendo','servicios','casa','telefono') then 'Hogar y servicios' when code in ('supermercado','comida') then 'Alimentación' when code in ('transporte','gasolina','viajes') then 'Movilidad y viajes' when code in ('salud','farmacia','cuidado','gimnasio') then 'Salud y bienestar' when code in ('educacion','electronicos') then 'Educación y tecnología' when code in ('subscripciones','entretenimiento') then 'Entretenimiento digital' when code in ('ropa','mascotas','regalos') then 'Estilo de vida y familia' when code='deudas' then 'Finanzas y obligaciones' else 'Otros' end;
alter table system_categories alter column parent_code set not null;
alter table system_categories alter column parent_name set not null;
comment on column system_categories.parent_code is 'Código de categoría principal usado sólo para agrupar y buscar subcategorías del sistema.';
comment on column system_categories.parent_name is 'Nombre visible de la categoría principal; el usuario sigue guardando el UUID de la subcategoría.';
commit;
