# Día 7: monedas, avisos de presupuesto, protección de monto y plan Gratis (para Codex)

Objetivo: probar las ramas `feature/dia7` de backend y front. **No cambies código.** Todo va a
`IALogs/logs/`. No subas imágenes ni logs de más de 1 MB.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Proyectos: `paktay-local` y `paktay-fresh`.
- Mismo Keycloak (`keycloakservices-local`, 28180) y mismos `L`, `F`, `wait_up` que en el día 4.
  Exporta `L` antes de los scripts. Scripts de Python desde `packtay_backend`.
- No escribas tokens ni secretos en los logs.
- Si el build falla **sólo por pruebas unitarias**, anótalas y sigue con
  `./mvnw -B -DskipTests package`. Sólo detente si no compila.
- En el mensaje del commit escribe el nombre real de la corrida (el valor de `$RUN`).
- Firebase **no** está configurado: los avisos no se envían y el log debe decir
  `push_skipped` o `budget_alert_no_device`. Eso es lo esperado.

## 1. Backend

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia7
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia7 && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|FAIL|ERROR\]" /tmp/mvn.log | head -n 200 > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-local-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-local-up.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/03-local-history.txt" 2>&1
export BANK_ROW=$($L exec -T business-db psql -U paktay -d paktay -At -F, -c "select o.bank_id, o.card_type, coalesce(o.brand,'') from bank_card_offerings o join banks b on b.id=o.bank_id where b.active limit 1")
export L
```

Guarda como `/tmp/dia7.py` y ejecuta `python3 /tmp/dia7.py > "$LOGS/04-dia7.txt" 2>&1`.

```python
import json, os, subprocess, time, uuid, urllib.request, urllib.error, datetime as dt
A = "http://localhost:28081"; B = "http://localhost:28082"
def call(m, url, body=None, t=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + t} if t else {})})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read(); return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try: return e.code, json.loads(raw)
        except Exception: return e.code, raw.decode()[:200]
def sql(q):
    cmd = os.environ["L"].split() + ["exec", "-T", "business-db", "psql", "-U", "paktay", "-d", "paktay", "-At", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
def check(label, cond, detail=""):
    print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail)[:300] if detail != "" else ""))
def user(name):
    email = f"codex{name}{int(time.time()*1000)}@paktay.local"; pwd = "Prueba-Paktay-2026!"
    call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex " + name, "password": pwd})
    return call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})[1]["access_token"]
now = dt.datetime.now(dt.timezone.utc)
bank, ctype, brand = os.environ["BANK_ROW"].split(",")
def new_card(T, alias, limit=None):
    body = {"bankId": bank, "cardType": ctype, "creditBrand": brand or None, "alias": alias, "colorDark": "#FF8A4E", "colorLight": "#E8641F"}
    if limit: body["initialBudget"] = limit
    return call("POST", B + "/api/v1/user/cards", body, T)
def new_cat(T, i):
    return call("POST", B + "/api/v1/user/categories", {"code": f"d7-{i}", "name": f"Dia7 {i}", "icon": "tag",
        "colorDark": "#F59E0B", "colorLight": "#D97706", "sortOrder": i + 1}, T)[1]["id"]
def expense(T, card, cat, amount, merchant, origin="AUTOMATIC", when=None):
    return call("POST", B + "/api/v1/user/expenses", {"idempotencyKey": str(uuid.uuid4()), "cardId": card, "categoryId": cat,
        "amount": amount, "currencyCode": "USD", "merchant": merchant, "occurredAt": (when or now).isoformat(),
        "recurring": False, "origin": origin}, T)

print("== Monedas")
T = user("dia7")
code, cur = call("GET", B + "/api/v1/catalog/currencies", t=T)
codes = [c["code"] for c in cur] if isinstance(cur, list) else []
check("catálogo con 9 monedas, USD primero", code == 200 and len(codes) == 9 and codes[0] == "USD", codes)
check("incluye BRL, CLP, GBP y CAD", all(c in codes for c in ("BRL", "CLP", "GBP", "CAD")), codes)
code, countries = call("GET", B + "/api/v1/catalog/countries", t=T)
check("países con Brasil y Chile", any(c["code"] == "BR" for c in countries) and any(c["code"] == "CL" for c in countries))

