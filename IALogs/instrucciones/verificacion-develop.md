# Verificación de `develop` en la Mac (para Codex)

Objetivo: compilar y probar backend y front en `develop`, y dejar la salida en
`packtay_backend/IALogs/logs/` para que otra IA la lea. **No arregles código.**
Si algo falla, sigue con el resto y regístralo.

## Reglas

- Trabaja siempre en `develop` de los dos repos. **Nunca toques `main`**: ahí vive TestFlight.
- Usa sólo el proyecto Docker aislado `paktay-local`. **Nunca** `down -v` ni `stop`
  sobre el stack del túnel (proyecto por defecto con `docker-compose.cloudflare.yml`).
- No escribas secretos en los logs: nada de contenido de `.env`, tokens ni contraseñas.
  Si una salida los trae, reemplázalos por `***`.
- Cada paso guarda su salida completa (stdout y stderr) en su propio archivo.

## Preparación

Si en `develop` hay ramas de corrección pendientes de mezclar, la persona te lo dirá;
prueba sólo `develop`.

```bash
cd <carpeta que contiene packtay_backend y packtay_mobile_front>
export RUN=$(date +%Y-%m-%d_%H%M)
export LOGS=$PWD/packtay_backend/IALogs/logs/$RUN
mkdir -p "$LOGS"
for r in packtay_backend packtay_mobile_front; do
  git -C $r fetch origin && git -C $r checkout develop && git -C $r pull --ff-only
  git -C $r log --oneline -3 > "$LOGS/00-git-$r.log"
done
```

El `.env` del backend debe tener definidas `BUSINESS_DB_PASSWORD`,
`KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_SERVICE_CLIENT_SECRET`
y `PAKTAY_ADMIN_PASSWORD`. Si falta alguna, escribe sólo **el nombre** en
`$LOGS/00-env-faltantes.log` y detente en el paso 2.

## Pasos

```bash
# 1. Backend: compilar y tests
cd packtay_backend
./mvnw -B clean package > "$LOGS/01-backend-mvn.log" 2>&1; echo "exit=$?" >> "$LOGS/01-backend-mvn.log"

# 2. Backend: levantar aislado, con Keycloak propio en 28180 (nunca el de producción en 8180)
C="docker compose -p paktay-local --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml"
$C config --services > "$LOGS/02-backend-up.log" 2>&1
$C up --build -d keycloak-db keycloak business-db keycloak-init auth-svc business-svc >> "$LOGS/02-backend-up.log" 2>&1; echo "exit=$?" >> "$LOGS/02-backend-up.log"
# Keycloak tarda en importar el realm: espera hasta 4 min a que los dos servicios respondan
for i in $(seq 1 24); do curl -sf localhost:28082/actuator/health >/dev/null && break; sleep 10; done
$C ps -a >> "$LOGS/02-backend-up.log" 2>&1

# 3. Backend: salud, OpenAPI y Swagger (deben ser 200)
for u in http://localhost:28081 http://localhost:28082; do
  for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do
    echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"
  done
done > "$LOGS/03-backend-health.log" 2>&1

# 4. Backend: rutas publicadas (no deben aparecer shortcut, movements, unregistered, profile/automatic)
curl -s http://localhost:28082/v3/api-docs | grep -Eo '"/api/v1/[^"]+"' | sort -u > "$LOGS/04-backend-rutas.log" 2>&1

# 5. Backend: logs de arranque y apagado sin borrar datos
$C logs --no-color --tail=300 keycloak keycloak-init auth-svc business-svc > "$LOGS/05-backend-docker-logs.log" 2>&1
$C down > /dev/null 2>&1
cd ..

# 6. Front: dependencias, tipos, tests y lint
cd packtay_mobile_front
npm ci > "$LOGS/06-front-npm-ci.log" 2>&1; echo "exit=$?" >> "$LOGS/06-front-npm-ci.log"
npm run env > "$LOGS/07-front-env.log" 2>&1
npx tsc --noEmit > "$LOGS/08-front-tsc.log" 2>&1; echo "exit=$?" >> "$LOGS/08-front-tsc.log"
npx jest --ci > "$LOGS/09-front-jest.log" 2>&1; echo "exit=$?" >> "$LOGS/09-front-jest.log"
npm run lint > "$LOGS/10-front-lint.log" 2>&1; echo "exit=$?" >> "$LOGS/10-front-lint.log"

# 7. iOS: compilar app y widget para simulador (el widget nunca se ha compilado)
# Gemfile.lock fija Bundler 1.17.2, que no corre en Ruby 4. Si falla, usa CocoaPods directo.
cd ios
{ bundle install && bundle exec pod install; } > "$LOGS/11-ios-pods.log" 2>&1 \
  || { echo "--- bundler falló, se usa pod directo" >> "$LOGS/11-ios-pods.log"; pod install >> "$LOGS/11-ios-pods.log" 2>&1; }
echo "exit=$?" >> "$LOGS/11-ios-pods.log"
# Si xcodebuild dice "CoreSimulator is out of date", NO uses sudo: anótalo en el RESUMEN
# como acción para la persona ("sudo xcodebuild -runFirstLaunch") y sigue.
xcodebuild -workspace FinanceApp.xcworkspace -scheme FinanceApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build \
  > "$LOGS/12-ios-build-full.log" 2>&1; echo "exit=$?" >> "$LOGS/12-ios-build-full.log"
grep -E "error:|warning: .*PaktayWidget|BUILD (SUCCEEDED|FAILED)" "$LOGS/12-ios-build-full.log" > "$LOGS/12-ios-build.log"
cd ../..
```

## Resumen

Escribe `$LOGS/RESUMEN.md` con una tabla: paso, OK o FALLO, y para cada fallo las
primeras 20 líneas del error. Si `12-ios-build-full.log` pesa más de 5 MB, bórralo y
deja sólo `12-ios-build.log`.

## Subir

```bash
cd packtay_backend
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): verificación de develop $RUN"
git push origin develop
```

Sólo se suben archivos dentro de `IALogs/logs/`. Nada más.
