# Día 6: reglas por comercio, otra moneda, duplicados, límite propio y eliminar cuenta (para Codex)

Objetivo: probar las ramas `feature/dia6` de backend y front. **No cambies código.** Todo va a
`IALogs/logs/`. No subas imágenes ni logs de más de 1 MB.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Proyectos permitidos: `paktay-local` y `paktay-fresh`.
- Mismo Keycloak (`keycloakservices-local` en 28180) y mismos comandos `L`, `F` y `wait_up` que
  en el día 4 (`IALogs/instrucciones/dia4.md`). Exporta `L` antes de los scripts (`export L`).
- No escribas tokens ni secretos en los logs.
- En los scripts de Python que llaman a docker compose, ejecuta desde `packtay_backend` (el
  `--env-file .env` es relativo).

## 1. Backend: compilar y migrar (V6) en la base existente y desde cero

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia6
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia6 && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|FAIL|ERROR\]" /tmp/mvn.log | head -n 200 > "$LOGS/01-mvn.txt"
```

Si el build falla **sólo por pruebas unitarias** (compila, pero hay `Tests run: … Failures`),
anótalas en el resumen y **sigue** con el resto de la corrida empaquetando sin pruebas:
`./mvnw -B -DskipTests package > /tmp/mvn-skip.log 2>&1`. Sólo detente si no compila.

### Reglas actuales antes de migrar (revisión del dueño)

Antes de levantar la rama nueva, guarda las reglas que hay hoy en la base local (V5), para
compararlas con las que deja V6. Levanta sólo la base:

```bash
$L up -d business-db > /dev/null 2>&1; sleep 5
$L exec -T business-db psql -U paktay -d paktay -c "select s.normalization_version v, s.merchant_normalized comercio, uc.name categoria, s.selection_count usos, s.last_selected_at::date ultimo from user_consumption_selections s join user_categories uc on uc.id = s.category_id where s.active order by s.user_id, s.selection_count desc limit 300" > "$LOGS/00-reglas-antes.txt" 2>&1
```

Después de la sección 2 (con V6 ya aplicada) repite la consulta y guárdala en
`$LOGS/05b-reglas-despues.txt`.

### Buzón de correo (Mailpit) para probar el PIN de contraseña

El PIN nunca se probó. Levanta un Mailpit suelto en la red de `paktay-local` y apunta el SMTP
de auth-svc a él **sólo en esta corrida** con variables de entorno del shell (docker compose
les da prioridad sobre `.env`; no edites `.env`):

```bash
NET=$(docker network ls --format '{{.Name}}' | grep -E '^paktay-local_default$' || true)
[ -z "$NET" ] && docker network create paktay-local_default >/dev/null && NET=paktay-local_default
docker rm -f paktay-mailpit-test >/dev/null 2>&1
docker run -d --name paktay-mailpit-test --network "$NET" --network-alias mailpit \
  -e MP_SMTP_AUTH_ACCEPT_ANY=1 -e MP_SMTP_AUTH_ALLOW_INSECURE=1 \
  -p 127.0.0.1:28025:8025 axllent/mailpit:latest > "$LOGS/02-mailpit.txt" 2>&1
export SMTP_HOST=mailpit SMTP_PORT=1025 SMTP_SECURE=false SMTP_STARTTLS=false SMTP_USER=codex SMTP_PASS=codex
$L up --build -d > "$LOGS/03-local-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/03-local-up.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/04-local-history.txt" 2>&1
export BANK_ROW=$($L exec -T business-db psql -U paktay -d paktay -At -F, -c "select o.bank_id, o.card_type, coalesce(o.brand,'') from bank_card_offerings o join banks b on b.id=o.bank_id where b.active limit 1")
export L
```

Si `paktay-local_default` no es la red real del proyecto, usa la que muestre
`docker network ls` para `paktay-local` y anótalo en el resumen.

## 2. Escenario del día 6

Guarda como `/tmp/dia6.py` y ejecuta `python3 /tmp/dia6.py > "$LOGS/05-dia6.txt" 2>&1`.

```python
import json, os, subprocess, time, uuid, urllib.request, urllib.error, datetime as dt
A = "http://localhost:28081"; B = "http://localhost:28082"; MP = "http://127.0.0.1:28025"
def call(m, url, body=None, t=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + t} if t else {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"null")
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
    email = f"codex+{name}{int(time.time()*1000)}@paktay.local"; pwd = "Prueba-Paktay-2026!"
    call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex " + name, "password": pwd})
    return email, pwd, call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})[1]["access_token"]
