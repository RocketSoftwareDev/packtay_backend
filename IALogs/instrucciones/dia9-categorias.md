# Día 9 · categorías y presupuesto (bug de testers) — para Codex

Prueba las ramas `feature/dia9-categorias` de backend y móvil. **No cambies código.** Todo va a
`IALogs/logs/`. Sin imágenes, tokens ni secretos en los logs.

Qué hay nuevo (V14):
- `user_categories.reserved` y la reservada **«Sin categoría»** para cada usuario (trigger en
  `app_users` + backfill). Recibe los gastos de categorías eliminadas.
- `DELETE /api/v1/user/categories/{id}` ahora **siempre desactiva** (antes borraba las que no
  tenían gastos) y saca la categoría de los presupuestos.
- `GET /api/v1/user/categories/inactive`, `POST /{id}/reactivate`, `DELETE /{id}/permanent`.
- Eliminar para siempre: solo desactivadas, sin gastos ACTIVE en los últimos 3 meses y sin
  recurrentes ACTIVE o PAUSED; sus gastos pasan a «Sin categoría», también los de meses cerrados.
- Un nombre solo choca con las categorías propias (antes chocaba con todo el catálogo).
- El presupuesto del mes nuevo solo copia categorías activas.

## Reglas

- **Nunca** `main`, `paktay-prod` ni el VPS. Solo `paktay-local` (y `paktay-fresh` desde cero).
- Mismos `L`, `F` y `wait_up`; Keycloak local (28180); corre `keycloak-init` como siempre.
- Si el build falla, guarda todos los `ERROR]` en `$LOGS/01b-mvn-error.txt` y detente.

