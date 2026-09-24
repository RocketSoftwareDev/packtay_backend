# RESUMEN - Día 5: resumen del mes e Inicio único (2026-09-24_1359-dia5)

Ramas probadas: backend `feature/dia5-resumen-inicio` (`6e1931e`) y front
`feature/dia5-resumen-inicio` (`c6dd28f`). Sin cambios de código. Keycloak central
(`keycloakservices-local` en 28180) como identidad, mismos comandos `L`/`F` que el día 4.

## 1. Backend (01-mvn-resumen.txt)

```text
48 tests (23 de SummaryMath nuevos + 25 previos), 0 fallos
BUILD SUCCESS
```

`$L up --build -d` OK; auth 200, biz 200. La base local quedó en V5 (vía días previos; sin
migración nueva este día).

## 2. Escenario del resumen (03-resumen.txt)

19/19 checks OK:

- Resumen vacío: 200, budget null, pace NONE.
- Presupuesto guardado: budgetAmount 250 (global 100 + 100 + OWN 50).
- Resumen intermedio (3 gastos: 95/100, 30/100, 60/50):
  `budget=250`, `spent=185`, `percent=74` (piso).
  cat-0 AT_LIMIT (GLOBAL), cat-1 OK, cat-2 OVER con overBy 10 (OWN).
- `recent` con 3; tarjeta `ownLimit=300`; `daysLeft=7`, `elapsed=80`, `pace=OK`,
  `resetsOn=2026-10-01`.
- Tras anular C: spent=125; `budgets/current` budgetAmount 250 / spentAmount 125 / percent 50.
- `summary?month=2026-13`, `?month=abc`, `?month=2099-01` -> todos 400.
- Paginación limit=1: 4 filas, 4 ids distintos, sin repetir (4 páginas).

### OpenAPI, salud y errores

- `/api/v1/user/summary` presente en OpenAPI: `True` (04-openapi.txt).
- Salud/docs: 6/6 HTTP 200 (05-health.txt).
- `06-errores.log`: 3 WARN `request_rejected` = los 400 esperados por month inválido
  (formato / mes futuro); sin exceptions.

`$L stop` tras backend; luego `git checkout develop`.

## 3. Front (07-npm-ci.log, 08-tsc.log, 09-jest.log, 10-lint.txt)

| Chequeo | Resultado |
|---|---|
| `npm ci` | exit=0 |
| `npx tsc --noEmit` | **exit=2 — 2 errores nuevos** (ver hallazgos) |
| Jest | **53 suites PASS, 583 tests PASS, 0 fallos** |
| `npm run lint` | exit=0, 0 errores / 100 warnings |

## 4. Simulador iOS (vista de los cambios de Inicio / resumen)

- Simulador `iPhone 16 Pro Max - PAKTAY` seguía booteado (iOS 26.5).
- `pod install` exit=0 (83 deps / 82 pods); modificó `project.pbxproj`,
  `PaktayWidget/Info.plist` y `Podfile.lock`; se compiló con ese estado y luego se
  restauraron (working tree limpio, front de vuelta en develop).
- FinanceApp: **BUILD SUCCEEDED** (13-xcodebuild.log).
- App instalada y lanzada; Metro recién arrancado en `:8088` con `--reset-cache` (el Metro
  de la corrida anterior quedó con cache corrupta tras el `npm ci` del día 5: no resolvía
  `@babel/runtime` y fallaba el bundle; al reiniciarlo limpio, el bundle carga al 93% sin
  errores).
- Sin capturas subidas (la guía del día 5 prohíbe imágenes en logs); el simulador quedó
  abierto y usable con la app del día 5 instalada.

## Hallazgos (para el equipo, sin cambiar código)

1. **tsc falla con 2 errores en la rama del día 5** (08-tsc.log):
   - `__tests__/InicioScreen.test.tsx(280,13)`: TS2367 comparación `ElementType` vs
     `"View"` sin solapamiento.
   - `src/features/expenses/monthSummaryStore.ts(289,45)`: `multiRemove` no existe en el
     tipo de `AsyncStorage` (el store lo usa, probablemente se añadió el API en el día 5).
   - Afectan sólo a compilación de TS (Jest y Metro pasan porque Babel no chequearía tipos),
     pero es un door-blocker del contrato de tsc de la guía.
2. **Metro de corridas anteriores puede quedar con cache inválida** si en el medio se hace
   `npm ci`: reiniciar con `npx react-native start --reset-cache`.

## Estado final

Backend y front en `develop` (working trees limpios). `paktay-local` restaurado (auth 200,
biz 200) para dejar el simulador usable. Keycloak central arriba y sano.