print("== Plan (beta = PRO)")
code, ent = call("GET", B + "/api/v1/user/entitlements", t=T)
check("sin suscripción: PRO sin límites", code == 200 and ent.get("plan") == "PRO" and ent["limits"]["capturesPerMonth"] is None, ent)

print("== Avisos de presupuesto")
card = new_card(T, "La del super", limit=100)[1]["id"]
c1, c2 = new_cat(T, 1), new_cat(T, 2)
code, b = call("PUT", B + "/api/v1/user/budgets/current", {"globalAmount": 80, "currencyCode": "USD", "recurrence": "MONTHLY",
    "categories": [{"categoryId": c1}]}, T)
check("presupuesto: categoría 1 con 80", code == 200, b)
uid = sql(f"select user_id from cards where id = '{card}'")
expense(T, card, c1, 50, "FYBECA 1 QUITO")
check("62 %: sin aviso", sql(f"select count(*) from notification_log where user_id = '{uid}'") == "0")
expense(T, card, c1, 23, "FYBECA 2 QUITO")
check("91 % de la categoría: aviso de 90", sql(f"select threshold from notification_log where user_id = '{uid}' and kind = 'CATEGORY_BUDGET'") == "90")
expense(T, card, c1, 1, "FYBECA 3 QUITO")
check("otro gasto al 92 %: no repite el 90", sql(f"select count(*) from notification_log where user_id = '{uid}' and kind = 'CATEGORY_BUDGET'") == "1")
check("tarjeta al 74 % de Mi límite 100: sin aviso de tarjeta", sql(f"select count(*) from notification_log where user_id = '{uid}' and kind = 'CARD_LIMIT'") == "0")
expense(T, card, c2, 30, "SUPERMAXI 1 QUITO")
check("tarjeta de 74 a 104 %: se reservan 90 y 100 (llega sólo el de 100)",
      sql(f"select string_agg(threshold::text, ',' order by threshold) from notification_log where user_id = '{uid}' and kind = 'CARD_LIMIT'") == "90,100")
code, _ = call("PUT", B + f"/api/v1/security/devices/{uuid.uuid4()}/push-token", {"token": "x"}, T)
check("push-token de un dispositivo que no existe -> 400", code == 400, code)
dev = str(uuid.uuid4())
call("PUT", B + "/api/v1/security/devices/me", {"deviceId": dev, "platform": "ios", "deviceName": "iPhone Codex", "biometricEnabled": False}, T)
code, _ = call("PUT", B + f"/api/v1/security/devices/{dev}/push-token", {"token": "token-de-prueba"}, T)
check("guardar push-token -> 204", code == 204, code)
check("token guardado", sql(f"select push_token from user_devices where device_id = '{dev}'") == "token-de-prueba")
code, _ = call("PUT", B + f"/api/v1/security/devices/{dev}/push-token", {"token": None}, T)
check("quitar push-token", code == 204 and sql(f"select coalesce(push_token,'null') from user_devices where device_id = '{dev}'") == "null")

print("== maxAmount de la regla")
code, rules = call("GET", B + "/api/v1/user/merchant-rules", t=T)
fy = [r for r in rules if r.get("merchantKey") == "FYBECA"]
check("FYBECA con maxAmount 50", fy and float(fy[0].get("maxAmount") or 0) == 50, fy)

print("== Plan Gratis")
T2 = user("free")
card2 = new_card(T2, "Unica")[1]["id"]; cat2 = new_cat(T2, 1)
uid2 = sql(f"select user_id from cards where id = '{card2}'")
sql(f"insert into user_subscription (user_id, plan, source) values ('{uid2}', 'FREE', 'TESTER') on conflict (user_id) do update set plan = 'FREE'")
code, ent = call("GET", B + "/api/v1/user/entitlements", t=T2)
check("FREE: límites 2 / 20 / 3 / 3", ent.get("plan") == "FREE" and ent["limits"] == {"cards": 2, "capturesPerMonth": 20, "budgetCategories": 3, "historyMonths": 3}, ent)
for i in range(20):
    expense(T2, card2, cat2, 1, f"TIENDA {i} QUITO", when=now - dt.timedelta(minutes=i * 2))
