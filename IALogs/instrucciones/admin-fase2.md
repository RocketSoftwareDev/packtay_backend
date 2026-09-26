# Panel admin, fase 2: catálogos, indicadores, notificaciones y estado (para Codex)

Objetivo: compilar y probar `feature/admin-fase2-catalogos-indicadores` (sale de `develop`, que ya
tiene la fase 1). **No cambies código.** Todo va a `IALogs/logs/`.

Qué trae (business-svc, todo bajo `/api/v1/admin`, rol ADMIN):
- `catalog/categories` (GET agrupado, POST solo `{parentCode, name}`, PATCH `{active}`).
- `catalog/banks` (GET `?origin=SYSTEM|CUSTOM`, `banks/counts`, POST, PATCH, `banks/{id}/offerings`).
- `catalog/currencies` y `catalog/countries` (GET, POST, PATCH con reglas de moneda base y moneda activa).
- `metrics/summary`, `notifications`, `notifications/summary`, `notifications/test`, `system/status`.
- El cliente `paktay-admin-web` de Keycloak pasa de `localhost:5173` a `localhost:3000` (la web es Next).

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Solo `paktay-local` y `paktay-fresh`.
- Mismos `L`, `F` y `wait_up` que en la fase 1b; mismo Keycloak local (28180).
- No escribas tokens ni secretos en los logs.
- Si el build falla, guarda **todos** los `ERROR]` en `$LOGS/01b-mvn-error.txt` y detente.

## 1. Compilar con pruebas

```bash
cd packtay_backend
git fetch origin && git checkout feature/admin-fase2-catalogos-indicadores && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-fase2
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
./mvnw -B clean package > "$LOGS/01-mvn-full.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn-full.log"
grep -E "Tests run:|BUILD|FAIL|ERROR\]" "$LOGS/01-mvn-full.log" | head -n 300 > "$LOGS/01-mvn.txt"
```

## 2. Levantar

Igual que la fase 1b (Mailpit incluido, variables del shell). Además:

```bash
$L up --build -d > "$LOGS/03-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/03-up.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/03b-keycloak-init.log" 2>&1; echo "exit=$?" >> "$LOGS/03b-keycloak-init.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/04-history.txt" 2>&1
set -a; . ./.env; set +a      # PAKTAY_ADMIN_EMAIL / PAKTAY_ADMIN_PASSWORD, sin imprimirlos
export L
```

Comprueba que `keycloak-init` dejó el cliente del panel apuntando a `http://localhost:3000/*`
(guarda `redirectUris` y `webOrigins` de `paktay-admin-web` en `$LOGS/03c-cliente.txt`).

## 3. Escenario

Guarda como `/tmp/fase2.py` y ejecuta `python3 /tmp/fase2.py > "$LOGS/05-fase2.txt" 2>&1`.

```python
import json, os, random, string, subprocess, time, urllib.request, urllib.error
A = "http://localhost:28081"; B = "http://localhost:28082"
def call(m, url, body=None, t=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + t} if t else {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        data = e.read()
        try: return e.code, json.loads(data)
        except Exception: return e.code, data.decode()[:200]
def sql(q):
    cmd = os.environ["L"].split() + ["exec", "-T", "business-db", "psql", "-U", "paktay", "-d", "paktay", "-At", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:300] if detail != "" else ""))
def login(email, pwd):
    code, body = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})
    return body.get("access_token") if code == 200 else None

stamp = int(time.time()); PWD = "Prueba-Paktay-2026!"
ADMIN = login(os.environ["PAKTAY_ADMIN_EMAIL"], os.environ["PAKTAY_ADMIN_PASSWORD"])
check("login del admin", ADMIN is not None)
email = f"codexf2{stamp}@paktay.local"
call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Fase Dos", "password": PWD})
USER = login(email, PWD)
check("usuario sin ADMIN -> 403 en catálogos", call("GET", B + "/api/v1/admin/catalog/banks", t=USER)[0] == 403)

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
print("oferta repetida ->", call("POST", B + f"/api/v1/admin/catalog/banks/{bid}/offerings", {"cardType": "CREDIT", "brand": "VISA"}, t=ADMIN)[0], "(409 si hay índice único; anotar)")
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

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/admin/catalog/categories","/api/v1/admin/catalog/categories/{code}","/api/v1/admin/catalog/banks","/api/v1/admin/catalog/banks/counts","/api/v1/admin/catalog/banks/{id}/offerings","/api/v1/admin/catalog/currencies/{code}","/api/v1/admin/catalog/countries","/api/v1/admin/metrics/summary","/api/v1/admin/notifications/test","/api/v1/admin/system/status"]})' > "$LOGS/06-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/07-health.txt"
$L logs --no-color --tail=400 business-svc | grep -iE "error|exception" | head -n 150 > "$LOGS/08-logs.txt"
$L stop
docker rm -f paktay-mailpit-test >/dev/null 2>&1
```

No hace falta la base desde cero: esta fase no trae migraciones.

## Entrega

`RESUMEN.md` con Maven (pruebas por módulo), OK/FAIL del escenario, el código de "oferta repetida"
y el cliente del panel en Keycloak. Commit y push a `develop` solo de `IALogs/logs/$RUN` con el
mensaje `chore(ialogs): $RUN`.
