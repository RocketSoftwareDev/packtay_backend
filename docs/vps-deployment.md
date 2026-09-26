# Despliegue de Paktay en un VPS

Paktay **no se despliega en Render**: el backend es una pila de contenedores Docker
que vive en un VPS propio y se publica por **Cloudflare Tunnel**. Mientras el VPS no
esté operativo, la misma pila corre desde la Mac para pruebas usando exactamente ese
túnel — ver [docs/cloudflare-tunnel.md](cloudflare-tunnel.md). En cuanto exista el VPS,
el pase es el mismo: clonar el repositorio en el servidor, levantar el compose y
publicarlo con Cloudflare.

Los pasos de configuración de Keycloak, `auth-svc` y `business-svc` de esta guía no
dependen del hosteo: valen igual para la Mac, para el VPS y para cualquier otro sitio
donde corra el compose.

## 0. Estado actual: pruebas desde la Mac

Hoy el stack completo (Keycloak, `auth-svc`, `business-svc`, `business-db`) se levanta
en local con `docker compose` y se publica con Cloudflare Tunnel
(`docker-compose.cloudflare.yml`, perfil `tunnel`). Cloudflare termina HTTPS y
`cloudflared` enlaza cada hostname con su servicio interno; **PostgreSQL no se publica**.

Hostnames de ejemplo:

| Hostname | Servicio interno |
| --- | --- |
| `login.rocketsoftwarecore.com` | Keycloak (`host.docker.internal:8180` en la Mac) |
| `auth.rocketsoftwarecore.com` | `http://auth-svc:8081` |
| `api.rocketsoftwarecore.com` | `http://business-svc:8082` |

En la Mac, Keycloak corre en un contenedor aparte («Keycloak Services»), de ahí que el
compose del túnel apunte a `host.docker.internal:8180`. En el VPS ese detalle desaparece:
Keycloak pasa a ser un servicio más del compose y las URLs internas usan el nombre del
servicio (p. ej. `http://keycloak:8180`).

## 1. Bases de datos

### 1.1 Base de negocio en Supabase

1. Crea un proyecto de Supabase llamado `paktay-dev`.
2. Selecciona la región más cercana a los servicios.
3. Guarda la contraseña de la base de datos en un gestor de contraseñas.
4. En **Project Settings > Database**, copia la cadena de conexión PostgreSQL de tipo
   **directo** o **session pooler**.

Supabase se usa solo como PostgreSQL de negocio. No se usa Supabase Auth: la identidad
la administra Keycloak.

No ejecutes manualmente `database/SUPABASE.txt`: contiene referencias a `auth.users` y
políticas RLS de Supabase Auth que no aplican a Keycloak.

Ejecuta una sola vez, desde **SQL Editor**, el archivo `database/paktay_mvp_v0_1_postgres.sql`.
Es el esquema de negocio vigente: usa el `sub` de Keycloak como usuario local, no guarda
JWT y contiene los catálogos, tarjetas, categorías, presupuestos, gastos y cuotas.

La base debe estar vacía. Las migraciones `V1` a `V5` de
`business-svc/src/main/resources/db/migration` pertenecen al modelo local anterior y no
son compatibles con este esquema.

### 1.2 Base exclusiva para Keycloak

Keycloak necesita un PostgreSQL propio y exclusivo (nunca la base de Supabase para sus
tablas internas). En el VPS se provisiona junto al stack (p. ej. el contenedor
`keycloak-db` del compose o un clúster del propio servidor); en la Mac se usa la base
`keycloakservices-local-db` del «Keycloak Services» local.

## 2. Desplegar en el VPS

1. Clona `RocketSoftwareDev/packtay_backend`, rama `main`, en el servidor.
2. Prepara `.env` con los secretos (admin de Keycloak, credenciales de las bases,
   `KEYCLOAK_SERVICE_CLIENT_SECRET`, `CLOUDFLARE_TUNNEL_TOKEN`, ...). `.env` está
   gitignoreado: nunca se sube al repositorio.
3. Levanta la pila y el túnel:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.cloudflare.yml \
     --profile tunnel up --build -d
   ```

4. Importa el realm la primera vez (Keycloak con `--import-realm` sobre una base vacía).
   Los cambios posteriores al `paktay-realm.json` deben aplicarse por la consola de
   administración o una migración controlada: el import no sobrescribe un realm existente.
5. Comprueba:

   ```text
   https://<auth-url>/actuator/health
   https://<auth-url>/v3/api-docs
   https://<auth-url>/swagger-ui/index.html
   https://<business-url>/actuator/health
   ```

   Swagger queda disponible en `/swagger-ui/index.html` en los hostnames `auth` y `api`.
   En un entorno público real conviene limitarlo con una política de Cloudflare Access
   basada en ruta, sin aplicar esa política a los endpoints que consume la app móvil.

## 3. Configurar Keycloak

Abre `https://<keycloak-url>/admin` e inicia sesión con el administrador bootstrap.

