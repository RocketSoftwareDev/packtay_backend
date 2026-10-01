# Día 8c: entrar con Apple y Google + bloqueo con Face ID (para Codex)

Prueba las ramas `feature/dia8c-login-social` de backend y móvil. Todo va a `IALogs/logs/`.
Sin imágenes, sin tokens completos ni secretos en los logs.

Qué hay nuevo:
- auth-svc: `POST /api/v1/auth/social/{apple|google}` (valida el ID token del proveedor, deja
  listo al usuario en Keycloak —por vínculo, vinculando por correo o creándolo sin contraseña con
  `USER`— y abre la sesión con token exchange), `POST /api/v1/auth/social/refresh`,
  `GET /api/v1/auth/account/methods`, y eliminar cuenta sin contraseña para quien no la tiene.
- business-svc: V13 (`social_provider_tokens`, para revocar Apple al eliminar la cuenta).
- `infra/keycloak/social-setup.sh`: proveedores y permisos del token exchange.
- Móvil: botones de Apple y Google, renovación de esas sesiones a través de auth-svc, perfil y
  eliminar cuenta sin contraseña con Face ID, y el bloqueo con Face ID reescrito.
- Aceptación de Términos y Privacidad con fecha y versión (`app_users`, V13). La línea «Al
  continuar con Apple o Google aceptas…» va bajo los botones en Login y Crear cuenta. Las URL
  salen de `PAKTAY_URL_TERMS` y `PAKTAY_URL_PRIVACY` en la app, y las versiones de
  `LEGAL_TERMS_VERSION` y `LEGAL_PRIVACY_VERSION` en auth-svc.

Sin cuentas reales de Apple y Google, el backend se prueba con el **proveedor falso** de la
prueba previa (`dia8c-spike.md`): un realm `fakeidp` que emite tokens como Google. Apple solo se
prueba con pruebas unitarias aquí; Apple real y Face ID real, en el iPhone del dueño.

## Reglas

- **Nunca** `main`, `paktay-prod` ni el VPS. Keycloak desechable propio (`kc-8c`, puerto 28190);
  no toques `keycloakservices-local`.
- `paktay-local` se apunta a `kc-8c` solo durante la prueba (con un `.env.8c`, abajo) y al final
  vuelve a su configuración normal.
- Mismos `L`, `F` y `wait_up`. Si el build falla, guarda todos los `ERROR]` en
  `$LOGS/01b-mvn-error.txt` y detente. Si fallan solo pruebas unitarias, anótalas y sigue con
  `-DskipTests`.
- **Excepción a «no cambies código»** solo en el paso 4: `npm install` actualiza
  `package-lock.json` y `pod install` el `Podfile.lock` en la rama `feature/dia8c-login-social`
  del móvil; haz commit de esos dos archivos ahí (`chore(dia8c): lockfiles de Apple y Google`).

## 1. Backend: compilar y Keycloak desechable

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia8c
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia8c-login-social && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|FAIL|ERROR\]" /tmp/mvn.log | head -n 300 > "$LOGS/01-mvn.txt"
```

Levanta `kc-8c` como `kc-spike` (misma imagen 26.2, `start-dev`, admin con contraseña aleatoria en
`KC_ADMIN_PW`), con:

```text
--features=token-exchange:v1,admin-fine-grained-authz:v1
--hostname=http://host.docker.internal:28190
```

El `--hostname` fija el emisor de todos los tokens, se pidan desde donde se pidan, y así coincide
con lo que validan los servicios. Después:

1. Importa `paktay` desde `infra/keycloak/paktay-realm.json` y crea `fakeidp` igual que en la
   prueba previa (cliente público `fake-app` con direct grant; usuarios con correo verificado
   `nuevo@fake.local`, `existente@fake.local`, `bloqueado@fake.local`).
2. Corre `keycloak-init` apuntando a `kc-8c` (con `SHARED_KEYCLOAK_INTERNAL_URL=http://host.docker.internal:28190`
   y `KEYCLOAK_ADMIN_PASSWORD=$KC_ADMIN_PW`). Si `set-password` falla porque el admin de PAKTAY no
   existe en el realm importado, créalo antes con ese correo y vuelve a correrlo.
3. `infra/keycloak/social-setup.sh` con `KC_CONTAINER=kc-8c`, `KC_ADMIN_PASSWORD=$KC_ADMIN_PW`,
   `GOOGLE_CLIENT_ID=fake-app`, `GOOGLE_ISSUER=http://host.docker.internal:28190/realms/fakeidp` y
   `GOOGLE_JWKS=http://host.docker.internal:28190/realms/fakeidp/protocol/openid-connect/certs`.
   Guarda su salida en `$LOGS/02-social-setup.txt`. Córrelo **dos veces** (idempotente).

