# Panel admin: regresión completa (para Codex)

Único script para probar el panel; reemplaza a `admin-fase2.md` y `admin-seguridad1.md`.
**No cambies código.** Todo va a `IALogs/logs/`.

Ramas: `develop` en los tres repos (backend PR #26 y #27, web PR #4, móvil PR #29). Mientras
`fix/test-contrasenia-temporal` no esté integrada en `develop`, usa `BRANCH=fix/test-contrasenia-temporal`
(corrige `TemporaryPasswordServiceTest`, que no compilaba y rompía también el build de la imagen).
**Obligatorio:** correr `keycloak-init` (paso 1) en cada run: crea el cliente `paktay-admin-panel`,
sincroniza la contraseña del admin con `PAKTAY_ADMIN_PASSWORD` y aplica la fuerza bruta. Sin él, el
login del panel responde 401 y todo el escenario falla en cascada (run `1239-develop-3repos`). En
`develop`:
- **Contraseñas:** el admin envía una contraseña temporal por correo (`POST
  /api/v1/admin/users/{id}/password/temporary`); el login del móvil con ella responde
  `password_change_required`; se cambia con `PUT /api/v1/auth/password/temporary`; vence a las 24 h.
- **Cuenta bloqueada:** login y recuperación responden `403 ACCOUNT_BLOCKED`.
- **Fuerza bruta:** 3 fallos pausan la cuenta; recuperar con PIN la libera.
- El panel tiene **login propio**: `POST /api/v1/admin/session/login|refresh|logout` en auth-svc,
  con el cliente confidencial `paktay-admin-panel`. Ya no existe el cliente `paktay-admin-web`.
- `/api/v1/admin/**` solo acepta tokens de ese login (`azp`): un token del login del móvil
  (`/api/v1/auth/login`) recibe `403 ADMIN_TOKEN_REQUIRED` aunque la cuenta sea ADMIN.
- El realm pausa una cuenta 1 minuto tras 3 contraseñas malas (también en el móvil).

Cubre:
- Login propio del panel: rol ADMIN obligatorio, error genérico, renovación, cierre y auditoría.
- Solo tokens del panel en rutas de admin; la app móvil y las rutas públicas no cambian.
- Catálogos: categorías, bancos y ofertas, monedas y países.
- Resumen, notificaciones y estado del sistema.
- Auditoría de las acciones del administrador.
- Cabecera obligatoria y CORS por dominio (panel y formulario público por separado).
- La web real (BFF) contra este backend, si el repo `packtay_web_admin` está en la Mac.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Solo `paktay-local`.
- Mismos `L`, `F` y `wait_up` de siempre; Keycloak local (28180); Mailpit como en la fase 1b.
- No escribas tokens ni secretos en los logs.
- Si el build falla, guarda **todos** los `ERROR]` en `$LOGS/01b-mvn-error.txt` y detente.

## 1. Compilar y levantar

```bash
cd packtay_backend
export BRANCH=${BRANCH:-develop}
git fetch origin && git checkout "$BRANCH" && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-panel
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
# Secreto nuevo del cliente del panel (solo en .env, nunca en logs).
grep -q '^KEYCLOAK_ADMIN_PANEL_CLIENT_SECRET=.\+' .env || echo "KEYCLOAK_ADMIN_PANEL_CLIENT_SECRET=$(openssl rand -hex 32)" >> .env
./mvnw -B clean package > "$LOGS/01-mvn-full.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn-full.log"
grep -E "Tests run:|BUILD|FAIL|ERROR\]" "$LOGS/01-mvn-full.log" | head -n 300 > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-up.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/02b-keycloak-init.log" 2>&1; echo "exit=$?" >> "$LOGS/02b-keycloak-init.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/03-history.txt" 2>&1
set -a; . ./.env; set +a      # PAKTAY_ADMIN_EMAIL / PAKTAY_ADMIN_PASSWORD, sin imprimirlos
export L
```

Guarda en `$LOGS/03c-keycloak.txt` (sin secretos): si existen los clientes `paktay-admin-panel`
(con `publicClient`, `standardFlowEnabled`, `directAccessGrantsEnabled` y los atributos
`access.token.lifespan`, `client.session.idle.timeout`, `client.session.max.lifespan`) y
`paktay-admin-web` (tiene que **no** existir), y `bruteForceProtected` y `failureFactor` del realm.

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
    """Login de la app móvil."""
    code, body = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd}, panel=False)
    return body.get("access_token") if code == 200 and isinstance(body, dict) else None
