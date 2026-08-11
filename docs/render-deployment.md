# Despliegue de Paktay en Render

Esta guía despliega el backend desde el repositorio `RocketSoftwareDev/packtay_backend` con tres servicios independientes:

1. Keycloak: OAuth 2.0, OpenID Connect, usuarios y roles.
2. `auth-svc`: registro, cambio de contraseña y administración de usuarios.
3. `business-svc`: tarjetas, bancos, dispositivos y la lógica financiera futura.

## 0. Estado requerido antes del primer despliegue

No despliegues todavía los Dockerfiles actuales. Están pensados para recibir un JAR ya compilado en `target/`, pero `target/` no se versiona en GitHub. Antes de seguir se debe publicar el ajuste de Dockerfiles para que Render compile cada servicio desde el repositorio.

Cuando ese commit esté en `main`, continúa con los pasos siguientes.

## 1. Crear las bases de datos

### 1.1 Base de negocio en Supabase

1. Crea un proyecto de Supabase llamado `paktay-dev`.
2. Selecciona la región más cercana a los servicios de Render.
3. Guarda la contraseña de la base de datos en un gestor de contraseñas.
4. En **Project Settings > Database**, copia la cadena de conexión PostgreSQL de tipo **directo** o **session pooler**.

Supabase se usa solo como PostgreSQL de negocio. No se usa Supabase Auth: la identidad es administrada por Keycloak.

No ejecutes manualmente `database/SUPABASE.txt`: contiene referencias a `auth.users` y políticas RLS de Supabase Auth que no aplican a Keycloak. Las migraciones que debe ejecutar el backend son las de `business-svc/src/main/resources/db/migration`.

### 1.2 Base exclusiva para Keycloak en Render

1. En Render, abre **New > PostgreSQL**.
2. Nombre: `paktay-keycloak-db`.
3. Región: la misma elegida para los servicios de Render.
4. Crea la base y conserva su **Internal Database URL**, usuario y contraseña.

Nunca uses la base de Supabase para las tablas internas de Keycloak.

## 2. Crear el servicio Keycloak

1. En Render selecciona **New > Web Service**.
2. Conecta el repositorio `RocketSoftwareDev/packtay_backend`, rama `main`.
3. Nombre sugerido: `paktay-keycloak`.
4. Usa el Dockerfile de `infra/keycloak/Dockerfile` cuando esté disponible en el commit de preparación para Render.
5. Crea secretos nuevos y exclusivos para producción:

```text
KC_BOOTSTRAP_ADMIN_USERNAME=<administrador-de-keycloak>
KC_BOOTSTRAP_ADMIN_PASSWORD=<contraseña-larga-y-única>
KC_DB=postgres
KC_DB_URL=jdbc:postgresql://<host-interno-render>:5432/<base-keycloak>
KC_DB_USERNAME=<usuario-keycloak>
KC_DB_PASSWORD=<contraseña-keycloak>
KC_PROXY_HEADERS=xforwarded
KC_HEALTH_ENABLED=true
```

6. Configura el comando de inicio de producción para importar el realm la primera vez y escuchar el puerto asignado por Render:

```bash
start --import-realm --http-port $PORT
```

7. Despliega y guarda la URL pública, por ejemplo `https://paktay-keycloak.onrender.com`.

El import del realm crea `paktay` la primera vez. Los cambios futuros a `infra/keycloak/paktay-realm.json` no modifican automáticamente un realm que ya existe; deben aplicarse mediante la consola de administración o una migración controlada.

## 3. Configurar Keycloak

Abre `https://<keycloak-url>/admin` e inicia sesión con el administrador bootstrap.

### 3.1 Verificar realm y clientes

Comprueba que existen:

- Realm: `paktay`.
- Cliente público móvil: `paktay-mobile`.
- Cliente técnico: `paktay-auth-service`.
- Roles de realm: `USER` y `ADMIN`.

