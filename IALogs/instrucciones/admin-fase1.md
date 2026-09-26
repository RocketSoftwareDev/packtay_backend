# Panel admin, fase 1: roles, usuarios, auditoría, soporte y bloqueos (para Codex)

Objetivo: compilar y probar `feature/admin-fase1-roles-soporte` del backend (incluye la fase 0).
**No cambies código.** Todo va a `IALogs/logs/`. Esta máquina de desarrollo no tiene Java ni
Docker: nada de esto se compiló antes; si no compila, guarda el error completo y detente.

Qué trae:
- V9: `normalize_email`, `admin_audit`, `support_tickets`, `support_messages`,
  `blocked_identities` y `purge_user` que además borra tickets y eventos del usuario.
- business-svc: `/api/v1/admin/users` (lista, detalle, plan), `/api/v1/admin/audit`,
  `/api/v1/admin/support/tickets/**` y `/api/v1/public/support/**` (formulario sin token).
  Cuenta bloqueada → 403 con `code = ACCOUNT_BLOCKED`. Se dejaron de auditar gastos y tarjetas.
- auth-svc: `/api/v1/admin/users/{id}/block|unblock|password`, `DELETE /api/v1/admin/users/{id}`
  (ahora purga los datos), `/api/v1/admin/admins`, `/api/v1/admin/users/{id}/roles/admin`,
  `/api/v1/admin/blocks`. El registro revisa bloqueos y crea `app_users` con el correo.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Solo `paktay-local` y `paktay-fresh`.
- Keycloak compartido `keycloakservices-local` (28180) y los mismos `L`, `F` y `wait_up` del día 7.
- No escribas tokens, contraseñas ni secretos en los logs.
- Si el build falla **solo por pruebas**, anótalas y sigue con `./mvnw -B -DskipTests package`.
  Si no compila, detente y sube el log.

## 1. Compilar

```bash
cd packtay_backend
git fetch origin && git checkout feature/admin-fase1-roles-soporte && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-fase1
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|FAIL|ERROR\]" /tmp/mvn.log | head -n 300 > "$LOGS/01-mvn.txt"
```

Si hay `ERROR]` de compilación, copia además las 80 líneas alrededor del primero a
`$LOGS/01b-mvn-error.txt`.

## 2. Levantar con Mailpit (correo de soporte)

Igual que en el día 6: Mailpit en la red de `paktay-local`, SMTP apuntado a él solo en esta
corrida (variables del shell, no edites `.env`). Ahora business-svc también manda correos.

```bash
NET=$(docker network ls --format '{{.Name}}' | grep -E '^paktay-local_default$' || true)
[ -z "$NET" ] && docker network create paktay-local_default >/dev/null && NET=paktay-local_default
docker rm -f paktay-mailpit-test >/dev/null 2>&1
docker run -d --name paktay-mailpit-test --network "$NET" --network-alias mailpit \
  -e MP_SMTP_AUTH_ACCEPT_ANY=1 -e MP_SMTP_AUTH_ALLOW_INSECURE=1 \
  -p 127.0.0.1:28025:8025 axllent/mailpit:latest > "$LOGS/02-mailpit.txt" 2>&1
export SMTP_HOST=mailpit SMTP_PORT=1025 SMTP_SECURE=false SMTP_STARTTLS=false SMTP_USER=codex SMTP_PASS=codex SMTP_FROM=no-reply@paktay.local
export SUPPORT_VERIFY_LINK='http://localhost:28082/api/v1/public/support/verify?token={token}'
$L up --build -d > "$LOGS/03-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/03-up.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/03b-keycloak-init.log" 2>&1; echo "exit=$?" >> "$LOGS/03b-keycloak-init.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/04-history.txt" 2>&1
set -a; . ./.env; set +a      # para PAKTAY_ADMIN_EMAIL y PAKTAY_ADMIN_PASSWORD (no los imprimas)
export L
```

`keycloak-init` vuelve a poner el mismo secreto y contraseña de `.env` (permitido, como en la fase 0).
El usuario `PAKTAY_ADMIN_EMAIL` debe tener el rol ADMIN; si el login de admin del script falla
con 403, anótalo y revisa sus roles en Keycloak.

## 3. Escenario

Guarda como `/tmp/fase1.py` y ejecuta `python3 /tmp/fase1.py > "$LOGS/05-fase1.txt" 2>&1`.

