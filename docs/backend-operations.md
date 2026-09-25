# Operación del backend Paktay

Esta guía describe la arquitectura, el arranque local y público, Cloudflare Tunnel,
PostgreSQL/DBeaver, disponibilidad de la Mac y diagnóstico básico.

## Arquitectura

| Componente | Puerto local | URL pública | Función |
| --- | ---: | --- | --- |
| `auth-svc` | `8081` | `https://paktayauth.rocketsoftwarecore.com` | Registro, login de pruebas, contraseña y administración de identidad |
| `business-svc` | `8082` | `https://paktay.rocketsoftwarecore.com` | Perfil, avatar, bancos, tarjetas, categorías, gastos y dispositivos |
| Keycloak central | `8180` | `https://keycloak.rocketsoftwarecore.com` | OAuth/OIDC compartido; Paktay se diferencia mediante el realm `paktay` |
| PostgreSQL Business | `5433` | No se publica | Base de negocio `paktay` |
| PostgreSQL Keycloak | Sin puerto host | No se publica | Persistencia interna de Keycloak |
| `cloudflared` | Sin puerto host | Salida HTTPS hacia Cloudflare | Publica los tres servicios sin abrir puertos del router |

Flujo principal:

```text
App móvil ──HTTPS──> Cloudflare ──túnel──> auth-svc / business-svc / Keycloak
                                              │
                                              └──> PostgreSQL y Supabase Storage
```

Las bases de datos, secretos y tokens técnicos nunca se incluyen en la aplicación móvil.

## Variables y secretos

El archivo `.env` no se confirma en Git. Contiene contraseñas de PostgreSQL y Keycloak,
el token del túnel y la clave secreta de Supabase. Para crear uno nuevo:

```bash
cp .env.example .env
```

La app móvil solo necesita estas URLs públicas:

```dotenv
PAKTAY_AUTH_BASE_URL=https://paktayauth.rocketsoftwarecore.com
PAKTAY_BUSINESS_BASE_URL=https://paktay.rocketsoftwarecore.com
PAKTAY_KEYCLOAK_BASE_URL=https://keycloak.rocketsoftwarecore.com
```

## Arranque

Modo local, sin publicar Cloudflare:

```bash
docker compose up --build -d
```

Modo servidor público:

```bash
docker compose -f docker-compose.yml -f docker-compose.cloudflare.yml \
  --profile tunnel up --build -d
```

Estado y logs:

```bash
docker compose ps
docker compose logs -f keycloak
docker compose logs -f auth-svc business-svc cloudflared
docker stats
```

Detener sin borrar datos:

```bash
docker compose -f docker-compose.yml -f docker-compose.cloudflare.yml \
  --profile tunnel stop
```

No ejecutes `docker compose down -v` salvo que quieras eliminar de forma intencional las dos
bases locales. Los volúmenes `business-data` y `keycloak-data` contienen los datos persistentes.

## Cloudflare Tunnel

El túnel administrado tiene estas rutas:

| Hostname | Origen Docker |
| --- | --- |
| `paktayauth.rocketsoftwarecore.com` | `http://auth-svc:8081` |
| `paktay.rocketsoftwarecore.com` | `http://business-svc:8082` |
| `keycloak.rocketsoftwarecore.com` | Keycloak Services central (`host.docker.internal:8180`) |
| Regla final | `http_status:404` |

La configuración pública de Keycloak debe coincidir exactamente con el emisor del JWT:

```text
https://keycloak.rocketsoftwarecore.com/realms/paktay
```

El token `CLOUDFLARE_TUNNEL_TOKEN` solo vive en `.env`. Si se comparte o aparece en un log,
captura o chat, debe rotarse desde Cloudflare Zero Trust.

Comprobaciones públicas:

```bash
curl https://paktayauth.rocketsoftwarecore.com/actuator/health
curl https://paktay.rocketsoftwarecore.com/actuator/health
curl https://keycloak.rocketsoftwarecore.com/realms/paktay/.well-known/openid-configuration
```

Swagger público:

