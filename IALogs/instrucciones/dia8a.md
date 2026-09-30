# Día 8a: pagos recurrentes (para Codex)

Prueba las ramas `feature/dia8a-recurrentes` de backend y móvil. **No cambies código.** Todo va a
`IALogs/logs/`. Sin imágenes ni logs de más de 1 MB.

Qué hay nuevo (V12):
- `recurring_payments` (plantilla: nombre, monto, tarjeta, categoría, MONTHLY/YEARLY, día FIRST/DAY/LAST,
  fin opcional `endMonth`, estado ACTIVE/PAUSED/CANCELLED) y `recurring_occurrences` (cobros PENDING →
  CONFIRMED/SKIPPED/CANCELLED). `expenses.recurring_payment_id`.
- Rutas en `/api/v1/user/recurring-payments` (ver Swagger, tag «Usuario · Pagos recurrentes»).
- `GET /api/v1/user/summary` trae `committed` y `free`, y `committed` por categoría.
- `GET /api/v1/user/entitlements` trae `limits.recurring` (Gratis 2) y `usage.recurring`.
- Trabajo cada 30 min: deja los cobros pendientes y manda el aviso de la víspera a las 19:00 del usuario.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Solo `paktay-local` (y `paktay-fresh` para desde cero).
- Mismos `L`, `F` y `wait_up`; Keycloak local (28180); corre `keycloak-init` como en `admin-panel.md`.
- Sin tokens ni secretos en los logs.
- Si falla **sólo** por pruebas unitarias, anótalas y sigue con `./mvnw -B -DskipTests package`. Detente sólo
  si no compila (guarda todos los `ERROR]` en `$LOGS/01b-mvn-error.txt`).
- En el commit de logs escribe el nombre real de la corrida (valor de `$RUN`).

## 1. Backend

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia8a
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia8a-recurrentes && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|FAIL|ERROR\]" /tmp/mvn.log | head -n 300 > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-up.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/02b-keycloak-init.log" 2>&1; echo "exit=$?" >> "$LOGS/02b-keycloak-init.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/03-history.txt" 2>&1
export BANK_ROW=$($L exec -T business-db psql -U paktay -d paktay -At -F, -c "select o.bank_id, o.card_type, coalesce(o.brand,'') from bank_card_offerings o join banks b on b.id=o.bank_id where b.active limit 1")
export L
```

Guarda como `/tmp/dia8a.py` y ejecuta `python3 /tmp/dia8a.py > "$LOGS/04-dia8a.txt" 2>&1`.

```python
import calendar, json, os, subprocess, time, uuid, urllib.request, urllib.error, datetime as dt
from zoneinfo import ZoneInfo
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
    return email, pwd, call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})[1]["access_token"]
bank, ctype, brand = os.environ["BANK_ROW"].split(",")
today = dt.datetime.now(ZoneInfo("America/Guayaquil")).date()
R = B + "/api/v1/user/recurring-payments"
def rec(T, name, amount, card, cat, **kw):
    body = {"name": name, "amount": amount, "cardId": card, "categoryId": cat, "frequency": "MONTHLY",
            "dayRule": "DAY", "dayOfMonth": today.day, "monthOfYear": None, "endMonth": None, "startsTomorrow": False}
    body.update(kw); return call("POST", R, body, T)

email, pwd, T = user("dia8a")
card = call("POST", B + "/api/v1/user/cards", {"bankId": bank, "cardType": ctype, "creditBrand": brand or None,
    "alias": "Principal", "colorDark": "#FF8A4E", "colorLight": "#E8641F"}, T)[1]["id"]
cat = call("POST", B + "/api/v1/user/categories", {"code": "d8-1", "name": "Suscripciones D8", "icon": "tv",
    "colorDark": "#8B5CF6", "colorLight": "#7C3AED", "sortOrder": 1}, T)[1]["id"]
call("PUT", B + "/api/v1/user/budgets/current", {"globalAmount": 100, "currencyCode": "USD", "recurrence": "MONTHLY",
    "categories": [{"categoryId": cat}]}, T)
