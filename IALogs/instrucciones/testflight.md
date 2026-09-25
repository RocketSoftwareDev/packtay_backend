# Beta en TestFlight: producción a `develop` y build nuevo (para Codex)

Contexto y responsabilidades: `docs/testflight-beta.md`. Aquí sí vas a **tocar producción**
(`paktay-prod`), pero sólo después de un respaldo verificado y de un ensayo que pase completo.
Cada fase tiene una **PUERTA**: si algo de la puerta falla, **te detienes**, escribes el
resumen, subes los logs y no sigues con la fase siguiente.

## Reglas

- Nunca `docker compose down -v` sobre `paktay-prod`, nunca borres el volumen
  `packtay_backend_business-data`, nunca toques el Keycloak central (`keycloakservices-local`).
- Nunca imprimas en logs contraseñas, tokens, el contenido de `.env`, del `.json` de Firebase ni
  del `.p8`. De los secretos sólo anota si **están definidos** (sí/no).
- El respaldo va a `~/paktay-backups/` (fuera del repo y fuera de Docker) y **no se sube a git**.
- Logs a `IALogs/logs/$RUN/` como siempre; sin datos personales (los conteos sí, los
  correos no).
- `main` no se toca en ningún repo.
- Mismos `F` y `wait_up` que en los días anteriores para `paktay-fresh`.

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-testflight
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
export TS=$(date +%Y%m%d-%H%M)
mkdir -p ~/paktay-backups ~/paktay-builds
```

## Fase 0 · Precondiciones

1. El front `develop` ya trae Firebase (PR de `feature/firebase-push` integrado):
   ```bash
   git -C ../packtay_mobile_front fetch origin
   git -C ../packtay_mobile_front merge-base --is-ancestor origin/feature/firebase-push origin/develop && echo "firebase en develop: si" || echo "firebase en develop: NO"
   ```
2. Cómo corre hoy producción. Anota proyecto, carpeta, archivos de compose, archivo de entorno
   y perfiles, leyendo las etiquetas del contenedor (no imprimas variables de entorno):
   ```bash
   docker ps --filter label=com.docker.compose.project=paktay-prod --format '{{.Names}}  {{.Status}}' > "$LOGS/00-prod-contenedores.txt"
   C=$(docker ps --filter label=com.docker.compose.project=paktay-prod --filter label=com.docker.compose.service=business-svc -q | head -n1)
   docker inspect "$C" --format 'dir={{ index .Config.Labels "com.docker.compose.project.working_dir" }}
   files={{ index .Config.Labels "com.docker.compose.project.config_files" }}
   env={{ index .Config.Labels "com.docker.compose.project.environment_file" }}' > "$LOGS/00-prod-compose.txt"
   ```
   Con eso arma el comando `P_OLD` (el de hoy, desde su carpeta) y `P_NEW` (mismo proyecto
   `paktay-prod`, mismos archivos de compose y el mismo archivo de entorno, pero desde la carpeta
   del despliegue nuevo de la fase 4). Si hay contenedor `cloudflared`, incluye `--profile tunnel`.
   Anota en el log la rama y el commit de la carpeta de producción (`git -C <dir> log -1 --oneline`).
3. En el archivo de entorno de producción, anota **sólo si están definidos**:
   `FIREBASE_CREDENTIALS_HOST_FILE`, `SMTP_FROM`, `BUSINESS_DB_PASSWORD` (por ejemplo con
   `grep -c '^FIREBASE_CREDENTIALS_HOST_FILE=.\+' <env>`). Si `FIREBASE_CREDENTIALS_HOST_FILE`
   apunta a un archivo, anota si ese archivo existe y su tamaño es mayor que 0.

**PUERTA 0:** Firebase en `develop` = sí, y encontraste carpeta, archivos y entorno de
producción. Si no, detente.

## Fase 1 · Respaldo de producción

```bash
DB=$(docker ps --filter label=com.docker.compose.project=paktay-prod --filter label=com.docker.compose.service=business-db -q | head -n1)
docker exec "$DB" pg_dump -U paktay -d paktay -Fc > ~/paktay-backups/prod-$TS.dump
docker exec "$DB" pg_dump -U paktay -d paktay -s > ~/paktay-backups/prod-$TS-schema.sql
ls -l ~/paktay-backups/prod-$TS* | awk '{print $5, $9}' > "$LOGS/01-respaldo.txt"
docker exec -i "$DB" pg_restore -l < ~/paktay-backups/prod-$TS.dump | wc -l | xargs echo "objetos en el respaldo:" >> "$LOGS/01-respaldo.txt"
docker exec "$DB" psql -U paktay -d paktay -At -c "select 'app_users', count(*) from app_users union all select 'cards', count(*) from cards union all select 'expenses', count(*) from expenses union all select 'user_categories', count(*) from user_categories union all select 'budget_allocations', count(*) from budget_allocations" > "$LOGS/01-conteos-antes.txt"
```

**PUERTA 1:** el `.dump` existe, pesa más de 0 y `pg_restore -l` lista objetos.

## Fase 2 · Cómo está la base de producción

```bash
docker exec "$DB" psql -U paktay -d paktay -At -c "select to_regclass('public.flyway_schema_history')" > "$LOGS/02-flyway-prod.txt"
docker exec "$DB" psql -U paktay -d paktay -At -c "select version, description, checksum, success from flyway_schema_history order by installed_rank" >> "$LOGS/02-flyway-prod.txt" 2>&1
```

Compara el esquema de producción con el de V1 (sólo estructura, sin datos):

```bash
$F down -v > /dev/null 2>&1
$F up -d business-db > /dev/null 2>&1; sleep 8
FDB=$(docker ps --filter label=com.docker.compose.project=paktay-fresh --filter label=com.docker.compose.service=business-db -q | head -n1)
docker exec -i "$FDB" psql -U paktay -d paktay -q -v ON_ERROR_STOP=1 < business-svc/src/main/resources/db/migration/V1__baseline.sql > "$LOGS/02-v1-aplicada.txt" 2>&1; echo "exit=$?" >> "$LOGS/02-v1-aplicada.txt"
docker exec "$FDB" pg_dump -U paktay -d paktay -s > /tmp/v1-schema.sql
norm() { grep -vE '^(--|SET |SELECT pg_catalog|$)' "$1" | sed 's/ OWNER TO [^;]*;//' ; }
diff <(norm /tmp/v1-schema.sql) <(norm ~/paktay-backups/prod-$TS-schema.sql) | head -n 400 > "$LOGS/02-diff-v1-vs-prod.txt"; wc -l < "$LOGS/02-diff-v1-vs-prod.txt" | xargs echo "lineas de diferencia:" >> "$LOGS/02-flyway-prod.txt"
$F down -v > /dev/null 2>&1
```

No hay puerta aquí: son datos para entender el ensayo. Anota en el resumen si producción ya
tiene `flyway_schema_history` y con qué versiones, y un resumen de las diferencias (tablas o
columnas que sobran o faltan respecto de V1).

## Fase 3 · Ensayo con una copia de producción

```bash
$F up -d business-db > /dev/null 2>&1; sleep 8
FDB=$(docker ps --filter label=com.docker.compose.project=paktay-fresh --filter label=com.docker.compose.service=business-db -q | head -n1)
docker exec -i "$FDB" pg_restore -U paktay -d paktay --no-owner --clean --if-exists < ~/paktay-backups/prod-$TS.dump > "$LOGS/03-restauracion.txt" 2>&1; echo "exit=$?" >> "$LOGS/03-restauracion.txt"
$F up --build -d > "$LOGS/03-ensayo-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/03-ensayo-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/03-ensayo-flyway.txt" 2>&1
$F exec -T business-db psql -U paktay -d paktay -At -c "select 'app_users', count(*) from app_users union all select 'cards', count(*) from cards union all select 'expenses', count(*) from expenses union all select 'user_categories', count(*) from user_categories union all select 'budget_allocations', count(*) from budget_allocations" > "$LOGS/03-ensayo-conteos.txt"
$F logs --no-color --tail=400 business-svc | grep -iE "error|exception|flyway|migrat" | head -n 120 > "$LOGS/03-ensayo-logs.txt"
```

Humo en el ensayo: con el mismo estilo de script del día 7, registra un usuario nuevo en
`paktay-fresh`, crea una tarjeta, una categoría, un gasto y pide `GET /api/v1/user/summary`,
`GET /api/v1/user/expenses`, `GET /api/v1/user/merchant-rules` y `GET /api/v1/user/entitlements`.
Todo debe responder 2xx. Guarda las líneas `OK`/`FAIL` en `$LOGS/03-ensayo-humo.txt`.

`pg_restore --clean` puede avisar de objetos que no existían: eso no es error. Sí lo es un
`exit` distinto de 0 con errores de tipos, triggers o datos.

**PUERTA 3:** Flyway llega a V8 con todo en `t`; `business-svc` arranca sin excepciones de
Flyway; los conteos del ensayo son **iguales** a los de `01-conteos-antes.txt` (app_users puede
tener +1 por el usuario de humo); el humo es todo `OK`. Si algo falla: `$F down -v`, resumen,
subida y **fin**. No toques producción.

```bash
$F down -v > /dev/null 2>&1
```

## Fase 4 · Despliegue de `develop` en producción

```bash
git worktree add ~/paktay-deploy/backend-$TS origin/develop
# Copia el archivo de entorno de producción al worktree SIN imprimirlo (misma ruta relativa que use P_OLD).
```

1. Detén la versión actual **sin borrar volúmenes**: `$P_OLD stop auth-svc business-svc`.
2. Desde `~/paktay-deploy/backend-$TS`: `$P_NEW up --build -d`. El volumen de datos es externo
   (`packtay_backend_business-data`), así que es la misma base.
3. Espera a que estén sanos y comprueba:
   ```bash
   for u in https://paktayauth.rocketsoftwarecore.com https://paktay.rocketsoftwarecore.com; do for p in /actuator/health /v3/api-docs; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/04-prod-salud.txt"
   docker exec "$DB" psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/04-prod-flyway.txt"
   docker exec "$DB" psql -U paktay -d paktay -At -c "select 'app_users', count(*) from app_users union all select 'cards', count(*) from cards union all select 'expenses', count(*) from expenses union all select 'user_categories', count(*) from user_categories union all select 'budget_allocations', count(*) from budget_allocations" > "$LOGS/04-prod-conteos.txt"
   docker logs --tail 300 $(docker ps --filter label=com.docker.compose.project=paktay-prod --filter label=com.docker.compose.service=business-svc -q | head -n1) 2>&1 | grep -E "push_sender_ready|ERROR|Exception" | head -n 60 > "$LOGS/04-prod-logs.txt"
   ```
   (Si `$DB` cambió de contenedor, vuelve a buscarlo.)
4. Humo en producción con un usuario **nuevo de prueba** (`codex+prod…@paktay.local`): registro,
   login, tarjeta, categoría, gasto, resumen. Al final **elimina ese usuario** con
   `POST /api/v1/auth/account/delete` para no dejar basura. Guarda `OK`/`FAIL` en
   `$LOGS/04-prod-humo.txt`.

**PUERTA 4:** salud 200 por las URLs públicas; Flyway en V8; conteos iguales a los de antes;
humo todo `OK`; `push_sender_ready enabled=true` si Firebase estaba definido en la fase 0.
Si falla cualquier cosa → **Vuelta atrás**.

### Vuelta atrás (sólo si falla la puerta 4)

```bash
$P_NEW stop auth-svc business-svc
docker exec "$DB" psql -U paktay -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = 'paktay' and pid <> pg_backend_pid()"
docker exec "$DB" psql -U paktay -d postgres -c "drop database paktay"
docker exec "$DB" psql -U paktay -d postgres -c "create database paktay owner paktay"
docker exec -i "$DB" pg_restore -U paktay -d paktay --no-owner < ~/paktay-backups/prod-$TS.dump
```

Después, desde la carpeta original de producción (que sigue en `main`): `$P_OLD up -d`, comprueba
salud por las URLs públicas y los conteos, y anota todo en `$LOGS/04-vuelta-atras.txt`. Fin.

## Fase 5 · Build de TestFlight desde `develop`

```bash
cd ../packtay_mobile_front
git fetch origin && git checkout develop && git pull --ff-only
grep -E "CURRENT_PROJECT_VERSION|MARKETING_VERSION" ios/FinanceApp.xcodeproj/project.pbxproj | sort -u > "$LOGS/05-version-actual.txt"
```

1. **Número de build:** usa `YYYYMMDDNN` de hoy (p. ej. `2026092501`), mayor que el actual.
   Reemplaza **todas** las líneas `CURRENT_PROJECT_VERSION = …;` (app y widget deben quedar
   iguales; `scripts/generate-env.mjs` lo exige). `MARKETING_VERSION` no se cambia. Haz ese
   cambio en una rama `release/beta-<build>`, commit `chore(release): build <build> para TestFlight`
   y súbela (el dueño la integra después).
2. **Entorno de producción:** debe existir `.env.production` con
   ```
   PAKTAY_AUTH_BASE_URL=https://paktayauth.rocketsoftwarecore.com
   PAKTAY_BUSINESS_BASE_URL=https://paktay.rocketsoftwarecore.com
   PAKTAY_KEYCLOAK_BASE_URL=https://keycloak.rocketsoftwarecore.com
   PAKTAY_KEYCLOAK_REALM=paktay
   PAKTAY_KEYCLOAK_CLIENT_ID=paktay-mobile
   PAKTAY_URL_SHORTCUT=<el que ya tenga, o vacío>
   PAKTAY_URL_TERMS=
   PAKTAY_URL_PRIVACY=
   ```
   Si ya existe, respeta sus valores y sólo agrega las claves que falten. No lo subas a git.
3. Dependencias y configuración:
   ```bash
   npm ci > /tmp/npm.log 2>&1; echo "exit=$?" >> /tmp/npm.log; tail -n 3 /tmp/npm.log > "$LOGS/05-npm.txt"
   node scripts/generate-env.mjs --production > "$LOGS/05-env.txt" 2>&1
   (cd ios && pod install > /tmp/pod.log 2>&1; echo "exit=$?" >> /tmp/pod.log); tail -n 3 /tmp/pod.log > "$LOGS/05-pod.txt"
   npx tsc --noEmit > /tmp/tsc.log 2>&1; echo "exit=$?" >> /tmp/tsc.log; tail -n 20 /tmp/tsc.log > "$LOGS/05-tsc.txt"
   npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log; grep -E "Tests:|Test Suites:|exit=" /tmp/jest.log > "$LOGS/05-jest.txt"
   ```
   **PUERTA 5a:** `generate-env` sin error, tsc `exit=0`, Jest sin fallos. Si falla, detente
   (producción ya quedó en `develop` y funciona con el build viejo sólo a medias: anótalo bien).
4. Archivo y subida (firma automática con la cuenta de Xcode de la Mac):
   ```bash
   B=$(grep -m1 "CURRENT_PROJECT_VERSION" ios/FinanceApp.xcodeproj/project.pbxproj | sed 's/[^0-9]//g')
   TEAM=$(grep -m1 "DEVELOPMENT_TEAM" ios/FinanceApp.xcodeproj/project.pbxproj | sed 's/.*= *//; s/;//')
   xcodebuild -workspace ios/FinanceApp.xcworkspace -scheme FinanceApp -configuration Release \
     -destination 'generic/platform=iOS' -archivePath ~/paktay-builds/PAKTAY-$B.xcarchive \
     -allowProvisioningUpdates archive > /tmp/archive.log 2>&1; echo "exit=$?" >> /tmp/archive.log
   grep -E "error:|ARCHIVE SUCCEEDED|ARCHIVE FAILED|exit=" /tmp/archive.log | head -n 60 > "$LOGS/05-archive.txt"
   cat > /tmp/ExportOptions.plist <<EOF
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0"><dict>
     <key>method</key><string>app-store-connect</string>
     <key>destination</key><string>upload</string>
     <key>signingStyle</key><string>automatic</string>
     <key>teamID</key><string>$TEAM</string>
     <key>uploadSymbols</key><true/>
   </dict></plist>
   EOF
   xcodebuild -exportArchive -archivePath ~/paktay-builds/PAKTAY-$B.xcarchive \
     -exportOptionsPlist /tmp/ExportOptions.plist -exportPath ~/paktay-builds/export-$B \
     -allowProvisioningUpdates > /tmp/export.log 2>&1; echo "exit=$?" >> /tmp/export.log
   grep -E "error:|EXPORT SUCCEEDED|EXPORT FAILED|Upload|exit=" /tmp/export.log | head -n 60 > "$LOGS/05-export.txt"
   ```
   Si la subida falla por cuenta o permisos de App Store Connect, cambia `destination` a
   `export`, genera el `.ipa` en `~/paktay-builds/export-$B` y anota en el resumen que el dueño
   debe subirlo con Transporter o desde Xcode › Organizer. Con el `.ipa` a mano, comprueba el
   entorno de avisos:
   `unzip -o -q ~/paktay-builds/export-$B/*.ipa -d /tmp/ipa && codesign -d --entitlements - /tmp/ipa/Payload/*.app 2>/dev/null | grep -A1 aps-environment` → debe decir `production`.

```bash
git checkout develop
cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md` con cada fase y su puerta (pasó / no pasó / no se llegó), el tamaño del
respaldo (no la ruta completa del usuario), el historial de Flyway antes y después, el diff de
esquema resumido, conteos antes / ensayo / después, humo, salud pública, número de build,
resultado del archivo y de la subida, y **qué le toca hacer al dueño** (pasos 6 y 7 de
`docs/testflight-beta.md`).

```bash
git worktree list >> "$LOGS/99-worktrees.txt"
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): beta en TestFlight ($RUN)"
git push origin develop
```