iso = lambda d: d.isoformat()
now = dt.datetime.now(dt.timezone.utc)
bank, ctype, brand = os.environ["BANK_ROW"].split(",")
def new_card(T, alias):
    return call("POST", B + "/api/v1/user/cards", {"bankId": bank, "cardType": ctype, "creditBrand": brand or None,
        "alias": alias, "colorDark": "#FF8A4E", "colorLight": "#E8641F"}, T)
def new_cat(T, i):
    return call("POST", B + "/api/v1/user/categories", {"code": f"d6-{i}", "name": f"Dia6 {i}", "icon": "tag",
        "colorDark": "#F59E0B", "colorLight": "#D97706", "sortOrder": i + 1}, T)[1]["id"]
def expense(T, card, cat, amount, merchant, when, origin=None, **extra):
    body = {"idempotencyKey": str(uuid.uuid4()), "cardId": card, "categoryId": cat, "amount": amount,
            "currencyCode": "USD", "merchant": merchant, "occurredAt": iso(when), "recurring": False, **extra}
    if origin: body["origin"] = origin
    return call("POST", B + "/api/v1/user/expenses", body, T)

print("== SQL")
check("merchant_rule_key FYBECA 123 QUITO = FYBECA", sql("select merchant_rule_key('FYBECA 123 QUITO')") == "FYBECA")
check("merchant_rule_key PAYPAL SPOTIFYAB", sql("select merchant_rule_key('PAYPAL SPOTIFYAB')") == "PAYPAL SPOTIFYAB")
check("no quedan capturas con regla guardadas como MANUAL",
      sql("select count(*) from expenses where assigned_by_rule and origin = 'MANUAL' and kind = 'EXPENSE'") == "0")
check("no quedan reglas v1", sql("select count(*) from user_consumption_selections where normalization_version = 1") == "0")

email, pwd, T = user("dia6")
card = new_card(T, "Principal")[1]["id"]
c1, c2 = new_cat(T, 1), new_cat(T, 2)

print("== Países")
code, countries = call("GET", B + "/api/v1/catalog/countries", t=T)
co = [c for c in countries if c.get("code") == "CO"] if isinstance(countries, list) else []
check("catálogo de países: CO usa COP", code == 200 and co and co[0]["currencyCode"] == "COP", countries if not co else co[0])

print("== Captura de Wallet y regla")
code, w1 = expense(T, card, c1, 18.40, "FYBECA 123 QUITO", now, origin="AUTOMATIC")
check("captura AUTOMATIC 201 con origin AUTOMATIC", code == 201 and w1.get("origin") == "AUTOMATIC", w1)
code, rules = call("GET", B + "/api/v1/user/merchant-rules", t=T)
fy = [r for r in rules if r.get("merchantKey") == "FYBECA"] if isinstance(rules, list) else []
check("se creó la regla FYBECA → c1 con 1 uso", fy and fy[0]["categoryId"] == c1 and fy[0]["uses"] == 1, rules)

