# Base de datos

Desde el 2026-09-23 el esquema de negocio lo gestiona **Flyway** desde `business-svc`,
en `business-svc/src/main/resources/db/migration/`:

- `V1__baseline.sql`: el esquema real de ese día, volcado con `pg_dump --schema-only`.
- `V2__limpieza_tablas_muertas.sql`: quita cuotas, ingresos, la cola de pendientes del
  atajo, la credencial del atajo y los pagos no registrados.
- `V3__catalogos.sql`: 7 monedas, 29 bancos, 38 ofertas de tarjeta y 22 categorías del
  sistema, volcados de la base de pruebas. Idempotente (`ON CONFLICT DO NOTHING`).
- `V4__contexto_estados_duo.sql`: zona horaria y país del usuario, tarjetas `DELETED`,
  estados de gasto, tablas preparatorias de Duo, consentimientos y suscripciones, bancos
  por país / propios, y purga de `audit_log` a 90 días.
- `V5__anulacion_gastos.sql`: acción de auditoría `VOID` y alta de registros `REFUND` en
  tarjetas inactivas o eliminadas (el requisito de tarjeta activa en el alta queda sólo
  para `kind = 'EXPENSE'`).

Una base nueva se crea sola al arrancar `business-svc`. En una base que ya existía sin
historial de Flyway, `V1` se marca como baseline y se aplican `V2` en adelante.

Reglas:

- Ningún cambio de esquema fuera de Flyway. Nada de `psql < archivo.sql` a mano.
- Una migración publicada no se edita; se escribe otra.
- `auth-svc` no crea tablas: `password_pins` está en `V1`.

Los 20 archivos `paktay_mvp_v0_*.sql` que había aquí se borraron el 2026-09-23: ya no
levantaban una base desde cero y el esquema real se había desviado de ellos. Siguen en
el historial de git. `SUPABASE.txt` queda como referencia de diseño funcional.

## Despliegue y comprobación

Usa el mismo `docker-compose.yml` en todos los entornos y configura `.env`.
`docker compose up -d --build` inicia los servicios y aplica las migraciones
pendientes automáticamente. V6 incorpora reglas/países/cuenta, V7 restaura la
protección de gastos y V8 incorpora monedas, avisos y plan.

Para consultar el resultado:

```sh
docker compose exec -T business-db psql -U paktay -d paktay -c \
  'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

Conserva `BUSINESS_DATA_VOLUME` para actualizar una base existente. Para iniciar
una base vacía, configura un nombre de volumen nuevo antes de levantar los
servicios; conserva el anterior como respaldo. Esto no modifica la base de
Keycloak Services.
