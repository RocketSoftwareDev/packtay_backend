# Prueba local Día 8a — pagos recurrentes

- Corrida: 2026-09-30_1545-dia8a
- Entorno: `paktay-local` y `paktay-fresh`; no se inició ni modificó `paktay-prod`.
- Backend `develop` actualizado: `ea8d3391e8afb03b900901929c5009fc335ef939`.
- Backend probado: `feature/dia8a-recurrentes` en `08c28e7c3e4ad8db574a6fef9a12f64afca38e9e`.
- Mobile `develop` actualizado: `fefaab29ce657440fe449902871f7823169a8e9a`.
- Mobile probado: `feature/dia8a-recurrentes` en `5c91df320df4460ccb7f3133027b70984fe9370c`.

## Backend

- `./mvnw -B clean package`: **OK**, 86 pruebas, 0 fallos, 0 errores.
- `keycloak-init` local: **OK** después de corregir una contraseña local que no cumplía la política de mayúsculas. El secreto no se guardó en Git ni en los logs.
- Flyway local: **V12 aplicada**.
- Flujo funcional `04-dia8a.txt`: **OK** en todas las comprobaciones: mensual, anual, pendiente, confirmación, actualización de monto, skip, día 31, fin de mes, pausa, reanudación, edición, cancelación, límite Gratis y eliminación de cuenta.
- OpenAPI: las cuatro rutas de pagos recurrentes aparecen.
- Health local: Auth y Business respondieron `200` en health, OpenAPI y Swagger.
- Aviso de víspera: no se creó fila porque la zona horaria simulada ya había pasado las 19:00 cuando corrió el job; la instrucción indica que esta condición no cuenta como fallo. Queda documentado en `05-vispera.txt`.

## Mobile

- `npm ci`: **OK**.
- `npm run env`: **OK**.
- `npx tsc --noEmit`: **OK**.
- `npx jest --ci`: **OK**, 65 suites y 773 pruebas.
- `npm run lint`: **OK**, 0 errores y 143 warnings.

## Desde cero

- `paktay-fresh`: **OK** con health listo.
- Flyway desde base vacía: versiones `1` a `12`, todas exitosas.
- El proyecto y su volumen fresh fueron eliminados al terminar; se conservó `paktay-local`.

## Estado final

Backend y mobile regresaron a `develop`. `paktay-local` quedó levantado con backend, web admin, bases locales y Keycloak local. No se tocaron contenedores ni datos de producción.
