# Día 2b: catálogos y prueba de Flyway — 2026-09-23_1120-dia2b

Backend desarrollado en la rama `feature/dia2-esquema-flyway` (`0652a8c feat(db): Flyway como
único dueño del esquema; V1 baseline real y V2 sin tablas muertas`). Base local probada sobre
`paktay-local` (existente) y sobre `paktay-fresh` (desechable). No se cambió código; el único SQL
producido (`01-catalogos.sql`) vive en `IALogs/logs/`. Se preservaron `main` y `paktay-prod`.

## 1. Catálogos desde la base local (develop antes de migrar)

`01-catalogos.sql` con `pg_dump --data-only --column-inserts` de las 4 tablas de catálogo:

| Tabla | INSERT |
|---|---|
| bank_card_offerings | 38 |
| banks | 29 |
| currencies | 7 |
| system_categories | 22 |
| **Total** | **96** |

`01-catalogos.err` vacío (`0` bytes). Copia de seguridad del esquema local pre-migración en
`02-antes.sql` (schema-only).

## 2. Rama feature sobre la base existente (baseline + V2)

Maven: `./mvnw -B -q clean package -DskipTests` → `exit=0`.

Flyway (`05-local-flyway.log`): la tabla `flyway_schema_history` no existía en la base local por
lo que Flyway creó el historial, aplicó **baseline version: 1** y migró a **v2 "limpieza tablas
muertas"** → esquema en `v2` (success=t). Historial (`06-local-history.txt`):

```text
1|<< Flyway Baseline >>|t
2|limpieza tablas muertas|t
```

Tablas finales (`07-local-tablas.txt`): **17** (app_users, audit_log, bank_card_offerings, banks,
budget_allocations, cards, currencies, expenses, financial_periods, flyway_schema_history,
password_pins, system_categories, user_budget_settings, user_categories, user_category_budgets,
user_consumption_selections, user_devices). La V2 eliminó las tablas muertas (installment_*,
monthly_incomes, unregistered_payments, shortcut_credentials, pending_movements y la vista
v_monthly_expense_summary).

Salud (`08-local-health.txt`): 4/4 HTTP 200 (health y OpenAPI de auth y business).

## 3. Rama feature desde cero (V1 + V2 sin catálogos todavía)

Flyway en base recién creada (`10-fresh-flyway.log`): historial inexistente, migró **V1
"baseline"** y luego **V2** → esquema en `v2` con `success=t`. Historial (`11-fresh-history.txt`):

```text
1|baseline|t
2|limpieza tablas muertas|t
```

Tablas finales (`12-fresh-tablas.txt`): **17**, idénticas a las de la base existente (misma lista,
incluida `flyway_schema_history`). Mismo set que local → la prueba fresh confirma que el esquema
la levanta desde cero sin errores.

Salud (`13-fresh-health.txt`): 4/4 HTTP 200.

`14-fresh-down.log`: `paktay-fresh` detenido, volumenes `business-data` y `keycloak-local-data` y
red eliminados (`exit=0`). `paktay-local` quedó detenido con `$L stop` al final del paso 2.

## Conclusión

La rama `feature/dia2-esquema-flyway` deja Flyway como único dueño del esquema y funciona en
ambos escenarios: base preexistente sin historial (baseline v1 + v2) y base vacía (v1 + v2).
Las 17 tablas resultantes son iguales en ambos caminos; en la local, la V2 borró las tablas
muertas que existían en el esquema viejo. Ninguno de los checks de salud ni OpenAPI falló.
No hubo errores que recuperar en `03-mvn.log`, `01-catalogos.err` ni en los logs de contenedor.