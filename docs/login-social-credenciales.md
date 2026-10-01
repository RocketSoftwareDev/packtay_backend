# Credenciales para entrar con Apple y Google (día 8c)

Guía paso a paso para crear, desde la Mac, todo lo que piden Apple y Google, y dónde va cada
dato. Nada de esto es código: son consolas web, Xcode y archivos `.env`.

**Regla de oro:** los Client ID, el bundle id, el Team ID y el Key ID **no son secretos**. La
key `.p8` de Apple **sí**: se descarga una sola vez, nunca se sube a Git, nunca se pega en un
chat y solo vive en el VPS con `chmod 600`.

Antes de empezar, ten a mano:
- el **bundle id** de la app (Xcode › target FinanceApp › General › *Bundle Identifier*);
- el **dominio del panel web**, donde están `https://<panel>/docs/terms` y `/docs/privacy`;
- el **correo remitente** de la app (`SMTP_FROM` del back).

---

## Parte 1 · Apple

### 1.1 Activar Sign in with Apple en el App ID

1. Entra a <https://developer.apple.com/account> › **Certificates, Identifiers & Profiles** ›
   **Identifiers**.
2. Abre el App ID cuyo *Bundle ID* es el de la app.
3. En **Capabilities**, marca **Sign in with Apple** › **Edit** › deja *Enable as a primary App
   ID* › **Save**.
4. **Save** arriba a la derecha y confirma. Apple avisa que los perfiles de firma quedan
   inválidos: es normal (paso 1.5).

### 1.2 Crear la key de Sign in with Apple (.p8)

Sirve para que el servidor revoque el acceso cuando alguien elimina su cuenta. Apple lo exige
para aprobar la app.

1. **Certificates, Identifiers & Profiles** › **Keys** › botón **+**.
2. *Key Name*: `PAKTAY Sign in with Apple`.
3. Marca **Sign in with Apple** › **Configure** › *Primary App ID*: el de la app › **Save**.
4. **Continue** › **Register**.
5. **Download**: baja `AuthKey_XXXXXXXXXX.p8`. **Solo se puede descargar esta vez.** Guárdalo
   en un sitio seguro de la Mac (no en la carpeta del repo).
6. Anota:
   - **Key ID**: los 10 caracteres del nombre del archivo (también salen en la lista de Keys);
   - **Team ID**: arriba a la derecha de la cuenta, o en **Membership details**.

Si la `.p8` se pierde o se filtra: en **Keys**, **Revoke** y crea otra (repite el paso 1.2).

### 1.3 Correo oculto de Apple (Private Email Relay)

Quien elige «Ocultar mi correo» queda con una dirección `@privaterelay.appleid.com`. Apple solo
le reenvía correos que salgan de un dominio registrado aquí.

1. **Certificates, Identifiers & Profiles** › **Services** › **Sign in with Apple for Email
   Communication** › **Configure**.
2. **+** en *Email Sources*: agrega el **dominio** y la **dirección** de `SMTP_FROM`.
3. Apple pide que el dominio tenga **SPF** (registro TXT en el DNS, en Cloudflare). Si usas
   Google Workspace: `v=spf1 include:_spf.google.com ~all`. Apple marca el dominio como verificado
   cuando lo encuentra.

**Ojo:** si `SMTP_FROM` es una dirección `@gmail.com`, no se puede registrar (el dominio no es
tuyo). Para que lleguen correos al correo oculto hace falta un remitente con dominio propio. Sin
esto, entrar con Apple funciona igual; solo no llegan los correos a quien ocultó el suyo.

### 1.4 Llevar la `.p8` al VPS

Desde la Mac (ajusta la ruta del archivo):

```bash
scp ~/Documentos/AuthKey_XXXXXXXXXX.p8 paktay-vps:/opt/paktay/secrets/apple-signin.p8
ssh paktay-vps 'chmod 600 /opt/paktay/secrets/apple-signin.p8'
```

Después bórrala de Descargas si quedó allí y guarda una copia solo en tu gestor de contraseñas.

### 1.5 Xcode

1. Abre `ios/FinanceApp.xcworkspace` › target **FinanceApp** › **Signing & Capabilities**.
2. Si no aparece **Sign in with Apple**: **+ Capability** › *Sign in with Apple*. (Los
   `.entitlements` del repo ya lo declaran; esto lo asocia al perfil de firma.)
3. Con *Automatically manage signing* activo, Xcode regenera los perfiles. Si da error,
   **Xcode › Settings › Accounts › Download Manual Profiles** y vuelve a compilar.

---

## Parte 2 · Google

Usa el **mismo proyecto de Google Cloud que Firebase** (los avisos push), para no tener dos.

### 2.1 Pantalla de consentimiento

1. Entra a <https://console.cloud.google.com> y elige el proyecto de Firebase de PAKTAY.
2. Menú › **Google Auth Platform** (antes «APIs y servicios › Pantalla de consentimiento»).
3. **Branding**:
   - *App name*: `PAKTAY`; *User support email*: tu correo de soporte; logo opcional.
   - *App domain*: página de inicio (el dominio del panel o el sitio), **Privacy policy**
     `https://<panel>/docs/privacy` y **Terms of service** `https://<panel>/docs/terms`.
   - *Authorized domains*: el dominio raíz del panel (p. ej. `rocketsoftwarecore.com`).
   - *Developer contact*: tu correo.
