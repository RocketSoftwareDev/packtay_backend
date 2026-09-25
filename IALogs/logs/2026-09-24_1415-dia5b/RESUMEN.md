# RESUMEN - Día 5b: límite mensual de tarjeta y tipos del front (2026-09-24_1415-dia5b)

Ramas probadas: backend `fix/limite-tarjeta-mensual` (`3c4c74a`) y front `fix/dia5-tipos`
(`582e0db`). Sin cambios de código. Keycloak central (`keycloakservices-local`, 28180)
como identidad; mismos comandos `L` que el día 5.

## Backend

### Build (01-mvn.txt)

```text
48 tests, 0 fallos
BUILD SUCCESS
```

`$L up --build -d` OK; auth 200, biz 200. `business-svc` reconstruido con el fix.

### Escenario del límite mensual (03-limite.txt) — 5/5 OK

- Alta de tarjeta con `initialBudget=300` → `currentPeriodBudget=300`.
- Al mover el período actual un mes atrás (simula el cambio de mes), la tarjeta conserva
  el límite 300 en el mes nuevo.
- `financial_periods`: `2026-08-01` y `2026-09-01`.
- `budget_allocations`: `2026-08-01|300.00` y `2026-09-01|300.00` (una por mes, mismas
  cantidades).
- `summary` → `ownLimit: 300.0`.
- **Segunda lectura no duplica**: count de allocations scope CARD = `2` (una por período,
  no duplicadas).

Nota de ejecución: el script de la guía usa `os.environ["L"]` con `--env-file .env`;
heredando el `cwd` del shell, su función `sql()` devolvía stdout vacío (uid en blanco →
update sin efecto y count `""` ≠ "2"). Con `cwd` fijado a `packtay_backend` en esa misma
función, el script pasa 5/5. Recomendación para el equipo: hacer el `sql()` del script con
`cwd` explícito (o usar rutas absolutas en `L`) para que la prueba sea robusta al directorio
de arranque.

## Front (04-tsc.log, 05-jest.txt)

| Chequeo | Resultado |
|---|---|
| `npm ci` | exit=0 |
| `npx tsc --noEmit` | **exit=0** (el fix corrige los 2 errores del día 5) |
| Jest | 53 suites PASS, 583 tests PASS, 0 fallos |

## Estado final

Backend y front en `develop` (working trees limpios). `paktay-local` detenido al terminar
el backend; luego se levantó de nuevo para dejar el entorno como estaba.