def panel_login(email, pwd, panel=True):
    """Login propio del panel (lo llama el servidor de la web)."""
    return call("POST", A + "/api/v1/admin/session/login", {"email": email, "password": pwd}, panel=panel)

stamp = int(time.time()); PWD = "Prueba-Paktay-2026!"
AE, AP = os.environ["PAKTAY_ADMIN_EMAIL"], os.environ["PAKTAY_ADMIN_PASSWORD"]
code, body = panel_login(AE, AP)
ADMIN = body.get("access_token") if code == 200 and isinstance(body, dict) else None
REFRESH = body.get("refresh_token") if ADMIN else None
check("login del panel (admin)", ADMIN is not None, code)
email = f"codexpanel{stamp}@paktay.local"
call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Panel", "password": PWD}, panel=False)
USER = login(email, PWD)
check("usuario sin ADMIN -> 403 en catálogos", call("GET", B + "/api/v1/admin/catalog/banks", t=USER)[0] == 403)

print("== Login propio del panel")
code, body = panel_login(AE, "Clave-Mala-2026!")
check("contraseña mala -> 401 INVALID_CREDENTIALS", code == 401 and body.get("code") == "INVALID_CREDENTIALS", (code, body))
bad_message = body.get("message") if isinstance(body, dict) else None
code, body = panel_login(email, PWD)
check("usuario sin ADMIN -> el mismo 401 que una contraseña mala", code == 401 and isinstance(body, dict)
      and body.get("code") == "INVALID_CREDENTIALS" and body.get("message") == bad_message, (code, body))
check("login del panel sin cabecera -> 403", panel_login(AE, AP, panel=False)[0] == 403)
MOBILE_ADMIN = login(AE, AP)
code, body = call("GET", B + "/api/v1/admin/users", t=MOBILE_ADMIN)
check("token del móvil (cuenta ADMIN) en business -> 403 ADMIN_TOKEN_REQUIRED", code == 403 and "ADMIN_TOKEN_REQUIRED" in json.dumps(body), (code, body))
code, body = call("GET", A + "/api/v1/admin/admins", t=MOBILE_ADMIN)
check("token del móvil (cuenta ADMIN) en auth -> 403 ADMIN_TOKEN_REQUIRED", code == 403 and "ADMIN_TOKEN_REQUIRED" in json.dumps(body), (code, body))
code, body = call("POST", A + "/api/v1/admin/session/refresh", {"refreshToken": REFRESH})
check("renovar -> 200 con token nuevo", code == 200 and isinstance(body, dict) and bool(body.get("access_token")), code)
R2 = body.get("refresh_token") if code == 200 else None
check("cerrar sesión -> 204", call("POST", A + "/api/v1/admin/session/logout", {"refreshToken": R2})[0] == 204)
code, body = call("POST", A + "/api/v1/admin/session/refresh", {"refreshToken": R2})
check("renovar después de cerrar -> 401 SESSION_EXPIRED", code == 401 and isinstance(body, dict) and body.get("code") == "SESSION_EXPIRED", (code, body))
check("auditado: Inicio de sesión en el panel", sql("select count(*) from admin_audit where action = 'Inicio de sesión en el panel' and created_at > now() - interval '10 minutes'") != "0")
# El access token del panel sigue valiendo hasta que vence (15 min): el resto del escenario lo usa.

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
check("versión del esquema V10", any(v["value"].endswith("V10") for v in st.get("versions", [])), st.get("versions"))

print("== Auditoría")
for action in ["Subcategoría creada", "Categoría desactivada", "Banco creado", "Oferta de tarjeta agregada", "Banco desactivado", "Moneda creada", "País creado"]:
    check(f"auditado: {action}", sql(f"select count(*) from admin_audit where action = '{action}' and created_at > now() - interval '10 minutes'") != "0")

print("== Contraseñas (usuario de prueba, nunca el admin)")
import re, urllib.parse
MP = "http://127.0.0.1:28025"
def mail_text(to, marker, tries=15):
    """Texto del correo más reciente para `to` que contenga `marker` (Mailpit)."""
    for _ in range(tries):
        try:
            data = json.load(urllib.request.urlopen(f"{MP}/api/v1/messages?limit=50"))
            for m in data.get("messages", []):
                if any(a.get("Address", "").lower() == to.lower() for a in m.get("To", [])):
                    text = json.load(urllib.request.urlopen(f"{MP}/api/v1/message/{m['ID']}")).get("Text", "")
                    if marker in text: return text
        except Exception as e:
            print("mailpit:", e)
        time.sleep(1)
    return ""