print("== Duplicado")
code, dup = expense(T, card, c1, 18.40, "FYBECA 123 QUITO", now + dt.timedelta(seconds=20), origin="AUTOMATIC")
check("mismo pago 20 s después devuelve el mismo gasto", code == 201 and dup.get("id") == w1.get("id"), dup)
check("quedó un solo gasto FYBECA", sql(f"select count(*) from expenses e join cards c on c.id=e.card_id where c.id='{card}' and e.merchant_raw='FYBECA 123 QUITO'") == "1")
check("el duplicado quedó en la bitácora", sql(f"select count(*) from audit_log where entity_id='{w1['id']}' and action='DUPLICATE'") == "1")
code, later = expense(T, card, c1, 18.40, "FYBECA 123 QUITO", now + dt.timedelta(minutes=3), origin="AUTOMATIC")
check("mismo comercio y monto 3 min después sí es otro gasto", code == 201 and later.get("id") != w1.get("id"), later)
code, rules = call("GET", B + "/api/v1/user/merchant-rules", t=T)
fy = [r for r in rules if r.get("merchantKey") == "FYBECA"]
check("la regla FYBECA suma 2 usos", fy and fy[0]["uses"] == 2, fy)

print("== Gasto manual: fecha y reglas")
code, m8 = expense(T, card, c2, 5, "MANUALSHOP", now - dt.timedelta(days=8))
check("manual 8 días atrás -> 400", code == 400, m8)
code, m6 = expense(T, card, c2, 5, "MANUALSHOP", now - dt.timedelta(days=6))
check("manual 6 días atrás -> 201 MANUAL", code == 201 and m6.get("origin") == "MANUAL", m6)
code, mf = expense(T, card, c2, 5, "MANUALSHOP", now + dt.timedelta(hours=1))
check("manual en el futuro -> 400", code == 400, mf)
code, rules = call("GET", B + "/api/v1/user/merchant-rules", t=T)
check("un gasto manual no crea regla", not any(r.get("merchantKey") == "MANUALSHOP" for r in rules), rules)
code, wold = expense(T, card, c1, 7, "SUPERMAXI 042 QUITO", now - dt.timedelta(days=20), origin="AUTOMATIC")
check("captura de Wallet con 20 días (revisada tarde) sí se guarda", code == 201, wold)

print("== Editar")
code, card2 = new_card(T, "Segunda")
card2 = card2.get("id") if isinstance(card2, dict) else None
code, e1 = call("PUT", B + f"/api/v1/user/expenses/{later['id']}", {"categoryId": c1, "cardId": card2}, T)
check("cambiar la tarjeta de un gasto de Wallet -> 400", code == 400, e1)
code, e2 = call("PUT", B + f"/api/v1/user/expenses/{later['id']}", {"categoryId": c2, "cardId": card}, T)
check("cambiar la categoría de un gasto de Wallet -> 200", code == 200 and e2.get("categoryId") == c2, e2)
code, rules = call("GET", B + "/api/v1/user/merchant-rules", t=T)
fy = [r for r in rules if r.get("merchantKey") == "FYBECA"]
check("la regla FYBECA pasó a c2", fy and fy[0]["categoryId"] == c2, fy)
code, g1 = call("GET", B + f"/api/v1/user/expenses/{w1['id']}", t=T)
check("el gasto anterior de FYBECA sigue en c1", g1.get("categoryId") == c1, g1)
code, e3 = call("PUT", B + f"/api/v1/user/expenses/{m6['id']}", {"categoryId": c1, "cardId": card2}, T)
check("un manual sí cambia de tarjeta", code == 200 and e3.get("cardId") == card2, e3)

print("== Mover comercio desde Categorías")
code, moved = call("PUT", B + f"/api/v1/user/merchant-rules/{fy[0]['id']}", {"categoryId": c1}, T)
check("mover FYBECA a c1 -> 200", code == 200 and moved.get("categoryId") == c1, moved)
code, only = call("GET", B + f"/api/v1/user/merchant-rules?categoryId={c1}", t=T)
check("filtro por categoría", code == 200 and all(r["categoryId"] == c1 for r in only) and any(r["merchantKey"] == "FYBECA" for r in only), only)
code, bad = call("PUT", B + f"/api/v1/user/merchant-rules/{uuid.uuid4()}", {"categoryId": c1}, T)
check("regla inexistente -> 404", code == 404, bad)

