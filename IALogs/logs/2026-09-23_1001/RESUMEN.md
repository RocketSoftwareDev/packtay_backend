# Reverificación de develop — 2026-09-23_1001

Backend: `b1a802286b520f6ded96d75bf46e7de9b19dbcfc`. Frontend: `30b4a4d9430feb5b142b7522070ff3319bab32ad`.

Se ejecutó la guía actualizada de `IALogs/instrucciones/verificacion-develop.md` sin corregir código, probando únicamente develop. Se preservaron main, los repositorios hermanos y los volúmenes Docker. Solo se publican los logs de esta ejecución.

## Comparación con la ejecución anterior

El permiso de mvnw y los servicios Keycloak locales están corregidos. TypeScript pasa. Jest pasa de 491 a 508 tests correctos y de 28 a 11 fallidos. Pods ya se instala mediante la alternativa documentada; la compilación iOS sigue fallando.

## Entorno local y ajustes de preparación

Solo se usó el proyecto Docker `paktay-local`, con `docker-compose.yml`, `docker-compose.local.yml` y `docker-compose.local-keycloak.yml`. Keycloak local se publicó en 28180 y la base business en 25433. Todas las consultas HTTP se hicieron a localhost:28081/28082. Supabase y el túnel están deshabilitados en el .env de pruebas. No se ejecutaron operaciones Compose contra producción.

El volumen business-data conservado tenía una contraseña preexistente, distinta de la generada durante la preparación anterior. Esto produjo inicialmente errores de autenticación PostgreSQL. Se comprobó la credencial de la configuración previa exclusivamente contra `paktay-local-business-db-1` (SELECT 1) y se actualizó solo BUSINESS_DB_PASSWORD del .env ignorado de pruebas. No se cambió la contraseña almacenada en PostgreSQL ni se borró el volumen. Tras recrear los servicios locales con esa configuración, business-svc respondió salud 200. El incidente y su resolución se conservan en `02-backend-diagnostico.log` y `02-backend-ajuste-entorno.log`.

El fallo 503 de auth-svc es del entorno de correo: SMTP_HOST apunta a 127.0.0.1 y puerto 1025, sin servidor SMTP en ese contenedor. Los logs muestran MailHealthIndicator y Connection refused. No se usó el SMTP de producción; este resultado no demuestra una regresión de autenticación ni de Keycloak.

Node/npm se hicieron accesibles mediante PATH temporal y se usó Java 17. Bundler usó vendor/bundle local. CocoaPods modificó automáticamente tres archivos versionados; se preservó el diff en `11-ios-cambios-generados.log` y se restauraron exactamente al HEAD tras la prueba. El código de ambos clones queda intacto.

## Resultados

| Paso | Estado | Evidencia |
|---|---|---|
| 00 Preparación | OK | develop actualizado en ambos repositorios; variables obligatorias presentes; entorno exclusivamente local. |
| 01 Maven | OK | Comando original ./mvnw: BUILD SUCCESS. 16 tests, 0 fallos y 0 errores. business-svc no tiene tests. |
| 02 Docker local | OK | Arranque con los tres Compose de la guía: Keycloak y ambas bases sanos; keycloak-init exit 0; APIs iniciadas. Se corrigió la configuración de credencial local del volumen existente, sin modificar la base. |
| 03 Salud / OpenAPI / Swagger | FALLO | 5 de 6 endpoints HTTP 200. auth-svc /actuator/health da 503 por SMTP local ausente. business-svc /actuator/health da 200; OpenAPI y Swagger dan 200 en ambos. |
| 04 Rutas | OK | 21 rutas publicadas; ninguna contiene shortcut, movements, unregistered o profile/automatic. |
| 05 Logs y cierre | OK | Logs recogidos; down únicamente de paktay-local, sin -v y conservando datos. |
| 06 npm ci | OK | Dependencias instaladas; ver advertencias de npm en el log. |
| 07 npm run env | OK | Entorno local generado; puertos 28081, 28082 y 28180. |
| 08 TypeScript | OK | npx tsc --noEmit: exit 0. Desaparecen TS2448/TS2454. |
| 09 Jest | FALLO | 508 tests OK, 11 fallidos; 46 suites OK, 2 fallidas (519 tests / 48 suites). |
| 10 Lint | OK | 0 errores y 97 advertencias; exit 0. |
| 11 Pods | OK | Bundler falla por untaint con Ruby 4; la alternativa pod install de la guía pasa (82 Pods instalados). |
| 12 Build iOS app y widget | FALLO | BUILD FAILED / exit 65. CoreSimulator desactualizado y targets de Pods 13.0/12.4 incompatibles con el SDK instalado, que exige 15.0 o superior. App y widget no quedan verificados. |

