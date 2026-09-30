# Día 8b: pasar producción de la Mac al VPS (para Codex)

Trasladar **tal cual** la pila que hoy sirve producción desde la Mac a un VPS Hetzner CAX21
(Ubuntu, ARM64, ya asegurado por el dueño): los contenedores de `paktay-prod`, el Keycloak
central y sus datos. Las URLs públicas no cambian porque el túnel de Cloudflare es el mismo.
Guía de referencia: `docs/vps-deployment.md` (en `develop`).

Va por **fases con puerta**. Al terminar cada fase escribe su resumen, sube los logs y
**detente**. Solo sigues con la siguiente cuando el dueño lo diga con la palabra indicada.

## Reglas

- El código sale de `develop` (backend). Debe incluir la rama `feature/dia8b-vps`
  (`extra_hosts: host-gateway` y el script de respaldo); si `develop` todavía no la tiene,
  detente y dilo.
- **Nunca** `docker compose down -v`, `docker volume rm` ni `docker system prune` en la Mac.
  En el VPS, solo sobre los volúmenes del **ensayo** de la fase B y solo en la fase C.
- **Ningún secreto en logs, commits ni en la salida**: de los `.env` solo se listan **nombres**
  de variables, nunca valores. Los `.env` y el `.json` de Firebase se copian con `scp`.
- **Un solo conector del túnel.** `cloudflared` nunca corre a la vez en la Mac y en el VPS.
- Acceso al VPS: alias SSH `paktay-vps` en `~/.ssh/config` de la Mac (lo configura el dueño).
  Si no conecta, detente.
- Logs: `export RUN=$(date +%Y-%m-%d_%H%M)-vps-<fase>`, `export LOGS=$PWD/IALogs/logs/$RUN`,
  desde `packtay_backend` en `develop`. Al final de cada fase: `RESUMEN.md`, commit
  `chore(ialogs): VPS fase <X> ($RUN)` y push a `develop`.

## Fase A · Inventario en la Mac (solo lectura)

Nada se detiene ni se modifica. Guarda en `$LOGS`:

1. `A1-contenedores.txt`: `docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'`.
2. `A2-proyectos.txt`: para cada contenedor de producción (los de `paktay-prod`, el Keycloak
   central y su base, `cloudflared`, y el web admin si está en Docker), las etiquetas
   `com.docker.compose.project`, `.project.working_dir`, `.project.config_files` y
   `.service`. Así sabemos qué carpetas y qué compose hay que llevar.
3. `A3-volumenes.txt`: volúmenes montados por esos contenedores y su tamaño.
4. `A4-env-nombres.txt`: **solo los nombres** de las variables de cada `.env` que usan esos
   proyectos (`sed 's/=.*//'`). Marca cuáles tienen valor vacío.
5. `A5-keycloak.txt`: versión, imagen, puerto publicado, `KC_HOSTNAME` (o equivalente) y la
   lista de realms (`kcadm.sh get realms --fields realm,enabled`). Si hay realms además de
   `paktay`, lístalos: también se mudan.
6. `A6-bases.txt`: tamaño de cada base, versión mayor de PostgreSQL de cada contenedor, y
   `select version, success from flyway_schema_history order by installed_rank` de negocio.
7. `A7-tunel.txt`: en qué contenedor corre `cloudflared` y si usa `CLOUDFLARE_TUNNEL_TOKEN`
   (sí/no, sin el valor). Si las rutas del túnel no se pueden leer desde la Mac, dilo: el
   dueño las confirma en el panel de Cloudflare.
8. `A8-arquitectura.txt`: `uname -m` en la Mac y `ssh paktay-vps 'uname -m; lsb_release -ds; free -h; df -h /; docker --version; docker compose version'`.
9. `A9-web-admin.txt`: dónde y cómo corre hoy el panel web (Docker, `pnpm start`, otro).

`RESUMEN.md` de la fase A: qué se mudará (proyectos, carpetas, volúmenes, realms), qué falta
en el VPS y cualquier sorpresa. **Detente.** Palabra para seguir: **«fase B»**.

## Fase B · Montar y ensayar en el VPS (producción sigue en la Mac)

1. Si el VPS no tiene Docker Engine y el plugin de Compose, instálalos desde el repositorio
   oficial de Docker para Ubuntu y agrega el usuario al grupo `docker`.
2. Crea `/opt/paktay/{secrets,backups}` (`chmod 700`). Copia con `rsync` (excluyendo
   `target/`, `node_modules/`, `.git/` si pesa, y los `IALogs/`):
   - el repo del backend en `develop` → `/opt/paktay/packtay_backend`;
   - la carpeta del proyecto del Keycloak central (la de `A2`) → `/opt/paktay/keycloak`.
   Si alguno de los dos es un repo Git con remoto, anota el remoto y la rama para que los
   próximos despliegues sean `git pull`.
3. Copia con `scp` los `.env` de cada proyecto y el `firebase-admin.json`
   (`/opt/paktay/secrets`, `chmod 600`). En el `.env` del backend en el VPS deja
   `FIREBASE_CREDENTIALS_HOST_FILE` **vacío** durante el ensayo: con una copia de datos
   reales, el VPS mandaría avisos push de verdad (presupuesto, víspera) duplicados con los
   de la Mac. Se activa en la fase C.
4. En el compose del Keycloak en el VPS, el puerto se publica en `172.17.0.1:8180`
   (puente de Docker), **no** en `0.0.0.0`. Si hay que editar su compose, hazlo con un
   `docker-compose.override.yml` en `/opt/paktay/keycloak` y anótalo.