def mobile(email_, pwd_):
    return call("POST", A + "/api/v1/auth/login", {"username": email_, "password": pwd_}, panel=False)
code, page = call("GET", B + "/api/v1/admin/users?q=" + urllib.parse.quote(email), t=ADMIN)
UID = page["items"][0]["id"] if code == 200 and page.get("items") else None
check("id del usuario de prueba", UID is not None, code)

import base64
payload = ADMIN.split(".")[1]
ADMIN_ID = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))).get("sub")
check("admin no se manda temporal a sí mismo -> 409", call("POST", A + f"/api/v1/admin/users/{ADMIN_ID}/password/temporary", t=ADMIN)[0] == 409)
code, body = call("POST", A + f"/api/v1/admin/users/{UID}/password/temporary", t=ADMIN)
check("admin envía contraseña temporal -> 200", code == 200, (code, body))
text = mail_text(email, "contraseña temporal de PAKTAY es:")
m = re.search(r"es: (\S+)", text)
TEMP = m.group(1) if m else None
check("llegó el correo con la contraseña temporal", TEMP is not None and len(TEMP) == 16, bool(text))
check("la contraseña anterior ya no sirve", mobile(email, PWD)[0] == 400)
code, body = mobile(email, TEMP)
check("login con la temporal -> 200 y password_change_required", code == 200 and body.get("password_change_required") is True, (code, {k: v for k, v in body.items() if "token" not in k} if isinstance(body, dict) else body))
TEMP_TOKEN = body.get("access_token") if code == 200 else None
check("la temporal no deja entrar al panel -> 403 PASSWORD_CHANGE_REQUIRED",
      call("PUT", A + f"/api/v1/admin/users/{UID}/roles/admin", t=ADMIN)[0] in (200, 204)
      and panel_login(email, TEMP)[1].get("code") == "PASSWORD_CHANGE_REQUIRED")
call("DELETE", A + f"/api/v1/admin/users/{UID}/roles/admin", t=ADMIN)
NEW = "Nueva-Clave-2026!"
code, body = call("PUT", A + "/api/v1/auth/password/temporary", {"newPassword": NEW}, t=TEMP_TOKEN, panel=False)
check("cambiar la temporal sin PIN -> 200", code == 200, (code, body))
code, body = mobile(email, NEW)
check("login con la nueva, sin marca de cambio", code == 200 and "password_change_required" not in body, code)
check("cambiar la temporal otra vez -> 400", call("PUT", A + "/api/v1/auth/password/temporary", {"newPassword": "Otra-Clave-2026!"}, t=body.get("access_token"), panel=False)[0] == 400)
PWD = NEW

call("POST", A + f"/api/v1/admin/users/{UID}/password/temporary", t=ADMIN)
text = mail_text(email, "contraseña temporal de PAKTAY es:")
TEMP2 = re.search(r"es: (\S+)", text).group(1) if text else None
sql(f"update password_temporary set expires_at = now() - interval '1 minute' where user_id = '{UID}'")
code, body = mobile(email, TEMP2)
check("temporal vencida -> 400 TEMPORARY_PASSWORD_EXPIRED", code == 400 and isinstance(body, dict) and body.get("code") == "TEMPORARY_PASSWORD_EXPIRED", (code, body))

print("== Cuenta bloqueada")
check("bloquear usuario", call("POST", A + f"/api/v1/admin/users/{UID}/block", t=ADMIN)[0] == 200)
check("admin no manda temporal a una cuenta bloqueada -> 409", call("POST", A + f"/api/v1/admin/users/{UID}/password/temporary", t=ADMIN)[0] == 409)
code, body = mobile(email, "cualquier-cosa")
check("login de cuenta bloqueada -> 403 ACCOUNT_BLOCKED", code == 403 and isinstance(body, dict) and body.get("code") == "ACCOUNT_BLOCKED", (code, body))
code, body = call("POST", A + "/api/v1/auth/password-reset/request", {"email": email}, panel=False)
check("pedir PIN con la cuenta bloqueada -> 403 ACCOUNT_BLOCKED (mensaje de soporte)", code == 403 and isinstance(body, dict)
      and body.get("code") == "ACCOUNT_BLOCKED" and "soporte" in body.get("message", ""), (code, body))
check("un correo sin cuenta sigue recibiendo respuesta genérica -> 200", call("POST", A + "/api/v1/auth/password-reset/request", {"email": f"nadie{stamp}@paktay.local"}, panel=False)[0] == 200)
check("desbloquear usuario", call("POST", A + f"/api/v1/admin/users/{UID}/unblock", t=ADMIN)[0] == 200)

