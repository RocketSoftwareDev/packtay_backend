# Día 3: probar la rama del backend (para Codex)

Objetivo: compilar y probar `feature/dia3-contexto-estados` sobre la base existente y desde
cero, y ejercitar la ruta nueva. **No cambies código.** Todo va a `IALogs/logs/`.

## Reglas

- **Nunca** toques `main` ni `paktay-prod`. Proyectos permitidos: `paktay-local` y
  `paktay-fresh` (desechable).
- No escribas secretos ni tokens en los logs: si imprimes una respuesta de login, recorta
  `access_token` y `refresh_token` a sus primeros 8 caracteres.

## Preparación

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia3
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia3-contexto-estados && git pull --ff-only
L="docker compose -p paktay-local --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml"
F="docker compose -p paktay-fresh --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml"
wait_up() { for i in $(seq 1 30); do curl -sf localhost:28082/actuator/health >/dev/null && curl -sf localhost:28081/actuator/health >/dev/null && return 0; sleep 10; done; return 1; }
```

## 1. Compilar

```bash
./mvnw -B clean package > "$LOGS/01-mvn.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn.log"
```

## 2. Base existente (debe aplicar V4 sobre V1-V3)

```bash
$L up --build -d > "$LOGS/02-local-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-local-up.log"
$L logs --no-color business-svc | grep -iE "flyway|migrat|error|exception" > "$LOGS/03-local-flyway.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/04-local-history.txt" 2>&1
```

## 3. Ruta nueva con un usuario de prueba (en la base local)

```bash
EMAIL="codex+$(date +%s)@paktay.local"; PASS="Prueba-Paktay-2026!"
curl -s -o "$LOGS/05-register.json" -w "%{http_code}\n" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"displayName\":\"Codex Prueba\",\"password\":\"$PASS\"}" \
  localhost:28081/api/v1/auth/register > "$LOGS/05-register.code"
TOKEN=$(curl -s -H 'Content-Type: application/json' -d "{\"username\":\"$EMAIL\",\"password\":\"$PASS\"}" \
  localhost:28081/api/v1/auth/login | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))')
echo "token_len=${#TOKEN}" > "$LOGS/06-token.txt"
H="Authorization: Bearer $TOKEN"
{
  echo "GET perfil antes"; curl -s -w " HTTP%{http_code}\n" -H "$H" localhost:28082/api/v1/user/profile
  echo "PUT contexto valido"; curl -s -w " HTTP%{http_code}\n" -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"timezone":"Pacific/Galapagos","countryCode":"EC"}' localhost:28082/api/v1/user/profile/context
  echo "PUT zona invalida (esperado 400)"; curl -s -w " HTTP%{http_code}\n" -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"timezone":"Marte/Olympus","countryCode":"EC"}' localhost:28082/api/v1/user/profile/context
  echo "PUT pais invalido (esperado 400)"; curl -s -w " HTTP%{http_code}\n" -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"timezone":"America/Guayaquil","countryCode":"ecu"}' localhost:28082/api/v1/user/profile/context
  echo "GET perfil despues"; curl -s -w " HTTP%{http_code}\n" -H "$H" localhost:28082/api/v1/user/profile
  echo "GET tarjetas"; curl -s -w " HTTP%{http_code}\n" -H "$H" localhost:28082/api/v1/user/cards
  echo "GET presupuesto actual"; curl -s -w " HTTP%{http_code}\n" -H "$H" localhost:28082/api/v1/user/budgets/current
  echo "GET gastos"; curl -s -w " HTTP%{http_code}\n" -H "$H" localhost:28082/api/v1/user/expenses
  echo "GET bancos (solo SYSTEM, esperado 29 o menos activos)"; curl -s -H "$H" localhost:28082/api/v1/catalog/banks | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d) if isinstance(d,list) else d)'
} > "$LOGS/07-rutas.txt" 2>&1
curl -s localhost:28082/v3/api-docs | python3 -c 'import sys,json;d=json.load(sys.stdin);print("/api/v1/user/profile/context" in d.get("paths",{}))' > "$LOGS/08-openapi-context.txt"
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs /swagger-ui/index.html; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/09-health.txt"
$L logs --no-color --tail=200 business-svc auth-svc > "$LOGS/10-local-logs.log" 2>&1
$L stop
```

## 4. Desde cero (V1-V4 en base vacía)

```bash
$F up --build -d > "$LOGS/11-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/11-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/12-fresh-history.txt" 2>&1
$F exec -T business-db psql -U paktay -d paktay -At -c "select 'currencies',count(*) from currencies union all select 'banks',count(*) from banks union all select 'offerings',count(*) from bank_card_offerings union all select 'categories',count(*) from system_categories" >> "$LOGS/12-fresh-history.txt" 2>&1
$F logs --no-color business-svc | grep -iE "flyway|migrat|error|exception" > "$LOGS/13-fresh-flyway.log"
$F down -v > "$LOGS/14-fresh-down.log" 2>&1
git checkout develop
```

## Resumen y subida

`RESUMEN.md`: resultado de Maven, versiones de Flyway en local y en fresh (esperado 1 a 4),
códigos HTTP de cada línea de `07-rutas.txt` (los dos PUT inválidos deben dar 400 y el
perfil final debe mostrar `Pacific/Galapagos`), si OpenAPI incluye la ruta, y las primeras
20 líneas de cualquier error o excepción.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 3 backend ($RUN)"
git push origin develop
```
