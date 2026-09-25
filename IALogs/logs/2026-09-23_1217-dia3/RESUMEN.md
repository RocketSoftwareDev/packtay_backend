# RESUMEN - Día 3 backend + front (2026-09-23_1217-dia3)

Ramas probadas: backend `feature/dia3-contexto-estados` (`23f57dd`) y front
`feature/dia3-contexto-sesion` (`aa42e55`). Reejecución de la instrucción del día 3
después de traer develop (sin cambios nuevos: desarrollos ya mergeados). Se repitió el
simulador de iOS para ver la app de la rama del front cara a cara.

## 1. Compilar backend (01-mvn.log)

```text
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

## 2. Base existente (paktay-local): V4 sobre V1-V3 (03-local-flyway.log, 04-local-history.txt)

```text
1|<< Flyway Baseline >>|t
2|limpieza tablas muertas|t
3|catalogos|t
4|contexto estados duo|t
```

`up=0` con salud 200 en auth y business.

## 3. Ruta nueva con usuario de prueba (07-rutas.txt)

- Registro HTTP **201** (`05-register.json`). Por el hallazgo conocido de la corrida
  anterior (Keycloak 26 exige `firstName`+`lastName` completos para login), se completó el
  perfil del usuario vía API admin antes del login. Login OK → token de 1423 caracteres
  (recortado a 8 en `06-token.txt`).

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

`13-fresh-flyway.log` con migración secuencial V1→V4 sin errores. `down -v` ejecutado.
`paktay-prod` no se tocó. `paktay-local` se levantó después para la prueba en simulador.

## 5. Front: rama del día 3 (feature/dia3-contexto-sesion)

| Chequeo | Resultado |
|---|---|
| `npm ci` | exit=0 |
| `tsc --noEmit` | exit=0, sin errores |
| Jest | **51 suites PASS, 537 tests PASS, 0 fallos** |
| `lint` | exit=0, **0 errores / 99 warnings** |

## 6. Simulador iOS (cara a cara)

- Simulador `iPhone 16 Pro Max - PAKTAY` booteado (iOS 26.5) y ventana abierta (Xcode 27:
  la UI del simulador la maneja DeviceHub, ya abierto).
- `pod install` exit=0 (82 pods). Aviso: en esta rama, el `Podfile.lock` cambia su checksum
  al instalar (hermes/React-Core-prebuilt); para que el build no falle por "sandbox not in
  sync" se compiló con el `Podfile.lock` recién generado y después se restauró con `git
  checkout`.
- FinanceApp: **BUILD SUCCEEDED** en `Debug-iphonesimulator`.
- PaktayWidget: **BUILD SUCCEEDED**.
- App instalada en el simulador y lanzada (`org.reactjs.native.example.FinanceApp`).
- Metro servido en `:8088` (bundle `./index.js` cargado al 100%).
- Deep link `paktay://gasto?comercio=sim-demo&monto=12,50` recibido por la app
  (`UIOpenURLAction` en logs del sistema del simulador) — la sesión venció/no existe y
  navega al flujo de la rama (sin sesión → Login).
- Capturas guardadas: `23-sim-01-inicio.png`, `23-sim-02-app.png`,
  `23-sim-03-deeplink.png`. (El que ejecuta no puede inspeccionarlas visualmente — quedan
  para revisión humana.)

Nota: en Xcode 27 ya no existe `Simulator.app`; la ventana del simulador se muestra vía
`DeviceHub.app` (abierto en esta corrida).

## Hallazgo de la corrida anterior (reconfirmado, sin cambiar código)

El endpoint de registro crea el usuario con `firstName=displayName` y **sin `lastName`**
(+ `emailVerified=false`), y Keycloak 26 rechaza el login del usuario recién creado con
"Account is not fully set up" hasta completar el perfil. Se resolvió en la prueba
completando el perfil por API admin. Recomendación para el equipo: incluir `lastName` (u
ocupar displayName también como lastName) en el payload de registro o marcar el perfil
completo al crear el usuario.