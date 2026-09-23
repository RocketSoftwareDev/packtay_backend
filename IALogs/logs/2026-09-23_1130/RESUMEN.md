# Verificación de develop — 2026-09-23_1130

Backend: `df6111203fc08ced0a490375bab0302f7762112d` (merge PR #10 Flyway). Frontend: `98794e7f3455dd07dbcd3b67423855452c4697d0`.

Se ejecutó la guía `IALogs/instrucciones/verificacion-develop.md` sin corregir código, probando únicamente develop. Se preservaron main, los repositorios hermanos y los volúmenes Docker. Solo se publican los logs de esta ejecución.

## Comparación con la corrida 1039

Primera verificación tras el merge de `feature/dia2-esquema-flyway` (PR #10). Ahora el esquema lo gestiona Flyway (V1 baseline + V2 limpieza + V3 catálogos) y desaparecieron los 20 SQL manuales de `database/`. La base local `paktay-local` ya tenía V1+V2 aplicados desde la corrida dia2b; al arrancar business-svc, Flyway aplicó la **V3 "catalogos"**, que pobló los catálogos (7 monedas, 29 bancos, 38 ofertas, 22 categorías) y coinciden con los 4b esperados por la guía. Todo vuelve a pasar.

## Resultados

| Paso | Estado | Evidencia |
|---|---|---|
| 00 Preparación | OK | develop actualizado en ambos repositorios; variables obligatorias presentes; entorno exclusivamente local. |
| 01 Maven | OK | Comando original ./mvnw: BUILD SUCCESS. 16 tests, 0 fallos y 0 errores. business-svc no tiene tests. |
| 02 Docker local | OK | Arranque con los tres Compose de la guía: Keycloak y ambas bases sanos; keycloak-init exit 0; APIs iniciadas. Flyway migró V3 en el arranque. |
| 03 Salud / OpenAPI / Swagger | OK | 6 de 6 endpoints HTTP 200. /actuator/health, OpenAPI y Swagger de auth-svc (28081) y business-svc (28082). |
| 04 Rutas | OK | 21 rutas publicadas; ninguna contiene shortcut, movements, unregistered o profile/automatic. |
| 04b Flyway y catálogos | OK | Historial: 1 baseline, 2 limpieza, 3 catalogos, todas success=t. Conteos: currencies 7, banks 29, bank_card_offerings 38, system_categories 22 (esperados). |
| 05 Logs y cierre | OK | Logs recogidos; down únicamente de paktay-local, sin -v y conservando datos. |
| 06 npm ci | OK | Dependencias instaladas; solo advertencia de allowScripts por fsevents. |
| 07 npm run env | OK | Entorno local generado; puertos 28081, 28082 y 28180. |
| 08 TypeScript | OK | tsc --noEmit: exit 0. |
| 09 Jest | OK | 518 tests OK, 0 fallidos; 48 suites OK. |
| 10 Lint | OK | 0 errores y 97 advertencias; exit 0. |
| 11 Pods | OK | Bundler falla por untaint con Ruby 4; la alternativa pod directo pasa (82 Pods instalados). |
| 12 Build iOS app y widget | OK | FinanceApp y PaktayWidget: BUILD SUCCEEDED en simulador. Citizen CoreSimulator ya actualizado; sin necesidad de -runFirstLaunch. |

## Notas de entorno

Node/npm se hicieron accesibles mediante PATH temporal (Node 22.22.2 de Raycast y npm.cli directo); `npx` no está en el PATH de la shell y se usaron los binarios de node_modules. No se usó Bundler. CocoaPods modificó automáticamente tres archivos versionados; se restauraron al HEAD tras la prueba. El código de ambos clones queda intacto.

`12-ios-build-full.log` superó 1 MB y se recortó a las últimas 300 líneas según la guía. Sin fallos ni incidencias que recuperar; las primeras 20 líneas de error no aplican porque no hubo ninguno.