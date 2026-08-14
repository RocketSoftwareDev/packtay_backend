# Paktay Backend

Primera entrega del backend: OAuth 2.0/OIDC, registro, inicio de sesión, cambio de contraseña, acceso protegido y operaciones de administrador. Las contraseñas solo existen en Keycloak; no se guardan en los servicios Java ni en Supabase.

## Servicios

- `auth-svc` (8081): registro, configuración OIDC, cambio de contraseña y administración de identidades.
- `business-svc` (8082): puerta de entrada protegida para la futura lógica financiera.
- `keycloak` (8180): emisor de tokens y roles `USER`/`ADMIN`.
- `keycloak-db`: PostgreSQL exclusivo de Keycloak. Supabase se conectará después únicamente a `business-svc`.

## Desarrollo local

1. Instala Docker Desktop y Java 17.
2. Copia `.env.example` a `.env` y cambia sus secretos antes de levantar los servicios.
3. Compila los JAR: `./mvnw.cmd clean package`.
4. Arranca el entorno: `docker compose up --build`.
5. Importa `postman/Paktay-Auth.postman_environment.json` y `postman/Paktay-Auth.postman_collection.json` en Postman y selecciona el ambiente **Paktay - Local**.

El contenedor `keycloak-init` asigna al cliente técnico solo los permisos de Keycloak necesarios para administrar usuarios. No utiliza la contraseña del administrador de Keycloak desde los servicios Java.

En el primer arranque, PostgreSQL ejecuta automáticamente `database/paktay_mvp_v0_1_postgres.sql` y carga el esquema junto con los catálogos iniciales de monedas, categorías y bancos. Para repetir la inicialización desde cero usa `docker compose down -v` antes de volver a levantar el entorno.

## Healthchecks y activación en Render

Las rutas públicas que debe consultar el frontend son:

- `GET https://<auth-url>/actuator/health`
- `GET https://<business-url>/actuator/health`

Una llamada a cada URL activa los dos servicios web cuando Render los ha suspendido. Ambos healthchecks consultan también el documento OIDC de Keycloak, por lo que despiertan y validan ese servicio. El healthcheck de `business-svc` valida además su conexión PostgreSQL.

Durante el arranque puede responder temporalmente `503 Service Unavailable`; el frontend debe reintentar con espera progresiva hasta recibir `200 OK`. No se deben usar las rutas de `liveness` para este flujo porque solo comprueban el proceso Java y no sus dependencias.

## Orden para probar en Postman

1. **Registrar usuario** guarda `userId`.
2. **Iniciar sesión como usuario** guarda `accessToken`.
3. **Ingresar a la aplicación** verifica el JWT contra `business-svc`.
4. **Cambiar contraseña propia** requiere el token del usuario.
5. **Iniciar sesión como admin**, **Reemplazar contraseña** y **Eliminar usuario** requieren el rol `ADMIN`.

Las credenciales administrativas se definen exclusivamente en `.env` y no deben confirmarse en Git.

## Aplicación móvil

React Native debe iniciar sesión directamente con Keycloak mediante **Authorization Code + PKCE** usando el cliente público `paktay-mobile` y el redirect `paktay://oauth/callback`. Luego envía `Authorization: Bearer <access_token>` a ambos servicios. `POST /api/v1/auth/login` está incluido únicamente para pruebas automatizadas y Postman; no debe usarse desde la app publicada.

## Despliegue

La guía para Render, Keycloak y Supabase está en [docs/render-deployment.md](docs/render-deployment.md).

La operación completa del backend local, Cloudflare Tunnel, PostgreSQL y DBeaver está en
[docs/backend-operations.md](docs/backend-operations.md).
