# Paktay en el VPS de Hetzner

Producción corre en un VPS **Hetzner CAX21** (ARM64, 4 vCPU, 8 GB) con Ubuntu. Es la misma
pila Docker que antes corría en la Mac, **trasladada tal cual**: mismos contenedores, mismos
datos, mismo túnel de Cloudflare y, por tanto, **las mismas URLs públicas**. La app móvil no
necesita una build nueva.

La Mac es Apple Silicon (ARM64) igual que el CAX21, así que las imágenes y los volcados
son compatibles.

## Qué corre en el VPS

| Proyecto Docker | Contenido | URL pública (por el túnel) |
| --- | --- | --- |
| `paktay-prod` (este repo, rama `develop`) | `auth-svc`, `business-svc`, `business-db`, `cloudflared` | `paktayauth.rocketsoftwarecore.com`, `paktay.rocketsoftwarecore.com` |
| Keycloak central (el mismo proyecto que había en la Mac) | Keycloak + su PostgreSQL | `keycloak.rocketsoftwarecore.com` |

- Las rutas del túnel siguen apuntando a `http://auth-svc:8081`, `http://business-svc:8082` y
  `http://host.docker.internal:8180`. En Linux ese nombre no existe por defecto: el compose
  lo define con `extra_hosts: host.docker.internal:host-gateway`, y Keycloak publica su puerto
  en la IP del puente de Docker (`172.17.0.1:8180`), **nunca** en `0.0.0.0`.
- PostgreSQL no se publica. El Firewall de Hetzner solo deja entrar SSH: el túnel es una
  conexión de salida y no necesita puertos abiertos.
- Las fotos de perfil siguen en Supabase Storage hasta después del lanzamiento. Es la única
  dependencia externa aparte de Cloudflare, Firebase y el SMTP.

## Carpetas del servidor

```text
/opt/paktay/packtay_backend     este repo (rama develop)
/opt/paktay/keycloak            el proyecto del Keycloak central, copiado desde la Mac
/opt/paktay/secrets             firebase-admin.json (chmod 600)
/opt/paktay/backups             volcados diarios (chmod 700)
/opt/paktay/backup.env          variables del respaldo (chmod 600)
```

Los `.env` se copian de la Mac con `scp`. Nunca pasan por Git ni por los logs.

## Arranque y operación

```bash
cd /opt/paktay/packtay_backend
docker compose -p paktay-prod --profile tunnel up --build -d
docker compose -p paktay-prod ps
docker compose -p paktay-prod logs --tail=200 auth-svc business-svc
```

Actualizar a lo último de `develop`:

```bash
git pull --ff-only
docker compose -p paktay-prod up --build -d auth-svc business-svc
```

Flyway aplica solo las versiones pendientes al arrancar `business-svc`.

**Prohibido** usar `docker compose down -v` en `paktay-prod` o en el Keycloak: borra los datos.

## Un solo conector del túnel

El VPS usa **el mismo token** de túnel que usaba la Mac. Si `cloudflared` corre en los dos
sitios a la vez, Cloudflare reparte las peticiones entre ambos y parte de los usuarios
escribe en la base vieja. Por eso la Mac queda con sus contenedores detenidos y
`restart=no`. Para volver a la Mac, primero se detiene `cloudflared` en el VPS.

## Respaldo

`infra/vps/backup.sh` vuelca las dos bases con `pg_dump -Fc`, comprueba que cada volcado se
puede leer, guarda 14 días y, si `STORAGE_BOX` está definido, copia el día al Storage Box.
Cron (usuario con acceso a Docker):

```cron
30 3 * * * /opt/paktay/packtay_backend/infra/vps/backup.sh >> /opt/paktay/backups/backup.log 2>&1
```

Además, los Backups de Hetzner (instantánea diaria del disco) van activados en la consola.

Restaurar la base de negocio desde un volcado:

```bash
docker compose -p paktay-prod stop auth-svc business-svc
docker exec -i paktay-prod-business-db-1 pg_restore -U paktay -d paktay --clean --if-exists < paktay.dump
docker compose -p paktay-prod start auth-svc business-svc
```

## Comprobaciones

```bash
curl -fsS https://paktayauth.rocketsoftwarecore.com/actuator/health
curl -fsS https://paktay.rocketsoftwarecore.com/actuator/health
curl -fsS https://keycloak.rocketsoftwarecore.com/realms/paktay/.well-known/openid-configuration
```

Las rutas nuevas siguen la definición de terminado de `AGENTS.md` (salud, OpenAPI y Swagger
en `200`).

## Seguridad

- SSH solo con llave. Los secretos viven únicamente en los `.env` y en `/opt/paktay/secrets`.
- Swagger es público en los hostnames `auth` y `api`. Antes del lanzamiento conviene
  limitarlo con una política de Cloudflare Access por ruta, sin tocar las rutas de la app.
- No se guardan números de tarjeta, CVV, PIN ni biometría.
