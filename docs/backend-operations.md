# Operación del backend Paktay

Esta guía describe la arquitectura, el arranque local y público, Cloudflare Tunnel,
PostgreSQL/DBeaver, disponibilidad de la Mac y diagnóstico básico.

## Arquitectura

| Componente | Puerto local | URL pública | Función |
| --- | ---: | --- | --- |
| `auth-svc` | `8081` | `https://paktayauth.rocketsoftwarecore.com` | Registro, login de pruebas, contraseña y administración de identidad |
| `business-svc` | `8082` | `https://paktay.rocketsoftwarecore.com` | Perfil, avatar, bancos, tarjetas, categorías, gastos y dispositivos |
| Keycloak | `8180` | `https://paktaykeycloak.rocketsoftwarecore.com` | OAuth/OIDC, usuarios, roles, access token y refresh token |
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
PAKTAY_KEYCLOAK_BASE_URL=https://paktaykeycloak.rocketsoftwarecore.com
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
| `paktaykeycloak.rocketsoftwarecore.com` | `http://keycloak:8080` |
| Regla final | `http_status:404` |

La configuración pública de Keycloak debe coincidir exactamente con el emisor del JWT:

```text
https://paktaykeycloak.rocketsoftwarecore.com/realms/paktay
```

El token `CLOUDFLARE_TUNNEL_TOKEN` solo vive en `.env`. Si se comparte o aparece en un log,
captura o chat, debe rotarse desde Cloudflare Zero Trust.

Comprobaciones públicas:

```bash
curl https://paktayauth.rocketsoftwarecore.com/actuator/health
curl https://paktay.rocketsoftwarecore.com/actuator/health
curl https://paktaykeycloak.rocketsoftwarecore.com/realms/paktay/.well-known/openid-configuration
```

Swagger público:

- Auth: `https://paktayauth.rocketsoftwarecore.com/swagger-ui/index.html`
- Business: `https://paktay.rocketsoftwarecore.com/swagger-ui/index.html`

## ¿Qué ocurre si la Mac se apaga o duerme?

- Mac apagada o reiniciándose: backend, Keycloak y túnel quedan fuera de línea.
- Pantalla bloqueada: continúa funcionando mientras el sistema permanezca despierto.
- Pantalla apagada con la Mac despierta: continúa funcionando.
- Reposo del sistema o tapa cerrada en un portátil: normalmente deja de responder.
- Pérdida de Internet o cierre de Docker Desktop: deja de responder.

Para una prueba temporal puedes mantener la Mac despierta con:

```bash
caffeinate -dimsu
```

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

- `POST /api/v1/user/expenses`: crea un gasto manual; `idempotencyKey` evita duplicados.
- `GET /api/v1/user/expenses`: consulta el historial persistido.
- `POST /api/v1/user/movements/shortcut`: guarda un consumo entrante en la cola.
- `GET /api/v1/user/movements/pending`: consulta la cola pendiente.
- `POST /api/v1/user/movements/{movementId}/confirm`: crea el gasto y confirma la cola atómicamente.
- `DELETE /api/v1/user/movements/{movementId}`: descarta el evento sin borrar su auditoría.

Para una instalación existente se aplica una sola vez:

```bash
docker compose exec -T business-db psql -U paktay -d paktay -v ON_ERROR_STOP=1 \
  < database/paktay_mvp_v0_9_cards_and_expenses.sql
```

## Respaldo básico

```bash
docker compose exec -T business-db pg_dump -U paktay -d paktay -Fc > paktay.dump
docker compose exec -T keycloak-db pg_dump -U keycloak -d keycloak -Fc > keycloak.dump
```

Los archivos de respaldo contienen datos sensibles y no deben confirmarse en Git.
