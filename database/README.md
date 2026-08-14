# Base de datos

La especificación de `SUPABASE.txt` es la fuente de diseño funcional de Paktay, pero usa Supabase Auth (`auth.users` y `auth.uid()`).

Paktay usa Keycloak como autoridad de identidad. Antes de crear las migraciones de negocio se debe reemplazar esa dependencia por el UUID `sub` emitido por Keycloak. El servicio `business-svc` será el único que se conecte a Supabase PostgreSQL; la aplicación móvil no accederá directamente a las tablas.

## Docker local

`docker-compose.yml` monta `paktay_mvp_v0_1_postgres.sql` en `/docker-entrypoint-initdb.d/`. PostgreSQL lo ejecuta automáticamente una sola vez al crear un volumen vacío. En este entorno Flyway queda desactivado porque las migraciones `V1-V5` pertenecen al esquema anterior y no deben mezclarse con este archivo.

Para recrear la base desde cero después de cambiar el SQL:

```bash
docker compose down -v
docker compose up --build
```