## 2. Backend apuntando a kc-8c

Crea `.env.8c` (no se sube) con:

```dotenv
SHARED_KEYCLOAK_INTERNAL_URL=http://host.docker.internal:28190
KEYCLOAK_PUBLIC_URL=http://host.docker.internal:28190
KEYCLOAK_ISSUER_URI=http://host.docker.internal:28190/realms/paktay
KEYCLOAK_SERVICE_CLIENT_SECRET=<el que dejó keycloak-init en kc-8c>
SOCIAL_GOOGLE_CLIENT_IDS=fake-app
SOCIAL_GOOGLE_ISSUER_OVERRIDE=http://host.docker.internal:28190/realms/fakeidp
SOCIAL_GOOGLE_JWKS_URL_OVERRIDE=http://host.docker.internal:28190/realms/fakeidp/protocol/openid-connect/certs
```

```bash
L8="docker compose -p paktay-local --env-file .env --env-file .env.local --env-file .env.8c"
$L8 up --build -d > "$LOGS/03-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/03-up.log"
$L8 exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/04-history.txt"
```

## 3. Prueba de punta a punta (`05-dia8c.txt`, una línea `OK`/`FAIL` por prueba)

Escríbela en Python como las de días anteriores (`A=http://localhost:28081`,
`B=http://localhost:28082`, `KC=http://localhost:28190`). Un ID token falso se pide a `fakeidp`
con password grant (`client_id=fake-app`, `scope=openid email profile`) y se manda como
`idToken` a `POST $A/api/v1/auth/social/google`.

1. **Usuario nuevo** (`nuevo@fake.local`): 200; claims del access token `azp=paktay-auth-service`,
   `realm_access.roles` incluye `USER`, `email` correcto. Con ese token, `GET $B/api/v1/user/profile`
   (o la ruta de perfil que use la app) responde 200. Hay fila en `app_users` con el correo y en
   `admin_audit` el alta con `source=google`.
2. **Segunda vez:** mismo `sub`, sin usuario duplicado en Keycloak.
3. **Métodos:** `GET $A/api/v1/auth/account/methods` → `hasPassword=false`, `providers=["google"]`.
4. **Cuenta que ya existía:** registra `existente@fake.local` con `POST $A/api/v1/auth/register`
   (con contraseña); después entra con Google con ese correo: 200, el `sub` es el de la cuenta
   registrada, su login con contraseña (`/api/v1/auth/login`) sigue en 200, y sus métodos son
   `hasPassword=true`, `providers=["google"]`.
5. **Renovar:** `POST $A/api/v1/auth/social/refresh` con el refresh token de la prueba 1 → 200; otra
   vez con el refresh token nuevo → 200. Un refresh token inventado → 401 `SESSION_EXPIRED`.
6. **Rechazos:** ID token con la firma alterada → 400 `SOCIAL_TOKEN_INVALID`; token de `fakeidp`
   pedido con **otro** cliente (crea `otra-app` en `fakeidp`) → 400 `SOCIAL_TOKEN_INVALID`
   (audiencia); `POST $A/api/v1/auth/social/apple` → 404 (Apple apagado en esta prueba);
   `POST $A/api/v1/auth/social/facebook` → 404; cuerpo sin `idToken` → 400.
7. **Cuenta bloqueada:** entra una vez con `bloqueado@fake.local`, desactiva ese usuario en `paktay`
   por la API de admin (`enabled=false`) y vuelve a entrar → 403 `ACCOUNT_BLOCKED`.
8. **Eliminar sin contraseña:** con el token del usuario de la prueba 1, `POST $A/api/v1/auth/account/delete`
   con `{}` → 200; el usuario ya no existe en Keycloak ni en `app_users`.
9. **Eliminar con contraseña:** con un token de `existente@fake.local`, `{}` → 400 y
   `{"password":"<la suya>"}` → 200.
10. **Términos y Privacidad:** `app_users` tiene `terms_accepted_at`, `terms_version`,
    `privacy_accepted_at` y `privacy_version` (V13).
    - El usuario de la prueba 1 (entró con `acceptedLegal: true`) tiene las dos fechas y las dos
      versiones en `1`.
    - Una cuenta **nueva** con Google **sin** `acceptedLegal` (crea `sinacepto@fake.local` en
      `fakeidp`) → 400 `LEGAL_ACCEPTANCE_REQUIRED` y no se crea en Keycloak.
    - `POST $A/api/v1/auth/register` con `acceptedLegal: true` guarda fechas y versiones; sin el
      campo, quedan en null (las builds viejas siguen registrando).
    - Volver a entrar con la misma versión no cambia `terms_accepted_at`.
