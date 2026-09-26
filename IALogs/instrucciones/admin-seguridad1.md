# Panel admin, seguridad 1: CORS por dominio y cabecera X-Paktay-Client (para Codex)

Rama `feature/admin-seguridad-cors-cabecera` (sale de la fase 2). **No cambies código.**

Qué trae:
- `/api/v1/admin/**` (auth-svc y business-svc) exige la cabecera `X-Paktay-Client: admin-web`;
  sin ella responde 403 con `code = ADMIN_CLIENT_REQUIRED`. Las demás rutas (app móvil, auth,
  catálogo público, soporte público) no cambian.
- CORS: `PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS` (panel, todas las rutas) y la nueva
  `PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS` (solo `/api/v1/public/**`; vacía = la del panel).

## Reglas

Las de siempre: nunca `main` ni `paktay-prod`, sin secretos en los logs, mismos `L`, `F`,
`wait_up` y Keycloak local (28180). Si no compila, guarda todos los `ERROR]` y detente.

## 1. Compilar y levantar

```bash
cd packtay_backend
git fetch origin && git checkout feature/admin-seguridad-cors-cabecera && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-seguridad1
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
./mvnw -B clean package > "$LOGS/01-mvn-full.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn-full.log"
grep -E "Tests run:|BUILD|FAIL|ERROR\]" "$LOGS/01-mvn-full.log" | head -n 200 > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-up.log"
set -a; . ./.env; set +a
```

## 2. Matriz de acceso (valores por defecto: CORS localhost)

Guarda como `/tmp/seg1.py` y ejecuta `python3 /tmp/seg1.py > "$LOGS/03-matriz.txt" 2>&1`.

```python
import json, os, time, urllib.request, urllib.error
A = "http://localhost:28081"; B = "http://localhost:28082"
def call(m, url, body=None, headers=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, dict(r.headers), r.read().decode()[:200]
    except urllib.error.HTTPError as e: return e.code, dict(e.headers), e.read().decode()[:200]
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:250] if detail != "" else ""))
def login(email, pwd):
    code, _, body = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})
    return json.loads(body).get("access_token") if code == 200 else None

ADMIN = login(os.environ["PAKTAY_ADMIN_EMAIL"], os.environ["PAKTAY_ADMIN_PASSWORD"])
auth = {"Authorization": "Bearer " + ADMIN}
client = {"X-Paktay-Client": "admin-web"}
stamp = int(time.time())
email = f"codexseg{stamp}@paktay.local"; pwd = "Prueba-Paktay-2026!"
call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Seguridad", "password": pwd})
USER = {"Authorization": "Bearer " + login(email, pwd)}

code, _, body = call("GET", B + "/api/v1/admin/users", headers=auth)
check("business admin sin cabecera -> 403 ADMIN_CLIENT_REQUIRED", code == 403 and "ADMIN_CLIENT_REQUIRED" in body, (code, body))
check("business admin con cabecera -> 200", call("GET", B + "/api/v1/admin/users", headers={**auth, **client})[0] == 200)
code, _, body = call("GET", A + "/api/v1/admin/admins", headers=auth)
check("auth admin sin cabecera -> 403", code == 403 and "ADMIN_CLIENT_REQUIRED" in body, (code, body))
check("auth admin con cabecera -> 200", call("GET", A + "/api/v1/admin/admins", headers={**auth, **client})[0] == 200)
check("cabecera con otro valor -> 403", call("GET", B + "/api/v1/admin/users", headers={**auth, "X-Paktay-Client": "otro"})[0] == 403)
check("sin token pero con cabecera -> 401", call("GET", B + "/api/v1/admin/users", headers=client)[0] == 401)
check("ruta de la app sin cabecera -> 200 (el móvil no cambia)", call("GET", B + "/api/v1/user/cards", headers=USER)[0] == 200)
check("login de la app sin cabecera -> 200", login(email, pwd) is not None)
check("catálogo público sin cabecera -> 200", call("GET", B + "/api/v1/catalog/currencies", headers=USER)[0] == 200)
check("soporte público sin cabecera -> 202", call("POST", B + "/api/v1/public/support/tickets", {"email": email, "reason": "Prueba de seguridad", "website": ""})[0] == 202)

def preflight(url, origin, headers="authorization,x-paktay-client"):
    code, h, _ = call("OPTIONS", url, headers={"Origin": origin, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": headers})
    return code, h.get("Access-Control-Allow-Origin")
code, allow = preflight(B + "/api/v1/admin/users", "http://localhost:3000")
check("preflight desde el panel local permitido", code == 200 and allow == "http://localhost:3000", (code, allow))
code, allow = preflight(B + "/api/v1/admin/users", "https://malicioso.example")
check("preflight desde otro dominio rechazado", code == 403 and allow is None, (code, allow))
code, allow = preflight(A + "/api/v1/admin/admins", "http://localhost:3000")
check("preflight auth-svc permite X-Paktay-Client", code == 200, (code, allow))
```

## 3. CORS con dominios de producción simulados

```bash
export PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS=https://admin.paktay.test
export PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS=https://soporte.paktay.test
$L up -d auth-svc business-svc > "$LOGS/04-restart.log" 2>&1; wait_up
for o in http://localhost:3000 https://admin.paktay.test https://soporte.paktay.test; do
  for p in /api/v1/admin/users /api/v1/public/support/tickets; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS "http://localhost:28082$p" -H "Origin: $o" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: content-type,x-paktay-client")
    echo "$o $p $code"
  done
done > "$LOGS/05-cors-produccion.txt"
unset PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS
$L up -d auth-svc business-svc > /dev/null 2>&1; wait_up
$L stop
```

Esperado en `05-cors-produccion.txt`: `localhost:3000` → 403 en las dos; `admin.paktay.test` →
200 en `/admin/users` y 403 en `/public/...`; `soporte.paktay.test` → 403 en `/admin/users` y 200
en `/public/...`.

## Entrega

`RESUMEN.md` con Maven, la matriz OK/FAIL y la tabla de CORS. Commit y push a `develop` solo de
`IALogs/logs/$RUN` con el mensaje `chore(ialogs): $RUN`.
