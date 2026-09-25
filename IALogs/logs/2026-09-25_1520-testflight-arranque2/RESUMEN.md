# Segunda corrida: arranque TestFlight

- **Develop actualizado:** backend `d0f34d6`; frontend `16ac4b6` antes del release.
- **Producción (sólo lectura):** los contenedores paktay-prod usan la carpeta local del backend, actualmente en `develop`/`d0f34d6`; Flyway V1–V8 figuran exitosas. Health de auth y business: HTTP 200. No se desplegó ni modificó producción en esta corrida.
- **Causa previa reportada:** el build anterior cerraba por `NSInvalidArgumentException: AppDelegate no reconoce window`, desde Firebase Messaging durante el arranque. El cambio de `develop` restaura `AppDelegate.window` y SceneDelegate la asigna.
- **Build nuevo probado en simulador:** Release compiló correctamente; `org.reactjs.native.example.FinanceApp` siguió vivo a los 30 s y se capturó la pantalla de creación de cuenta. El aviso Firebase de configuración por bundle ID del simulador no es fatal; el bundle de dispositivo usa el identificador real.
- **Puerta de pruebas:** TypeScript `exit=0`; Jest 62 suites y 724 pruebas, todas pasaron.
- **Build TestFlight:** `2026092504`, commit frontend `e168f54` en `develop` (push exitoso).
- **Firma/APNs:** archivo y exportación local exitosos. IPA firmada con `aps-environment = production` para `com.rocketsoftwarecore.paktay.mobile`.
- **Carga:** App Store Connect confirmó `Upload succeeded`; el paquete quedó procesándose. Xcode advirtió que faltan dSYM de React, ReactNativeDependencies y hermesvm; no impidió la carga.
- **Pendiente del dueño:** esperar a que App Store Connect termine de procesar el build y habilitarlo para TestFlight. Revisar los reportes de crash del nuevo build si los testers vuelven a reportar cierres.

La carpeta local `packtay_backend/.env.local` se usó sólo para validar Compose, continúa ignorada y no se añadió a Git. Los logs no incluyen secretos.
