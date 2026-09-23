# RESUMEN - Día 3 backend (2026-09-23_1154-dia3)

Rama probada: `feature/dia3-contexto-estados` (`23f57dd`). Objetivo cumplido: compilar la
rama, aplicar V4 sobre la base existente y desde cero, y ejercitar la ruta nueva de contexto
del usuario.

## 1. Compilar (01-mvn.log)

```text
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

## 2. Base existente (paktay-local): V4 sobre V1-V3 (03-local-flyway.log, 04-local-history.txt)

Flyway validó 5 migraciones (V1-V4 y el baseline interno) y aplicó solo la pendiente:

```text
1|<< Flyway Baseline >>|t
2|limpieza tablas muertas|t
3|catalogos|t
4|contexto estados duo|t
```

Todos los registros con `success=t`. `up=0` (health 200 en auth y business).

## 3. Ruta nueva con usuario de prueba (07-rutas.txt)

- Registro: HTTP **201** (`05-register.json`).
- Login: token obtenido (token_len=1423; `access_token` recortado a 8 caracteres en el log).
- Ejercitación de rutas protegidas (todas con `Bearer <token>`):

| Línea | HTTP | Nota |
|---|---|---|
| GET perfil antes | 200 | timezone ya en `Pacific/Galapagos` (usuario reutilizado del primer intento) |
| PUT contexto válido | 200 | perfil persistió `Pacific/Galapagos`/`EC` |
| PUT zona inválida | **400** | "Zona horaria inválida: usa un identificador IANA como America/Guayaquil" |
| PUT país inválido | **400** | "countryCode: must match `^[A-Z]{2}$`" |
| GET perfil después | 200 | `timezone: Pacific/Galapagos`, `countryCode: EC` |
| GET tarjetas | 200 | `[]` |
| GET presupuesto actual | 200 | nivel mes 2026-09, USD, recurrencia THIS_MONTH, `spentAmount: 0` |
| GET gastos | 200 | `[]` |
| GET bancos (SISTEMA) | 200 | 17 bancos activos |

OpenAPI: la ruta está presente → `08-openapi-context.txt` = `True`.
Salud y docs en ambos servicios: 6/6 HTTP 200 (`09-health.txt`).

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

`13-fresh-flyway.log` confirma migración secuencial de V1→V4 sin errores. `down -v` ejecutado
(`14-fresh-down.log`, exit=0). `paktay-local` quedó detenido; `paktay-prod` no se tocó.

## Hallazgo relevante (no se cambió código)

En el primer intento, el login del usuario recién registrado falló en Keycloak 26 con
`invalid_grant` / `Account is not fully set up` y evento `resolve_required_actions`
(`userId=null`). Causa: Keycloak 26 considera incompleta una cuenta cuando al usuario le
faltan atributos de perfil `firstName`/`lastName` completos. El endpoint de registro
(`KeycloakIdentityService.register`) crea el usuario con `firstName=displayName` pero **sin
`lastName`**, y con `emailVerified=false`. Se completó el perfil del usuario de prueba por
la API de administración (solo estado de datos, sin código) para poder ejercitar la ruta.

Implicación para el equipo: revisar el flujo de registro para decidir si el payload debe
incluir `lastName` (o un valor por defecto) y/o marcar `emailVerified`, porque de lo
contrario el login directo de usuarios nuevos fallará en producción con Keycloak 26.