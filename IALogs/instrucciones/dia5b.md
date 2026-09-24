# Día 5b: límite de tarjeta mensual y tipos del front (para Codex)

Prueba dos ramas cortas: backend `fix/limite-tarjeta-mensual` y front `fix/dia5-tipos`.
**No cambies código.** Mismas reglas, Keycloak y comandos `L`/`wait_up` que en el día 5.

## Backend: el límite se repite al cambiar de mes

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia5b
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout fix/limite-tarjeta-mensual && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log; grep -E "Tests run:|BUILD|exit=" /tmp/mvn.log > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-up.log"
export BANK_ROW=$($L exec -T business-db psql -U paktay -d paktay -At -F, -c "select o.bank_id, o.card_type, coalesce(o.brand,'') from bank_card_offerings o join banks b on b.id=o.bank_id where b.active limit 1")
```

Simula el cambio de mes moviendo el período actual al mes anterior. Guarda como `/tmp/dia5b.py`
y ejecútalo con `python3 /tmp/dia5b.py > "$LOGS/03-limite.txt" 2>&1`.

```python
import json, os, subprocess, time, urllib.request, urllib.error
A = "http://localhost:28081"; B = "http://localhost:28082"
def call(m, url, body=None, t=None):
    req = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + t} if t else {})})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e: return e.code, e.read().decode()[:200]
def sql(q):
    cmd = os.environ["L"].split() + ["exec", "-T", "business-db", "psql", "-U", "paktay", "-d", "paktay", "-At", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
def check(label, cond, detail=""): print(("OK   " if cond else "FAIL ") + label + ("  | " + str(detail) if detail else ""))
email = f"codex+{int(time.time())}@paktay.local"; pwd = "Prueba-Paktay-2026!"
call("POST", A + "/api/v1/auth/register", {"email": email, "displayName": "Codex Limite", "password": pwd})
T = call("POST", A + "/api/v1/auth/login", {"username": email, "password": pwd})[1]["access_token"]
bank, ctype, brand = os.environ["BANK_ROW"].split(",")
card = call("POST", B + "/api/v1/user/cards", {"bankId": bank, "cardType": ctype, "creditBrand": brand or None, "alias": "Limite", "colorDark": "#FF8A4E", "colorLight": "#E8641F", "initialBudget": 300}, T)[1]
uid = sql(f"select user_id from cards where id = '{card['id']}'")
check("alta con límite 300", float(card.get("currentPeriodBudget") or 0) == 300, card.get("currentPeriodBudget"))
# El período actual pasa a ser el mes anterior: así el mes en curso no existe todavía.
sql(f"update financial_periods set period_month = (period_month - interval '1 month')::date where user_id = '{uid}'")
code, cards = call("GET", B + "/api/v1/user/cards", t=T)
mine = [c for c in cards if c["id"] == card["id"]][0] if isinstance(cards, list) else {}
check("mes nuevo: la tarjeta conserva el límite 300", float(mine.get("currentPeriodBudget") or 0) == 300, mine.get("currentPeriodBudget"))
print("periodos:", sql(f"select period_month from financial_periods where user_id = '{uid}' order by 1"))
print("limites:", sql(f"select fp.period_month, ba.amount from budget_allocations ba join financial_periods fp on fp.id = ba.period_id where ba.user_id = '{uid}' order by 1"))
code, s = call("GET", B + "/api/v1/user/summary", t=T)
check("resumen: ownLimit 300", any(float(c.get("ownLimit") or 0) == 300 for c in s.get("cards", [])), s.get("cards"))
code, cards2 = call("GET", B + "/api/v1/user/cards", t=T)
check("segunda lectura no duplica", sql(f"select count(*) from budget_allocations where user_id = '{uid}' and scope = 'CARD'") == "2")
```

Si el campo del límite en la respuesta de tarjetas no se llama `currentPeriodBudget`, usa el
nombre real que devuelva `GET /api/v1/user/cards` y anótalo en el resumen.

```bash
$L stop; git checkout develop; cd ..
```

## Front: tsc limpio

```bash
cd packtay_mobile_front
git fetch origin && git checkout fix/dia5-tipos && git pull --ff-only
npm ci > /dev/null 2>&1; npm run env > /dev/null 2>&1
npx tsc --noEmit > "$LOGS/04-tsc.log" 2>&1; echo "exit=$?" >> "$LOGS/04-tsc.log"
npx jest --ci > /tmp/jest.log 2>&1; grep -E "Tests:|Test Suites:" /tmp/jest.log > "$LOGS/05-jest.txt"
git checkout develop; cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md` con las líneas `OK`/`FAIL`, tsc y Jest. Luego:

```bash
git add IALogs/logs/$RUN && git commit -m "chore(ialogs): prueba día 5b ($RUN)" && git push origin develop
```
