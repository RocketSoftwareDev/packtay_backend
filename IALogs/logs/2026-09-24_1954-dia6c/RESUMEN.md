# Día 6c — `fix/dia6-correcciones` (2026-09-24_1954-dia6c)

Prueba del fix del PIN para correos con `+` en `KeycloakIdentityService.findByEmail`
(`a60d996`). No se cambió código.

## Backend

- **Maven**: BUILD SUCCESS, 57 tests, 0 fallos (exit=0).
- Mailpit + `paktay-local` levantados con `SMTP_FROM=no-reply@paktay.local` (como manda
  la guía 6c, con `+` en los correos de prueba).

## Prueba del PIN (`02-pin.txt`)

1. `OK   PIN llega a correo con + 200` — `codex+pin{ts}@paktay.local` recibió el PIN.
2. `OK   PIN llega a correo normal 200` — `codexpin{ts}@paktay.local` recibió el PIN.

Ambos `password-reset/request` responden 200 y Mailpit entrega el correo al destinatario
correcto. El `+` ya no rompe la búsqueda: `findByEmail` codifica el correo y Keycloak
encuentra al usuario. Fix validado.

## Estado del entorno al terminar

- `paktay-local` detenido; Mailpit `paktay-mailpit-test` eliminado.
- SMTP_* / SMTP_FROM / KEYCLOAK_ADMIN_PASSWORD sin exportar (secreto no escrito en logs).
- `keycloakservices-local` (28180) sano.
- Repo backend en `develop`, árbol limpio salvo este RUN.