uid = sql(f"select user_id from cards where id = '{card}'")

print("== Crear y pendiente de hoy")
code, n = rec(T, "Netflix", 15.99, card, cat)
check("crear mensual con el día de hoy -> 201", code == 201 and n.get("status") == "ACTIVE", n)
code, pend = call("GET", R + "/occurrences/pending", t=T)
mine = [p for p in pend if p["recurringPaymentId"] == n["id"]] if isinstance(pend, list) else []
check("el cobro de hoy queda pendiente", len(mine) == 1 and mine[0]["dueDate"] == today.isoformat(), pend)
code, s = call("GET", B + "/api/v1/user/summary", t=T)
check("resumen: comprometido incluye 15.99", float(s.get("committed") or 0) >= 15.99, {k: s.get(k) for k in ("spent", "committed", "free", "budget")})
check("resumen: libre = budget - spent - committed", s.get("free") is not None and abs(float(s["free"]) - (float(s["budget"]) - float(s["spent"]) - float(s["committed"]))) < 0.01)
cs = [c for c in s.get("categories", []) if c["categoryId"] == cat]
check("categoría con comprometido", cs and float(cs[0].get("committed") or 0) >= 15.99, cs)

print("== Confirmar con otro monto y usarlo desde ahora")
code, e = call("POST", R + f"/occurrences/{mine[0]['id']}/confirm", {"amount": 17.50, "updateExpected": True}, T)
check("confirmar crea el gasto enlazado", code == 200 and float(e.get("amount") or 0) == 17.5 and e.get("recurringPaymentId") == n["id"], e)
check("el recurrente ahora espera 17.50", sql(f"select amount from recurring_payments where id = '{n['id']}'") == "17.50")
nx = [i for i in call("GET", R, t=T)[1]["items"] if i["id"] == n["id"]]
check("tras confirmar, el próximo cobro ya no es hoy", nx and nx[0]["nextDueDate"] > today.isoformat(), nx)
code, again = call("POST", R + f"/occurrences/{mine[0]['id']}/confirm", {}, T)
check("confirmar dos veces -> 404 (ya resuelto)", code == 404, again)
code, s2 = call("GET", B + "/api/v1/user/summary", t=T)
check("gastado sube 17.50 y comprometido baja", float(s2["spent"]) >= 17.5 and float(s2["committed"]) < float(s["committed"]), {k: s2.get(k) for k in ("spent", "committed")})

print("== Anual y «Este mes no se cobró»")
code, y = rec(T, "Seguro", 120, card, cat, frequency="YEARLY", monthOfYear=today.month)
check("crear anual -> 201", code == 201, y)
yp = [p for p in call("GET", R + "/occurrences/pending", t=T)[1] if p["recurringPaymentId"] == y["id"]]
code, _ = call("POST", R + f"/occurrences/{yp[0]['id']}/skip", t=T)
check("skip -> 204 y ya no está pendiente", code == 204 and not [p for p in call("GET", R + "/occurrences/pending", t=T)[1] if p["id"] == yp[0]["id"]])
check("anual: próximo cobro el año que viene", call("GET", R, t=T)[1]["items"] and any(i["id"] == y["id"] and i["nextDueDate"] and i["nextDueDate"].startswith(str(today.year + 1)) for i in call("GET", R, t=T)[1]["items"]))

