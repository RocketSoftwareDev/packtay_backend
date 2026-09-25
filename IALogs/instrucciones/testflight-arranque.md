# TestFlight: la app se cierra al abrir (para Codex)

> **Segunda vuelta (después de `2026-09-25_1444-testflight-arranque`):** la corrida mostró
> que el cierre real es `NSInvalidArgumentException: AppDelegate no reconoce window`, desde
> `RNFBMessagingNSNotificationCenter.application_onDidFinishLaunchingNotification`. Al
> adoptar UIScene se quitó `window` del `AppDelegate`; ya volvió (el `SceneDelegate` la
> asigna). Repite **sólo los pasos 0, 2, 3 y 4** con `develop` actualizado
> (`RUN=...-testflight-arranque2`). El aviso de Firebase «default app not yet configured»
> en el simulador no es fatal: el bundle del simulador es `org.reactjs.native.example…` y
> el plist es del bundle real; en el iPhone coincide.

El build subido a TestFlight (2026092503, commit `0020541` del front) se cierra al abrir. Causa
probable: Firebase nunca se inicializa. No hay `FirebaseApp.configure()` en `AppDelegate`, y
el módulo de Messaging (paso 7b) pide `Messaging.messaging()` al terminar el arranque; sin una
FirebaseApp configurada iOS lanza una excepción. En `develop` ya se agregó la inicialización
(`ios/FinanceApp/AppDelegate.swift`). Aquí se **confirma la causa** con el build viejo, se
**prueba el arreglo** y, si funciona, se sube un build nuevo.

Reglas: no toques `main` ni el backend de producción en esta corrida; sin secretos en los
logs; logs a `IALogs/logs/$RUN/` como siempre (en `develop` `IALogs/` vuelve a versionarse).

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-testflight-arranque
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
cd ../packtay_mobile_front
git fetch origin && git checkout develop && git pull --ff-only
```

## 0 · Estado de producción (sólo lectura)

Anota si la corrida de `testflight.md` llegó a desplegar `develop` en `paktay-prod`: rama y commit
de la carpeta desde la que corre, y el historial de Flyway de producción.

```bash
C=$(docker ps --filter label=com.docker.compose.project=paktay-prod --filter label=com.docker.compose.service=business-svc -q | head -n1)
docker inspect "$C" --format 'dir={{ index .Config.Labels "com.docker.compose.project.working_dir" }}' > "$LOGS/00-prod.txt"
DB=$(docker ps --filter label=com.docker.compose.project=paktay-prod --filter label=com.docker.compose.service=business-db -q | head -n1)
docker exec "$DB" psql -U paktay -d paktay -At -c "select version, success from flyway_schema_history order by installed_rank" >> "$LOGS/00-prod.txt" 2>&1
for u in https://paktayauth.rocketsoftwarecore.com https://paktay.rocketsoftwarecore.com; do echo "$u $(curl -s -o /dev/null -w '%{http_code}' $u/actuator/health)"; done >> "$LOGS/00-prod.txt"
```

Si en tu máquina quedaron logs de la corrida `testflight` que no se subieron (antes `IALogs/`
estaba ignorado), cópialos también a `IALogs/logs/` con su nombre original.

## 1 · Reproducir el cierre con el build viejo (Release en simulador)

```bash
git checkout 0020541
npm ci > /tmp/npm.log 2>&1; node scripts/generate-env.mjs --production > /dev/null 2>&1
(cd ios && pod install > /tmp/pod.log 2>&1)
SIM="iPhone 16"   # usa cualquier simulador disponible (xcrun simctl list devices available)
xcrun simctl boot "$SIM" 2>/dev/null; true
rm -rf /tmp/dd-viejo
xcodebuild -workspace ios/FinanceApp.xcworkspace -scheme FinanceApp -configuration Release \
  -sdk iphonesimulator -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath /tmp/dd-viejo \
  CODE_SIGNING_ALLOWED=NO build > /tmp/xcb-viejo.log 2>&1; echo "exit=$?" >> /tmp/xcb-viejo.log
grep -E "error:|BUILD SUCCEEDED|BUILD FAILED|exit=" /tmp/xcb-viejo.log | head -n 40 > "$LOGS/01-build-viejo.txt"
APP=$(find /tmp/dd-viejo/Build/Products/Release-iphonesimulator -maxdepth 1 -name "*.app" | head -n1)
xcrun simctl uninstall booted com.rocketsoftwarecore.paktay.mobile 2>/dev/null; xcrun simctl install booted "$APP"
xcrun simctl launch booted com.rocketsoftwarecore.paktay.mobile > "$LOGS/01-launch-viejo.txt" 2>&1
sleep 20
xcrun simctl spawn booted launchctl list | grep -i paktay > "$LOGS/01-vivo-viejo.txt" 2>&1 || echo "no esta corriendo" > "$LOGS/01-vivo-viejo.txt"
ls -t ~/Library/Logs/DiagnosticReports/ | grep -iE "FinanceApp|PAKTAY" | head -n 3 > "$LOGS/01-reportes.txt"
R=$(ls -t ~/Library/Logs/DiagnosticReports/ | grep -iE "FinanceApp|PAKTAY" | head -n1)
[ -n "$R" ] && head -c 20000 ~/Library/Logs/DiagnosticReports/"$R" > "$LOGS/01-cierre-viejo.txt"
xcrun simctl spawn booted log show --last 2m --style compact --predicate 'process CONTAINS "FinanceApp" OR eventMessage CONTAINS "Firebase"' 2>/dev/null | tail -n 200 > "$LOGS/01-log-viejo.txt"
```

Anota en el resumen la **razón de la excepción** del reporte (p. ej. «The default FirebaseApp
instance must be configured…»). Si el build viejo **no** se cierra en el simulador, dilo: la
causa puede ser otra (dispositivo físico, permisos, iOS del tester) y hará falta el reporte de
TestFlight (paso 4).

## 2 · Probar el arreglo (develop)

```bash
git checkout develop && git pull --ff-only
npm ci > /tmp/npm.log 2>&1; node scripts/generate-env.mjs --production > /dev/null 2>&1
(cd ios && pod install > /tmp/pod.log 2>&1)
rm -rf /tmp/dd-nuevo
xcodebuild -workspace ios/FinanceApp.xcworkspace -scheme FinanceApp -configuration Release \
  -sdk iphonesimulator -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath /tmp/dd-nuevo \
  CODE_SIGNING_ALLOWED=NO build > /tmp/xcb-nuevo.log 2>&1; echo "exit=$?" >> /tmp/xcb-nuevo.log
