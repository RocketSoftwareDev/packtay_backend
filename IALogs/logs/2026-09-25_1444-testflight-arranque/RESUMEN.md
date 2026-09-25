# Arranque TestFlight — 2026-09-25_1444-testflight-arranque

- **Develop sincronizado:** backend `35c188d`; frontend `e428658`. `.env.local` del backend permanece ignorado y `docker compose --env-file .env.local config -q` terminó con exit 0.
- **Estado observado, solo lectura:** el contenedor etiquetado `paktay-prod` declara como directorio fuente el checkout backend de esta máquina; ese checkout está en `develop` (`35c188d`). Flyway informa V1–V8 exitosas y ambos health endpoints responden HTTP 200. No se modificó producción.
- **Build viejo (2026092503, `0020541`):** Release de simulador compiló. Cerró durante el arranque. FirebaseCore avisó que no había FirebaseApp por defecto; la excepción fatal fue `NSInvalidArgumentException` porque `AppDelegate` no responde a `window`. Primer frame de app: `RNFBMessagingNSNotificationCenter.application_onDidFinishLaunchingNotification`.
- **Build nuevo (2026092503, `e428658`):** Release compiló y el `.app` contiene `GoogleService-Info.plist`. La app volvió a cerrarse antes de 30 s con la misma excepción `AppDelegate.window`; el cambio que solo llama `FirebaseApp.configure()` no basta. FirebaseCore aún emitió el aviso de app por defecto no configurada.
- **Detalle del simulador:** el bundle ID de Release Simulator es `org.reactjs.native.example.FinanceApp`, mientras que el plist Firebase está registrado para `com.rocketsoftwarecore.paktay.mobile`. El aviso de Firebase puede estar influido por esa diferencia; el crash fatal `AppDelegate.window` se reproduce igual en los dos builds.
- **Puerta:** FALLA. No se ejecutaron tsc/Jest ni se generó/subió otro IPA. Hace falta corregir el acceso `AppDelegate.window` usado por RNFirebase Messaging y repetir esta prueba.

Los logs guardados no incluyen tokens ni secretos. La captura de pantalla permanece en `/tmp/arranque-nuevo.png` y no se versiona.