### 3.1 Verificar realm y clientes

Comprueba que existen:

- Realm: `paktay`.
- Cliente público móvil: `paktay-mobile`.
- Cliente técnico: `paktay-auth-service`.
- Cliente confidencial del panel: `paktay-admin-panel` (solo direct grant, lo usa auth-svc para
  el login propio del panel). Lo crea o actualiza el perfil `identity-setup` con
  `KEYCLOAK_ADMIN_PANEL_CLIENT_SECRET`, y borra el antiguo `paktay-admin-web` si existe. Ver
  `docs/panel-admin.md`.
- Protección contra fuerza bruta del realm activada (la aplica `identity-setup`).
- Roles de realm: `USER` y `ADMIN`.

En `paktay-mobile`, conserva el redirect URI:

```text
paktay://oauth/callback
```

### 3.2 Cambiar el secreto del cliente técnico

1. Ve a **Clients > paktay-auth-service > Credentials**.
2. Genera un secreto nuevo.
3. Guárdalo únicamente como variable `KEYCLOAK_SERVICE_CLIENT_SECRET` en el `.env`.

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

Son necesarios para registrar usuarios, asignarles el rol `USER`, reemplazar contraseñas
y eliminarlos. No asignes `realm-admin`.

## 4. Crear auth-svc

Variables (en el `.env`, nunca en git):

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

Verifica:

```text
http://localhost:8081/actuator/health
http://localhost:8081/swagger-ui/index.html
```

## 5. Crear business-svc

Variables:

```text
KEYCLOAK_ISSUER_URI=https://<keycloak-url>/realms/paktay
KEYCLOAK_JWK_SET_URI=https://<keycloak-url>/realms/paktay/protocol/openid-connect/certs
KEYCLOAK_HEALTH_URL=https://<keycloak-url>/realms/paktay
BUSINESS_DB_URL=jdbc:postgresql://<supabase-host>:<puerto>/<base>?sslmode=require
BUSINESS_DB_USERNAME=<usuario-de-supabase>
BUSINESS_DB_PASSWORD=<contraseña-de-supabase>
FLYWAY_ENABLED=false
```

`FLYWAY_ENABLED=false` evita ejecutar las migraciones antiguas sobre Supabase, que ya
tiene el esquema cargado con `database/paktay_mvp_v0_1_postgres.sql`.

Verifica:

```text
http://localhost:8082/actuator/health
http://localhost:8082/swagger-ui/index.html
```

## Activación desde el frontend

Antes de iniciar el flujo de autenticación, el frontend puede llamar en paralelo a:

```text
GET https://<auth-url>/actuator/health
GET https://<business-url>/actuator/health
```

Los dos healthchecks consultan a su vez la configuración OIDC de Keycloak, de modo que
también lo validan. Business valida adicionalmente PostgreSQL/Supabase. Mientras el
servidor arranca (o si el túnel se corta) es normal recibir `502`, `503` o un error de
red; el frontend debe reintentar con espera progresiva y considerar el backend listo
únicamente cuando ambas rutas respondan `200`. El endpoint no requiere token y no expone
secretos ni detalles internos de las dependencias.

## 6. Prueba final

1. Importa la colección `postman/Paktay-Auth.postman_collection.json`.
2. Reemplaza `authUrl` y `businessUrl` por las URLs HTTPS del túnel.
3. Ejecuta en orden: registro, login de pruebas, entrada protegida, administración.

Para la aplicación React Native usa Authorization Code + PKCE directamente contra
Keycloak. El endpoint `POST /api/v1/auth/login` existe solo para Postman y pruebas, no
para producción móvil.

## Seguridad

- Nunca subas `.env`, cadenas JDBC, claves de Supabase, secretos de Keycloak ni tokens a
  GitHub; los secretos viven solo en el `.env` del VPS.
- Elimina o cambia el administrador local `admin@paktay.local` antes de producción.
- Mantén HTTPS en todas las URLs (lo garantiza el túnel de Cloudflare).
- La credencial de Firebase (`firebase-admin.json` para avisos push, ver
  `docs/firebase-notificaciones.md`) vive en el VPS fuera del repo, con `chmod 600`,
  y se monta de solo lectura; en la Mac no está y los avisos quedan apagados.
- No almacenes números completos de tarjetas, CVV, PIN ni biometría.