## Tests que siguen fallando

`ReviewQueueForms.test.tsx`: 1 fallo, el test espera el botón “Guardar gasto” tras elegir una categoría y no lo encuentra.

`MovementScreens.test.tsx`: 10 fallos en ReviewQueueScreen; expectativas de textos y controles (“Monto 11,99”, “Otra”, “Guardar”, “Ignorar”, entre otros) no coinciden con el formulario que se renderiza. Ver stdout completo en `09-front-jest.log`. Los errores de isUnknownCard ya no aparecen.

## Acción manual de iOS

La guía indica ejecutar manualmente `sudo xcodebuild -runFirstLaunch` cuando aparezca “CoreSimulator is out of date”. No se ejecutó sudo. Xcode informa CoreSimulator 1051.55.0 frente a 1171.7.0 esperado, además de un error del plugin CoreDevice. También deben revisarse los deployment targets de `AsyncStorage-AsyncStorage_resources` (13.0) y `RNSVG-RNSVGFilters` (12.4); el SDK instalado admite 15.0–27.0.x. No se modificaron estos targets durante la verificación.

## Primeras 20 líneas de cada fallo o incidencia recuperada

### 03 Salud auth-svc

Archivo: `03-backend-health.log`.

```text
' http://localhost:28081/actuator/health
HTTP 503

exit=0
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
' http://localhost:28081/v3/api-docs
HTTP 200

exit=0
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
' http://localhost:28081/swagger-ui/index.html
HTTP 200

exit=0
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
' http://localhost:28082/actuator/health
HTTP 200

exit=0
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
```

### 03 Causa SMTP local

Archivo: `05-backend-docker-logs.log`.