print("== Fuerza bruta: 3 fallos pausan la cuenta y el PIN la libera")
# La contraseña vigente es TEMP2 (vencida). Sin pausa respondería TEMPORARY_PASSWORD_EXPIRED; con
# pausa, Keycloak la rechaza aunque sea correcta y la respuesta es INVALID_CREDENTIALS.
for _ in range(3):
    mobile(email, "Clave-Mala-2026!")
code, body = mobile(email, TEMP2)
check("tras 3 fallos la cuenta queda pausada (INVALID_CREDENTIALS con la contraseña correcta)", code == 400 and isinstance(body, dict) and body.get("code") == "INVALID_CREDENTIALS", (code, body))
check("pedir PIN con la cuenta pausada -> 200", call("POST", A + "/api/v1/auth/password-reset/request", {"email": email}, panel=False)[0] == 200)
text = mail_text(email, "Tu PIN de PAKTAY es:")
pin = re.search(r"es: (\d{6})", text).group(1) if text else None
code, body = call("POST", A + "/api/v1/auth/password-reset/verify", {"email": email, "pin": pin}, panel=False)
token = body.get("resetToken") if code == 200 and isinstance(body, dict) else None
check("PIN validado", token is not None, code)
FINAL = "Final-Clave-2026!"
check("recuperar con PIN -> 200", call("POST", A + "/api/v1/auth/password-reset/complete", {"email": email, "resetToken": token, "newPassword": FINAL}, panel=False)[0] == 200)
code, body = mobile(email, FINAL)
check("entra de inmediato: el PIN quitó la pausa y la temporal pendiente", code == 200 and "password_change_required" not in body, (code, body if code != 200 else "ok"))
check("auditado: Contraseña temporal enviada", sql("select count(*) from admin_audit where action = 'Contraseña temporal enviada' and created_at > now() - interval '10 minutes'") != "0")
```

## 3. OpenAPI, salud y logs

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/admin/catalog/categories","/api/v1/admin/catalog/categories/{code}","/api/v1/admin/catalog/banks","/api/v1/admin/catalog/banks/counts","/api/v1/admin/catalog/banks/{id}/offerings","/api/v1/admin/catalog/currencies/{code}","/api/v1/admin/catalog/countries","/api/v1/admin/metrics/summary","/api/v1/admin/notifications/test","/api/v1/admin/system/status"]})' > "$LOGS/05-openapi.txt"
curl -s localhost:28081/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/admin/session/login","/api/v1/admin/session/refresh","/api/v1/admin/session/logout"]})' >> "$LOGS/05-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/06-health.txt"
$L logs --no-color --tail=400 auth-svc business-svc | grep -iE "error|exception" | head -n 150 > "$LOGS/07-logs.txt"
```

## 4. La web real (BFF) contra este backend

Solo si existe `../packtay_web_admin` (si no, anótalo en el resumen y sigue). Necesita Node 20+ y pnpm.

```bash
cd ../packtay_web_admin
git fetch origin && git checkout ${WEB_BRANCH:-develop} && git pull --ff-only
pnpm install --frozen-lockfile > "$LOGS/10-web-install.log" 2>&1
cat > .env.local <<EOF
NEXT_PUBLIC_USE_MOCKS=false
AUTH_API_URL=http://localhost:28081
BUSINESS_API_URL=http://localhost:28082
ADMIN_SESSION_SECRET=$(openssl rand -hex 32)
EOF
pnpm build > "$LOGS/10-web-build.log" 2>&1; echo "exit=$?" >> "$LOGS/10-web-build.log"
(pnpm exec next start -p 3000 > "$LOGS/10-web-start.log" 2>&1 &) ; sleep 8
python3 /tmp/web.py > "$LOGS/11-web-bff.txt" 2>&1
pkill -f "next start -p 3000" || true
rm -f .env.local
cd ../packtay_backend
```

`/tmp/web.py` (el navegador solo habla con la web; la web con el backend):