grep -E "error:|BUILD SUCCEEDED|BUILD FAILED|exit=" /tmp/xcb-nuevo.log | head -n 40 > "$LOGS/02-build-nuevo.txt"
APP=$(find /tmp/dd-nuevo/Build/Products/Release-iphonesimulator -maxdepth 1 -name "*.app" | head -n1)
xcrun simctl uninstall booted com.rocketsoftwarecore.paktay.mobile 2>/dev/null; xcrun simctl install booted "$APP"
xcrun simctl launch booted com.rocketsoftwarecore.paktay.mobile > "$LOGS/02-launch-nuevo.txt" 2>&1
sleep 30
xcrun simctl spawn booted launchctl list | grep -i paktay > "$LOGS/02-vivo-nuevo.txt" 2>&1 || echo "no esta corriendo" > "$LOGS/02-vivo-nuevo.txt"
xcrun simctl io booted screenshot /tmp/arranque.png >/dev/null 2>&1 && echo "captura tomada (no se sube)" >> "$LOGS/02-vivo-nuevo.txt"
npx tsc --noEmit > /tmp/tsc.log 2>&1; echo "exit=$?" >> /tmp/tsc.log; tail -n 20 /tmp/tsc.log > "$LOGS/02-tsc.txt"
npx jest --ci > /tmp/jest.log 2>&1; echo "exit=$?" >> /tmp/jest.log; grep -E "Tests:|Test Suites:|exit=" /tmp/jest.log > "$LOGS/02-jest.txt"
```

Mira tú la captura: debe verse el splash o el login, no el escritorio del simulador.

**PUERTA:** el build nuevo compila, sigue vivo a los 30 s, tsc `exit=0` y Jest sin fallos. Si
no, detente y sube los logs (incluye el reporte de cierre del build nuevo como en el paso 1).

## 3 · Build nuevo para TestFlight

Si pasó la puerta: sube `CURRENT_PROJECT_VERSION` a `2026092504` (o el siguiente libre de hoy) en
**todas** sus líneas del `project.pbxproj`, commit en `develop`
(`chore(release): build <n> con Firebase inicializado`), push, y archiva y sube igual que en la
**fase 5, paso 4** de `IALogs/instrucciones/testflight.md` (mismo `ExportOptions.plist`).
Anota el resultado de archivo y subida.

### 3b · Antes de subir: el entorno de avisos debe ser producción

El entitlements del repo dice `aps-environment = development`; al exportar para App Store
Connect, Xcode lo cambia a `production` si el perfil de distribución tiene Push. Compruébalo
**antes** de subir exportando una vez a disco con el mismo `ExportOptions.plist` pero
`destination = export`:

```bash
sed 's/<string>upload<\/string>/<string>export<\/string>/' /tmp/ExportOptions.plist > /tmp/ExportOptions-local.plist
xcodebuild -exportArchive -archivePath ~/paktay-builds/PAKTAY-$B.xcarchive -exportOptionsPlist /tmp/ExportOptions-local.plist \
  -exportPath ~/paktay-builds/ipa-$B -allowProvisioningUpdates > /tmp/export-local.log 2>&1; echo "exit=$?" >> /tmp/export-local.log
rm -rf /tmp/ipa && unzip -o -q ~/paktay-builds/ipa-$B/*.ipa -d /tmp/ipa
codesign -d --entitlements - /tmp/ipa/Payload/*.app 2>/dev/null | grep -A1 aps-environment > "$LOGS/03-aps-environment.txt"
```

Debe decir `production`. Si dice `development`, **no subas**: anótalo (hay que revisar el perfil
de distribución y la capacidad Push del identificador de la app).

## 4 · Si el cierre sigue en TestFlight

Pídele al dueño el reporte: Xcode › Window › Organizer › Crashes (app PAKTAY, build nuevo), o en
el iPhone Ajustes › Privacidad y seguridad › Análisis y mejoras › Datos de análisis › `PAKTAY…`.
Con ese texto (sin datos personales) se ubica la línea exacta.

## Resumen y subida

`RESUMEN.md`: estado de producción, razón del cierre del build viejo, si el nuevo arranca, tsc,
Jest, número de build, archivo y subida.

```bash
cd ../packtay_backend
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): arranque de TestFlight ($RUN)"
git push origin develop
```
