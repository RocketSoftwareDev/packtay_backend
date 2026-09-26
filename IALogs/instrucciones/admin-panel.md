# Panel admin: regresión completa en develop (para Codex)

Reemplaza a `admin-fase2.md` y `admin-seguridad1.md` (runs `2026-09-26_1108-admin-fase2` y
`2026-09-26_1112-admin-seguridad1`, los dos en verde). Desde el PR #25, `develop` exige la cabecera
`X-Paktay-Client` en `/api/v1/admin/**`, así que el escenario de la fase 2 ya no corre sin ella. Este
archivo es el único que se usa para probar el panel. **No cambies código.** Todo va a `IALogs/logs/`.

Cubre:
- Catálogos: categorías, bancos y ofertas, monedas y países.
- Resumen, notificaciones y estado del sistema.
- Auditoría de las acciones del administrador.
- Cabecera obligatoria solo en rutas de admin (la app móvil y las rutas públicas no cambian).
- CORS por dominio: panel y formulario público por separado.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Solo `paktay-local`.
- Mismos `L`, `F` y `wait_up` de siempre; Keycloak local (28180); Mailpit como en la fase 1b.
- No escribas tokens ni secretos en los logs.
- Si el build falla, guarda **todos** los `ERROR]` en `$LOGS/01b-mvn-error.txt` y detente.

## 1. Compilar y levantar

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-panel
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
./mvnw -B clean package > "$LOGS/01-mvn-full.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn-full.log"
grep -E "Tests run:|BUILD|FAIL|ERROR\]" "$LOGS/01-mvn-full.log" | head -n 300 > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-up.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/02b-keycloak-init.log" 2>&1; echo "exit=$?" >> "$LOGS/02b-keycloak-init.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/03-history.txt" 2>&1
set -a; . ./.env; set +a      # PAKTAY_ADMIN_EMAIL / PAKTAY_ADMIN_PASSWORD, sin imprimirlos
export L
```

## 2. Escenario

Guarda como `/tmp/panel.py` y ejecuta `python3 /tmp/panel.py > "$LOGS/04-escenario.txt" 2>&1`.

```python
import json, os, string, subprocess, time, urllib.request, urllib.error
A = "http://localhost:28081"; B = "http://localhost:28082"
PANEL = {"X-Paktay-Client": "admin-web"}

def raw(m, url, body=None, headers=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e: return e.code, dict(e.headers), e.read()
def call(m, url, body=None, t=None, panel=True, extra=None):
    """Por defecto se comporta como el panel: token + cabecera X-Paktay-Client."""
    h = {**(PANEL if panel else {}), **({"Authorization": "Bearer " + t} if t else {}), **(extra or {})}
    code, _, data = raw(m, url, body, h)
    try: return code, json.loads(data or b"null")
    except Exception: return code, data.decode()[:200]
def sql(q):
    cmd = os.environ["L"].split() + ["exec", "-T", "business-db", "psql", "-U", "paktay", "-d", "paktay", "-At", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:300] if detail != "" else ""))
def login(email, pwd):
    code, body = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd}, panel=False)
    return body.get("access_token") if code == 200 and isinstance(body, dict) else None

stamp = int(time.time()); PWD = "Prueba-Paktay-2026!"
ADMIN = login(os.environ["PAKTAY_ADMIN_EMAIL"], os.environ["PAKTAY_ADMIN_PASSWORD"])
check("login del admin", ADMIN is not None)
email = f"codexpanel{stamp}@paktay.local"
call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Panel", "password": PWD}, panel=False)
USER = login(email, PWD)
check("usuario sin ADMIN -> 403 en catálogos", call("GET", B + "/api/v1/admin/catalog/banks", t=USER)[0] == 403)

