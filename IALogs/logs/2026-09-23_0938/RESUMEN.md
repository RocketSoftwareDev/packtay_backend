# Verificación de develop — 2026-09-23_0938

Backend evaluado: `3065924b95b5d12e9eb580d8f6b099fdb0114b37`. Frontend evaluado: `bcd5f0fed43dccbe9e5f128350236dba413075c2`.

Se siguió `IALogs/instrucciones/verificacion-develop.md`, sin corregir código ni cambiar main. Los clones están dentro de `Paktay/`; se preservaron los repositorios hermanos y sus cambios locales.

Se reemplazó la copia inicial del .env por configuración exclusivamente local y credenciales nuevas antes de ejecutar pruebas. Supabase y Cloudflare están deshabilitados en ese entorno; SMTP apunta a loopback. No se publican archivos .env ni secretos.

Docker Desktop estaba apagado y se abrió para la verificación. Al abrirlo se observaron contenedores del proyecto paktay-prod en ejecución; ninguna operación Compose de esta verificación se dirigió a ese proyecto. Todos los comandos Compose usaron explícitamente `-p paktay-local`. El cierre eliminó contenedores preexistentes de paktay-local y su red, conservando volúmenes.

El comando de arranque de la guía falló por servicios ausentes. No se añadió un Compose alternativo ni se usó Keycloak de producción. Por ese bloqueo no se pudo validar la integración con base de datos y Keycloak locales.

Node 22.22.2 y npm instalados se hicieron accesibles con un PATH temporal. Maven se ejecutó adicionalmente mediante bash, manteniendo intacto el permiso registrado en Git. Bundler se configuró con BUNDLE_PATH al vendor/bundle local. Cada log contiene stdout/stderr y códigos de salida; no se ocultaron los fallos de los comandos originales.

| Paso | Estado | Resultado |
|---|---|---|
| 00 Preparación | OK | Ambos repositorios actualizados en develop; cinco variables requeridas presentes. Entorno de pruebas exclusivamente local. |
| 01 ./mvnw -B clean package | FALLO | El archivo viene con modo Git 100644, sin bit de ejecución (exit 126). |
| 01 adicional: bash ./mvnw -B clean package | OK | BUILD SUCCESS; 16 tests auth-svc, 0 fallos, 0 errores. business-svc no contiene tests. |
| 02 Docker up | FALLO | no such service: keycloak-db. Tampoco está definido keycloak en los dos Compose de la guía. |
| 03 Health / OpenAPI / Swagger | FALLO | Las seis consultas locales devuelven HTTP 000 y curl exit 7; APIs no iniciadas por el fallo del paso 02. |
| 04 Rutas publicadas | FALLO | Sin documento OpenAPI local; no se pudo verificar la ausencia de shortcut, movements, unregistered y profile/automatic. |
| 05 Logs y cierre | OK | Logs recogidos y down de paktay-local completado sin -v. Los logs recogidos son históricos del 2026-09-10, no de un arranque exitoso de esta verificación. |
| 06 npm ci | OK | Dependencias instaladas; conservar las advertencias de npm en el log. |
| 07 npm run env | OK | Configuración local generada para puertos 28081, 28082 y 28180. |
| 08 TypeScript | FALLO | TS2448 y TS2454: isUnknownCard se usa antes de declararse en ReviewQueueScreen.tsx:203. |
| 09 Jest | FALLO | 44 suites OK y 4 fallidas; 491 tests OK y 28 fallidos (519 total). |
| 10 Lint | OK | 0 errores, 97 advertencias; exit 0. |
| 11 Bundler / Pods | FALLO | Bundler 1.17.2 fijado en Gemfile.lock falla con Ruby 4: undefined method untaint. pod install no se ejecuta por la condición && de la guía. |
| 12 Build iOS app y widget | FALLO | xcodebuild exit 65 / BUILD FAILED. Faltan Pods y Xcode reporta incompatibilidades CoreDevice/CoreSimulator. App y widget no quedan verificados. |

## Detalles de Jest

Fallan `ReviewQueueForms.test.tsx`, `MovementScreens.test.tsx`, `ProfileScreen.test.tsx` y `App.test.tsx`. Además del TypeError de isUnknownCard, hay discrepancias de textos esperados: “No hay consumos por revisar”, “Día 1”, “Pago sin tarjeta” y “Supermaxi”. El log completo también registra mensajes asíncronos posteriores al fin de tests.

## Primeras 20 líneas por fallo

### 01 Permiso del wrapper

Archivo: `01-backend-mvn.log`.

```text
[Errno 13] Permission denied: './mvnw'

exit=126
```

### 02 Arranque local

Archivo: `02-backend-up.log`.

```text
no such service: keycloak-db

exit=1
$ docker compose -p paktay-local --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml ps
NAME      IMAGE     COMMAND   SERVICE   CREATED   STATUS    PORTS

exit=0
```

### 03 Endpoints locales

Archivo: `03-backend-health.log`.