5. **Ensayo con datos reales sin corte:** en la Mac, `pg_dump -Fc` en caliente de la base de
   negocio y de la de Keycloak (sin detener nada). Pásalos al VPS con `scp`, levanta en el
   VPS **solo las bases**, restáuralas con `pg_restore`, y luego levanta Keycloak,
   `auth-svc` y `business-svc` **sin** el perfil `tunnel` (sin `cloudflared`).
6. Comprueba en el VPS por `localhost` y guarda en `B-ensayo.txt`:
   - salud, `/v3/api-docs` y `/swagger-ui/index.html` de auth (8081) y business (8082) en `200`;
   - Keycloak: `/realms/paktay/.well-known/openid-configuration` en `172.17.0.1:8180`, con el
     `issuer` público de siempre (`https://keycloak.rocketsoftwarecore.com/realms/paktay`);
   - Flyway: la última versión de la Mac y, si `develop` trae más, que se aplicaron (V12);
   - conteos de `app_users`, `cards`, `expenses`, `user_categories`, `recurring_payments`
     comparados con la Mac (iguales, salvo los recién creados en la Mac tras el volcado);
   - desde el contenedor `auth-svc`: `getent hosts host.docker.internal` y un `curl` a
     `http://host.docker.internal:8180/realms/paktay` en `200`;
   - `ss -tlnp`: 8180 solo en `172.17.0.1`; 8081, 8082 y 5433 solo en `127.0.0.1`.
   - `business-svc` arrancó con los avisos push apagados (sin credencial de Firebase).
   - Humo de login: registra un usuario `codex-vps-<timestamp>@paktay.local`, haz login y
     elimínalo (`/api/v1/auth/account/delete`). Todo esto ocurre en la base de ensayo.
7. `docker stats --no-stream` en `B-recursos.txt`.

`RESUMEN.md` de la fase B con lo anterior y el tiempo que tardaron el volcado, la copia y la
restauración (será la duración del corte). **Detente.** Palabra para seguir: **«corte»**.

## Fase C · Corte (solo con «corte», en la ventana que diga el dueño)

Anota la hora de inicio y de fin. Mientras dure, la app no responde.

1. **Mac:** `docker update --restart=no` a `cloudflared`, los servicios de `paktay-prod` y
   los del Keycloak central. Luego `docker stop` **primero** a `cloudflared` y después al
   resto. **Sin** `down`, sin borrar volúmenes: la Mac queda intacta como vuelta atrás.
2. **Mac:** volcados finales `pg_dump -Fc` de las dos bases (ahora ya nadie escribe) y su
   `sha256sum`. Pásalos al VPS y comprueba el `sha256sum` allí.
3. **VPS:** detén los servicios del ensayo, restaura los volcados finales con
   `pg_restore --clean --if-exists` sobre las bases del ensayo (o recrea esos volúmenes del
   ensayo, solo en el VPS). Pon
   `FIREBASE_CREDENTIALS_HOST_FILE=/opt/paktay/secrets/firebase-admin.json` en el `.env`
   del backend (ahora sí: la Mac ya está detenida). Levanta Keycloak, `auth-svc` y `business-svc`, repite las
   comprobaciones del paso B6 salvo el humo, y **solo entonces** levanta `cloudflared`
   (`--profile tunnel`).
4. **Desde la Mac, por Internet** (`C-publico.txt`):
   - `https://paktayauth.rocketsoftwarecore.com/actuator/health`, `https://paktay.rocketsoftwarecore.com/actuator/health` y el `.well-known` de `https://keycloak.rocketsoftwarecore.com/realms/paktay` en `200`;
   - humo de login con un usuario `codex-vps-<timestamp>@paktay.local`, que se elimina al final;
   - en el panel de Cloudflare el túnel debe mostrar **un solo conector** (el del VPS). Si
     no se puede comprobar desde la terminal, pídeselo al dueño en el resumen.
5. Revisa `docker compose logs --tail=300` de `auth-svc` y `business-svc` en el VPS en busca
   de `ERROR` o `Exception` (`C-errores.txt`).

Si algo del paso 3 o 4 falla y no se arregla en 15 minutos: **vuelta atrás** (abajo).

`RESUMEN.md` de la fase C. **Detente.** Palabra para seguir: **«fase D»**.

## Fase D · Respaldo y cierre

1. Crea `/opt/paktay/backup.env` (`chmod 600`) con `BUSINESS_DB_CONTAINER`,
   `KEYCLOAK_DB_CONTAINER`, `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_NAME` y, si el dueño dio uno,
   `STORAGE_BOX`. Ejecuta `infra/vps/backup.sh` una vez (`D-backup.txt`).
2. **Prueba de restauración:** restaura `paktay.dump` en un contenedor `postgres` temporal
   con la misma versión mayor, compara los conteos del paso B6 y bórralo.
3. Instala el cron diario de `docs/vps-deployment.md` y guarda `crontab -l`.
4. Comprueba que los contenedores tienen `restart: unless-stopped` y que tras
   `sudo reboot` todo vuelve solo (salud pública en `200` otra vez). Anota cuánto tardó.
5. En la Mac, confirma que `cloudflared` sigue detenido con `restart=no`.

`RESUMEN.md` final: estado del VPS, respaldo, reinicio y lo que queda en la Mac.

## Vuelta atrás (solo si falla la fase C)

1. VPS: `docker stop` a `cloudflared` **primero**.
2. Mac: `docker update --restart=unless-stopped` y `docker start` al Keycloak central, a su
   base, a `paktay-prod` y por último a `cloudflared`.
3. Comprueba la salud pública. Lo escrito en el VPS durante el corte se pierde; anótalo.