print("== Cabecera X-Paktay-Client")
code, body = call("GET", B + "/api/v1/admin/users", t=ADMIN, panel=False)
check("business admin sin cabecera -> 403 ADMIN_CLIENT_REQUIRED", code == 403 and "ADMIN_CLIENT_REQUIRED" in json.dumps(body), (code, body))
check("business admin con cabecera -> 200", call("GET", B + "/api/v1/admin/users", t=ADMIN)[0] == 200)
code, body = call("GET", A + "/api/v1/admin/admins", t=ADMIN, panel=False)
check("auth admin sin cabecera -> 403", code == 403 and "ADMIN_CLIENT_REQUIRED" in json.dumps(body), (code, body))
check("auth admin con cabecera -> 200", call("GET", A + "/api/v1/admin/admins", t=ADMIN)[0] == 200)
check("cabecera con otro valor -> 403", call("GET", B + "/api/v1/admin/users", t=ADMIN, panel=False, extra={"X-Paktay-Client": "otro"})[0] == 403)
check("sin token pero con cabecera -> 401", call("GET", B + "/api/v1/admin/users")[0] == 401)
check("ruta de la app sin cabecera -> 200 (el móvil no cambia)", call("GET", B + "/api/v1/user/cards", t=USER, panel=False)[0] == 200)
check("catálogo de la app sin cabecera -> 200", call("GET", B + "/api/v1/catalog/currencies", t=USER, panel=False)[0] == 200)
check("soporte público sin cabecera -> 202", call("POST", B + "/api/v1/public/support/tickets", {"email": email, "reason": "Prueba del panel", "website": ""}, panel=False)[0] == 202)