```text
curl: (7) Failed to connect to localhost port 28081 after 0 ms: Couldn't connect to server
HTTP 000

exit=7
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
' http://localhost:28081/v3/api-docs
curl: (7) Failed to connect to localhost port 28081 after 0 ms: Couldn't connect to server
HTTP 000

exit=7
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
' http://localhost:28081/swagger-ui/index.html
curl: (7) Failed to connect to localhost port 28081 after 0 ms: Couldn't connect to server
HTTP 000

exit=7
$ curl -sS --max-time 15 -o /dev/null -w 'HTTP %{http_code}
' http://localhost:28082/actuator/health
curl: (7) Failed to connect to localhost port 28082 after 0 ms: Couldn't connect to server
HTTP 000
```

### 04 Rutas

Archivo: `04-backend-rutas.log`.

```text
curl: (7) Failed to connect to localhost port 28082 after 0 ms: Couldn't connect to server

exit=1
```

### 08 TypeScript

Archivo: `08-front-tsc.log`.

```text
src/features/wallet/screens/ReviewQueueScreen.tsx(203,24): error TS2448: Block-scoped variable 'isUnknownCard' used before its declaration.
src/features/wallet/screens/ReviewQueueScreen.tsx(203,24): error TS2454: Variable 'isUnknownCard' is used before being assigned.

exit=2
```

### 09 Jest

Archivo: `09-front-jest.log`.

```text
FAIL __tests__/ReviewQueueForms.test.tsx
  ● formulario 3 · el usuario no tiene con qué registrar › sin tarjetas ni categorías celebra el atajo y pide los dos pasos

    TypeError: isUnknownCard is not a function

      201 |   const missingCount =
      202 |     (isDataMissing ? 1 : 0) +
    > 203 |     (focus !== null && isUnknownCard(focus) ? 1 : 0) +
          |                        ^
      204 |     (resolvedCategory === null ? 1 : 0);
      205 |
      206 |   const canSave =

      at isUnknownCard (src/features/wallet/screens/ReviewQueueScreen.tsx:203:24)
      at Object.react_stack_bottom_frame (node_modules/react-test-renderer/cjs/react-test-renderer.development.js:15670:20)
      at renderWithHooks (node_modules/react-test-renderer/cjs/react-test-renderer.development.js:4863:22)
      at updateFunctionComponent (node_modules/react-test-renderer/cjs/react-test-renderer.development.js:7018:19)
      at beginWork (node_modules/react-test-renderer/cjs/react-test-renderer.development.js:8499:18)
      at runWithFiberInDEV (node_modules/react-test-renderer/cjs/react-test-renderer.development.js:2315:13)
      at performUnitOfWork (node_modules/react-test-renderer/cjs/react-test-renderer.development.js:13224:22)
```

### 11 Bundler

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
  Expected in:     <BF770DE4-8425-37CA-99BB-029235010A99> /Library/Developer/PrivateFrameworks/CoreDevice.framework/Versions/A/CoreDevice, NSLocalizedDescription=Loading a plug-in failed., NSFilePath=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework, NSLocalizedFailureReason=The plug-in “com.apple.dt.DVTCoreDeviceCore” at path “/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework” could not be loaded.  The plug-in or one of its prerequisite plug-ins may be missing or damaged., NSUnderlyingError=0xcb521e010 {Error Domain=NSCocoaErrorDomain Code=3588 "dlopen(/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, 0x0109): Symbol not found: _$s10CoreDevice17DetailedOperationC7metricsSDySSAA12CodableValueOGvg
  Referenced from: <E47C0F61-0399-382B-A241-7222F33F774E> /Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/Versions/A/DVTCoreDeviceCore
  Expected in:     <BF770DE4-8425-37CA-99BB-029235010A99> /Library/Developer/PrivateFrameworks/CoreDevice.framework/Versions/A/CoreDevice" UserInfo={NSLocalizedFailureReason=The bundle couldn’t be loaded., NSLocalizedRecoverySuggestion=Try reinstalling the bundle., NSFilePath=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, NSDebugDescription=dlopen(/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/DVTCoreDeviceCore, 0x0109): Symbol not found: _$s10CoreDevice17DetailedOperationC7metricsSDySSAA12CodableValueOGvg
  Referenced from: <E47C0F61-0399-382B-A241-7222F33F774E> /Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework/Versions/A/DVTCoreDeviceCore
  Expected in:     <BF770DE4-8425-37CA-99BB-029235010A99> /Library/Developer/PrivateFrameworks/CoreDevice.framework/Versions/A/CoreDevice, NSBundlePath=/Applications/Xcode.app/Contents/SharedFrameworks/DVTCoreDeviceCore.framework, NSLocalizedDescription=The bundle “DVTCoreDeviceCore” couldn’t be loaded.}}}
Object:   <DVTDevice>
Method:   +_knownDeviceLocators
Thread:   <_NSMainThread: 0x1012a6760>{number = 1, name = main}
Please file a bug at https://feedbackassistant.apple.com with this warning message and any useful information you can provide.
Command line invocation:
    /Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild -workspace FinanceApp.xcworkspace -scheme FinanceApp -configuration Debug -sdk iphonesimulator -destination "generic/platform=iOS Simulator" build

Build settings from command line:
    SDKROOT = iphonesimulator27.0

2026-09-23 09:46:23.347 xcodebuild[22562:68354]  DVTErrorPresenter: Unable to load simulator devices.
Domain: DVTCoreSimulatorAdditionsErrorDomain
Code: 3
```

El log completo de Xcode es menor de 5 MB y se conserva junto al extracto. Solo se incluyen archivos de esta carpeta IALogs/logs en el commit.