```python
import json, os, re, subprocess, time, urllib.request, urllib.error
A = "http://localhost:28081"; B = "http://localhost:28082"; MP = "http://127.0.0.1:28025"
def call(m, url, body=None, t=None, raw=False):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + t} if t else {})})
    try:
        with urllib.request.urlopen(req) as r:
            data = r.read(); return r.status, (data.decode() if raw else json.loads(data or b"null"))
    except urllib.error.HTTPError as e:
        data = e.read()
        try: return e.code, (data.decode()[:200] if raw else json.loads(data))
        except Exception: return e.code, data.decode()[:200]
def sql(q):
    cmd = os.environ["L"].split() + ["exec", "-T", "business-db", "psql", "-U", "paktay", "-d", "paktay", "-At", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:300] if detail != "" else ""))
def login(email, pwd):
    code, body = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})
    return body.get("access_token") if code == 200 else None
def mails():
    try:
        with urllib.request.urlopen(MP + "/api/v1/messages") as r: return json.loads(r.read()).get("messages", [])
    except Exception as e: print("mailpit:", e); return []
def mail_text(msg_id):
    with urllib.request.urlopen(MP + "/api/v1/message/" + msg_id) as r: return json.loads(r.read()).get("Text", "")

PWD = "Prueba-Paktay-2026!"
stamp = int(time.time() * 1000)
ADMIN = login(os.environ["PAKTAY_ADMIN_EMAIL"], os.environ["PAKTAY_ADMIN_PASSWORD"])
check("login del admin", ADMIN is not None)

print("== Registro, app_users y auditoría")
email = f"codexf1{stamp}@paktay.local"
code, reg = call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Fase Uno", "password": PWD})
check("registro 201", code == 201, reg); uid = reg.get("id") if isinstance(reg, dict) else None
check("app_users tiene el correo desde el registro", sql(f"select email from app_users where id = '{uid}'") == email)
check("alta en admin_audit", sql(f"select count(*) from admin_audit where kind = 'USER_CREATED' and subject_user_id = '{uid}'") == "1")
T = login(email, PWD); check("login del usuario", T is not None)

print("== Permisos")
check("usuario sin ADMIN -> 403 en business", call("GET", B + "/api/v1/admin/users", t=T)[0] == 403)
check("usuario sin ADMIN -> 403 en auth", call("GET", A + "/api/v1/admin/admins", t=T)[0] == 403)
check("sin token -> 401", call("GET", B + "/api/v1/admin/users")[0] == 401)

print("== Usuarios (business)")
code, page = call("GET", B + f"/api/v1/admin/users?q={email}&size=5", t=ADMIN)
check("lista con búsqueda", code == 200 and page["total"] >= 1 and page["items"][0]["email"] == email, page)
code, det = call("GET", B + f"/api/v1/admin/users/{uid}", t=ADMIN)
check("detalle sin montos", code == 200 and "usage" in det and "amount" not in json.dumps(det).lower(), det)
code, det = call("PUT", B + f"/api/v1/admin/users/{uid}/plan", {"plan": "FREE"}, t=ADMIN)
check("cambiar plan a FREE", code == 200 and det["plan"] == "FREE" and det["usage"]["captureLimit"] == 20, det)
check("plan cambiado en auditoría", sql(f"select count(*) from admin_audit where action = 'Plan cambiado' and subject_user_id = '{uid}'") == "1")
check("plan inválido -> 400", call("PUT", B + f"/api/v1/admin/users/{uid}/plan", {"plan": "ORO"}, t=ADMIN)[0] == 400)

print("== Roles (auth)")
code, admins = call("GET", A + "/api/v1/admin/admins", t=ADMIN)
check("lista de administradores", code == 200 and isinstance(admins, list) and len(admins) >= 1, admins)
import base64
payload = ADMIN.split(".")[1]; payload += "=" * (-len(payload) % 4)
admin_id = json.loads(base64.urlsafe_b64decode(payload))["sub"]
check("asignar ADMIN", call("PUT", A + f"/api/v1/admin/users/{uid}/roles/admin", t=ADMIN)[0] == 200)
T_new = login(email, PWD)
check("con el rol nuevo entra al admin", call("GET", B + "/api/v1/admin/users", t=T_new)[0] == 200)
check("quitar ADMIN", call("DELETE", A + f"/api/v1/admin/users/{uid}/roles/admin", t=ADMIN)[0] == 200)
check("nadie se quita el rol a sí mismo -> 409", call("DELETE", A + f"/api/v1/admin/users/{admin_id}/roles/admin", t=ADMIN)[0] == 409)
check("no puede bloquearse a sí mismo -> 409", call("POST", A + f"/api/v1/admin/users/{admin_id}/block", t=ADMIN)[0] == 409)
check("roles en auditoría", sql(f"select count(*) from admin_audit where action like 'Rol ADMIN%' and subject_user_id = '{uid}'") == "2")

print("== Bloqueo de cuenta")
check("bloquear", call("POST", A + f"/api/v1/admin/users/{uid}/block", t=ADMIN)[0] == 200)
code, body = call("GET", B + "/api/v1/user/cards", t=T)
check("token viejo -> 403 ACCOUNT_BLOCKED", code == 403 and body.get("code") == "ACCOUNT_BLOCKED", (code, body))
check("ya no puede iniciar sesión", login(email, PWD) is None)
check("desbloquear", call("POST", A + f"/api/v1/admin/users/{uid}/unblock", t=ADMIN)[0] == 200)
check("vuelve a entrar", login(email, PWD) is not None)

print("== Bloqueos de correo y dominio")
code, blk = call("POST", A + "/api/v1/admin/blocks", {"type": "DOMAIN", "value": "codex-desechable.test", "scopes": ["TICKETS", "REGISTRATION"], "reason": "FAKE_EMAIL", "alsoBlockIp": False}, t=ADMIN)
check("bloquear dominio", code == 201 and blk["value"] == "codex-desechable.test", blk)
code, body = call("POST", A + "/api/v1/auth/register", {"email": f"x{stamp}@codex-desechable.test", "displayName": "Bloqueado", "password": PWD})
check("registro con dominio bloqueado -> 400 genérico", code == 400 and "registrar" in json.dumps(body), body)
check("dominio público -> 400", call("POST", A + "/api/v1/admin/blocks", {"type": "DOMAIN", "value": "gmail.com", "scopes": ["TICKETS"], "reason": "SPAM", "alsoBlockIp": False}, t=ADMIN)[0] == 400)
check("duplicado -> 409", call("POST", A + "/api/v1/admin/blocks", {"type": "DOMAIN", "value": "codex-desechable.test", "scopes": ["TICKETS"], "reason": "SPAM", "alsoBlockIp": False}, t=ADMIN)[0] == 409)
code, body = call("POST", A + "/api/v1/admin/blocks", {"type": "EMAIL", "value": f"Codex.Troll+{stamp}@gmail.com", "scopes": ["TICKETS"], "reason": "OFFENSIVE", "alsoBlockIp": False}, t=ADMIN)
check("correo guardado normalizado", code == 201 and body["value"] == "codextroll@gmail.com", body)

print("== Soporte público")
try: urllib.request.urlopen(urllib.request.Request(MP + "/api/v1/messages", method="DELETE"))
except Exception as e: print("mailpit:", e)
code, t1 = call("POST", B + "/api/v1/public/support/tickets", {"email": email, "reason": "No me llegan las capturas de Wallet", "website": ""})
check("crear ticket -> 202 con código", code == 202 and t1["ticketId"].startswith("PK-") and t1["status"] == "PENDING_VERIFICATION", t1)
check("queda sin confirmar", sql(f"select status from support_tickets where code = '{t1['ticketId']}'") == "PENDING_VERIFICATION")
code, lst = call("GET", B + "/api/v1/admin/support/tickets?status=NEW", t=ADMIN)
check("sin confirmar no aparece en la bandeja", code == 200 and all(x["code"] != t1["ticketId"] for x in lst))
time.sleep(3)
link = None
for m in mails():
    if any(a.get("Address") == email for a in m.get("To", [])):
        found = re.search(r"https?://\S+token=[A-Za-z0-9_-]+", mail_text(m["ID"]))
        link = found.group(0) if found else None
check("llegó el correo con el enlace", link is not None)
if link:
    code, page = call("GET", link, raw=True)
    check("confirmar -> 200", code == 200 and "confirmada" in page, code)
    check("el enlace no sirve dos veces -> 410", call("GET", link, raw=True)[0] == 410)
code, lst = call("GET", B + "/api/v1/admin/support/tickets?status=NEW", t=ADMIN)
mine = [x for x in lst if x["code"] == t1["ticketId"]] if code == 200 else []
check("confirmado aparece en NEW con la cuenta vinculada", len(mine) == 1 and mine[0]["account"] and mine[0]["account"]["userId"] == uid, mine)
if mine:
    tid = mine[0]["id"]
    code, det = call("GET", B + f"/api/v1/admin/support/tickets/{tid}", t=ADMIN)
    check("detalle con conversación", code == 200 and [m["author"] for m in det["messages"]] == ["USER", "SYSTEM"], det)
    code, det = call("POST", B + f"/api/v1/admin/support/tickets/{tid}/messages", {"body": "Revisar logs", "kind": "NOTE", "resolve": False}, t=ADMIN)
    check("nota interna", code == 200 and det["messages"][-1]["author"] == "NOTE" and det["status"] == "NEW", det)
    code, det = call("POST", B + f"/api/v1/admin/support/tickets/{tid}/messages", {"body": "Ya lo revisamos.", "kind": "REPLY", "resolve": True}, t=ADMIN)
    check("responder y resolver", code == 200 and det["status"] == "RESOLVED", det)
    time.sleep(2)
    check("la respuesta salió por correo", any("Respuesta" in m.get("Subject", "") for m in mails()))
code, counts = call("GET", B + "/api/v1/admin/support/tickets/counts", t=ADMIN)
check("contadores", code == 200 and {"new", "inProgress", "resolved", "pendingVerification", "blocks"} <= set(counts), counts)

before = sql("select count(*) from support_tickets")
code, fake = call("POST", B + "/api/v1/public/support/tickets", {"email": f"otro{stamp}@codex-desechable.test", "reason": "Soy un correo bloqueado", "website": ""})
check("bloqueado recibe 202 pero no se guarda", code == 202 and sql("select count(*) from support_tickets") == before, fake)
code, fake = call("POST", B + "/api/v1/public/support/tickets", {"email": f"bot{stamp}@paktay.local", "reason": "Soy un bot", "website": "http://spam"})
check("campo trampa lleno -> 202 sin guardar", code == 202 and sql("select count(*) from support_tickets") == before, fake)
codes = [call("POST", B + "/api/v1/public/support/tickets", {"email": f"lim{i}{stamp}@paktay.local", "reason": "Prueba de límite", "website": ""})[0] for i in range(6)]
check("límite por IP -> 429", 429 in codes, codes)

print("== Auditoría (business)")
code, audit = call("GET", B + "/api/v1/admin/audit?days=7", t=ADMIN)
check("lista de auditoría con conteos", code == 200 and audit["counts"]["ALL"] >= 1 and len(audit["items"]) >= 1, audit.get("counts") if isinstance(audit, dict) else audit)
check("ya no se auditan gastos ni tarjetas", sql("select count(*) from audit_log where created_at > now() - interval '10 minutes'") == "0")

print("== Borrado desde el admin")
check("eliminar cuenta -> 200", call("DELETE", A + f"/api/v1/admin/users/{uid}", t=ADMIN)[0] == 200)
check("datos purgados", sql(f"select count(*) from app_users where id = '{uid}'") == "0")
check("tickets del correo borrados", sql(f"select count(*) from support_tickets where email_normalized = public.normalize_email('{email}')") == "0")
check("eventos del usuario borrados", sql(f"select count(*) from admin_audit where subject_user_id = '{uid}'") == "0")
check("cierre anónimo en auditoría", sql("select subject_label from admin_audit where kind = 'ACCOUNT_DELETED' order by created_at desc limit 1").startswith("id anónimo"))
check("normalize_email en SQL = Java", sql("select public.normalize_email('T.Roll+1@GoogleMail.com')") == "troll@gmail.com")
```

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/admin/users","/api/v1/admin/users/{id}","/api/v1/admin/users/{id}/plan","/api/v1/admin/audit","/api/v1/admin/support/tickets","/api/v1/admin/support/tickets/{id}/messages","/api/v1/public/support/tickets","/api/v1/public/support/verify"]})' > "$LOGS/06-openapi.txt"
curl -s localhost:28081/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/admin/admins","/api/v1/admin/users/{id}/block","/api/v1/admin/users/{id}/roles/admin","/api/v1/admin/blocks","/api/v1/admin/blocks/{id}"]})' >> "$LOGS/06-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/07-health.txt"
$L logs --no-color --tail=500 business-svc auth-svc | grep -iE "error|exception|support_|admin_|account_|register_" | head -n 250 > "$LOGS/08-logs.txt"
$L stop
docker rm -f paktay-mailpit-test >/dev/null 2>&1
unset SMTP_HOST SMTP_PORT SMTP_SECURE SMTP_STARTTLS SMTP_USER SMTP_PASS SMTP_FROM SUPPORT_VERIFY_LINK
```

## 4. Base desde cero

```bash
$F up --build -d > "$LOGS/09-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/09-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/10-fresh-history.txt" 2>&1
$F down -v > /dev/null 2>&1
```

Esperado: V1 a V9 con `success = t` en las dos bases.

## Entrega

`RESUMEN.md` con OK/FAIL de cada punto, el resultado de Maven (pruebas) y cualquier error de
arranque. Commit y push a `develop` solo de `IALogs/logs/$RUN` con el mensaje
`chore(ialogs): $RUN`.