En `paktay-mobile`, conserva el redirect URI:

```text
paktay://oauth/callback
```

### 3.2 Cambiar el secreto del cliente técnico

1. Ve a **Clients > paktay-auth-service > Credentials**.
2. Genera un secreto nuevo.
3. Guárdalo únicamente como variable `KEYCLOAK_SERVICE_CLIENT_SECRET` en Render para `auth-svc`.

### 3.3 Dar permisos mínimos al cliente técnico

1. Ve a **Clients > paktay-auth-service > Service account roles**.
2. En el selector de cliente, elige `realm-management`.
3. Asigna estos roles:

```text
manage-users
view-users
query-users
view-realm
```

Son necesarios para registrar usuarios, asignarles el rol `USER`, reemplazar contraseñas y eliminarlos. No asignes `realm-admin`.

## 4. Crear auth-svc

1. En Render: **New > Web Service**.
2. Repositorio y rama: `RocketSoftwareDev/packtay_backend`, `main`.
3. Nombre sugerido: `paktay-auth-svc`.
4. Dockerfile: `auth-svc/Dockerfile`.
5. Health check: `/actuator/health`.
6. Configura las variables:

```text
KEYCLOAK_PUBLIC_URL=https://<keycloak-url>
KEYCLOAK_INTERNAL_URL=https://<keycloak-url>
KEYCLOAK_ISSUER_URI=https://<keycloak-url>/realms/paktay
KEYCLOAK_JWK_SET_URI=https://<keycloak-url>/realms/paktay/protocol/openid-connect/certs
KEYCLOAK_REALM=paktay
KEYCLOAK_MOBILE_CLIENT_ID=paktay-mobile
KEYCLOAK_SERVICE_CLIENT_ID=paktay-auth-service
KEYCLOAK_SERVICE_CLIENT_SECRET=<secreto-generado-en-keycloak>
```

7. Despliega. Verifica:

```text
https://<auth-url>/actuator/health
https://<auth-url>/swagger-ui/index.html
```

## 5. Crear business-svc

1. En Render: **New > Web Service**.
2. Repositorio y rama: `RocketSoftwareDev/packtay_backend`, `main`.
3. Nombre sugerido: `paktay-business-svc`.
4. Dockerfile: `business-svc/Dockerfile`.
5. Health check: `/actuator/health`.
6. Configura:

```text
KEYCLOAK_ISSUER_URI=https://<keycloak-url>/realms/paktay
KEYCLOAK_JWK_SET_URI=https://<keycloak-url>/realms/paktay/protocol/openid-connect/certs
BUSINESS_DB_URL=jdbc:postgresql://<supabase-host>:<puerto>/<base>?sslmode=require
BUSINESS_DB_USERNAME=<usuario-de-supabase>
BUSINESS_DB_PASSWORD=<contraseña-de-supabase>
```

7. Despliega. Flyway ejecutará automáticamente las migraciones `V1` a `V5` contra la base de Supabase.
8. Verifica:

```text
https://<business-url>/actuator/health
https://<business-url>/swagger-ui/index.html
```

## 6. Prueba final

1. Importa la colección `postman/Paktay-Auth.postman_collection.json`.
2. Reemplaza `authUrl` y `businessUrl` por las URLs HTTPS de Render.
3. Ejecuta en orden: registro, login de pruebas, entrada protegida, administración.

Para la aplicación React Native usa Authorization Code + PKCE directamente contra Keycloak. El endpoint `POST /api/v1/auth/login` existe solo para Postman y pruebas, no para producción móvil.

## Seguridad

- Nunca subas `.env`, cadenas JDBC, claves de Supabase, secretos de Keycloak ni tokens a GitHub.
- Configura los secretos exclusivamente en Render.
- Elimina o cambia el administrador local `admin@paktay.local` antes de producción.
- Mantén HTTPS en todas las URLs de producción.
- No almacenes números completos de tarjetas, CVV, PIN ni biometría.