## 1. Backend

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia9-categorias
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
git checkout feature/dia9-categorias && git pull --ff-only
./mvnw -B clean package > /tmp/mvn.log 2>&1; echo "exit=$?" >> /tmp/mvn.log
grep -E "Tests run:|BUILD|FAIL|ERROR\]" /tmp/mvn.log | head -n 300 > "$LOGS/01-mvn.txt"
$L up --build -d > "$LOGS/02-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/02-up.log"
$L --profile identity-setup run --rm keycloak-init > "$LOGS/02b-keycloak-init.log" 2>&1
$L exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/03-history.txt"
$L exec -T business-db psql -U paktay -d paktay -At -c "select count(*) filter (where n <> 1) from (select u.id, count(uc.id) n from app_users u left join user_categories uc on uc.user_id = u.id and uc.reserved group by u.id) x" > "$LOGS/03b-reservadas.txt"
```

`03b-reservadas.txt` debe decir `0`: cada usuario existente tiene exactamente una reservada.

## 2. Prueba de punta a punta (`04-dia9.txt`, una línea `OK`/`FAIL` por prueba)

En Python como las de días anteriores, con un usuario nuevo (registro + login), una tarjeta y su
presupuesto. Para los gastos «viejos» y los períodos cerrados usa SQL contra `business-db`:
inserta por SQL un gasto con `occurred_at` hace 4 meses copiando las columnas obligatorias de uno
creado por la API, y para cerrar un mes inserta o actualiza `financial_periods.closed_at`.

1. **Reservada al registrarse:** el usuario nuevo tiene una fila `reserved` en `user_categories`;
   `GET /api/v1/user/categories` **no** la lista (no tiene gastos) y `GET /api/v1/user/profile`
   responde `isHaveCategory=false`.
2. **El nombre ya no choca con el catálogo:** crea una categoría propia llamada igual que una del
   catálogo que el usuario no tiene (una de `GET /api/v1/catalog/categories`) → 201. `POST /from-system/{id}` de esa misma del
   catálogo → 400 «Ya tienes una categoría con ese nombre».
3. **Desactivar siempre conserva:** categoría «Viajes» con un gasto de hoy; `DELETE /{id}` → 204;
   sigue en la base con `active=false`; ya no sale en `GET /` ni en `GET /api/v1/user/budgets/current`.
   Una categoría **sin gastos** también queda desactivada (no se borra).
4. **Inactivas:** `GET /inactive` lista «Viajes» con `deletable=false` y `deletableFrom` = fecha del
   gasto + 3 meses; `expensesToMove=1`.
5. **No se puede eliminar todavía:** `DELETE /{id}/permanent` → 409 con «Tiene gastos en los
   últimos 3 meses…». Con una categoría **activa** → 409 «Desactiva la categoría antes de eliminarla».
6. **Recurrente bloquea:** categoría «Streaming» sin gastos usada por un recurrente ACTIVE «Netflix»;
   desactívala; `GET /inactive` → `blockingRecurring=["Netflix"]`, `deletable=false`;
   `/permanent` → 409 que nombra a Netflix. Cancela el recurrente (`DELETE /api/v1/user/recurring-payments/{id}`)
   → `/permanent` → 200.
7. **Eliminar con gastos viejos y mes cerrado:** categoría «Mascotas» con un gasto de hace 4 meses
   (por SQL), cierra ese mes (`closed_at`). Desactívala; `GET /inactive` → `deletable=true`;
   `/permanent` → 200 `{"movedExpenses":1}`. El gasto ahora apunta a la reservada; la categoría ya no
   existe; `GET /` incluye al final «Sin categoría» con `reserved=true`.
8. **La protección sigue:** editar el monto de ese gasto del mes cerrado por la API → error (no 200).
9. **Reactivar:** reactiva «Viajes» → 200, vuelve a `GET /`, **no** vuelve sola al presupuesto.
10. **Nombre de una desactivada:** desactiva otra vez «Viajes» y crea una propia «viajes» → 409
    «Ya tienes «Viajes» desactivada. Reactívala desde Categorías.». Crear «Sin categoría» → 400.
11. **La reservada no se toca:** con su id: `PUT /{id}` y `PATCH /{id}/appearance` → 400; `DELETE /{id}`
    → 400; `DELETE /{id}/permanent` → 404; guardar presupuesto con ella → 400; crear un gasto con ella
    → 400; crear un recurrente con ella → 400.
12. **Presupuesto del mes nuevo:** presupuesto MONTHLY con «Comida» y «Transporte»; desactiva
    «Transporte»; simula el cambio de mes moviendo el período actual al mes anterior por SQL
    (`update financial_periods set period_month = period_month - interval '1 month' where id = ...`) y
    pide `GET /api/v1/user/budgets/current`: el período nuevo trae «Comida» y **no** «Transporte».
13. **OpenAPI:** `/api/v1/user/categories/inactive`, `/{categoryId}/reactivate` y
    `/{categoryId}/permanent` aparecen en `localhost:28082/v3/api-docs` (`05-openapi.txt`); salud,
    OpenAPI y Swagger de los dos servicios en 200 (`06-health.txt`).

```bash
$L logs --no-color --tail=400 business-svc | grep -iE "error|exception" | head -n 60 > "$LOGS/07-errores.txt"
$L stop
$F up --build -d > "$LOGS/08-fresh-up.log" 2>&1; wait_up; echo "up=$?" >> "$LOGS/08-fresh-up.log"
$F exec -T business-db psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" > "$LOGS/09-fresh-history.txt"
$F down -v > /dev/null 2>&1
git checkout develop
cd ..
```

## 3. Móvil

```bash
cd packtay_mobile_front
git fetch origin && git checkout feature/dia9-categorias && git pull --ff-only
npm ci > /tmp/npm.log 2>&1; echo "exit=$?" >> /tmp/npm.log; tail -n 3 /tmp/npm.log > "$LOGS/10-npm.txt"
npm run env > /dev/null 2>&1
npx tsc --noEmit > /tmp/tsc.log 2>&1; echo "exit=$?" >> /tmp/tsc.log; head -n 200 /tmp/tsc.log > "$LOGS/11-tsc.log"
npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log
grep -E "^(PASS|FAIL)|Tests:|Test Suites:" /tmp/jest.log > "$LOGS/12-jest.log"
grep -A 30 "  ● [^C]" /tmp/jest.log | head -n 600 > "$LOGS/12-jest-fallos.log"
npm run lint > /tmp/lint.log 2>&1; echo "exit=$?" >> /tmp/lint.log; grep -E "error|problems|exit=" /tmp/lint.log | head -n 100 > "$LOGS/13-lint.txt"
git checkout develop
cd ../packtay_backend
```

## Resumen y subida

`RESUMEN.md`: Maven (y `CategoryDeletionRuleTest`), V14 local y desde cero, reservadas (`03b`), cada
`OK`/`FAIL` de `04-dia9.txt`, OpenAPI, salud, tsc, Jest y lint.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): prueba del día 9 categorías ($RUN)"
git push origin develop
```
