# Día 5: resumen del mes e Inicio único (para Codex)

Objetivo: probar las ramas `feature/dia5-resumen-inicio` de backend y front. **No cambies
código.** Todo va a `IALogs/logs/`. No subas imágenes ni logs de más de 1 MB.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Proyectos: `paktay-local` y `paktay-fresh`.
- Mismo Keycloak y mismos comandos `L`, `F` y `wait_up` que en el día 4
  (`IALogs/instrucciones/dia4.md`). No cambies secretos ni roles.
- No escribas tokens ni secretos en los logs.

## Preparación y backend

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia5
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia5-resumen-inicio && git pull --ff-only
./mvnw -B clean package > "$LOGS/01-mvn.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn.log"
grep -E "Tests run:|BUILD|FAIL" "$LOGS/01-mvn.log" > "$LOGS/01-mvn-resumen.txt"; rm "$LOGS/01-mvn.log"
$L up --build -d > "$LOGS/02-local-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-local-up.log"
export BANK_ROW=$($L exec -T business-db psql -U paktay -d paktay -At -F, -c "select o.bank_id, o.card_type, coalesce(o.brand,'') from bank_card_offerings o join banks b on b.id=o.bank_id where b.active limit 1")
```

## Escenario del resumen

Guarda como `/tmp/dia5.py` y ejecuta `python3 /tmp/dia5.py > "$LOGS/03-resumen.txt" 2>&1`.

```python
import json, os, time, uuid, urllib.request, urllib.error, datetime
A = "http://localhost:28081"; B = "http://localhost:28082"
def call(m, url, body=None, t=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + t} if t else {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try: return e.code, json.loads(raw)
        except Exception: return e.code, raw.decode()[:200]
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail) if detail else ""))
email = f"codex+{int(time.time())}@paktay.local"; pwd = "Prueba-Paktay-2026!"
call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Dia Cinco", "password": pwd})
T = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})[1]["access_token"]
c, s0 = call("GET", B + "/api/v1/user/summary", t=T)
check("resumen sin nada: 200 y budget null", c == 200 and s0.get("budget") is None, {k: s0.get(k) for k in ("budget","spent","pace","daysLeft","counts")})
bank, ctype, brand = os.environ["BANK_ROW"].split(",")
card = call("POST", B + "/api/v1/user/cards", {"bankId": bank, "cardType": ctype, "creditBrand": brand or None, "alias": "Prueba", "colorDark": "#FF8A4E", "colorLight": "#E8641F", "initialBudget": 300}, T)[1]
cats = []
for i in range(3):
    code, cat = call("POST", B + "/api/v1/user/categories", {"code": f"cat-{i}", "name": f"Categoria {i}", "icon": "tag", "colorDark": "#F59E0B", "colorLight": "#D97706", "sortOrder": i + 1}, T)
    cats.append(cat["id"])
# global 100 para cat-0 y cat-1; cat-2 con monto propio 50 => presupuesto 250
code, b = call("PUT", B + "/api/v1/user/budgets/current", {"globalAmount": 100, "currencyCode": "USD", "recurrence": "MONTHLY",
    "categories": [{"categoryId": cats[0]}, {"categoryId": cats[1]}, {"categoryId": cats[2], "individualAmount": 50}]}, T)
check("guardar presupuesto 200", code == 200, {k: b.get(k) for k in ("budgetAmount","globalAmount")} if isinstance(b, dict) else b)
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
def spend(cat, amount, merchant):
    return call("POST", B + "/api/v1/user/expenses", {"idempotencyKey": str(uuid.uuid4()), "cardId": card["id"], "categoryId": cat,
        "amount": amount, "currencyCode": "USD", "merchant": merchant, "occurredAt": now, "recurring": False}, T)[1]
