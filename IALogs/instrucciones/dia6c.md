# Día 6c: PIN a un correo con "+" (para Codex)

En `2026-09-24_1947-dia6b` el PIN no llegaba a `codex+…@paktay.local`. No era el script:
auth-svc buscaba el correo en Keycloak sin codificar el `+`, así que cualquier usuario con
un alias tipo `ana+pruebas@gmail.com` no podía recuperar su contraseña. Se corrigió en
`fix/dia6-correcciones` (`KeycloakIdentityService.findByEmail`). **No cambies código.**

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia6c
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout fix/dia6-correcciones && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|exit=" /tmp/mvn.log | tail -n 5 > "$LOGS/01-mvn.txt"
```

Levanta Mailpit y `paktay-local` exactamente como en la sección "Buzón de correo" de
`dia6.md` (con `SMTP_FROM=no-reply@paktay.local`). Después guarda como `/tmp/dia6c.py` y
ejecuta `python3 /tmp/dia6c.py > "$LOGS/02-pin.txt" 2>&1`:

```python
import json, time, urllib.request, urllib.error
A = "http://localhost:28081"; MP = "http://127.0.0.1:28025"
def call(m, url, body=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e: return e.code, e.read().decode()[:200]
try: urllib.request.urlopen(urllib.request.Request(MP + "/api/v1/messages", method="DELETE"))
except Exception as e: print("mailpit:", e)
for email in (f"codex+pin{int(time.time())}@paktay.local", f"codexpin{int(time.time())}@paktay.local"):
    call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Pin", "password": "Prueba-Paktay-2026!"})
    code, _ = call("POST", A + "/api/v1/auth/password-reset/request", {"email": email})
    time.sleep(3)
    with urllib.request.urlopen(MP + "/api/v1/messages") as r: box = json.loads(r.read())
    to = [a.get("Address") for m in box.get("messages", []) for a in m.get("To", [])]
    print(("OK   " if code == 200 and email in to else "FAIL ") + "PIN llega a " + ("correo con +" if "+" in email else "correo normal"), code)
```

```bash
$L stop; docker rm -f paktay-mailpit-test >/dev/null 2>&1
unset SMTP_HOST SMTP_PORT SMTP_SECURE SMTP_STARTTLS SMTP_USER SMTP_PASS SMTP_FROM
git checkout develop
```

`RESUMEN.md` con Maven y las dos líneas. Subida:
`git add IALogs/logs/$RUN && git commit -m "chore(ialogs): prueba del día 6c ($RUN)" && git push origin develop`.