4. **Audience**: *External*. Estado de publicación: **Testing**. En **Test users**, agrega
   los correos de Google de cada tester (máximo 100). En modo Testing **solo ellos** pueden entrar
   con Google.
5. **Data access**: agrega solo `openid`, `.../auth/userinfo.email` y
   `.../auth/userinfo.profile`. Son permisos no sensibles: no piden verificación de Google.

### 2.2 Client ID de iOS

1. **Google Auth Platform** › **Clients** › **Create client**.
2. *Application type*: **iOS**. *Name*: `PAKTAY iOS`.
3. *Bundle ID*: el de la app. *Team ID*: el del paso 1.2 (opcional, recomendado).
4. **Create**. Anota:
   - **Client ID**: `1234567890-abcdef.apps.googleusercontent.com`;
   - **iOS URL scheme** (*reversed client ID*): `com.googleusercontent.apps.1234567890-abcdef`.

No hace falta un cliente «Web» por ahora: la app pide el token con el cliente iOS y el servidor
acepta esa audiencia. (Hará falta uno de Android cuando exista la app de Android.)

### 2.3 Pasar a producción (día 10)

Cuando `/docs/terms` y `/docs/privacy` tengan el texto definitivo: **Audience** › **Publish app**.
Con solo esos tres permisos, Google no exige revisión (a lo sumo verifica la marca, en días).
**No publiques antes:** una política de privacidad vacía es motivo de rechazo en Google y en Apple.

---

## Parte 3 · Dónde va cada dato

### App móvil (`packtay_mobile_front`)

`.env` y `.env.production`:

```dotenv
PAKTAY_GOOGLE_IOS_CLIENT_ID=1234567890-abcdef.apps.googleusercontent.com
PAKTAY_URL_TERMS=https://<panel>/docs/terms
PAKTAY_URL_PRIVACY=https://<panel>/docs/privacy
```

`ios/FinanceApp/Info.plist` ya tiene `CFBundleURLTypes` (el esquema `paktay://`). Agrega **otro**
`<dict>` dentro de ese mismo `<array>`:

```xml
<dict>
    <key>CFBundleURLName</key>
    <string>google-signin</string>
    <key>CFBundleURLSchemes</key>
    <array>
        <string>com.googleusercontent.apps.1234567890-abcdef</string>
    </array>
</dict>
```

Después: `npm run env`, `cd ios && pod install` y compilar. Sin el Client ID, el botón de Google
no aparece (es a propósito); el de Apple no necesita nada más.

### Back (`.env` del VPS)

```dotenv
SOCIAL_GOOGLE_CLIENT_IDS=1234567890-abcdef.apps.googleusercontent.com
SOCIAL_APPLE_CLIENT_IDS=<bundle id>
SOCIAL_APPLE_TEAM_ID=<Team ID>
SOCIAL_APPLE_KEY_ID=<Key ID>
SOCIAL_APPLE_PRIVATE_KEY_HOST_FILE=/opt/paktay/secrets/apple-signin.p8
SOCIAL_APPLE_PRIVATE_KEY_PATH=/run/secrets/apple-signin.p8
LEGAL_TERMS_VERSION=1
LEGAL_PRIVACY_VERSION=1
```

`SOCIAL_GOOGLE_ISSUER_OVERRIDE` y `SOCIAL_GOOGLE_JWKS_URL_OVERRIDE` quedan **vacías** en
producción (solo sirven para la prueba local con el proveedor falso).

`LEGAL_*_VERSION` tiene que coincidir con la versión de `src/features/legal/documents.ts` del
panel web.

### Keycloak del VPS (una vez, en una ventana avisada)

El token exchange necesita dos funciones de Keycloak 26.2 que vienen apagadas:

1. En el compose del Keycloak central (`/opt/paktay/keycloak`), agrega al comando de arranque:
   `--features=token-exchange:v1,admin-fine-grained-authz:v1`.
2. Reinícialo (`docker compose up -d`). **Mientras arranca, ningún realm de ese Keycloak
   responde**, tampoco el de otras apps: avisa antes.
3. Configura los proveedores y permisos (sin `GOOGLE_ISSUER` ni `GOOGLE_JWKS`, que son de prueba):

   ```bash
   cd /opt/paktay/packtay_backend
   KC_CONTAINER=<contenedor de keycloak> KC_ADMIN_PASSWORD=<admin de master> \
   GOOGLE_CLIENT_ID=1234567890-abcdef.apps.googleusercontent.com \
   APPLE_CLIENT_ID=<bundle id> \
   ./infra/keycloak/social-setup.sh
   ```

   Debe terminar con `social_setup_ok google=on apple=on`. Se puede repetir sin romper nada.
4. Reinicia auth-svc: `docker compose -p paktay-prod up -d auth-svc`.

### Comprobar que quedó encendido

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<auth>/api/v1/auth/social/google \
  -H 'Content-Type: application/json' -d '{"idToken":"x","acceptedLegal":true}'
```

- `400` → Google encendido (rechazó el token de mentira, que es lo correcto).
- `404` → `SOCIAL_GOOGLE_CLIENT_IDS` vacío o auth-svc sin reiniciar.

Lo mismo con `/social/apple` (con `"nonce":"x"`).

La prueba de verdad es en el iPhone: los pasos finales de `IALogs/instrucciones/dia8c.md`.
