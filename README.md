# Paktay Backend

Primera entrega del backend: OAuth 2.0/OIDC, registro, inicio de sesión, cambio de contraseña, acceso protegido y operaciones de administrador. Las contraseñas solo existen en Keycloak; no se guardan en los servicios Java ni en Supabase.

## Servicios

- `auth-svc` (8081): registro, configuración OIDC, cambio de contraseña y administración de identidades.
- `business-svc` (8082): puerta de entrada protegida para la futura lógica financiera.
- `keycloak` (8180): emisor de tokens y roles `USER`/`ADMIN`.
- `keycloak-db`: PostgreSQL exclusivo de Keycloak. Supabase se conectará después únicamente a `business-svc`.

## Desarrollo local

1. Instala Docker Desktop y Java 17. Docker no está disponible en el equipo actual, por lo que falta ejecutar esta prueba de integración.
2. Copia `.env.example` a `.env` y cambia sus secretos antes de levantar los servicios.
3. Compila los JAR: `./mvnw.cmd clean package`.
4. Arranca el entorno: `docker compose up --build`.
5. Importa `postman/Paktay-Auth.postman_environment.json` y `postman/Paktay-Auth.postman_collection.json` en Postman y selecciona el ambiente **Paktay - Local**.

El contenedor `keycloak-init` asigna al cliente técnico solo los permisos de Keycloak necesarios para administrar usuarios. No utiliza la contraseña del administrador de Keycloak desde los servicios Java.

## Orden para probar en Postman

1. **Registrar usuario** guarda `userId`.
2. **Iniciar sesión como usuario** guarda `accessToken`.
3. **Ingresar a la aplicación** verifica el JWT contra `business-svc`.
4. **Cambiar contraseña propia** requiere el token del usuario.
5. **Iniciar sesión como admin**, **Reemplazar contraseña** y **Eliminar usuario** requieren el rol `ADMIN`.

La cuenta administrativa local importada es `admin@paktay.local` / `AdminPaktay123!`. Debe sustituirse o eliminarse antes de un despliegue público.

## Aplicación móvil

React Native debe iniciar sesión directamente con Keycloak mediante **Authorization Code + PKCE** usando el cliente público `paktay-mobile` y el redirect `paktay://oauth/callback`. Luego envía `Authorization: Bearer <access_token>` a ambos servicios. `POST /api/v1/auth/login` está incluido únicamente para pruebas automatizadas y Postman; no debe usarse desde la app publicada.

## Despliegue

La guía para Render, Keycloak y Supabase está en [docs/render-deployment.md](docs/render-deployment.md).