```text
auth-svc-1       | 2026-09-23T15:04:55.246Z  WARN 1 --- [paktay-auth-svc] [nio-8081-exec-1] o.s.b.actuate.mail.MailHealthIndicator   : Mail health check failed
auth-svc-1       | 
auth-svc-1       | org.eclipse.angus.mail.util.MailConnectException: Couldn't connect to host, port: 127.0.0.1, 1025; timeout 5000
auth-svc-1       | 	at org.eclipse.angus.mail.smtp.SMTPTransport.openServer(SMTPTransport.java:2243) ~[jakarta.mail-2.0.3.jar!/:na]
auth-svc-1       | 	at org.eclipse.angus.mail.smtp.SMTPTransport.protocolConnect(SMTPTransport.java:729) ~[jakarta.mail-2.0.3.jar!/:na]
auth-svc-1       | 	at jakarta.mail.Service.connect(Service.java:345) ~[jakarta.mail-2.0.3.jar!/:na]
auth-svc-1       | 	at org.springframework.mail.javamail.JavaMailSenderImpl.connectTransport(JavaMailSenderImpl.java:480) ~[spring-context-support-6.2.6.jar!/:6.2.6]
auth-svc-1       | 	at org.springframework.mail.javamail.JavaMailSenderImpl.testConnection(JavaMailSenderImpl.java:360) ~[spring-context-support-6.2.6.jar!/:6.2.6]
auth-svc-1       | 	at org.springframework.boot.actuate.mail.MailHealthIndicator.doHealthCheck(MailHealthIndicator.java:52) ~[spring-boot-actuator-3.4.5.jar!/:3.4.5]
auth-svc-1       | 	at org.springframework.boot.actuate.health.AbstractHealthIndicator.health(AbstractHealthIndicator.java:82) ~[spring-boot-actuator-3.4.5.jar!/:3.4.5]
auth-svc-1       | 	at org.springframework.boot.actuate.health.HealthIndicator.getHealth(HealthIndicator.java:37) ~[spring-boot-actuator-3.4.5.jar!/:3.4.5]
auth-svc-1       | 	at org.springframework.boot.actuate.health.HealthEndpointWebExtension.getHealth(HealthEndpointWebExtension.java:94) ~[spring-boot-actuator-3.4.5.jar!/:3.4.5]
business-svc-1   | 2026-09-23T15:04:42.601Z  INFO 1 --- [paktay-business-svc] [           main] w.s.c.ServletWebServerApplicationContext : Root WebApplicationContext: initialization completed in 926 ms
business-svc-1   | 2026-09-23T15:04:43.142Z  INFO 1 --- [paktay-business-svc] [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 2 endpoints beneath base path '/actuator'
business-svc-1   | 2026-09-23T15:04:43.466Z  INFO 1 --- [paktay-business-svc] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8082 (http) with context path '/'
business-svc-1   | 2026-09-23T15:04:43.478Z  INFO 1 --- [paktay-business-svc] [           main] ec.paktay.business.BusinessApplication   : Started BusinessApplication in 2.082 seconds (process running for 2.321)
business-svc-1   | 2026-09-23T15:04:43.501Z DEBUG 1 --- [paktay-business-svc] [           main] o.s.jdbc.core.JdbcTemplate               : Executing prepared SQL query
auth-svc-1       | 	at org.springframework.boot.actuate.health.HealthEndpointWebExtension.getHealth(HealthEndpointWebExtension.java:47) ~[spring-boot-actuator-3.4.5.jar!/:3.4.5]
business-svc-1   | 2026-09-23T15:04:43.502Z DEBUG 1 --- [paktay-business-svc] [           main] o.s.jdbc.core.JdbcTemplate               : Executing prepared SQL statement [select current_database() as database_name, current_user as database_user]
auth-svc-1       | 	at org.springframework.boot.actuate.health.HealthEndpointSupport.getLoggedHealth(HealthEndpointSupport.java:172) ~[spring-boot-actuator-3.4.5.jar!/:3.4.5]
```

### 09 Jest

Archivo: `09-front-jest.log`.

```text
FAIL __tests__/ReviewQueueForms.test.tsx
  ● Console

    console.error
      An update to ReviewQueueScreen inside a test was not wrapped in act(...).
      
      When testing, code that causes React state updates should be wrapped into act(...):
      
      act(() => {
        /* fire events that update state */
      });
      /* assert on the output */
      
      This ensures that you're testing the behavior the user would see in the browser. Learn more at https://react.dev/link/wrap-tests-with-act

      225 |     // otro comercio, y heredar la categoría del anterior es justo el error que la
      226 |     // pantalla existe para evitar.
    > 227 |     setError(null);
          |     ^
      228 |     setFocusId(null);
```

### 11 Bundler (recuperado por pod directo)

Archivo: `11-ios-pods.log`.

