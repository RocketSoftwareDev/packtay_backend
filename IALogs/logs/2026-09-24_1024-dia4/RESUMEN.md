# RESUMEN - Día 4: historial desde el servidor, editar y anular (2026-09-24_1024-dia4)

Ramas probadas: backend `feature/dia4-gastos-servidor` (`9b37c5c`), front
`feature/dia4-gastos-servidor` (`6178571`). Sin cambios de código. Keycloak central
(`keycloakservices-local` en 28180) usado como identidad; tras un reinicio del daemon de
Docker Desktop, el Keycloak central había quedado detenido y se relanzó (contenedores
existentes, sin tocar secretos ni roles).

## 1. Backend (01-mvn.log)

```text
25 tests (21 de ExpenseCursor + 4 de ApiExceptionHandler), 0 fallos
BUILD SUCCESS
```

`auth-svc` no cambió en el día 4; `business-svc` reconstruido con el jar nuevo.
Nota: el primer `up --build` del día falló por "DeadlineExceeded" durante la descarga de
dependencias en el build (brazo Docker recién arrancado); reintentado, quedó OK.

### Base existente (03-local-history.txt)

```text
1|baseline|t
2|limpieza tablas muertas|t
3|catalogos|t
4|contexto estados duo|t
5|anulacion gastos|t
```

## 2. Rutas de gastos (04-rutas.txt)

| Línea | HTTP | Esperado |
|---|---|---|
| email / registro | 201 | OK (login previo 200) |
| alta tarjeta | 201 ACTIVE | OK |
| categorías (2 creadas) | 201/201 | OK (sin esto el script del `<  no >` fallaba; se añadieron `sortOrder`) |
| alta gasto | 201 MANUAL/EXPENSE/ACTIVE 12.5 | OK |
| reintento misma clave | 201 mismo id | OK (idempotencia) |
| lista limit=1 | 200 nextCursor | OK (1 solo gasto, null cursor) |
| lista limit=0 | **400** "limit debe estar entre 1 y 200" | OK |
| cursor inválido | **400** "El cursor no es válido" | OK |
| detalle | 200 ACTIVE | OK |
| detalle inexistente | **404** | OK |
| editar categoría y monto | 200 (cat2, 15.0, ACTIVE) | OK |
| anular | 200 VOIDED (con refund) | OK |
| anular otra vez | 200 (idempotente) | OK |
| anular el REFUND | **409** "no se puede anular" | OK |
| editar anulado | **409** "no se puede editar" | OK |
| sync since | 200 | OK |
| sync filas | REFUND/ACTIVE 15.0 y EXPENSE/VOIDED 15.0 | OK |
| presupuesto actual | 200 spent=0 | OK (el gasto se anuló) |

Ajuste al script de la guía: el usuario nuevo no trae categorías (alta manual del día 3),
así que se crearon dos categorías antes de listar, y el POST requiere `sortOrder` en
`CreateUserCategoryRequest`.

### OpenAPI, salud y errores

- Rutas en OpenAPI: `expenses`, `expenses/{id}`, `expenses/{id}/void`
  (05-openapi.txt).
- Salud/docs: 6/6 HTTP 200 (06-health.txt).
- Logs de business-svc: solo 2 WARN esperados (los 409 planificados), sin
  exceptions (07-errores.log).

### Desde cero (09-fresh-history.txt)

```text
1..5  (baseline, limpieza tablas muertas, catalogos, contexto estados duo, anulacion gastos)
```

Fresh derribado con `down -v`; `paktay-local` restaurado al final (auth 200, biz 200). El
Keycloak central siguió arriba durante la prueba.

## 3. Front (10-front-npm-ci.log, 11-front-tsc.log, 12-jest.log, 13-lint.log)

| Chequeo | Resultado |
|---|---|
| `npm ci` | exit=0 |
| `npx tsc --noEmit` | exit=0 |
| Jest | **53 suites PASS, 577 tests PASS, 0 fallos** |
| `npm run lint` | exit=0, **0 errores / 99 warnings** |

Se recrearon los wrappers de node/npm en `/tmp/nodebin` (el PATH de corridas anteriores no
persistió).

## 4. Simulador iOS (vista de los cambios)

- Simulador `iPhone 16 Pro Max - PAKTAY` booteado (iOS 26.5, UDID
  70765D58-74ED-4F85-8ED1-62CCFA39B868).
- `pod install` (con node en PATH) exit=0, 83 dependencias / 82 pods. Modificó
  `project.pbxproj`, `PaktayWidget/Info.plist` y `Podfile.lock`; se compiló con ese estado
  y después se restauraron al HEAD (working tree limpio).
- FinanceApp: **BUILD SUCCEEDED** (20-xcodebuild.log).
- App instalada y lanzada (`org.reactjs.native.example.FinanceApp`). Metro servido en
  `:8088` (como pide `AppDelegate.swift`), bundle `./index.js` cargado.
- El primer lanzamiento mostró "Connection refused" hacia 8088 (Metro aún arrancando); el
  relanzamiento conectó a Metro correctamente.
- Captura: `22-sim-dia4.png` (el que ejecuta no puede inspeccionarla visualmente; queda
  para revisión humana).

## Notas

- Make sure to revisar la captura del simulador y, si hace falta, navegar en la app; la
  sesión previa del simulador puede no existir y el flujo redirige a Login.
- El wrapper de node/npm no persiste entre arranques: recrear en `/tmp/nodebin` si se
  vuelve a tocar el front.