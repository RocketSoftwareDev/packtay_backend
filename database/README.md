# Base de datos

Desde el 2026-09-23 el esquema de negocio lo gestiona **Flyway** desde `business-svc`,
en `business-svc/src/main/resources/db/migration/`:

- `V1__baseline.sql`: el esquema real de ese día, volcado con `pg_dump --schema-only`.
- `V2__limpieza_tablas_muertas.sql`: quita cuotas, ingresos, la cola de pendientes del
  atajo, la credencial del atajo y los pagos no registrados.
- `V3__catalogos.sql`: monedas, bancos, ofertas de tarjeta y categorías del sistema
  (pendiente de volcar desde la base de pruebas).

Una base nueva se crea sola al arrancar `business-svc`. En una base que ya existía sin
historial de Flyway, `V1` se marca como baseline y se aplican `V2` en adelante.

Reglas:

- Ningún cambio de esquema fuera de Flyway. Nada de `psql < archivo.sql` a mano.
- Una migración publicada no se edita; se escribe otra.
- `auth-svc` no crea tablas: `password_pins` está en `V1`.

Los archivos `paktay_mvp_v0_*.sql` de esta carpeta son el historial anterior y ya no se
montan en Docker. Se borrarán cuando `V3__catalogos.sql` exista y una base desde cero
se haya probado.

Para recrear la base local aislada desde cero (nunca sobre producción):

```bash
docker compose -p paktay-local --env-file .env --env-file .env.local.example \
  -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml \
  down -v
```
