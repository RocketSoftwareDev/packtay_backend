# Día 4: historial desde el servidor, editar y anular (para Codex)

Objetivo: probar las ramas `feature/dia4-gastos-servidor` de backend y front. **No cambies
código.** Todo va a `IALogs/logs/`.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Proyectos permitidos: `paktay-local` y
  `paktay-fresh` (desechable).
- Usa el Keycloak que ya usaste en el día 3 (`keycloakservices-local` en 28180), igual que
  en la corrida `2026-09-23_1325-dia3-fix`. No cambies secretos ni roles del Keycloak.
- No escribas tokens ni secretos en los logs.

## Preparación

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia4
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia4-gastos-servidor && git pull --ff-only
```

Usa los mismos comandos `L` / `F` de docker compose que en la corrida del día 3 (sin
`docker-compose.local-keycloak.yml`) y la misma función `wait_up`.

## 1. Backend: compilar, V5 en la base existente y desde cero

```bash
./mvnw -B clean package > "$LOGS/01-mvn.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn.log"
grep -E "Tests run:|BUILD" "$LOGS/01-mvn.log" > "$LOGS/01-mvn-resumen.txt"
$L up --build -d > "$LOGS/02-local-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-local-up.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/03-local-history.txt" 2>&1
```

## 2. Rutas de gastos con un usuario de prueba

Guarda este script como `/tmp/dia4.py` y ejecútalo con `python3 /tmp/dia4.py > "$LOGS/04-rutas.txt" 2>&1`.
Necesita la variable `BANK_ROW` con un banco que tenga oferta de tarjeta; sácala así antes:

```bash
export BANK_ROW=$($L exec -T business-db psql -U paktay -d paktay -At -F, -c "select o.bank_id, o.card_type, coalesce(o.brand,'') from bank_card_offerings o join banks b on b.id=o.bank_id where b.active limit 1")
```

```python
import json, os, time, uuid, urllib.request, urllib.error
A = "http://localhost:28081"; B = "http://localhost:28082"
def call(method, url, body=None, token=None):
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read(); 
        try: return e.code, json.loads(raw)
        except Exception: return e.code, raw.decode()[:200]
def show(label, res, keys=None):
    code, body = res
    if keys and isinstance(body, dict): body = {k: body.get(k) for k in keys}
    print(f"{label}: HTTP{code} {json.dumps(body, ensure_ascii=False)[:400]}")
    return body
email = f"codex+{int(time.time())}@paktay.local"; pwd = "Prueba-Paktay-2026!"
show("registro", call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Dia Cuatro", "password": pwd}), ["id"])
code, tok = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd}); T = tok.get("access_token") if isinstance(tok, dict) else None
print("login:", code, "token_len", len(T or ""))
bank, ctype, brand = os.environ["BANK_ROW"].split(",")
card = show("alta tarjeta", call("POST", B + "/api/v1/user/cards", {"bankId": bank, "cardType": ctype, "creditBrand": brand or None, "alias": "Prueba", "colorDark": "#FF8A4E", "colorLight": "#E8641F"}, T), ["id", "status"])
cats = call("GET", B + "/api/v1/user/categories", token=T)[1]; cat = cats[0]["id"]; cat2 = cats[1]["id"] if len(cats) > 1 else cat
key = str(uuid.uuid4())
body = {"idempotencyKey": key, "cardId": card["id"], "categoryId": cat, "amount": 12.50, "currencyCode": "USD", "merchant": "Prueba Manual", "occurredAt": "2026-09-24T15:00:00Z", "recurring": False}
e1 = show("alta gasto", call("POST", B + "/api/v1/user/expenses", body, T), ["id", "origin", "kind", "status", "cardStatus", "amount"])
show("reintento misma clave (mismo id)", call("POST", B + "/api/v1/user/expenses", body, T), ["id"])
p = show("lista limit=1", call("GET", B + "/api/v1/user/expenses?limit=1", token=T), ["nextCursor"])
show("lista limit=0 (400)", call("GET", B + "/api/v1/user/expenses?limit=0", token=T))
show("cursor invalido (400)", call("GET", B + "/api/v1/user/expenses?cursor=basura", token=T))
show("detalle", call("GET", B + f"/api/v1/user/expenses/{e1['id']}", token=T), ["id", "status"])
show("detalle inexistente (404)", call("GET", B + f"/api/v1/user/expenses/{uuid.uuid4()}", token=T))
show("editar categoria y monto (manual)", call("PUT", B + f"/api/v1/user/expenses/{e1['id']}", {"categoryId": cat2, "cardId": card["id"], "amount": 15.00}, T), ["categoryId", "amount", "status"])
v = show("anular", call("POST", B + f"/api/v1/user/expenses/{e1['id']}/void", token=T))
show("anular otra vez (idempotente)", call("POST", B + f"/api/v1/user/expenses/{e1['id']}/void", token=T))
if isinstance(v, dict) and v.get("refund"):
    show("anular el REFUND (409)", call("POST", B + f"/api/v1/user/expenses/{v['refund']['id']}/void", token=T))
show("editar anulado (409)", call("PUT", B + f"/api/v1/user/expenses/{e1['id']}", {"categoryId": cat, "cardId": card["id"]}, T))
show("sync since", call("GET", B + "/api/v1/user/expenses?since=2026-01-01T00:00:00Z", token=T), ["nextCursor"])
code, s = call("GET", B + "/api/v1/user/expenses?since=2026-01-01T00:00:00Z", token=T)
print("sync filas:", [(i["kind"], i["status"], i["amount"]) for i in s.get("items", [])] if isinstance(s, dict) else s)
show("presupuesto (spent debe ser 0: el gasto se anulo)", call("GET", B + "/api/v1/user/budgets/current", token=T), ["spentAmount"])
```

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print([k for k in p if "expenses" in k])' > "$LOGS/05-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/06-health.txt"
$L logs --no-color --tail=200 business-svc | grep -iE "error|exception" > "$LOGS/07-errores.log"
$L stop

$F up --build -d > "$LOGS/08-fresh-up.log" 2>&1; wait_up
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/09-fresh-history.txt" 2>&1
$F down -v > /dev/null 2>&1
git checkout develop
cd ..
```

## 3. Front

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia4-gastos-servidor && git pull --ff-only
npm ci > "$LOGS/10-front-npm-ci.log" 2>&1; echo "exit=$?" >> "$LOGS/10-front-npm-ci.log"
npm run env > /dev/null 2>&1
npx tsc --noEmit > "$LOGS/11-front-tsc.log" 2>&1; echo "exit=$?" >> "$LOGS/11-front-tsc.log"
npx jest --ci > "$LOGS/12-jest-full.log" 2>&1; echo "exit=$?" >> "$LOGS/12-jest-full.log"
grep -E "^(PASS|FAIL)|Tests:|Test Suites:|  ● [^C]" "$LOGS/12-jest-full.log" > "$LOGS/12-jest.log"
grep -A 25 "  ● [^C]" "$LOGS/12-jest-full.log" | head -n 400 > "$LOGS/12-jest-fallos.log"; rm "$LOGS/12-jest-full.log"
npm run lint > "$LOGS/13-lint.log" 2>&1; echo "exit=$?" >> "$LOGS/13-lint.log"
git checkout develop
cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md`: Maven (tests y BUILD), Flyway local y fresh (esperado 1 a 5), cada línea de
`04-rutas.txt` con su HTTP y si coincide con lo esperado (marcado entre paréntesis), rutas
de gastos en OpenAPI, salud 6/6, tsc, Jest (fallidos con nombre y primeras líneas del
error) y lint.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 4 ($RUN)"
git push origin develop
```