spend(cats[0], 95, "A")      # cat-0: 95/100 => AT_LIMIT
spend(cats[1], 30, "B")      # cat-1: 30/100 => OK
e3 = spend(cats[2], 60, "C") # cat-2: 60/50 => OVER, overBy 10
c, s = call("GET", B + "/api/v1/user/summary", t=T)
by = {x["categoryId"]: x for x in s.get("categories", [])}
check("budget = 250 (100+100+50)", float(s.get("budget") or 0) == 250, s.get("budget"))
check("spent = 185", float(s.get("spent") or 0) == 185, s.get("spent"))
check("percent = 74 (piso)", s.get("percent") == 74, s.get("percent"))
check("cat-0 AT_LIMIT", by.get(cats[0], {}).get("status") == "AT_LIMIT", by.get(cats[0]))
check("cat-1 OK", by.get(cats[1], {}).get("status") == "OK", by.get(cats[1]))
check("cat-2 OVER con overBy 10", by.get(cats[2], {}).get("status") == "OVER" and float(by.get(cats[2], {}).get("overBy") or 0) == 10, by.get(cats[2]))
check("cat-2 budgetSource OWN", by.get(cats[2], {}).get("budgetSource") == "OWN")
check("cat-0 budgetSource GLOBAL", by.get(cats[0], {}).get("budgetSource") == "GLOBAL")
check("recent tiene 3", len(s.get("recent", [])) == 3)
check("tarjeta con ownLimit 300", any(float(x.get("ownLimit") or 0) == 300 for x in s.get("cards", [])), s.get("cards"))
print("daysLeft", s.get("daysLeft"), "elapsed", s.get("elapsedPercent"), "pace", s.get("pace"), "resetsOn", s.get("resetsOn"))
call("POST", B + f"/api/v1/user/expenses/{e3['id']}/void", t=T)
c, s2 = call("GET", B + "/api/v1/user/summary", t=T)
check("tras anular C: spent = 125", float(s2.get("spent") or 0) == 125, s2.get("spent"))
c, bc = call("GET", B + "/api/v1/user/budgets/current", t=T)
check("budgets/current budgetAmount = 250 y spent = 125", float(bc.get("budgetAmount") or 0) == 250 and float(bc.get("spentAmount") or 0) == 125, {k: bc.get(k) for k in ("budgetAmount","spentAmount","percent")})
for q, exp in (("?month=2026-13", 400), ("?month=abc", 400), ("?month=2099-01", 400)):
    check(f"summary{q} -> {exp}", call("GET", B + "/api/v1/user/summary" + q, t=T)[0] == exp)
# paginación con varias páginas (pendiente del día 4)
items, cursor, pages = [], None, 0
while True:
    code, p = call("GET", B + "/api/v1/user/expenses?limit=1" + (f"&cursor={cursor}" if cursor else ""), t=T)
    pages += 1; items += p.get("items", []); cursor = p.get("nextCursor")
    if not cursor or pages > 10: break
check("paginación limit=1 recorre las 4 filas sin repetir", len(items) == 4 and len({i["id"] for i in items}) == 4, (pages, len(items)))
```

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;print("/api/v1/user/summary" in json.load(sys.stdin)["paths"])' > "$LOGS/04-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/05-health.txt"
$L logs --no-color --tail=300 business-svc | grep -iE "error|exception" > "$LOGS/06-errores.log"
$L stop
git checkout develop
cd ..
```

## Front

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia5-resumen-inicio && git pull --ff-only
npm ci > "$LOGS/07-npm-ci.log" 2>&1; echo "exit=$?" >> "$LOGS/07-npm-ci.log"
npm run env > /dev/null 2>&1
npx tsc --noEmit > "$LOGS/08-tsc.log" 2>&1; echo "exit=$?" >> "$LOGS/08-tsc.log"
npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log
grep -E "^(PASS|FAIL)|Tests:|Test Suites:" /tmp/jest.log > "$LOGS/09-jest.log"
grep -A 30 "  ● [^C]" /tmp/jest.log | head -n 500 > "$LOGS/09-jest-fallos.log"
npm run lint > /tmp/lint.log 2>&1; echo "exit=$?" >> /tmp/lint.log; grep -E "problems|exit=" /tmp/lint.log > "$LOGS/10-lint.txt"
git checkout develop
cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md`: tests de Maven, cada línea `OK`/`FAIL` de `03-resumen.txt`, OpenAPI,
salud, tsc, Jest (suites y tests fallidos con nombre y primeras líneas) y lint.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 5 ($RUN)"
git push origin develop
```
