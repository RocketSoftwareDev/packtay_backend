# Guía de integración — App móvil Paktay

## Arquitectura

La aplicación móvil consume dos servicios HTTP y usa Keycloak como proveedor de identidad.

```text
App móvil → Keycloak → tokens OAuth/OIDC
App móvil → auth-svc → registro y utilidades de autenticación
App móvil → business-svc → tarjetas, dispositivos y lógica financiera
```

| Componente | Desarrollo local | Responsabilidad |
|---|---|---|
| Keycloak | `http://localhost:8180` | Inicio OAuth/OIDC, JWT y roles |
| auth-svc | `http://localhost:8081` | Registro, login de pruebas, cambio de contraseña |
| business-svc | `http://localhost:8082` | Datos de negocio protegidos con JWT |

En producción todas las URLs deben usar HTTPS; la app nunca debe usar `localhost`.

## Registro e inicio de sesión

### Registro

El registro se realiza una sola vez con **auth-svc**:

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "persona@ejemplo.com",
  "displayName": "Nombre Apellido",
  "password": "ClaveSegura123!"
}
```

Respuesta: se crea el usuario en Keycloak y se devuelve su `id`.

### Login normal

Para producción, la app debe abrir el flujo de **Keycloak Authorization Code + PKCE** con el cliente público `paktay-mobile` y el redirect URI `paktay://oauth/callback`.

No se debe usar `POST /api/v1/auth/login` en la app publicada: se conserva para Postman y pruebas automatizadas.

Al terminar OAuth/PKCE, Keycloak entrega:

- `access_token`: se envía al backend.
- `refresh_token`: permite renovar la sesión sin pedir contraseña.
- `expires_in` y `refresh_expires_in`: duración de los tokens.

Cada llamada protegida debe incluir:

```http
Authorization: Bearer <access_token>
```

## Biometría: Face ID, Touch ID y Android

La biometría se valida exclusivamente en el dispositivo. La app no envía rostro, huella ni imágenes al backend.

1. Tras el primer OAuth exitoso, guardar el `refresh_token` de forma segura.
2. iOS: usar Keychain y `LocalAuthentication` (Face ID/Touch ID).
3. Android: usar Keystore y `BiometricPrompt` (huella, rostro o credencial del dispositivo).
4. Con biometría aprobada, leer el `refresh_token`, renovarlo con Keycloak y usar el nuevo `access_token`.
5. Si el refresh token venció o fue revocado, solicitar OAuth/login completo nuevamente.

Después del login, registrar la preferencia del dispositivo en **business-svc**:

```http
PUT /api/v1/security/devices/me
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "deviceId": "UUID persistente por instalación",
  "platform": "ios",
  "deviceName": "iPhone de Ana",
  "biometricEnabled": true
}
```

Esto guarda únicamente la preferencia y el identificador de instalación, no información biométrica.

## Servicios de negocio disponibles

Todos requieren `Authorization: Bearer <access_token>`.

| Acción | Método y ruta | Servicio |
|---|---|---|
| Entrada autenticada | `GET /api/v1/app/entry` | business-svc |
| Listar bancos | `GET /api/v1/banks` | business-svc |
| Registrar tarjeta | `POST /api/v1/cards` | business-svc |
| Listar tarjetas | `GET /api/v1/cards` | business-svc |
| Guardar dispositivo/biometría | `PUT /api/v1/security/devices/me` | business-svc |
| Listar dispositivos | `GET /api/v1/security/devices` | business-svc |
| Eliminar dispositivo | `DELETE /api/v1/security/devices/{deviceId}` | business-svc |
| Cambiar contraseña | `PUT /api/v1/auth/password` | auth-svc |

### Registrar una tarjeta

Primero obtener el banco con `GET /api/v1/banks`. Luego usar su `id`:

```http
POST /api/v1/cards
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "bankId": "UUID del banco",
  "brand": "visa",
  "kind": "credit",
  "last4": "4242",
  "nickname": "Tarjeta principal"
}
```

Valores permitidos:

- `brand`: `visa`, `mastercard`, `amex`, `diners`, `other`.
- `kind`: `credit`, `debit`.
- `last4`: exactamente cuatro dígitos.

Nunca pedir ni enviar el número completo de una tarjeta.

## Documentación y pruebas

- Auth Swagger: `http://localhost:8081/swagger-ui/index.html`
- Business Swagger: `http://localhost:8082/swagger-ui/index.html`
- Colección Postman: `postman/Paktay-Auth.postman_collection.json`

## Pendiente antes de publicar

- Crear Keycloak y PostgreSQL/Supabase de producción.
- Usar dominios HTTPS públicos y actualizar redirect URI/orígenes permitidos de Keycloak.
- Configurar secretos de producción fuera del repositorio.
- Configurar permisos nativos: `NSFaceIDUsageDescription` en iOS y `BiometricPrompt` en Android.
- No incluir secretos de Keycloak, Supabase ni tokens en el APK/IPA.
