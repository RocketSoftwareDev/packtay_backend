# Firebase: notificaciones push de PAKTAY

Guía para crear el proyecto de Firebase y dejar listo lo que el código necesita. La hace el
dueño de las cuentas (Firebase y Apple Developer); el código lo hace el equipo con los datos
de la sección 6.

Tiempo estimado: 30 a 45 minutos. Costo: 0 USD (Firebase Cloud Messaging es gratis; la
cuenta de Apple Developer ya está pagada).

## 0. Qué se va a lograr y qué no

- **Sí:** el backend manda avisos al celular aunque la app esté cerrada: presupuesto al 90 %
  y al 100 % por categoría y por «Mi límite» de cada tarjeta, y más adelante los avisos de
  Duo (un gasto de tu pareja).
- **No por push:** «Pago por revisar», «Pago fuera de lo normal» y «Tienes pagos
  esperando». Esos pagos todavía no llegan al servidor cuando ocurren (viven en el teléfono
  hasta que se revisan), así que el servidor no puede avisar de algo que no conoce. Esos
  avisos los genera el propio iPhone con notificaciones locales, en el momento en que corre
  el atajo.

## 1. Crear el proyecto en Firebase

1. Entra a https://console.firebase.google.com con la cuenta de Google que será dueña
   (no la personal del día a día si piensas compartirla; puedes agregar a otros después).
2. **Agregar proyecto** → nombre `paktay` (el ID que proponga, p. ej. `paktay-1a2b3`,
   anótalo: es el **Project ID**).
3. Google Analytics: **desactivado** (no lo necesitamos y agrega consentimiento).
4. Crear.

Recomendación: un proyecto para producción y, si quieres probar sin riesgo, un segundo
`paktay-dev`. Con uno solo también funciona al principio.

## 2. Registrar la app de iOS

1. En el proyecto → **Agregar app** → iOS.
2. **ID del paquete (bundle ID):** `com.rocketsoftwarecore.paktay.mobile`
   (tiene que ser exactamente el del proyecto de Xcode).
3. Apodo: `PAKTAY iOS`. App Store ID: vacío por ahora.
4. Descarga **`GoogleService-Info.plist`**. No es un secreto fuerte (identifica la app,
   no da permisos de envío), pero guárdalo: lo necesita la app.
5. Los pasos de «agregar SDK» y «código de inicialización» sáltalos: los hace el equipo.

Android se registra igual cuando exista la app (bundle `google-services.json`).

## 3. Clave de notificaciones de Apple (APNs) y subirla a Firebase

Firebase necesita permiso de Apple para entregar en iPhone.

1. Entra a https://developer.apple.com/account/resources/authkeys/list
2. **+** → nombre `PAKTAY APNs` → marca **Apple Push Notifications service (APNs)** →
   Configure → entorno **Sandbox & Production** → Continuar → Registrar.
3. Descarga el archivo **`AuthKey_XXXXXXXXXX.p8`**. **Sólo se puede descargar una vez.**
   Guárdalo en un lugar seguro (gestor de contraseñas o bóveda). Es secreto.
4. Anota el **Key ID** (los 10 caracteres del nombre del archivo) y tu **Team ID**
   (arriba a la derecha en developer.apple.com, 10 caracteres).
5. En Firebase → ⚙ **Configuración del proyecto** → pestaña **Cloud Messaging** →
   sección *Configuración de apps de Apple* → **Clave de autenticación de APNs** → Subir:
   el `.p8`, el Key ID y el Team ID.

Con esto no hacen falta certificados `.p12` (la clave no vence cada año).

## 4. Activar push en el identificador de la app (Apple)

1. https://developer.apple.com/account/resources/identifiers/list → busca
   `com.rocketsoftwarecore.paktay.mobile`.
2. Marca **Push Notifications** → Guardar. (Si pide regenerar perfiles, Xcode lo hace solo
   con firma automática.)

En Xcode el equipo agregará las capacidades *Push Notifications* y *Background Modes →
Remote notifications*; no hace falta que lo hagas tú.

## 5. Cuenta de servicio para el backend (la llave del servidor)

El backend usa Firebase Admin para enviar. Necesita una cuenta de servicio.

1. Firebase → ⚙ **Configuración del proyecto** → **Cuentas de servicio** →
   **Generar nueva clave privada** → se descarga un `.json`.
2. **Es un secreto:** quien lo tenga puede mandar notificaciones a todos tus usuarios.
   - No lo subas a git ni lo pegues en el chat.
   - Guárdalo en la Mac (y luego en el VPS) fuera del repo, p. ej.
     `~/secrets/paktay/firebase-admin.json`, con permisos `chmod 600`.
   - El backend lo leerá desde la variable `FIREBASE_CREDENTIALS_FILE` (ruta al archivo),
     montada como volumen de sólo lectura en `business-svc`.

## 6. Qué me tienes que pasar (y qué no)

| Dato | Cómo me lo pasas |
|---|---|
| Project ID (p. ej. `paktay-1a2b3`) | En el chat, no es secreto |
| `GoogleService-Info.plist` | Déjalo en la Mac en `packtay_mobile_front/ios/FinanceApp/` (el equipo decide si se versiona) |
| Confirmación de que el `.p8` quedó subido en Firebase (paso 3.5) | En el chat: «APNs listo» |
| Ruta del `.json` de la cuenta de servicio en la Mac | En el chat, sólo la ruta, **nunca el contenido** |
| `.p8`, `.json`, contraseñas | **No me los pases.** Se quedan en tu bóveda y en la Mac |

## 7. Lo que hará el código (resumen para el equipo)

- **Base (Flyway):** `user_devices` gana `push_token` y `push_token_updated_at`; tabla
  `notification_log` (usuario, tipo, clave del umbral, mes) para no avisar dos veces lo mismo.
- **business-svc:** dependencia `com.google.firebase:firebase-admin`; servicio que envía por
  token; al crear o editar un gasto se recalcula el porcentaje de su categoría y de su tarjeta
  y, si cruza 90 % o 100 % por primera vez en el mes, se envía un aviso (sólo el de 100 % si
  los dos se cruzan con el mismo gasto). Tarea programada para «pagos esperando» no aplica
  (la cola vive en el teléfono): ese recordatorio es local.
- **auth/business:** `PUT /api/v1/user/devices/{deviceId}/push-token` para registrar el token.
- **App:** `@react-native-firebase/app` y `@react-native-firebase/messaging`, pedir permiso
  (la pantalla de permiso del onboarding ya existe), registrar el token al iniciar sesión y
  borrarlo al cerrar sesión o eliminar la cuenta; notificaciones locales para «pago por
  revisar» y «fuera de lo normal» desde la App Intent.
- **Sin cuenta de servicio configurada,** el backend arranca igual y sólo registra en el log
  que no envió (así Codex puede probar sin Firebase).

## 8. Cómo se prueba

1. En Firebase → **Messaging** → *Nueva campaña* → *Notificación de prueba*: pega el token
   que la app muestre en el log de desarrollo y envía. Si llega, Firebase y APNs están bien.
2. Después, con el backend: un gasto que lleve una categoría al 90 % debe dejar un aviso.

Enlaces: [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging) ·
[Claves de APNs en Apple](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)