- Auth: `https://paktayauth.rocketsoftwarecore.com/swagger-ui/index.html`
- Business: `https://paktay.rocketsoftwarecore.com/swagger-ui/index.html`

## ¿Qué ocurre si la Mac se apaga o duerme?

La compilación Release de iOS puede abrir sin cable y sin Metro porque contiene
su bundle JavaScript. Sin embargo, eso es independiente del servidor: mientras
este stack Docker sea el origen del túnel, la app necesita que la Mac permanezca
despierta para autenticarse y consultar o guardar información.

- Mac apagada o reiniciándose: backend, Keycloak y túnel quedan fuera de línea.
- Pantalla bloqueada: continúa funcionando mientras el sistema permanezca despierto.
- Pantalla apagada con la Mac despierta: continúa funcionando.
- Reposo del sistema o tapa cerrada en un portátil: normalmente deja de responder.
- Pérdida de Internet o cierre de Docker Desktop: deja de responder.

Para una prueba temporal puedes mantener la Mac despierta con:

```bash
caffeinate -dimsu
```

Para mantenerla despierta hasta cancelar manualmente, ejecuta el comando en una
terminal dedicada y termina con `Ctrl+C`. Bloquear la sesión es seguro; poner el
sistema en reposo no lo es.

El comando funciona mientras esa terminal permanezca abierta. Para uso continuo activa
“Evitar reposo automático con el adaptador de corriente” en Configuración del Sistema y configura
Docker Desktop para iniciar al iniciar sesión. Los contenedores principales usan
`restart: unless-stopped`, por lo que vuelven a arrancar cuando Docker vuelve a estar disponible.

Este montaje es apropiado para desarrollo y demostraciones. Para producción con disponibilidad
real se debe migrar a un servidor o proveedor que permanezca encendido las 24 horas.

## PostgreSQL y DBeaver

La base Business está publicada exclusivamente en loopback; solo programas de esta Mac pueden
conectarse directamente.

Configuración en DBeaver:

| Campo | Valor |
| --- | --- |
| Driver | PostgreSQL |
| Host | `localhost` |
| Port | `5433` |
| Database | `paktay` |
| Username | `paktay` |
| Password | Valor de `BUSINESS_DB_PASSWORD` en `.env` |
| SSL | Desactivado para esta conexión local |

La URL JDBC equivalente es:

```text
jdbc:postgresql://localhost:5433/paktay
```

La base interna de Keycloak no expone puerto deliberadamente. Para inspeccionarla sin cambiar
la red usa `docker compose exec` o DBeaver mediante una publicación temporal controlada.

Consulta desde Docker:

```bash
docker compose exec business-db psql -U paktay -d paktay
docker compose exec keycloak-db psql -U keycloak -d keycloak
```

## PostgreSQL con Homebrew

No necesitas instalar PostgreSQL para ejecutar Paktay porque la base corre en Docker. Si solo
quieres disponer del cliente `psql`, la opción pequeña es:

```bash
brew install libpq
brew link --force libpq
psql --version
```

Si también quieres un servidor PostgreSQL nativo independiente:

```bash
brew install postgresql@16
brew services start postgresql@16
```

No configures el PostgreSQL nativo en el puerto `5433`, que ya pertenece al contenedor Business.

## CORS y HTTPS

Spring procesa `X-Forwarded-Proto` para que OpenAPI anuncie URLs `https://` detrás de Cloudflare.
Los orígenes web permitidos se configuran en `.env` separados por comas:

```dotenv
PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS=http://localhost:*,http://127.0.0.1:*
```

React Native nativo no está sujeto a CORS. Expo Web, Swagger y aplicaciones web sí lo están.
No uses valores sin esquema como `paktay.rocketsoftwarecore.com`; siempre incluye `https://`.

