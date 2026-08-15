-- Paktay v0.14 — grupos generales para explorar el catálogo sin una lista plana.
begin;

update system_categories set
 parent_code = case
   when code in ('arriendo','servicios','casa','telefono') then 'hogar'
   when code in ('supermercado','comida') then 'alimentacion'
   when code in ('transporte','gasolina') then 'movilidad'
   when code in ('viajes') then 'turismo'
   when code in ('salud','farmacia','gimnasio','cuidado') then 'salud-bienestar'
   when code in ('educacion','electronicos') then 'educacion-tecnologia'
   when code in ('subscripciones','entretenimiento') then 'hobbies-entretenimiento'
   when code in ('ropa','mascotas','regalos') then 'estilo-vida'
   when code = 'deudas' then 'finanzas'
   else 'otros'
 end,
 parent_name = case
   when code in ('arriendo','servicios','casa','telefono') then 'Hogar y servicios'
   when code in ('supermercado','comida') then 'Alimentación'
   when code in ('transporte','gasolina') then 'Movilidad'
   when code in ('viajes') then 'Turismo'
   when code in ('salud','farmacia','gimnasio','cuidado') then 'Salud y bienestar'
   when code in ('educacion','electronicos') then 'Educación y tecnología'
   when code in ('subscripciones','entretenimiento') then 'Hobbies y entretenimiento'
   when code in ('ropa','mascotas','regalos') then 'Estilo de vida'
   when code = 'deudas' then 'Finanzas'
   else 'Otros'
 end;

comment on column system_categories.parent_code is
  'Identificador del grupo general usado para filtrar el catálogo administrativo; no se asigna al usuario.';
comment on column system_categories.parent_name is
  'Nombre del grupo general usado sólo en la exploración; el usuario guarda la subcategoría.';

commit;
