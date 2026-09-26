# Pase de `develop` a `main` y producción

Este documento prepara el despliegue. El servidor de producción debe ejecutar los comandos
desde su carpeta de despliegue y conservar el volumen `paktay_backend_business-data`.
No se debe usar el proyecto Docker `paktay-local` para producción.

## Promoción de código

En cada repositorio, conservar primero los cambios locales y actualizar `develop`. Después,
desde `main`, integrar `develop` con fast-forward si la historia lo permite. El dueño revisa
los diffs y hace el push de `main`.

```bash
git fetch origin
git checkout main
git pull --ff-only origin main
git merge --ff-only develop
git log --oneline -5
git push origin main
```

El web admin debe desplegarse como servidor Node, porque el BFF necesita mantener las cookies
HttpOnly y hablar con `auth-svc` y `business-svc` desde el servidor.

```bash
pnpm install --frozen-lockfile
pnpm exec tsc --noEmit
pnpm run lint
pnpm run build
NODE_ENV=production pnpm start
```

Variables del web admin, configuradas en el proveedor del servidor:

```text
NODE_ENV=production
NEXT_PUBLIC_USE_MOCKS=false
NEXT_PUBLIC_MOCK_AUTO_LOGIN=false
AUTH_API_URL=https://paktayauth.rocketsoftwarecore.com
BUSINESS_API_URL=https://paktay.rocketsoftwarecore.com
ADMIN_CLIENT_HEADER=admin-web
ADMIN_SESSION_SECRET=<secreto aleatorio de 32 caracteres o más>
```

El navegador solo debe conectarse al dominio del web admin. No se deben publicar las URLs de
los servicios internos como variables `NEXT_PUBLIC_*`. `ADMIN_SESSION_SECRET` se genera y se
guarda en el gestor de secretos del proveedor; nunca se sube a Git.

## Variables del backend

Usar [.env.production.example](../.env.production.example) como plantilla. Completar los
secretos en `.env` del VPS, especialmente las contraseñas de las bases, los secretos de
Keycloak, SMTP, Firebase y Cloudflare Tunnel.

Antes de levantar los servicios, comprobar que existen en Keycloak:

- realm `paktay`;
- cliente público `paktay-mobile`;
- cliente técnico `paktay-auth-service`;
- cliente confidencial `paktay-admin-panel`;
- roles de realm `USER` y `ADMIN`.

## Migraciones de base de datos

El `business-svc` actual controla el esquema con Flyway y contiene `V9__panel_admin_soporte.sql`
y `V10__contrasenia_temporal.sql`. Antes de migrar producción:

1. hacer un `pg_dump -Fc` y un dump de esquema;
2. revisar `flyway_schema_history` y confirmar la última versión aplicada;
3. restaurar el dump en una base de ensayo;
4. levantar el mismo commit y confirmar que Flyway termina sin errores;
5. comparar conteos de tablas antes y después;
6. solo entonces detener `auth-svc` y `business-svc` de producción, sin borrar volúmenes,
   levantar la nueva imagen y comprobar salud, OpenAPI y el humo de login/registro.

Flyway debe quedar habilitado para que aplique únicamente las versiones pendientes. No se deben
ejecutar manualmente V9 o V10 ni usar `docker compose down -v` sobre producción. Si la base de
producción fue creada con el SQL histórico de Supabase y no tiene historial Flyway, el pase queda
bloqueado hasta que el responsable de base de datos defina y ensaye el baseline; no se debe
marcar una base existente a ciegas.

## Docker local

El entorno local usa exclusivamente el proyecto `paktay-local`, puertos `28081`, `28082`,
`25433` y volumen `packtay_backend_develop_business-data`. Keycloak y Mailpit pueden ser
servicios locales externos (`keycloakservices-local`, `keycloakservices-local-db` y
`paktay-local-mailpit-1`); no deben mezclarse con `paktay-prod`.

```bash
docker compose -p paktay-local --env-file .env --env-file .env.local up --build -d
docker compose -p paktay-local ps
docker compose -p paktay-local logs --tail=200 auth-svc business-svc
```

La limpieza local se limita a contenedores detenidos del proyecto `paktay-local`. Nunca se
eliminan volúmenes de producción ni se ejecuta `down -v` sobre `paktay-prod`.

## Puerta de producción

El despliegue queda listo para aprobación cuando el respaldo está verificado, la migración
ensayada, ambos healthchecks públicos responden `200`, las versiones de Flyway son correctas,
el login del panel funciona con `paktay-admin-panel` y el humo no deja usuarios de prueba.

Este host no contiene el proyecto `paktay-prod`; por eso la migración, el reinicio del stack
de producción y la subida del web admin deben ejecutarse en el VPS/proveedor correspondiente.
