# RESUMEN - Fix registro con apellido (2026-09-23_1325-dia3-fix)

Rama probada: `fix/registro-apellido` (`0bb07c9`, "el registro envía apellido a Keycloak").
Objetivo: confirmar que el fix elimina el workaround documentado en la corrida 1254
(completar perfil por API admin antes del login). Se mantuvo el Keycloak central
(`keycloakservices-local` en `host.docker.internal:28180`); compose locales sin
`-f docker-compose.local-keycloak.yml`.

## 1. Compilar backend (01-mvn.log)

```text
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0   (3 nuevas del fix)
[INFO] BUILD SUCCESS
```

## 2. Base existente (paktay-local)

Stack levantado contra el Keycloak central; `auth-svc` recreado con el jar que trae el fix.

## 3. El fix resuelve el bloqueo "Account is not fully set up"

- Registro: HTTP **201**, displayName `"Codex Prueba Apellido"`.
- **Login inmediato SIN completar perfil por admin: HTTP 200** (antes daba
  "Account is not fully set up"). Token de 1391 caracteres (recortado en `06-token.txt`).
- Verificación en Keycloak central (`10-keycloak-user.txt`): el usuario quedó con
  `firstName='Codex'`, `lastName='Prueba Apellido'`, `emailVerified=False`. El login
  funciona aunque `emailVerified` siga en `false`. El bloqueante era solo el `lastName`.

### Rutas (07-rutas.txt)

| Línea | HTTP | Nota |
|---|---|---|
| GET perfil antes | 200 | `America/Guayaquil`/`EC` |
| PUT contexto válido | 200 | persistió `Pacific/Galapagos`/`EC` |
| PUT zona inválida | **400** | "Zona horaria inválida: usa un identificador IANA..." |
| PUT país inválido | **400** | `countryCode: must match "^[A-Z]{2}$"` |
| GET perfil después | 200 | `timezone: Pacific/Galapagos`, `countryCode: EC` |
| GET tarjetas | 200 | `[]` |
| GET presupuesto actual | 200 | nivel 2026-09, USD, THIS_MONTH, spent 0 |
| GET gastos | 200 | `[]` |
| GET bancos (SISTEMA) | 200 | 17 activos |

OpenAPI incluye la ruta: `08-openapi-context.txt` = `True`. Salud/docs: 6/6 HTTP 200
(`09-health.txt`).

## 4. Desde cero (paktay-fresh): V1-V4 en base vacía (12-fresh-history.txt)

```text
1|baseline|t
2|limpieza tablas muertas|t
3|catalogos|t
4|contexto estados duo|t
currencies|7
banks|29
offerings|38
categories|22
```

Fresh derribado con `down -v`; `paktay-local` restaurado al final (auth/business 200) y
el Keycloak central siguió arriba durante toda la prueba.

## 5. Front (develop ya trae feature/dia3-contexto-sesion)

| Chequeo | Resultado |
|---|---|
| `npm ci` | exit=0 |
| `tsc --noEmit` | exit=0, sin errores |
| Jest | **51 suites PASS, 537 tests PASS, 0 fallos** |
| `lint` | exit=0, **0 errores / 99 warnings** |

## Conclusión

El fix de `fix/registro-apellido` **elimina el workaround**: registrar y loguear funciona
sin tocar el perfil por API admin. Se recomienda también considerar marcar el
`emailVerified` (el login funciona hoy, pero el correo no está verificado).