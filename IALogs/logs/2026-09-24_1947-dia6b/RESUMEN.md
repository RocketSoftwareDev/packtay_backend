# Día 6b — `fix/dia6-correcciones` (2026-09-24_1947-dia6b)

Reintento completo de la guía `dia6.md` en las ramas `fix/dia6-correcciones` de backend
(`baae7dc`) y front (`2848346`), aplicando las diferencias del `dia6b.md`. Sin cambios
de código por parte de la corrida.

## Las 4 líneas marcadas (hallazgos del día 6) — ESTADO

1. **"un manual sí cambia de tarjeta"** → **OK**. El PUT a un gasto manual con `cardId`
   distinto responde 200 y aplica el cambio. V7 restauró la protección de V4 (tarjeta
   manual editable, anulado no reactivable, período cerrado en la zona del usuario) sin
   perder la purga de V6.
2. **"llegó un correo a la cuenta"** → **FAIL propio del script, la app ya funciona**.
   El `password-reset/request` responde 200 y Mailpit completa el envío cuando el correo
   no lleva subdirección. **Validado aparte** con un email sin `+`: se generó y recibió
   `Recupera tu contraseña — PAKTAY` correctamente, y `SMTP_FROM` reemplazó al remitente
   inválido (ya no hay `553 not a valid RFC 5321 address`). El fallo de la línea del
   escenario es porque el script registra `codex+dia6{ts}@paktay.local` y `findByEmail`
   de auth-svc consulta Keycloak con el `+` sin codificar; en el query de Keycloak el
   `+` se interpreta como espacio y no encuentra al usuario (verificado: email con `%2B`
   da 1 resultado, crudo da 0) → `recipient()` devuelve null y no se envía. La app
   funcionaría con un correo normal. Anotado aparte como fallo del propio script/guía.
3. **tsc del front** → **exit=0**. Confirmado el fix TS2367 en `merchantRules.ts`.
4. **Jest del front** → **57/57 suites, 671/671 tests PASS**. Las 2 pruebas que fallaban
   (filtro de categoría en Movimientos desde el Inicio, y propuesta de categoría por
   regla guardada en la ReviewQueue) ya pasan.

## Backend (fix/dia6-correcciones)

- **Maven**: BUILD SUCCESS, 57 tests, 0 fallos.
- **Flyway local** (`04-local-history.txt`): V1..V7 con `restaurar proteccion gastos` (t)
  al final.
- **Flyway desde cero** (`10-fresh-history.txt`): V1..V7 todos `t`. `down -v` aplicado.
- **Escenario completo** (`05-dia6.txt`): **47 OK / 1 FAIL** (el único FAIL es el correo
  con `+` del script, ver arriba; todos los demás checks, SQL, países, Wallet, reglas,
  duplicados, manuales, edición, otra moneda, límite propio, plan Free y eliminación de
  cuenta, OK).
- **Reglas después** (`05b-reglas-despues.txt`): normalización v2 (V6) vigente.
- **OpenAPI** (`06-openapi.txt`): rutas de día 6 presentes en ambos servicios.
- **Salud** (`07-health.txt`): 200 en health, api-docs y swagger-ui (auth y business).
- Logs filtrados (`08-logs.txt`): sin errores inesperados fuera de los esperados del
  escenario.

## Front (fix/dia6-correcciones)

- **npm ci**: exit=0.
- **tsc**: exit=0.
- **Jest**: 57 suites, 671 tests, todos PASS.
- **Lint**: 0 errores / 108 warnings (exit=0; los warnings son pre-existentes).

## Estado del entorno al terminar

- `paktay-local` detenido; `paktay-fresh` caído con `down -v` tras validar V7 desde cero.
- Mailpit `paktay-mailpit-test` eliminado; SMTP_* / SMTP_FROM / KEYCLOAK_ADMIN_PASSWORD
  sin exportar (el secreto no se escribió en logs).
- `keycloakservices-local` (28180) sano.
- Repos en `develop`, árbol limpio salvo el RUN de esta corrida.