# Base de datos

La especificación de `SUPABASE.txt` es la fuente de diseño funcional de Paktay, pero usa Supabase Auth (`auth.users` y `auth.uid()`).

Paktay usa Keycloak como autoridad de identidad. Antes de crear las migraciones de negocio se debe reemplazar esa dependencia por el UUID `sub` emitido por Keycloak. El servicio `business-svc` será el único que se conecte a Supabase PostgreSQL; la aplicación móvil no accederá directamente a las tablas.