11. **OpenAPI:** `/api/v1/auth/social/{provider}`, `/api/v1/auth/social/refresh` y
    `/api/v1/auth/account/methods` aparecen en `$A/v3/api-docs` (`06-openapi.txt`), y salud,
    OpenAPI y Swagger de los dos servicios en 200 (`07-health.txt`).

Después:

```bash
$L8 logs --no-color --tail=400 auth-svc | grep -iE "error|exception" | head -n 60 > "$LOGS/08-errores.txt"
$L8 stop
$L up -d > /dev/null 2>&1   # paktay-local vuelve a su Keycloak de siempre
docker rm -f kc-8c > /dev/null
$F up --build -d > "$LOGS/09-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/09-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/10-fresh-history.txt"
$F down -v > /dev/null 2>&1
git checkout develop
cd ..
```

## 4. Móvil

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia8c-login-social && git pull --ff-only
npm install > /tmp/npm.log 2>&1; echo "exit=$?" >> /tmp/npm.log; tail -n 5 /tmp/npm.log > "$LOGS/11-npm.txt"
(cd ios && pod install > /tmp/pod.log 2>&1; echo "exit=$?" >> /tmp/pod.log); tail -n 15 /tmp/pod.log > "$LOGS/12-pod.txt"
npm run env > /dev/null 2>&1
npx tsc --noEmit > /tmp/tsc.log 2>&1; echo "exit=$?" >> /tmp/tsc.log; head -n 200 /tmp/tsc.log > "$LOGS/13-tsc.log"
npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log
grep -E "^(PASS|FAIL)|Tests:|Test Suites:" /tmp/jest.log > "$LOGS/14-jest.log"
grep -A 30 "  ● [^C]" /tmp/jest.log | head -n 600 > "$LOGS/14-jest-fallos.log"
npm run lint > /tmp/lint.log 2>&1; echo "exit=$?" >> /tmp/lint.log; grep -E "error|problems|exit=" /tmp/lint.log | head -n 100 > "$LOGS/15-lint.txt"
```

- Si `npm install` y `pod install` terminan bien, commit de `package-lock.json` y `ios/Podfile.lock`
  en la rama (ver Reglas) y push.
- Compila para simulador sin firmar (`xcodebuild ... -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build`)
  y guarda las últimas 40 líneas en `16-xcodebuild.txt`.
- Comprueba que `ios/FinanceApp/FinanceApp.entitlements` y `FinanceApp.Release.entitlements`
  tienen `com.apple.developer.applesignin` y que están en `CODE_SIGN_ENTITLEMENTS` del target
  (`17-entitlements.txt`).
- **Pendiente del dueño, no lo hagas todavía:** el URL scheme de Google en `Info.plist` (el
  `REVERSED_CLIENT_ID`, `com.googleusercontent.apps.<...>`) y `PAKTAY_GOOGLE_IOS_CLIENT_ID` en
  `.env`/`.env.production`. Sin el Client ID real, el botón de Google no aparece (es lo esperado).

```bash
git checkout develop
cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md`: Maven (y las pruebas `SocialTokenVerifierTest`), V13 local y desde cero, la salida
del `social-setup.sh` (dos veces), cada `OK`/`FAIL` de `05-dia8c.txt`, OpenAPI, salud, npm/pod,
tsc, Jest, lint, xcodebuild y entitlements.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 8c ($RUN)"
git push origin develop
```

## Para el dueño, en su iPhone (después, con TestFlight)

Esto no lo puede hacer Codex. Cuando estén los Client ID reales y la build en TestFlight:

1. **Face ID:** activa «Bloqueo con Face ID» en Editar perfil (te pide la cara). Cierra la app del
   todo y ábrela: Face ID antes de ver nada. Cancela: te quedas en «PAKTAY está bloqueado», no en el
   inicio de sesión. Deja la app 1 minuto en segundo plano y vuelve: se bloquea. Menos de un minuto:
   no. Falla Face ID a propósito: te ofrece el código del iPhone.
2. **Apple:** entra con Apple con una cuenta nueva (prueba «Ocultar mi correo»), cierra sesión, entra
   otra vez: misma cuenta. Perfil: insignia «Entras con Apple» y sin «Cambiar contraseña».
3. **Google:** igual con Google (en modo Testing solo entran los correos agregados en Google Cloud).
4. **Vincular:** con una cuenta de correo y contraseña ya creada, entra con Google con ese mismo
   correo: ves tus mismos gastos.
5. **Eliminar:** con la cuenta de Apple, Eliminar mi cuenta pide Face ID y vuelves al inicio. En
   Ajustes › tu nombre › Iniciar sesión con Apple, PAKTAY ya no aparece.
