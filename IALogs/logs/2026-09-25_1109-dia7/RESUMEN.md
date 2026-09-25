# Día 7 — `feature/dia7` (2026-09-25_1109-dia7)

Prueba de las ramas `feature/dia7` de backend (`7666715`) y front (`0c0f2e2`): monedas del
servidor, avisos de presupuesto por Firebase (sin configurar), protección de monto, límites
del plan Gratis/Pro y código Swift nativo de avisos locales. Sin cambios de código (anotado
un fallo de tsc en un test).

## Backend

- **Maven**: BUILD SUCCESS, **60 tests, 0 fallos** (3 nuevos de `BudgetAlertServiceTest`).
- **Flyway local** (`03-local-history.txt`): V1..V8, `monedas avisos plan` (t) al final.
- **Sin suscripción = PRO ilimitado**: plan PRO, límites null (OK).
- **Escenario completo** (`04-dia7.txt`): **18/18 OK**. Destacados:
  - Catálogo con 9 monedas (USD primero) + BRL/CLP/GBP/CAD; países con Brasil y Chile.
  - Avisos de presupuesto: 62 % sin aviso, 91 % a 90, no repite el 90 al 92 %, tarjeta a
    74 % sin aviso, de 74→104 % se reservan 90 y 100 (llega solo el de 100).
  - Dispositivos: push-token de un device inexistente → 400; guardar/quitar push-token → 204
    con persistencia en `user_devices`.
  - Regla FYBECA con `maxAmount` 50.
  - Plan Gratis: límites 2/20/3/3; captura 21 → 409 ("Puedes anotarlo a mano"); manual sí se
    guarda; uso 20 capturas; presupuesto en 4 categorías → 409, 3 → 200, activar 4.ª → 409;
    de vuelta a PRO la captura se guarda.
- **Log de avisos** (`05-logs-avisos.txt`): `push_sender_ready enabled=false` y 2×
  `budget_alert_no_device` — **exactamente lo esperado** (Firebase sin configurar).
- **OpenAPI** (`06-openapi.txt`): rutas nuevas presentes (`catalog/currencies`,
  `user/entitlements`, `security/devices/{deviceId}/push-token`).
- **Salud** (`07-health.txt`): 200 en health/api-docs/swagger-ui en ambos servicios.
- **Flyway desde cero** (`09-fresh-history.txt`): V1..V8 todos `t`. `down -v` aplicado.
- Nota de infraestructura: Docker Desktop estaba caído al iniciar (reintento del build y
  Keycloak central devuelto a Up con su DB); sin impacto en el escenario.

## Front

- **npm ci**: exit=0 (Node 26.10 reinstalado vía brew tras el reinicio; wrappers en
  `/tmp/nodebin` recreados).
- **tsc (`11-tsc.log`)**: **exit=2**, error **solo en el test**
  `notificationContext.test.ts:74` — handler con firma de argumentos sobrada (TS2345). No
  es código de producción; se anota como único hallazgo. **Sin cambios de código.**
- **Jest**: **62/62 suites, 724/724 tests PASS** (incluye `notificationContext.test.ts`,
  que pasa en runtime).
- **Lint**: 0 errores / 125 warnings (exit=0; pre-existentes).
- **pod install**: exit=0, 83 dependencias del Podfile / 82 pods instalados.
- **xcodebuild** (esquema real `FinanceApp` del workspace, como asumía la guía):
  **BUILD SUCCEEDED**, exit=0; `PaktayWalletIntent.swift` compiló en el target
  `FinanceApp`.

## Estado del entorno al terminar

- `paktay-local` detenido; `paktay-fresh` caído con `down -v` tras validar V8 desde cero.
- `keycloakservices-local` (28180) en Up. KEYCLOAK_ADMIN_PASSWORD sin exportar.
- Repos en `develop`, árbol limpio salvo este RUN. Logs 88 K en total, sin secretos/tokens.