```text
/Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/shared_helpers.rb:272:in 'Bundler::SharedHelpers#search_up': undefined method 'untaint' for an instance of String (NoMethodError)

      current  = File.expand_path(SharedHelpers.pwd).untaint
                                                    ^^^^^^^^
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/shared_helpers.rb:259:in 'Bundler::SharedHelpers#find_file'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/shared_helpers.rb:251:in 'Bundler::SharedHelpers#find_gemfile'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/shared_helpers.rb:27:in 'Bundler::SharedHelpers#root'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler.rb:234:in 'Bundler.root'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler.rb:246:in 'Bundler.app_config_path'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler.rb:273:in 'Bundler.settings'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/feature_flag.rb:21:in 'block in Bundler::FeatureFlag#settings_method'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/cli.rb:97:in '<class:CLI>'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/cli.rb:7:in '<module:Bundler>'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/cli.rb:6:in '<top (required)>'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/exe/bundle:23:in 'Kernel#require'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/exe/bundle:23:in 'block in <top (required)>'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/lib/bundler/friendly_errors.rb:124:in 'Bundler.with_friendly_errors'
	from /Users/macmalona/Desktop/Proyecto/paktay/Paktay/packtay_mobile_front/vendor/bundle/ruby/4.0.0/gems/bundler-1.17.2/exe/bundle:22:in '<top (required)>'
	from /opt/homebrew/Cellar/ruby/4.0.6_1/lib/ruby/4.0.0/rubygems.rb:305:in 'Kernel#load'
	from /opt/homebrew/Cellar/ruby/4.0.6_1/lib/ruby/4.0.0/rubygems.rb:305:in 'Gem.activate_and_load_bin_path'
```

### 12 Xcode

Archivo: `12-ios-build-full.log`.

```text
Details:  No locator class for device extension 'Xcode.Device.CoreDevice', error: Error Domain=DVTPlugInErrorDomain Code=2 "Loading a plug-in failed." UserInfo={DVTPlugInIdentifierErrorKey=com.apple.dt.DVTCoreDeviceCore, DVTPlugInExecutablePathErrorKey=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, NSLocalizedRecoverySuggestion=The plug-in or one of its prerequisite plug-ins may be missing or damaged and may need to be reinstalled., DVTPlugInDYLDErrorMessageErrorKey=dlopen(/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, 0x0000): Symbol not found: _$s10CoreDevice17DetailedOperationC7metricsSDySSAA12CodableValueOGvg
  Referenced from: <E47C0F61-0399-382B-A241-7222F33F774E> /Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/Versions/A/DVTCoreDeviceCore
  Expected in:     <BF770DE4-8425-37CA-99BB-029235010A99> /Library/Developer/PrivateFrameworks/CoreDevice.framework/Versions/A/CoreDevice, NSLocalizedDescription=Loading a plug-in failed., NSFilePath=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework, NSLocalizedFailureReason=The plug-in “com.apple.dt.DVTCoreDeviceCore” at path “/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework” could not be loaded.  The plug-in or one of its prerequisite plug-ins may be missing or damaged., NSUnderlyingError=0xb5121d980 {Error Domain=NSCocoaErrorDomain Code=3588 "dlopen(/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, 0x0109): Symbol not found: _$s10CoreDevice17DetailedOperationC7metricsSDySSAA12CodableValueOGvg
  Referenced from: <E47C0F61-0399-382B-A241-7222F33F774E> /Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/Versions/A/DVTCoreDeviceCore
  Expected in:     <BF770DE4-8425-37CA-99BB-029235010A99> /Library/Developer/PrivateFrameworks/CoreDevice.framework/Versions/A/CoreDevice" UserInfo={NSLocalizedFailureReason=The bundle couldn’t be loaded., NSLocalizedRecoverySuggestion=Try reinstalling the bundle., NSFilePath=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, NSDebugDescription=dlopen(/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, 0x0109): Symbol not found: _$s10CoreDevice17DetailedOperationC7metricsSDySSAA12CodableValueOGvg
  Referenced from: <E47C0F61-0399-382B-A241-7222F33F774E> /Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/Versions/A/DVTCoreDeviceCore
  Expected in:     <BF770DE4-8425-37CA-99BB-029235010A99> /Library/Developer/PrivateFrameworks/CoreDevice.framework/Versions/A/CoreDevice, NSBundlePath=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework, NSLocalizedDescription=The bundle “DVTCoreDeviceCore” couldn’t be loaded.}}}
Object:   <DVTDevice>
Method:   +_knownDeviceLocators
Thread:   <_NSMainThread: 0x10110a760>{number = 1, name = main}
Please file a bug at https://feedbackassistant.apple.com with this warning message and any useful information you can provide.
2026-09-23 10:06:38.749 xcodebuild[48209:126747]  DVTErrorPresenter: Unable to load simulator devices.
Domain: DVTCoreSimulatorAdditionsErrorDomain
Code: 3
Failure Reason: The version of the CoreSimulator framework installed on this Mac is out-of-date and not supported by this version of Xcode.
Recovery Suggestion: Please ensure that you have installed all available updates to your Mac's software, and that you are running the most recent version of Xcode supported by macOS.
--
CoreSimulator is out of date. Current version (1051.55.0) is older than build version (1171.7.0).
Domain: DVTCoreSimulatorAdditionsErrorDomain
Code: 3
```

