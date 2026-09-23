# RESUMEN - Día 3 backend + front (2026-09-23_1254-dia3)

Ramas: backend `feature/dia3-contexto-estados` (merge de develop aplicado) y front
`feature/dia3-contexto-sesion`. En esta corrida se **mantuvo el arreglo de Keycloak**
hecho en `25-keycloak-fix.log`: la identidad local ya NO usa el Keycloak duplicado de
paktay-local, sino el Keycloak central `keycloakservices-local` en `host.docker.internal:28180`.
Por eso los compose de esta corrida se ejecutaron SIN `-f docker-compose.local-keycloak.yml`.

## 1. Compilar backend (01-mvn.log)

```text
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 2. Base existente (paktay-local): V4 sobre V1-V3 (04-local-history.txt)

El stack `paktay-local` ya estaba arriba contra el Keycloak central (no se recreó para no
romper el arreglo). Health auth/business 200. Historial Flyway:

```text
1|<< Flyway Baseline >>|t
2|limpieza tablas muertas|t
3|catalogos|t
4|contexto estados duo|t
```

## 3. Ruta nueva con usuario de prueba (07-rutas.txt)

- Registro HTTP **201**. Por el hallazgo conocido (Keycloak 26 exige `firstName`+`lastName`
  y `emailVerified=true` para login), se completó el perfil vía API admin del Keycloak
  central antes de loguear. Login OK → token de 1391 caracteres (recortado en
  `06-token.txt`).
- **Nota de infraestructura**: el registro falló 502 al inicio porque el secret del cliente
  `paktay-auth-service` en el Keycloak central no coincidía con el del `.env`, y el service
  account no tenía roles (`manage-users`, `view-users`, `query-users`, `view-realm`). Se
  alinearon vía `kcadm` del contenedor central (secret + roles), replicando lo que hace
  `keycloak-init` (34). Detalle sin secretos en `25b-keycloak-secret.log`.

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

El fresh también se levantó SIN el Keycloak duplicado (same compañers central). El primer
intento quedó "Created" porque `keycloak-init` intentaba loguear al Keycloak central con la
clave del `.env` (rechazada); se re-ejecutó exportando la clave admin del central solo en el
proceso del compose (no queda en logs) y el stack quedó sano. Migraciones:

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

Fresh derribado con `down -v`. `paktay-local` se restauró al final (auth/business 200) y
el Keycloak central quedó corriendo, como pide el arreglo.

## 5. Front: rama del día 3 (feature/dia3-contexto-sesion)

| Chequeo | Resultado |
|---|---|
| `npm ci` | exit=0 |
| `tsc --noEmit` | exit=0, sin errores |
| Jest | **51 suites PASS, 537 tests PASS, 0 fallos** |
| `lint` | exit=0, **0 errores / 99 warnings** |

Nota de entorno: en esta Mac el único node del sistema es el runtime embebido de Raycast
(v22.22.2) y npm vive en `/opt/homebrew/lib/node_modules/npm`; se usaron wrappers locales
en `nodebin/` (del run) para invocar `npm`/`npx` con ese node. Sin cambios al código.

## Hallazgo de la corrida anterior (reconfirmado al pasar al Keycloak central)

El registro sigue creando el usuario sin `lastName` y con `emailVerified=false`, y el
Keycloak 26 rechaza el login hasta completar el perfil (vía API admin). Adicionalmente el
Keycloak central requería alinear el secret del cliente y roles del service account
(`keycloak-init` ya no corre contra el duplicado). Recomendación: incluir `lastName` en el
registro o marcar el perfil completo al crear el usuario, y sincronizar el realm central de
forma idempotente.