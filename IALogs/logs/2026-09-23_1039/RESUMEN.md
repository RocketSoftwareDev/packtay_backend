# Reverificación de develop — 2026-09-23_1039

Backend: `163c437ab6a8193d9ed9d283bed68bb1eb814712`. Frontend: `98794e7f3455dd07dbcd3b67423855452c4697d0`.

Se ejecutó la guía actualizada de `IALogs/instrucciones/verificacion-develop.md` sin corregir código, probando únicamente develop. Se preservaron main, los repositorios hermanos y los volúmenes Docker. Solo se publican los logs de esta ejecución.

## Comparación con la ejecución anterior

La corrida 1001 fue parcial: 5 de 6 endpoints en salud, 11 tests de Jest fallidos, lint con 0 errores y 97 advertencias, build iOS fallido por CoreSimulator desactualizado (1051.55.0 frente a 1171.7.0) y deployment targets de Pods incompatibles con el SDK (fue necesario sudo xcodebuild -runFirstLaunch y no se ejecutó). En 1039 todo pasa de forma limpia: los 6 endpoints HTTP 200, Jest 518/518 en 48 suites, lint 0 errores/97 advertencias y build iOS de app y widget BUILD SUCCEEDED. El fallo de SMTP de auth-svc de la 1001 no aparece (mailpit sano y /actuator/health 200 en ambos servicios).

## Entorno local y ajustes de preparación

Solo se usó el proyecto Docker `paktay-local`, con `docker-compose.yml`, `docker-compose.local.yml` y `docker-compose.local-keycloak.yml`. Keycloak local se publicó en 28180 y la base business en 25433. Todas las consultas HTTP se hicieron a localhost:28081/28082. Supabase y el túnel están deshabilitados en el .env de pruebas. No se ejecutaron operaciones Compose contra producción.

Se conservó el .env exclusivamente local de verificaciones anteriores, sin regenerar credenciales ni imprimir valores. No fue necesario ajustar ninguna credencial: el volumen business-data aceptó la configuración existente y business-svc respondió salud 200 desde el primer arranque.

Node/npm se hicieron accesibles mediante PATH temporal apuntando a Node 22.22.2 (runtime de Raycast) y npm 11.19.0 (invocado por node). No se usó Bundler: pod install directo (la alternativa documentada de la guía). CocoaPods modificó automáticamente tres archivos versionados; se restauraron exactamente al HEAD tras la prueba. El código de ambos clones queda intacto.

## Resultados

| Paso | Estado | Evidencia |
|---|---|---|
| 00 Preparación | OK | develop actualizado en ambos repositorios; variables obligatorias presentes; entorno exclusivamente local. |
| 01 Maven | OK | Comando original ./mvnw: BUILD SUCCESS. Sin fallos. |
| 02 Docker local | OK | Arranque con los tres Compose de la guía: Keycloak y ambas bases sanos; keycloak-init exit 0; APIs iniciadas y health UP por wait loop. |
| 03 Salud / OpenAPI / Swagger | OK | 6 de 6 endpoints HTTP 200. /actuator/health, OpenAPI y Swagger de auth-svc (28081) y business-svc (28082). |
| 04 Rutas | OK | 21 rutas publicadas; ninguna contiene shortcut, movements, unregistered o profile/automatic. |
| 05 Logs y cierre | OK | Logs recogidos; down únicamente de paktay-local, sin -v y conservando datos. |
| 06 npm ci | OK | Dependencias instaladas; solo advertencia de allowScripts por fsevents. |
| 07 npm run env | OK | Entorno local generado; puertos 28081, 28082 y 28180. |
| 08 TypeScript | OK | npx tsc --noEmit: exit 0. |
| 09 Jest | OK | 518 tests OK, 0 fallidos; 48 suites OK (519 tests / 48 suites en referencia 1001; hoy 518/48). |
| 10 Lint | OK | 0 errores y 97 advertencias; exit 0. |
| 11 Pods | OK | Bundler falla por untaint con Ruby 4; la alternativa pod install de la guía pasa (82 Pods instalados). |
| 12 Build iOS app y widget | OK | FinanceApp y PaktayWidget: BUILD SUCCEEDED en simulador genérico. CoreSimulator ya compatible. |

## Notas de entorno resueltas desde la 1001

La corrida 1001 documentó CoreSimulator desactualizado (1051.55.0 vs 1171.7.0) y el plugin CoreDevice roto. Hoy xcodebuild resuelve los dispositivos del simulador y compila sin sudo ni -runFirstLaunch. Los deployment targets truncados ya no bloquean el build.

Se conserva el log completo de Xcode porque pesa menos de 5 MB. Los logs guardan stdout/stderr y códigos de salida; para HTTP se evalúa el estado HTTP, no solo el exit de curl. No hubo fallos ni incidencias que recuperar en esta ejecución.