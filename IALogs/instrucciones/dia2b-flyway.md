# Día 2b: catálogos y prueba de Flyway (para Codex)

Objetivo: volcar los catálogos como SQL y probar la rama `feature/dia2-esquema-flyway`
sobre una base existente y sobre una base desde cero. **No cambies código.** El único
archivo SQL que produces va a `IALogs/logs/`, no al código.

## Reglas

- **Nunca** toques `main` ni el proyecto `paktay-prod`.
- Proyectos Docker permitidos: `paktay-local` y `paktay-fresh` (desechable).
- Catálogos sí llevan datos (son públicos); tablas de usuarios **no**.
- No escribas secretos en los logs.

## Preparación

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia2b
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
L="docker compose -p paktay-local --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml"
```

## 1. Catálogos desde la base local (todavía con el compose de develop)

```bash
$L up -d business-db && sleep 10
$L exec -T business-db pg_dump -U paktay -d paktay --data-only --column-inserts --no-owner \
  -t public.currencies -t public.banks -t public.bank_card_offerings -t public.system_categories \
  > "$LOGS/01-catalogos.sql" 2> "$LOGS/01-catalogos.err"
grep -c "^INSERT" "$LOGS/01-catalogos.sql" > "$LOGS/01-catalogos-conteo.txt"
# Copia de seguridad del esquema local antes de migrar (sin datos)
$L exec -T business-db pg_dump -U paktay -d paktay --schema-only --no-owner > "$LOGS/02-antes.sql" 2>&1
$L stop
```

## 2. Rama nueva sobre la base existente (baseline + V2)

```bash
git checkout feature/dia2-esquema-flyway && git pull --ff-only
./mvnw -B -q clean package -DskipTests > "$LOGS/03-mvn.log" 2>&1; echo "exit=$?" >> "$LOGS/03-mvn.log"
$L up --build -d > "$LOGS/04-local-up.log" 2>&1
for i in $(seq 1 24); do curl -sf localhost:28082/actuator/health >/dev/null && break; sleep 10; done
$L logs --no-color business-svc | grep -iE "flyway|migrat|baseline|error" > "$LOGS/05-local-flyway.log"
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/06-local-history.txt" 2>&1
$L exec -T business-db psql -U paktay -d paktay -At -c "select table_name from information_schema.tables where table_schema='public' order by 1" > "$LOGS/07-local-tablas.txt" 2>&1
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/08-local-health.txt"
$L stop
```

## 3. Rama nueva desde cero (V1 + V2, sin catálogos todavía)

```bash
F="docker compose -p paktay-fresh --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml"
$F up --build -d > "$LOGS/09-fresh-up.log" 2>&1
for i in $(seq 1 24); do curl -sf localhost:28082/actuator/health >/dev/null && break; sleep 10; done
$F logs --no-color business-svc | grep -iE "flyway|migrat|baseline|error" > "$LOGS/10-fresh-flyway.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, description, success from flyway_schema_history order by installed_rank" > "$LOGS/11-fresh-history.txt" 2>&1
$F exec -T business-db psql -U paktay -d paktay -At -c "select table_name from information_schema.tables where table_schema='public' order by 1" > "$LOGS/12-fresh-tablas.txt" 2>&1
for u in http://localhost:28081 http://localhost:28082; do for p in /actuator/health /v3/api-docs; do echo "$u$p $(curl -s -o /dev/null -w '%{http_code}' $u$p)"; done; done > "$LOGS/13-fresh-health.txt"
$F down -v > "$LOGS/14-fresh-down.log" 2>&1
```

## Resumen y subida

`RESUMEN.md`: número de INSERT por tabla en `01-catalogos.sql`, versiones de Flyway en
local y en fresh, tablas finales de cada una, códigos HTTP, y las primeras 20 líneas de
cualquier error.

```bash
git checkout develop
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): catálogos y prueba de Flyway ($RUN)"
git push origin develop
```
