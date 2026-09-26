# Admin panel y clientes en develop

Run: `2026-09-26_1306-admin-panel-fronts`  
Backend: `a5bc599`  
Mobile: `ceff2b2`  
Web admin: `c663515`  
Entorno: local, auth `28081`, business `28082`, Keycloak `28180`.

| Bloque | Resultado | Evidencia |
|---|---|---|
| Backend Maven y tests | OK | 01-mvn.txt |
| Docker local y Keycloak init | OK | 02-up.log, 02b-keycloak-init.log |
| Admin panel completo | OK | 03-admin-panel-scenario.log, 0 fallos |
| Salud, OpenAPI y Swagger | OK, todos 200 | 07-health-openapi.log |
| Rutas OpenAPI admin | OK | 08-openapi-routes.log |
| CORS local/producción simulada | OK | 06-cors-produccion.txt |
| Web admin TypeScript/lint/build | OK | web-01 a web-06 |
| Web admin login/sesión/BFF/logout | OK | 04-web-admin-integration.log |
| Mobile npm ci/env/TypeScript/Jest/lint | OK | mobile-01 a mobile-05 |
| Mobile CocoaPods y build iOS Simulator | OK | mobile-06 y mobile-07 |

## Integración web admin

- Sin cabecera del cliente: `403`.
- Sin cookie de sesión: `401`.
- Login: `200`, cookies `pk_at` y `pk_rt`.
- Sesión autenticada: `200`.
- BFF hacia métricas del business service: `200`.
- Logout: `204`; la sesión posterior responde `401`.

Los cambios locales preexistentes del web admin y mobile se conservaron y no se incluyeron en el commit de logs.