```python
import json, os, urllib.request, urllib.error
W = "http://localhost:3000"
# Cookies a mano: son Secure (producción) y urllib no las reenviaría por http://localhost.
jar, raw_cookies = {}, []
def call(m, path, body=None, headers=None):
    h = {"Content-Type": "application/json", "X-Paktay-Client": "admin-web", **(headers or {})}
    if jar: h["Cookie"] = "; ".join(f"{k}={v}" for k, v in jar.items())
    req = urllib.request.Request(W + path, method=m, data=None if body is None else json.dumps(body).encode(), headers=h)
    try:
        r = urllib.request.urlopen(req); code = r.status
    except urllib.error.HTTPError as e:
        r = e; code = e.code
    for c in r.headers.get_all("Set-Cookie") or []:
        raw_cookies.append(c)
        name, _, value = c.split(";")[0].partition("=")
        if value: jar[name] = value
        else: jar.pop(name, None)
    return code, dict(r.headers), r.read().decode()
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:250] if detail != "" else ""))

check("sin sesión -> 401", call("GET", "/api/auth/session")[0] == 401)
code, _, body = call("POST", "/api/auth/login", {"email": os.environ["PAKTAY_ADMIN_EMAIL"], "password": "Clave-Mala-2026!"})
check("contraseña mala -> 401", code == 401, body)
raw_cookies.clear()
code, headers, body = call("POST", "/api/auth/login", {"email": os.environ["PAKTAY_ADMIN_EMAIL"], "password": os.environ["PAKTAY_ADMIN_PASSWORD"]})
check("login por la web -> 200 con el admin y sin tokens", code == 200 and '"user"' in body and "access_token" not in body, (code, body[:120]))
check("cookies pk_at y pk_rt HttpOnly, SameSite=Strict, Path=/api", set(jar) >= {"pk_at", "pk_rt"} and len(raw_cookies) == 2
      and all("httponly" in c.lower() and "samesite=strict" in c.lower() and "path=/api" in c.lower() for c in raw_cookies), sorted(jar))
code, _, body = call("GET", "/api/auth/session")
check("sesión -> 200", code == 200, body[:120])
code, _, body = call("GET", "/api/bff/business/api/v1/admin/users?size=5")
check("usuarios por el proxy -> 200", code == 200 and "items" in body, (code, body[:120]))
code, _, body = call("GET", "/api/bff/business/api/v1/admin/catalog/categories")
check("categorías por el proxy -> 200", code == 200, code)
code, _, body = call("GET", "/api/bff/auth/api/v1/admin/admins")
check("admins (auth-svc) por el proxy -> 200", code == 200, code)
check("el proxy no sale de /api/v1/admin", call("GET", "/api/bff/business/api/v1/user/cards")[0] == 404)
check("otro origen -> 403", call("GET", "/api/auth/session", headers={"Origin": "https://malicioso.example"})[0] == 403)
check("logout -> 204", call("POST", "/api/auth/logout")[0] == 204)
check("después del logout -> 401", call("GET", "/api/auth/session")[0] == 401)
code, headers, _ = call("GET", "/login", headers={"Content-Type": ""})
check("cabeceras de seguridad", "frame-ancestors 'none'" in headers.get("Content-Security-Policy", ""), headers.get("Content-Security-Policy", "")[:80])
```

Si algún check de usuarios falla por la forma de la respuesta (`items`), guarda los primeros 300
caracteres del cuerpo en el log para comparar con los tipos de la web.

## 4b. Pruebas del móvil (sin simulador)

Solo si existe `../packtay_mobile_front` con su `.env` (si no, anótalo y sigue). No toques `.env`.

```bash
cd ../packtay_mobile_front
git fetch origin && git checkout ${MOBILE_BRANCH:-develop} && git pull --ff-only
npm ci > "$LOGS/12-movil-install.log" 2>&1
npx tsc --noEmit > "$LOGS/12-movil-tsc.txt" 2>&1; echo "exit=$?" >> "$LOGS/12-movil-tsc.txt"
npm test -- __tests__/authSession.test.ts __tests__/passwordApi.test.ts __tests__/AuthScreens.test.tsx > "$LOGS/12-movil-jest.txt" 2>&1; echo "exit=$?" >> "$LOGS/12-movil-jest.txt"
npx eslint src __tests__ --quiet > "$LOGS/12-movil-eslint.txt" 2>&1; echo "exit=$?" >> "$LOGS/12-movil-eslint.txt"
cd ../packtay_backend
```

## 5. CORS con dominios de producción simulados

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

`RESUMEN.md` con la rama probada, Maven (pruebas por módulo), el OK/FAIL de cada bloque del
escenario, los clientes y la fuerza bruta en Keycloak (`03c`), la web real (`11-web-bff.txt`, o por
qué no se corrió), las pruebas del móvil (`12-*`) y la tabla de CORS. Copia los FAIL tal cual, sin resumirlos. Commit y push a `develop` solo de `IALogs/logs/$RUN` con el mensaje
`chore(ialogs): $RUN`.