print("== Reglas del día")
code, t1 = rec(T, "Mañana", 5, card, cat, startsTomorrow=True)
check("startsTomorrow: no queda pendiente hoy", code == 201 and not [p for p in call("GET", R + "/occurrences/pending", t=T)[1] if p["recurringPaymentId"] == t1["id"]])
code, d31 = rec(T, "Dia31", 1, card, cat, dayOfMonth=31, startsTomorrow=True)
nxt = dt.date.fromisoformat(d31["nextDueDate"]) if code == 201 else None
check("día 31 cae en el último día del mes", nxt is not None and nxt.day == min(31, calendar.monthrange(nxt.year, nxt.month)[1]), d31)
code, last = rec(T, "Fin", 1, card, cat, dayRule="LAST", dayOfMonth=None, startsTomorrow=True)
check("fin de mes", code == 201 and dt.date.fromisoformat(last["nextDueDate"]).day == calendar.monthrange(*map(int, last["nextDueDate"][:7].split("-")))[1], last)
code, bad = rec(T, "Pasado", 1, card, cat, endMonth=f"{today.year - 1}-01")
check("fin en un mes pasado -> 400", code == 400, bad)
code, bad = rec(T, "SinMes", 1, card, cat, frequency="YEARLY", monthOfYear=None)
check("anual sin mes -> 400", code == 400, bad)
code, lst = call("GET", R, t=T)
check("lista: activos primero y total mensual", code == 200 and lst["activeCount"] >= 5 and float(lst["monthlyTotal"]) > 0, {k: lst.get(k) for k in ("activeCount", "monthlyTotal")})

print("== Pausar, reanudar y editar")
code, p = call("POST", R + f"/{t1['id']}/pause", t=T)
check("pausar -> PAUSED sin próximo cobro", code == 200 and p["status"] == "PAUSED" and p["nextDueDate"] is None, p)
code, r2 = call("POST", R + f"/{t1['id']}/resume", t=T)
check("reanudar -> ACTIVE", code == 200 and r2["status"] == "ACTIVE", r2)
body = {"name": "Mañana editado", "amount": 6, "cardId": card, "categoryId": cat, "frequency": "MONTHLY", "dayRule": "FIRST",
        "dayOfMonth": None, "monthOfYear": None, "endMonth": None, "startsTomorrow": False}
code, ed = call("PUT", R + f"/{t1['id']}", body, T)
check("editar -> inicio de mes y nuevo monto", code == 200 and ed["dayRule"] == "FIRST" and float(ed["amount"]) == 6, ed)

print("== Cancelar la serie desde un cobro")
code, cx = rec(T, "Cancelable", 3, card, cat)
cp = [q for q in call("GET", R + "/occurrences/pending", t=T)[1] if q["recurringPaymentId"] == cx["id"]]
code, _ = call("POST", R + f"/occurrences/{cp[0]['id']}/cancel-series", t=T)
check("cancel-series -> 204 y ya no se lista", code == 204 and not any(i["id"] == cx["id"] for i in call("GET", R, t=T)[1]["items"]))
check("no se creó gasto de ese cobro", sql(f"select count(*) from expenses where recurring_payment_id = '{cx['id']}'") == "0")

print("== Anular un gasto de recurrente no cancela la serie")
code, v = call("POST", B + f"/api/v1/user/expenses/{e['id']}/void", t=T)
check("anular -> 200 y la serie sigue activa", code == 200 and sql(f"select status from recurring_payments where id = '{n['id']}'") == "ACTIVE", v)

print("== Plan Gratis: 2 activos, los pausados no cuentan")
email2, pwd2, T2 = user("free8a")
card2 = call("POST", B + "/api/v1/user/cards", {"bankId": bank, "cardType": ctype, "creditBrand": brand or None,
    "alias": "Free", "colorDark": "#FF8A4E", "colorLight": "#E8641F"}, T2)[1]["id"]
cat2 = call("POST", B + "/api/v1/user/categories", {"code": "d8-2", "name": "Casa D8", "icon": "house",
    "colorDark": "#F59E0B", "colorLight": "#D97706", "sortOrder": 1}, T2)[1]["id"]
uid2 = sql(f"select user_id from cards where id = '{card2}'")
sql(f"insert into user_subscription (user_id, plan, source) values ('{uid2}', 'FREE', 'TESTER') on conflict (user_id) do update set plan = 'FREE'")
a1 = rec(T2, "Uno", 1, card2, cat2, startsTomorrow=True)[1]; a2 = rec(T2, "Dos", 1, card2, cat2, startsTomorrow=True)[1]
code, a3 = rec(T2, "Tres", 1, card2, cat2, startsTomorrow=True)
check("tercer recurrente con plan Gratis -> 409", code == 409, a3)
call("POST", R + f"/{a2['id']}/pause", t=T2)
code, a3 = rec(T2, "Tres", 1, card2, cat2, startsTomorrow=True)
check("con uno pausado sí se puede", code == 201, a3)
code, rr = call("POST", R + f"/{a2['id']}/resume", t=T2)
check("reanudar el tercero activo -> 409", code == 409, rr)
code, ent = call("GET", B + "/api/v1/user/entitlements", t=T2)
check("entitlements: limits.recurring 2 y usage.recurring 2", ent["limits"].get("recurring") == 2 and ent["usage"].get("recurring") == 2, ent)