print("== CORS local (panel en localhost:3000)")
def preflight(url, origin):
    code, h, _ = raw("OPTIONS", url, headers={"Origin": origin, "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "authorization,x-paktay-client"})
    return code, h.get("Access-Control-Allow-Origin")
r = preflight(B + "/api/v1/admin/users", "http://localhost:3000")
check("preflight business desde el panel permitido", r == (200, "http://localhost:3000"), r)
r = preflight(A + "/api/v1/admin/admins", "http://localhost:3000")
check("preflight auth desde el panel permitido", r == (200, "http://localhost:3000"), r)
r = preflight(B + "/api/v1/admin/users", "https://malicioso.example")
check("preflight desde otro dominio rechazado", r == (403, None), r)

print("== Categorías")
code, groups = call("GET", B + "/api/v1/admin/catalog/categories", t=ADMIN)
check("grupos con subcategorías", code == 200 and len(groups) > 0 and all(g["subcategories"] for g in groups), code)
parent = groups[0]["code"]
code, sub = call("POST", B + "/api/v1/admin/catalog/categories", {"parentCode": parent, "name": f"Codex prueba {stamp}"}, t=ADMIN)
check("crear subcategoría con solo grupo y nombre", code == 201 and sub["code"] == f"codex-prueba-{stamp}" and sub["parentCode"] == parent, sub)
check("hereda el ícono del grupo", code == 201 and sub["icon"] == groups[0]["icon"], sub)
code, off = call("PATCH", B + f"/api/v1/admin/catalog/categories/codex-prueba-{stamp}", {"active": False}, t=ADMIN)
check("desactivar subcategoría", code == 200 and off["active"] is False, off)
check("grupo inexistente -> 404", call("POST", B + "/api/v1/admin/catalog/categories", {"parentCode": "no-existe", "name": "X"}, t=ADMIN)[0] == 404)

print("== Bancos")
code, banks = call("GET", B + "/api/v1/admin/catalog/banks?origin=SYSTEM", t=ADMIN)
check("bancos del sistema con ofertas", code == 200 and len(banks) > 0 and "offerings" in banks[0], code)
check("ofertas de débito sin marca", all(o["brand"] is None for b in banks for o in b["offerings"] if o["cardType"] == "DEBIT"))
code, counts = call("GET", B + "/api/v1/admin/catalog/banks/counts", t=ADMIN)
check("conteos", code == 200 and counts["system"] == len(banks), counts)
check("bancos de usuarios agrupados", call("GET", B + "/api/v1/admin/catalog/banks?origin=CUSTOM", t=ADMIN)[0] == 200)
code, bank = call("POST", B + "/api/v1/admin/catalog/banks", {"name": f"Banco Codex {stamp}", "country": "EC"}, t=ADMIN)
check("crear banco", code == 201 and bank["origin"] == "SYSTEM" and bank["offerings"] == [], bank)
bid = bank["id"] if code == 201 else None
code, b2 = call("POST", B + f"/api/v1/admin/catalog/banks/{bid}/offerings", {"cardType": "DEBIT", "brand": "VISA", "sourceUrl": ""}, t=ADMIN)
check("débito con marca se guarda sin marca", code == 200 and b2["offerings"][0]["brand"] is None, b2)
check("crédito sin marca -> 400", call("POST", B + f"/api/v1/admin/catalog/banks/{bid}/offerings", {"cardType": "CREDIT", "brand": None}, t=ADMIN)[0] == 400)
code, b3 = call("POST", B + f"/api/v1/admin/catalog/banks/{bid}/offerings", {"cardType": "CREDIT", "brand": "VISA", "sourceUrl": "https://ejemplo.ec"}, t=ADMIN)
check("crédito Visa", code == 200 and len(b3["offerings"]) == 2, b3)
check("oferta repetida -> 409", call("POST", B + f"/api/v1/admin/catalog/banks/{bid}/offerings", {"cardType": "CREDIT", "brand": "VISA"}, t=ADMIN)[0] == 409)
check("banco duplicado -> 409", call("POST", B + "/api/v1/admin/catalog/banks", {"name": f"banco codex {stamp}", "country": "EC"}, t=ADMIN)[0] == 409)
code, b4 = call("PATCH", B + f"/api/v1/admin/catalog/banks/{bid}", {"active": False}, t=ADMIN)
check("desactivar banco", code == 200 and b4["active"] is False, b4)

print("== Monedas y países")
code, curs = call("GET", B + "/api/v1/admin/catalog/currencies", t=ADMIN)
base = [c for c in curs if c["base"]] if code == 200 else []
check("hay una moneda base", len(base) == 1, base)
check("desactivar la base -> 409", call("PATCH", B + f"/api/v1/admin/catalog/currencies/{base[0]['code']}", {"active": False}, t=ADMIN)[0] == 409)
existing = {c["code"] for c in curs}
cur = next("Z" + a + b for a in string.ascii_uppercase for b in string.ascii_uppercase if "Z" + a + b not in existing)
code, c1 = call("POST", B + "/api/v1/admin/catalog/currencies", {"code": cur, "numericCode": "9" + str(stamp)[-2:], "name": "Moneda Codex", "symbol": "¤", "decimals": 2}, t=ADMIN)
check("crear moneda", code == 201 and c1["active"] is True, c1)
check("desactivar moneda nueva", call("PATCH", B + f"/api/v1/admin/catalog/currencies/{cur}", {"active": False}, t=ADMIN)[0] == 200)
code, cs = call("GET", B + "/api/v1/admin/catalog/countries", t=ADMIN)
existing_c = {c["code"] for c in cs} if code == 200 else set()
cc = next(a + b for a in "QXZ" for b in string.ascii_uppercase if a + b not in existing_c)
check("país con moneda inactiva -> 409", call("POST", B + "/api/v1/admin/catalog/countries", {"code": cc, "name": "País Codex", "currencyCode": cur}, t=ADMIN)[0] == 409)
call("PATCH", B + f"/api/v1/admin/catalog/currencies/{cur}", {"active": True}, t=ADMIN)
code, c2 = call("POST", B + "/api/v1/admin/catalog/countries", {"code": cc, "name": "País Codex", "currencyCode": cur}, t=ADMIN)
check("crear país", code == 201 and c2["usersCount"] == 0, c2)
check("moneda usada por país activo -> 409", call("PATCH", B + f"/api/v1/admin/catalog/currencies/{cur}", {"active": False}, t=ADMIN)[0] == 409)
check("desactivar país", call("PATCH", B + f"/api/v1/admin/catalog/countries/{cc}", {"active": False}, t=ADMIN)[0] == 200)

print("== Resumen, notificaciones y estado")
code, m = call("GET", B + "/api/v1/admin/metrics/summary", t=ADMIN)
check("cifras del resumen", code == 200 and m["activeUsers"] >= 1 and len(m["signupsByWeek"]) == 8 and m["plans"], m)
check("sin montos en el resumen", code == 200 and "amount" not in json.dumps(m).lower())
code, ns = call("GET", B + "/api/v1/admin/notifications/summary", t=ADMIN)
check("resumen de avisos", code == 200 and ns["failed"] == ns["sentThisMonth"] - ns["delivered"], ns)
check("registro de avisos", call("GET", B + "/api/v1/admin/notifications?result=FAILED", t=ADMIN)[0] == 200)
code, tp = call("POST", B + "/api/v1/admin/notifications/test", t=ADMIN)
check("push de prueba responde con mensaje", code == 200 and "message" in tp, tp)
code, st = call("GET", B + "/api/v1/admin/system/status", t=ADMIN)
ids = {s["id"]: s["health"] for s in st["services"]} if code == 200 else {}
check("estado con 6 servicios", code == 200 and len(ids) == 6, ids)
check("auth-svc UP desde business-svc", ids.get("auth-svc") == "UP", ids)
check("Keycloak y la base UP", ids.get("keycloak") == "UP" and ids.get("postgres") == "UP", ids)
check("versión del esquema V9", any(v["value"].endswith("V9") for v in st.get("versions", [])), st.get("versions"))

print("== Auditoría")
for action in ["Subcategoría creada", "Categoría desactivada", "Banco creado", "Oferta de tarjeta agregada", "Banco desactivado", "Moneda creada", "País creado"]:
    check(f"auditado: {action}", sql(f"select count(*) from admin_audit where action = '{action}' and created_at > now() - interval '10 minutes'") != "0")
```

## 3. OpenAPI, salud y logs

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/admin/catalog/categories","/api/v1/admin/catalog/categories/{code}","/api/v1/admin/catalog/banks","/api/v1/admin/catalog/banks/counts","/api/v1/admin/catalog/banks/{id}/offerings","/api/v1/admin/catalog/currencies/{code}","/api/v1/admin/catalog/countries","/api/v1/admin/metrics/summary","/api/v1/admin/notifications/test","/api/v1/admin/system/status"]})' > "$LOGS/05-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/06-health.txt"
$L logs --no-color --tail=400 auth-svc business-svc | grep -iE "error|exception" | head -n 150 > "$LOGS/07-logs.txt"
```

## 4. CORS con dominios de producción simulados

```bash
export PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS=https://admin.paktay.test
export PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS=https://soporte.paktay.test
$L up -d auth-svc business-svc > "$LOGS/08-restart.log" 2>&1; wait_up
for o in http://localhost:3000 https://admin.paktay.test https://soporte.paktay.test; do
  for p in /api/v1/admin/users /api/v1/public/support/tickets; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS "http://localhost:28082$p" -H "Origin: $o" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: content-type,x-paktay-client")
    echo "$o $p $code"
  done
done > "$LOGS/09-cors-produccion.txt"
unset PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS
$L up -d auth-svc business-svc > /dev/null 2>&1; wait_up
$L stop
docker rm -f paktay-mailpit-test >/dev/null 2>&1
```

Esperado en `09-cors-produccion.txt`: `localhost:3000` → 403 en las dos; `admin.paktay.test` →
200 en `/admin/users` y 403 en `/public/...`; `soporte.paktay.test` → 403 en `/admin/users` y 200
en `/public/...`.

## Entrega

`RESUMEN.md` con Maven (pruebas por módulo), el OK/FAIL de cada bloque del escenario, la tabla de CORS
y el cliente del panel en Keycloak. Commit y push a `develop` solo de `IALogs/logs/$RUN` con el mensaje
`chore(ialogs): $RUN`.