## Verificación obligatoria

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/v3/api-docs
curl http://localhost:8081/swagger-ui/index.html
curl http://localhost:8082/actuator/health
curl http://localhost:8082/v3/api-docs
curl http://localhost:8082/swagger-ui/index.html
```

Los seis deben responder HTTP 200. Las rutas autenticadas deben aparecer en OpenAPI con
`bearerAuth` y Swagger debe abrir sin autenticación.

## Entorno local aislado del túnel

El proyecto Docker `paktay-local` usa otra red, otros volúmenes y otros puertos. Reiniciarlo,
reconstruirlo o borrar sus datos no modifica los contenedores conectados a Cloudflare.

```bash
# Simulador iOS o navegador en la Mac
docker compose -p paktay-local \
  --env-file .env \
  --env-file .env.local.example \
  -f docker-compose.yml -f docker-compose.local.yml \
  up --build -d keycloak-db business-db keycloak keycloak-init auth-svc business-svc

# Estado y logs exclusivamente locales
docker compose -p paktay-local -f docker-compose.yml -f docker-compose.local.yml ps
docker compose -p paktay-local -f docker-compose.yml -f docker-compose.local.yml logs -f auth-svc business-svc

# Reiniciar solamente los servicios Java locales
docker compose -p paktay-local -f docker-compose.yml -f docker-compose.local.yml restart auth-svc business-svc

# Detener el entorno local conservando sus bases
docker compose -p paktay-local -f docker-compose.yml -f docker-compose.local.yml down
```

Puertos locales aislados:

- Auth: `http://localhost:28081`
- Business: `http://localhost:28082`
- Keycloak: `http://localhost:28180`
- PostgreSQL Business: `localhost:25433`

El `.env` del front para el simulador debe contener:

```dotenv
PAKTAY_AUTH_BASE_URL=http://localhost:28081
PAKTAY_BUSINESS_BASE_URL=http://localhost:28082
PAKTAY_KEYCLOAK_BASE_URL=http://localhost:28180
PAKTAY_KEYCLOAK_REALM=paktay
PAKTAY_KEYCLOAK_CLIENT_ID=paktay-mobile
```

Después ejecuta `npm run env` en el repositorio del front. En un celular físico reemplaza
`localhost` por la IP LAN de la Mac tanto en `.env.local.example` del backend como en el
`.env` del front; ambos deben coincidir porque esa URL forma parte del issuer del JWT.

## Tarjetas y gastos persistidos

Las tarjetas ya no dependen de un producto ni de una marca global. El móvil registra
`bankId`, `cardType` (`DEBIT` o `CREDIT`), `name`, `last4`, `colorDark` y `colorLight` en
`POST /api/v1/user/cards`.

El historial de gastos vive en PostgreSQL, no en el almacenamiento local del teléfono:

- `POST /api/v1/user/expenses`: crea un gasto; `idempotencyKey` evita duplicados.
- `GET /api/v1/user/expenses`: consulta el historial persistido, paginado (ver V5 abajo).

Los consumos que llegan desde Apple Wallet los captura el Atajo de iOS, quedan en la
bandeja local del teléfono y la app los envía con el JWT del usuario por la misma ruta
`POST /api/v1/user/expenses`. El backend no tiene cola de pendientes ni credencial
propia para el Atajo.

El esquema lo aplica Flyway al arrancar `business-svc`; no se ejecuta SQL a mano. Ver
`database/README.md`.

## Contexto del usuario, estados y auditoría (V4, día 3)

- **Zona horaria y país.** `app_users.timezone` (IANA, por defecto `America/Guayaquil`) y
  `app_users.country_code` (por defecto `EC`). Se cambian con
  `PUT /api/v1/user/profile/context` `{ "timezone": "America/Guayaquil", "countryCode": "EC" }`
  y se leen en `GET /api/v1/user/profile`. Se rechazan zonas desconocidas y desplazamientos
  fijos (`+05:00`), porque PostgreSQL invierte su signo.
- **Mes en la zona del usuario.** El período actual es
  `date_trunc('month', now() at time zone u.timezone)`, y lo gastado en un período compara
  `occurred_at` con los límites del mes convertidos con esa zona. Los filtros `from`/`to` de
  `GET /api/v1/user/expenses` son días del calendario del usuario. El trigger de período
  cerrado de `expenses` usa la misma regla.
