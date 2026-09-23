# Día 2: foto del esquema real — 2026-09-23_1108-dia2

Backend: `225a38216705968a50221c8af70395392f4f5af6`.

Se sacó el esquema de la base local (`paktay-local`) y se intentó levantar una base desde cero
(`paktay-fresh`) con los SQL de `database/` tal como los monta el compose. No se cambió código ni
SQL. Se preservaron `main`, `paktay-prod` y `paktay-local`. `paktay-fresh` se eliminó al final.

## Resultados

| Paso | Estado | Evidencia |
|---|---|---|
| 01 Esquema local | OK | `01-schema-local.sql` (3486 líneas), 25 objetos en `public` en information_schema, `03-filas-por-tabla.txt` con 24 filas (la vista no reporta), sin errores en `01-schema-local.err`. |
| 02 Base fresh desde cero | FALLO | La inicialización no completa: 0 tablas en `public`. `06-fresh-init-errores.txt` registra el error NOT NULL. |
| Tablas local | — | 25: app_users, audit_log, bank_card_offerings, banks, budget_allocations, cards, currencies, expenses, financial_periods, installment_payment_allocations, installment_payments, installment_plans, installments, monthly_incomes, password_pins, pending_movements, shortcut_credentials, system_categories, unregistered_payments, user_budget_settings, user_categories, user_category_budgets, user_consumption_selections, user_devices, v_monthly_expense_summary. |
| Tablas fresh | — | 0. `07-tablas-fresh.txt` vacío; `\dt` en fresh: "Did not find any relations". `08-schema-fresh.sql` es un pg_dump de base vacía (26 líneas). |
| 03 Cierre | OK | `09-fresh-down.log`: `paktay-fresh` detenido, contenedor, volumen `business-data` y red eliminados. `paktay-local` quedó detenido (business-db stop) y sin tocar en datos. |

## Diferencia local vs fresh

`10-diff-tablas.txt`: las 25 tablas/objetos existen sólo en local (1,25d0). No hay diferencias de
nombres entre `02` y `07` porque fresh quedó vacío por el error de inicialización.

## Error de inicialización fresh (docker-entrypoint-initdb.d/001-paktay-schema.sql)

`06-fresh-init-errores.txt` (línea 164 del log original):

```text
business-db-1  | psql:/docker-entrypoint-initdb.d/001-paktay-schema.sql:820: ERROR:  null value in column "alias" of relation "system_categories" violates not-null constraint
business-db-1  | DETAIL:  Failing row contains (caac08e3-0fea-4415-a6b7-87f92464ef11, arriendo, Arriendo o hipoteca, null, ARRIENDO O HIPOTECA, null, null, key, #2DD4BF, #0D9488, t, 1, 2026-09-23 16:08:48.949848+00).
```

Causa raíz: en `database/paktay_mvp_v0_1_postgres.sql` la tabla `system_categories` define
`alias varchar(80) not null` (línea 83), pero el INSERT de catálogo (línea 790) no incluye la
columna `alias`:

```text
insert into system_categories (code, name, normalized_name, icon, color_dark, color_light, display_order) values
    ('arriendo', 'Arriendo o hipoteca', 'ARRIENDO O HIPOTECA', 'key', '#2DD4BF', '#0D9488', 1),
    ...
```

El archivo abre `begin;` (línea 8), por lo que el ERROR aborta la transacción y PostgreSQL hace
ROLLBACK de todo el script: por eso fresh queda sin tablas aunque el log muestre 22 CREATE TABLE
antes del fallo. El contenedor quedó "healthy" con el directorio ya inicializado
("PostgreSQL Database directory appears to contain a database; Skipping initialization") y una
base vacía.

## Conteo de filas por tabla (local, sin valores de datos)

```text
app_users|5
audit_log|0
bank_card_offerings|38
banks|29
budget_allocations|0
cards|7
currencies|7
expenses|3
financial_periods|5
installment_payment_allocations|0
installment_payments|0
installment_plans|0
installments|0
monthly_incomes|0
password_pins|0
pending_movements|1
shortcut_credentials|1
system_categories|22
unregistered_payments|0
user_budget_settings|3
user_categories|67
user_category_budgets|67
user_consumption_selections|2
user_devices|0
```

No existe la tabla `flyway_schema_history` en la base local: el psql de `04-flyway-history.txt`
devolvió `relation "flyway_schema_history" does not exist`. El esquema no se gestiona con Flyway.

## Hallazgo

Los SQL de `database/` tal como los ejecuta el compose no levantan una base desde cero: el INSERT
del catálogo inicial de `system_categories` omite `alias` (NOT NULL). Para una base nueva habría
que incluir esa columna en el INSERT (por ejemplo `alias = name`) o relajar la restricción. No se
modificó nada en esta corrida.