### 02 Credencial del volumen local (resuelto)

Archivo: `02-backend-diagnostico.log`.

```text
business-db-1  | 2026-09-23 15:03:02.243 UTC [62] FATAL:  password authentication failed for user "paktay"
business-db-1  | 2026-09-23 15:03:02.243 UTC [62] DETAIL:  Connection matched file "/var/lib/postgresql/data/pg_hba.conf" line 128: "host all all all scram-sha-256"
business-db-1  | 2026-09-23 15:03:02.621 UTC [63] FATAL:  password authentication failed for user "paktay"
auth-svc-1     | 	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:439) ~[spring-boot-3.4.5.jar!/:3.4.5]
auth-svc-1      | 	at org.springframework.boot.SpringApplication.run(SpringApplication.java:318) ~[spring-boot-3.4.5.jar!/:3.4.5]
business-svc-1  | 	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:54) ~[postgresql-42.7.5.jar!/:42.7.5]
business-db-1  | 2026-09-23 15:03:02.621 UTC [63] DETAIL:  Connection matched file "/var/lib/postgresql/data/pg_hba.conf" line 128: "host all all all scram-sha-256"
business-db-1   | 2026-09-23 15:03:05.455 UTC [64] FATAL:  password authentication failed for user "paktay"
business-svc-1  | 	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:273) ~[postgresql-42.7.5.jar!/:42.7.5]
business-db-1   | 2026-09-23 15:03:05.455 UTC [64] DETAIL:  Connection matched file "/var/lib/postgresql/data/pg_hba.conf" line 128: "host all all all scram-sha-256"
auth-svc-1      | 	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1362) ~[spring-boot-3.4.5.jar!/:3.4.5]
business-db-1   | 2026-09-23 15:03:06.264 UTC [65] FATAL:  password authentication failed for user "paktay"
auth-svc-1      | 	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1351) ~[spring-boot-3.4.5.jar!/:3.4.5]
business-svc-1  | 	at org.postgresql.Driver.makeConnection(Driver.java:446) ~[postgresql-42.7.5.jar!/:42.7.5]
auth-svc-1      | 	at ec.paktay.auth.AuthApplication.main(AuthApplication.java:9) ~[!/:0.1.0-SNAPSHOT]
auth-svc-1      | 	at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[na:na]
auth-svc-1      | 	at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(Unknown Source) ~[na:na]
business-db-1   | 2026-09-23 15:03:06.264 UTC [65] DETAIL:  Connection matched file "/var/lib/postgresql/data/pg_hba.conf" line 128: "host all all all scram-sha-256"
business-db-1   | 2026-09-23 15:03:08.508 UTC [73] FATAL:  password authentication failed for user "paktay"
business-db-1   | 2026-09-23 15:03:08.508 UTC [73] DETAIL:  Connection matched file "/var/lib/postgresql/data/pg_hba.conf" line 128: "host all all all scram-sha-256"
```

Se conserva el log completo de Xcode porque pesa menos de 5 MB. Los logs guardan stdout/stderr y códigos de salida; para HTTP se evalúa el estado HTTP, no solo el exit de curl.