- **Tarjetas eliminadas.** `DELETE /api/v1/user/cards/{id}` ya no borra la fila: la pasa a
  `DELETED` (con `deactivated_at` y `name = null`), exige 3 meses sin consumos, vale desde
  `ACTIVE` o `INACTIVE` y no se revierte. `GET /cards` no la devuelve; sus gastos siguen
  contando y `ExpenseResponse.cardStatus` la marca como `DELETED`.
- **Estados de gasto.** `expenses.kind` (`EXPENSE`/`REFUND`), `status` (`ACTIVE`/`VOIDED`),
  `voided_by_expense_id`, `assigned_by_rule`, `space_id`. Aún no hay rutas para anular ni
  reembolsar (día 4). Mientras tanto **las sumas y conteos de consumo sólo cuentan
  `status = 'ACTIVE' and kind = 'EXPENSE'`** (presupuestos y conteo de uso reciente de una
  tarjeta). La guarda de borrado de categorías cuenta cualquier gasto porque protege una FK.
  El trigger permite cambiar tarjeta (sólo hacia una `ACTIVE`) y categoría, nunca usuario ni
  fecha; `VOIDED` no vuelve a `ACTIVE`.
- **Preparatorio sin rutas.** `spaces`, `space_members` (un Duo activo por usuario, máximo dos
  miembros activos), `user_consent`, `user_subscription`, `subscription_event`, y bancos por
  país / bancos `CUSTOM` por usuario. `GET /catalog/banks` sólo devuelve `origin = 'SYSTEM'`.
- **Auditoría.** `AuditService` escribe en `audit_log`: gasto creado (origen y
  `assignedByRule`), tarjeta desactivada, reactivada y eliminada, nombre de Wallet asociado y
  quitado. Sin datos personales. Un job diario (03:30, hora del servidor) purga filas de más
  de 90 días; el trigger `audit_log_no_delete` sólo admite borrar esas filas.

## Gastos desde el servidor: edición y anulación (V5, día 4)

- **Historial paginado.** `GET /api/v1/user/expenses` devuelve `{ items, nextCursor }` con
  paginación por clave (sin OFFSET): `occurred_at desc, id desc`, o con `since`
  `updated_at asc, id asc` para la sincronización incremental del teléfono. `limit` 1..200
  (50 por defecto). El cursor es Base64 opaco (`ExpenseCursor`). Detalle en
  `docs/mobile-app-integration.md`.
- **Detalle, edición y anulación.** `GET /{id}` (404 si no es del usuario), `PUT /{id}`
  (tarjeta y categoría siempre; monto y comercio sólo en `MANUAL`; sólo `ACTIVE` + `EXPENSE`;
  no toca `user_consumption_selections`) y `POST /{id}/void` (original a `VOIDED` + registro
  `REFUND` con la misma fecha, tarjeta, categoría y monto; idempotente; fila bloqueada con
  `FOR UPDATE`).
- **V5.** Acción de auditoría `VOID` y `validate_expense_ownership` exige tarjeta `ACTIVE` en
  el alta sólo para `kind = 'EXPENSE'`, para poder anular gastos de tarjetas inactivas o
  eliminadas. La edición se audita como `UPDATE` (nombres de campos cambiados y montos).
- **Errores.** `NotFoundException` → 404, `ConflictException` → 409, violaciones de
  restricciones → 409 "Conflicto con datos existentes", `RAISE EXCEPTION` de triggers
  (SQLSTATE `P0001`, por ejemplo período cerrado) → 409 con el mensaje del trigger, caída de
  conexión → 503, otros errores SQL → 500.
- **Idempotencia del alta.** `INSERT ... ON CONFLICT (user_id, idempotency_key) DO NOTHING`
  cubre la carrera de dos reintentos simultáneos sin abortar la transacción.

## Respaldo básico

```bash
docker compose exec -T business-db pg_dump -U paktay -d paktay -Fc > paktay.dump
docker compose exec -T keycloak-db pg_dump -U keycloak -d keycloak -Fc > keycloak.dump
```

Los archivos de respaldo contienen datos sensibles y no deben confirmarse en Git.