print("== Otra moneda")
code, fx = expense(T, card, c1, 3.10, "JUAN VALDEZ BOGOTA", now + dt.timedelta(minutes=5), origin="AUTOMATIC",
                   originalAmount=12000, originalCurrencyCode="COP", countryCode="CO")
check("pago en COP guardado con 3.10 USD y 12000 COP de referencia",
      code == 201 and float(fx.get("amount") or 0) == 3.10 and float(fx.get("originalAmount") or 0) == 12000
      and fx.get("originalCurrencyCode") == "COP", fx)
code, fxb = expense(T, card, c1, 3.10, "JUAN VALDEZ BOGOTA", now + dt.timedelta(minutes=9), origin="AUTOMATIC", originalAmount=12000)
check("originalAmount sin moneda -> 400", code == 400, fxb)

print("== Límite propio")
code, lim = call("PUT", B + f"/api/v1/user/cards/{card}/limit", {"amount": 300}, T)
check("poner límite 300", code == 200 and float(lim.get("currentPeriodBudget") or 0) == 300, lim)
code, lim = call("PUT", B + f"/api/v1/user/cards/{card}/limit", {"amount": None}, T)
check("quitar límite", code == 200 and lim.get("currentPeriodBudget") is None, lim)
uid = sql(f"select user_id from cards where id = '{card}'")
sql(f"update financial_periods set period_month = (period_month - interval '1 month')::date where user_id = '{uid}'")
code, cards = call("GET", B + "/api/v1/user/cards", t=T)
mine = [c for c in cards if c["id"] == card][0]
check("mes nuevo: el límite quitado no vuelve", mine.get("currentPeriodBudget") is None, mine)
code, lim = call("PUT", B + f"/api/v1/user/cards/{card}/limit", {"amount": 150}, T)
check("volver a poner 150", code == 200 and float(lim.get("currentPeriodBudget") or 0) == 150, lim)

print("== Plan Free: máximo 2 tarjetas")
sql(f"insert into user_subscription (user_id, plan, source) values ('{uid}', 'FREE', 'TESTER') on conflict (user_id) do update set plan = 'FREE'")
code, third = new_card(T, "Tercera")
check("tercera tarjeta con plan Free -> 409", code == 409, third)
sql(f"delete from user_subscription where user_id = '{uid}'")
code, third = new_card(T, "Tercera")
check("sin fila de plan (Pro de tester) sí se crea", code == 201, third)

print("== Eliminar cuenta")
email2, pwd2, T2 = user("borrar")
card_b = new_card(T2, "Borrar")[1]["id"]; cat_b = new_cat(T2, 9)
expense(T2, card_b, cat_b, 9.99, "FYBECA 1 QUITO", now, origin="AUTOMATIC")
uid2 = sql(f"select user_id from cards where id = '{card_b}'")
code, bad = call("POST", A + "/api/v1/auth/account/delete", {"password": "otra-cosa"}, T2)
check("contraseña incorrecta -> 400", code == 400, bad)
code, ok = call("POST", A + "/api/v1/auth/account/delete", {"password": pwd2}, T2)
check("eliminar cuenta -> 200", code == 200, ok)
for table, col in (("expenses", "user_id"), ("cards", "user_id"), ("user_categories", "user_id"),
                   ("user_consumption_selections", "user_id"), ("financial_periods", "user_id"),
                   ("app_users", "id"), ("audit_log", "subject_user_id")):
    check(f"sin filas en {table}", sql(f"select count(*) from {table} where {col} = '{uid2}'") == "0")
check("queda en deleted_accounts", sql(f"select count(*) from deleted_accounts where user_id = '{uid2}'") == "1")
code, lg = call("POST", A + "/api/v1/auth/login", {"username": email2, "password": pwd2})
check("ya no puede iniciar sesión", code in (400, 401), code)
code, late = call("GET", B + "/api/v1/user/cards", t=T2)
check("token viejo no recrea la cuenta", code >= 400 and sql(f"select count(*) from app_users where id = '{uid2}'") == "0", (code, late))
check("el otro usuario sigue intacto", sql(f"select count(*) from expenses where user_id = '{uid}'") != "0")