print("== Eliminar cuenta borra los recurrentes")
code, ok = call("POST", A + "/api/v1/auth/account/delete", {"password": pwd2}, T2)
check("eliminar cuenta -> 200", code == 200, ok)
for table in ("recurring_payments", "recurring_occurrences"):
    check(f"sin filas en {table}", sql(f"select count(*) from {table} where user_id = '{uid2}'") == "0")

print("== Aviso de la víspera (preparación; se verifica en el paso 2)")
utc_hour = dt.datetime.now(dt.timezone.utc).hour
offset = (19 - utc_hour) % 24
if offset > 12: offset -= 24
tz = f"Etc/GMT{'-' if offset > 0 else '+'}{abs(offset)}" if offset != 0 else "Etc/UTC"
sql(f"update app_users set timezone = '{tz}' where id = '{uid}'")
local_tomorrow = (dt.datetime.now(ZoneInfo(tz)) + dt.timedelta(days=1)).date()
code, tm = rec(T, "Vispera", 9.99, card, cat, dayOfMonth=local_tomorrow.day, startsTomorrow=True)
print("vispera preparada:", code, "tz", tz, "manana", local_tomorrow.isoformat())
open("/tmp/dia8a-uid", "w").write(uid)
```

### Paso 2 · Aviso de la víspera

El trabajo corre a los minutos 00 y 30. Espera hasta la próxima media hora (máximo 31 minutos, en una
espera que no bloquee más de lo necesario) y comprueba:

```bash
UID8=$(cat /tmp/dia8a-uid)
sleep $(( (30 - $(date +%M) % 30) * 60 + 60 ))
$L exec -T business-db psql -U paktay -d paktay -At -c "select count(*) from recurring_reminders where user_id = '$UID8'" > "$LOGS/05-vispera.txt"
$L logs --no-color --tail=500 business-svc | grep -E "recurring_job|RECURRING_REMINDER|push_skipped|ERROR|Exception" | head -n 60 >> "$LOGS/05-vispera.txt"
```

Debe haber 1 fila (Firebase local no envía de verdad: `push_skipped` o sin dispositivos es lo esperado).
Si la hora local simulada ya pasó de las 19 cuando corre el trabajo, anótalo y no lo cuentes como fallo.

```bash
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;p=json.load(sys.stdin)["paths"];print({k: k in p for k in ["/api/v1/user/recurring-payments","/api/v1/user/recurring-payments/{id}","/api/v1/user/recurring-payments/occurrences/pending","/api/v1/user/recurring-payments/occurrences/{occurrenceId}/confirm"]})' > "$LOGS/06-openapi.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/07-health.txt"
$L logs --no-color --tail=400 business-svc | grep -iE "error|exception" | head -n 60 > "$LOGS/08-errores.txt"
$L stop
$F up --build -d > "$LOGS/09-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/09-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/10-fresh-history.txt" 2>&1
$F down -v > /dev/null 2>&1
git checkout develop
cd ..
```

## 3. Móvil

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia8a-recurrentes && git pull --ff-only
npm ci > /tmp/npm.log 2>&1; echo "exit=$?" >> /tmp/npm.log; tail -n 3 /tmp/npm.log > "$LOGS/11-npm.txt"
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

`RESUMEN.md`: Maven, Flyway con V12 (local y desde cero), cada `OK`/`FAIL` de `04-dia8a.txt`, el aviso de
la víspera, OpenAPI, salud, tsc, Jest y lint.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 8a ($RUN)"
git push origin develop
```
