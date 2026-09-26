# Panel admin, fase 0: cliente `paktay-admin-web` en Keycloak (para Codex)

Objetivo: comprobar que `keycloak-init` crea el cliente del panel sin tocar lo demás, y que
el móvil sigue entrando igual. **No cambies código.** Todo va a `IALogs/logs/`.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Solo `paktay-local`.
- Keycloak: el compartido `keycloakservices-local` (28180), como en los días 4 a 7.
- En esta corrida **sí** se permite ejecutar `keycloak-init` sobre ese Keycloak. Vuelve a
  poner el mismo secreto y la misma contraseña que ya tiene `.env`, así que no cambia nada
  de lo existente. Si `.env` no tiene `KEYCLOAK_SERVICE_CLIENT_SECRET`, `PAKTAY_ADMIN_EMAIL`
  o `PAKTAY_ADMIN_PASSWORD`, detente y anótalo.
- No escribas tokens ni secretos en los logs.

## Pasos

```bash
cd packtay_backend
git fetch origin && git checkout feature/admin-fase0-acceso && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-fase0
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
# Mismo L y wait_up que en el día 7.
$L up -d > "$LOGS/01-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/01-up.log"

# Dos veces seguidas: la segunda debe terminar igual de bien (idempotente).
$L --profile identity-setup run --rm keycloak-init > "$LOGS/02-init-1.log" 2>&1; echo "exit=$?" >> "$LOGS/02-init-1.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/03-init-2.log" 2>&1; echo "exit=$?" >> "$LOGS/03-init-2.log"
```

Guarda como `/tmp/fase0.py` y ejecuta `python3 /tmp/fase0.py > "$LOGS/04-checks.txt" 2>&1`.
Usa la URL pública del Keycloak local (la misma que `KEYCLOAK_PUBLIC_URL` en 28180).

```python
import json, time, urllib.request, urllib.error, urllib.parse, base64, hashlib, os
KC = "http://localhost:28180/realms/paktay"; A = "http://localhost:28081"
def get(url):
    try:
        with urllib.request.urlopen(url) as r: return r.status, r.read().decode()[:300]
    except urllib.error.HTTPError as e: return e.code, e.read().decode()[:300]
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:200] if detail != "" else ""))
v = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode()
ch = base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).rstrip(b"=").decode()
def auth(client, redirect, method="S256"):
    q = {"client_id": client, "redirect_uri": redirect, "response_type": "code", "scope": "openid",
         "code_challenge": ch, "code_challenge_method": method}
    return get(KC + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(q))
s, b = auth("paktay-admin-web", "http://localhost:5173/callback")
check("panel: redirect local aceptado (pagina de login)", s == 200, s)
s, b = auth("paktay-admin-web", "https://malicioso.example/callback")
check("panel: redirect ajeno rechazado", s == 400, s)
s, b = auth("paktay-mobile", "paktay://oauth/callback")
check("movil: login sigue disponible", s == 200, s)
email = f"codexfase0{int(time.time()*1000)}@paktay.local"; pwd = "Prueba-Paktay-2026!"
req = lambda m, u, body: urllib.request.Request(u, method=m, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
try:
    urllib.request.urlopen(req("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex fase0", "password": pwd}))
    with urllib.request.urlopen(req("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})) as r:
        check("movil: registro y login por auth-svc", r.status == 200 and "access_token" in json.loads(r.read()))
except urllib.error.HTTPError as e:
    check("movil: registro y login por auth-svc", False, e.code)
```

Además, con `kcadm` dentro del contenedor de Keycloak (o la consola web), guarda en
`$LOGS/05-cliente.txt` los campos `publicClient`, `standardFlowEnabled`,
`directAccessGrantsEnabled`, `redirectUris`, `webOrigins` y `attributes` del cliente
`paktay-admin-web`. Esperado: público, sin password grant, redirect
`http://localhost:5173/*`, `pkce.code.challenge.method=S256` y `access.token.lifespan=900`.

## Entrega

`RESUMEN.md` con OK/FAIL de cada punto. Commit y push a `develop` solo de
`IALogs/logs/$RUN` con el mensaje `chore(ialogs): $RUN`.
