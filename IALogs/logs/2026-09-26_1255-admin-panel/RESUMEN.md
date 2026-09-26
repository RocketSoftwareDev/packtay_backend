# Admin panel en develop

Run: `2026-09-26_1255-admin-panel`  
Commit backend: `00e6c3e Merge pull request #28 from RocketSoftwareDev/fix/test-contrasenia-temporal`  
Entorno: local, servicios en 28081/28082, Keycloak local en 28180.

| Bloque | Resultado | Evidencia |
|---|---|---|
| Maven y tests | OK | 01-mvn.txt; BUILD SUCCESS |
| Keycloak init local | OK tras usar contraseña local válida | 02d-keycloak-init-valid-local.log |
| Login admin | OK | 03-login-check.log |
| Escenario admin | PARCIAL | 04-escenario.txt |
| Salud, OpenAPI y Swagger | OK, todos 200 | 06-health.txt |
| Rutas OpenAPI del panel | OK | 05-openapi.txt |
| CORS local/producción simulada | OK | 09-cors-produccion.txt |

## Fallos del escenario

El bloque de recuperación por PIN tuvo tres fallos:

- `PIN validado`: respondió 400.
- `recuperar con PIN -> 200`: no pudo continuar.
- Entrada posterior con la contraseña recuperada: respondió 400.

El resto de las comprobaciones del escenario quedó en OK, incluyendo catálogos, usuarios sin rol ADMIN, cabecera `X-Paktay-Client`, CORS, resumen, notificaciones, estado, auditoría, contraseñas temporales y bloqueo de cuenta.

La contraseña con sufijo `A1` se usó solo en memoria para el Keycloak local porque la contraseña base de `.env.local` no cumple la política de mayúscula. No se escribió en logs.