code, r21 = expense(T2, card2, cat2, 1, "TIENDA 21 QUITO", when=now + dt.timedelta(minutes=5))
check("captura 21 -> 409", code == 409, r21)
code, m = expense(T2, card2, cat2, 1, "TIENDA 21 QUITO", origin="MANUAL")
check("a mano sí se guarda", code == 201, m)
code, ent = call("GET", B + "/api/v1/user/entitlements", t=T2)
check("uso: 20 capturas", ent["usage"]["capturesThisMonth"] == 20, ent["usage"])
cats = [cat2] + [new_cat(T2, i) for i in range(2, 5)]
code, b4 = call("PUT", B + "/api/v1/user/budgets/current", {"globalAmount": 50, "currencyCode": "USD", "recurrence": "MONTHLY",
    "categories": [{"categoryId": c} for c in cats]}, T2)
check("presupuesto en 4 categorías con FREE -> 409", code == 409, b4)
code, b3 = call("PUT", B + "/api/v1/user/budgets/current", {"globalAmount": 50, "currencyCode": "USD", "recurrence": "MONTHLY",
    "categories": [{"categoryId": c} for c in cats[:3]]}, T2)
check("presupuesto en 3 categorías -> 200", code == 200, b3)
code, add4 = call("PUT", B + f"/api/v1/user/budgets/current/categories/{cats[3]}", {"individualAmount": None, "active": True}, T2)
check("activar una 4.ª categoría -> 409", code == 409, add4)
sql(f"delete from user_subscription where user_id = '{uid2}'")
code, r21b = expense(T2, card2, cat2, 1, "TIENDA 22 QUITO", when=now + dt.timedelta(minutes=9))
check("de vuelta en PRO la captura se guarda", code == 201, r21b)
```

```bash
$L logs --no-color --tail=500 business-svc | grep -E "push_sender_ready|push_skipped|budget_alert|ERROR|Exception" | head -n 100 > "$LOGS/05-logs-avisos.txt"
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/catalog/currencies","/api/v1/user/entitlements","/api/v1/security/devices/{deviceId}/push-token"]})' > "$LOGS/06-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/07-health.txt"
$L stop
$F up --build -d > "$LOGS/08-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/08-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/09-fresh-history.txt" 2>&1
$F down -v > /dev/null 2>&1
git checkout develop
cd ..
```

## 2. Front

El día 7 agrega código nativo en `ios/FinanceApp/PaktayWalletIntent.swift` (avisos locales).
Además de tsc y Jest, compila la app para el simulador para confirmar que el Swift compila.

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia7 && git pull --ff-only
npm ci > /tmp/npm.log 2>&1; echo "exit=$?" >> /tmp/npm.log; tail -n 5 /tmp/npm.log > "$LOGS/10-npm-ci.txt"
npm run env > /dev/null 2>&1
npx tsc --noEmit > /tmp/tsc.log 2>&1; echo "exit=$?" >> /tmp/tsc.log; head -n 200 /tmp/tsc.log > "$LOGS/11-tsc.log"
npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log
grep -E "^(PASS|FAIL)|Tests:|Test Suites:" /tmp/jest.log > "$LOGS/12-jest.log"
grep -A 30 "  ● [^C]" /tmp/jest.log | head -n 600 > "$LOGS/12-jest-fallos.log"
npm run lint > /tmp/lint.log 2>&1; echo "exit=$?" >> /tmp/lint.log; grep -E "error|problems|exit=" /tmp/lint.log | head -n 100 > "$LOGS/13-lint.txt"
(cd ios && pod install > /tmp/pod.log 2>&1; echo "exit=$?" >> /tmp/pod.log); tail -n 5 /tmp/pod.log > "$LOGS/14-pod.txt"
xcodebuild -workspace ios/FinanceApp.xcworkspace -scheme FinanceApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build > /tmp/xcb.log 2>&1; echo "exit=$?" >> /tmp/xcb.log
grep -E "error:|BUILD SUCCEEDED|BUILD FAILED|exit=" /tmp/xcb.log | head -n 80 > "$LOGS/15-xcodebuild.txt"
git checkout develop
cd ../packtay_backend
```

Si el esquema o el workspace no se llaman así, usa los que existan (`xcodebuild -list`) y anótalo.

## Resumen y subida

`RESUMEN.md`: Maven, Flyway (con V8, local y desde cero), cada `OK`/`FAIL` de `04-dia7.txt`,
avisos en el log, OpenAPI, salud, tsc, Jest, lint, pod install y xcodebuild.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 7 ($RUN)"
git push origin develop
```