print("== Correo del PIN (Mailpit)")
try:
    urllib.request.urlopen(urllib.request.Request(MP + "/api/v1/messages", method="DELETE"))
except Exception as e:
    print("mailpit no responde:", e)
code, pin = call("POST", A + "/api/v1/auth/password-reset/request", {"email": email})
check("pedir PIN -> 200", code == 200, pin)
time.sleep(3)
try:
    with urllib.request.urlopen(MP + "/api/v1/messages") as r: box = json.loads(r.read())
    msgs = box.get("messages", [])
    to = [a.get("Address") for m in msgs for a in m.get("To", [])]
    check("llegó un correo a la cuenta", email in to, {"total": box.get("total"), "subjects": [m.get("Subject") for m in msgs]})
except Exception as e:
    check("llegó un correo a la cuenta", False, e)
```

```bash
$L exec -T business-db psql -U paktay -d paktay -c "select s.normalization_version v, s.merchant_normalized comercio, uc.name categoria, s.selection_count usos, s.last_selected_at::date ultimo from user_consumption_selections s join user_categories uc on uc.id = s.category_id where s.active and s.consumption_name not like 'FYBECA%' and s.consumption_name not like 'JUAN VALDEZ%' and s.consumption_name not like 'SUPERMAXI 042%' order by s.user_id, s.selection_count desc limit 300" > "$LOGS/05b-reglas-despues.txt" 2>&1
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/user/merchant-rules","/api/v1/user/merchant-rules/{id}","/api/v1/catalog/countries","/api/v1/user/cards/{cardId}/limit"]})' > "$LOGS/06-openapi.txt"
curl -s localhost:28081/v3/api-docs | python3 -c 'import sys,json;print("/api/v1/auth/account/delete" in json.load(sys.stdin)["paths"])' >> "$LOGS/06-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/07-health.txt"
$L logs --no-color --tail=400 business-svc auth-svc | grep -iE "error|exception|wallet_duplicate|account_" | head -n 200 > "$LOGS/08-logs.txt"
$L stop
docker rm -f paktay-mailpit-test >/dev/null 2>&1
unset SMTP_HOST SMTP_PORT SMTP_SECURE SMTP_STARTTLS SMTP_USER SMTP_PASS
```

### Base desde cero

```bash
$F up --build -d > "$LOGS/09-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/09-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/10-fresh-history.txt" 2>&1
$F down -v > /dev/null 2>&1
git checkout develop
cd ..
```

## 3. Front

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia6 && git pull --ff-only
npm ci > /tmp/npm.log 2>&1; echo "exit=$?" >> /tmp/npm.log; tail -n 5 /tmp/npm.log > "$LOGS/11-npm-ci.txt"
npm run env > /dev/null 2>&1
npx tsc --noEmit > /tmp/tsc.log 2>&1; echo "exit=$?" >> /tmp/tsc.log; head -n 200 /tmp/tsc.log > "$LOGS/12-tsc.log"
npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log
grep -E "^(PASS|FAIL)|Tests:|Test Suites:" /tmp/jest.log > "$LOGS/13-jest.log"
grep -A 30 "  ● [^C]" /tmp/jest.log | head -n 600 > "$LOGS/13-jest-fallos.log"
npm run lint > /tmp/lint.log 2>&1; echo "exit=$?" >> /tmp/lint.log; grep -E "error|problems|exit=" /tmp/lint.log | head -n 100 > "$LOGS/14-lint.txt"
git checkout develop
cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md`: tests de Maven, historial de Flyway (local y desde cero, con V6), cada línea
`OK`/`FAIL` de `05-dia6.txt`, OpenAPI, salud, tsc, Jest (suites y tests fallidos con nombre y
primeras líneas) y lint. Si algo del script falla por el propio script (no por la app),
anótalo aparte.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 6 ($RUN)"
git push origin develop
```
