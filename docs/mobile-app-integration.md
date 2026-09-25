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
| Consultar perfil (incluye `timezone`, `countryCode`) | `GET /api/v1/user/profile` | business-svc |
| Cambiar zona horaria y país | `PUT /api/v1/user/profile/context` | business-svc |
| Eliminar tarjeta (borrado lógico) | `DELETE /api/v1/user/cards/{cardId}` | business-svc |
| Registrar gasto manual | `POST /api/v1/user/expenses` | business-svc |
| Historial paginado y sincronización | `GET /api/v1/user/expenses` | business-svc |
| Consultar un gasto | `GET /api/v1/user/expenses/{id}` | business-svc |
| Editar un gasto | `PUT /api/v1/user/expenses/{id}` | business-svc |
| Anular un gasto | `POST /api/v1/user/expenses/{id}/void` | business-svc |
| Resumen del mes (inicio) | `GET /api/v1/user/summary?month=YYYY-MM` | business-svc |

Desde el día 3 (V4):

- `PUT /api/v1/user/profile/context` recibe `{ "timezone": "America/Guayaquil", "countryCode": "EC" }`.
  La zona debe ser IANA; el país, dos letras mayúsculas. La app debería enviar la zona del
  teléfono al iniciar sesión si difiere de la del perfil: el mes de los presupuestos y los
  filtros `from`/`to` del historial se calculan con ella.
- Eliminar una tarjeta la pasa a `DELETED`: desaparece de `GET /api/v1/user/cards`, no se
  reactiva y sus gastos se conservan. Los gastos traen `cardStatus` para mostrar "Eliminada".
- `ExpenseResponse` suma `cardStatus`, `kind` (`EXPENSE`/`REFUND`), `status` (`ACTIVE`/`VOIDED`)
  y `assignedByRule`. `POST /api/v1/user/expenses` acepta `assignedByRule` opcional
  (`false` por defecto) para marcar capturas asignadas por una regla del teléfono.

## Gastos (día 4, V5)

Todas las rutas requieren `Authorization: Bearer <access_token>`. Los errores devuelven
`{ "message": "...", "requestId": "..." }`.

### Formato de un gasto (`ExpenseResponse`)

```json
{
  "id": "UUID",
  "cardId": "UUID",
  "cardName": "VISA PLATINUM o null",
  "cardStatus": "ACTIVE | INACTIVE | DELETED",
  "categoryId": "UUID",
  "categoryName": "Comida",
  "origin": "MANUAL | AUTOMATIC",
  "kind": "EXPENSE | REFUND",
  "status": "ACTIVE | VOIDED",
  "voidedByExpenseId": "UUID del REFUND o null",
  "assignedByRule": false,
  "amount": 12.50,
  "currencyCode": "USD",
  "merchantRaw": "Comercio de prueba",
  "occurredAt": "2026-09-24T15:04:05.123456Z",
  "updatedAt": "2026-09-24T15:04:05.123456Z"
}
```

Los totales (presupuestos, conteo de consumos recientes de una tarjeta) sólo cuentan
`status = ACTIVE` y `kind = EXPENSE`. `amount` siempre es positivo, también en un `REFUND`.

### Registrar (`POST /api/v1/user/expenses`)

Sin cambios de contrato. Un reintento con el mismo `idempotencyKey`, aunque llegue en
paralelo con el primero, devuelve `201` con el mismo gasto y no crea otro.

### Historial paginado (`GET /api/v1/user/expenses`)

Respuesta: `{ "items": [ExpenseResponse...], "nextCursor": "texto opaco o null" }`.
**Cambio de contrato:** antes devolvía un arreglo.

| Parámetro | Tipo | Uso |
|---|---|---|
| `from`, `to` | `yyyy-MM-dd` | Días inclusivos en la zona horaria del perfil |
| `cardId`, `categoryId` | UUID | Filtros opcionales |
| `limit` | entero | 1 a 200; 50 por defecto; fuera de rango = 400 |
| `cursor` | texto | `nextCursor` de la página anterior; inválido = 400 |
| `since` | instante ISO-8601 | Activa la sincronización incremental |

- **Sin `since`:** todo el historial (anulados, `REFUND` y gastos de tarjetas eliminadas
  incluidos), por `occurredAt` desc y `id` desc.
- **Con `since`:** filas con `updatedAt >= since`, por `updatedAt` asc e `id` asc. Incluye
  gastos editados, anulados (`VOIDED`) y sus `REFUND`. Es la vía para mantener el espejo local:
  1. primera carga sin `since` (o con un `since` muy antiguo), recorriendo `nextCursor`;
  2. guardar el mayor `updatedAt` recibido;
  3. en la siguiente sincronización pedir `since` = ese valor menos un margen (por ejemplo
     2 minutos) y recorrer `nextCursor` hasta `null`;
  4. hacer upsert por `id`: la misma fila puede llegar más de una vez.
- Para pedir la página siguiente se repiten exactamente los mismos filtros y `since`, más
  `cursor`. Un cursor obtenido sin `since` no sirve con `since` y viceversa (400).
- `cardName` y `categoryName` son los actuales al momento de la consulta; renombrar una
  tarjeta o categoría no cambia el `updatedAt` del gasto, así que el teléfono debe tomar esos
  nombres de las listas de tarjetas y categorías, no del espejo de gastos.

### Consultar uno (`GET /api/v1/user/expenses/{id}`)

`200` con `ExpenseResponse`, o `404` si no existe o es de otro usuario.

### Editar (`PUT /api/v1/user/expenses/{id}`)

```json
{
  "categoryId": "UUID (obligatorio)",
  "cardId": "UUID (obligatorio)",
  "amount": 15.00,
  "merchantRaw": "Nuevo comercio"
}
```

- `categoryId` y `cardId` siempre se envían; si cambian deben ser del usuario y estar activos.
- `amount` y `merchantRaw` son opcionales y sólo cambian en gastos `MANUAL`. En un gasto
  `AUTOMATIC` sólo se aceptan si son iguales al valor actual; si difieren, `400` con
  "El monto de un gasto capturado por Wallet no se puede cambiar" (o el equivalente del comercio).
- Sólo se editan gastos `ACTIVE` de tipo `EXPENSE`: un anulado o un `REFUND` devuelve `409`.
  Un gasto de un período cerrado también devuelve `409`.
- Editar no enseña ni cambia reglas de categorización.
- Respuesta `200` con el `ExpenseResponse` actualizado.

### Anular (`POST /api/v1/user/expenses/{id}/void`)

Sin cuerpo. Respuesta `200`:

```json
{ "voided": ExpenseResponse, "refund": ExpenseResponse }
```

- El original queda `status = VOIDED` con `voidedByExpenseId` = id del `REFUND`, y deja de contar.
- El `REFUND` es `kind = REFUND`, `status = ACTIVE`, mismo monto, tarjeta, categoría, moneda,
  origen y `occurredAt` que el original (queda en el mismo mes), y `merchantRaw`
  "Anulación: <comercio original>".
- Idempotente: anular de nuevo devuelve el mismo par. Anular un `REFUND` devuelve `409`.
- Funciona aunque la tarjeta ya esté inactiva o eliminada.

## Resumen del mes

`GET /api/v1/user/summary?month=YYYY-MM` (día 5). `month` es opcional: por defecto, el mes
actual en la zona horaria del perfil. Un `month` mal formado o futuro devuelve `400`.

Es la única fuente de números de la pantalla de inicio: la app no recalcula nada, sólo
muestra. `GET /api/v1/user/budgets/current` usa las mismas reglas para sus totales.

### Reglas

- **Mes:** mes calendario en la zona horaria del usuario; vuelve a cero el día 1
  (`resetsOn`). Con recurrencia `MONTHLY` el presupuesto pasa solo al mes siguiente.
- **Presupuesto (`budget`):** suma del presupuesto efectivo de cada categoría **activa**
  seleccionada en el presupuesto. Efectivo = su monto propio si lo tiene; si no, el global.
  El global es un monto **por categoría**, no un tope: global 100, 10 categorías y una con
  monto propio 50 = 950. Sin categorías con monto, `budget` es `null`.
- **Gastado (`spent`):** gastos `ACTIVE` de tipo `EXPENSE` del mes en la moneda del
  presupuesto (`currency`, USD por ahora). Los gastos en otra moneda no suman: se cuentan en
  `otherCurrencyCount` para que la app pueda avisarlo.
- **Días:** `daysLeft = daysInMonth - dayOfMonth + 1` (cuenta hoy: el 24 de septiembre
  quedan 7). En un mes pasado `dayOfMonth = daysInMonth` y `daysLeft = 0`.
- **Porcentajes:** siempre redondeados hacia abajo (99.6 % se muestra 99, nunca 100).
  `percent = spent / budget * 100`; `elapsedPercent = dayOfMonth / daysInMonth * 100`.
- **Ritmo (`pace`):** `NONE` sin presupuesto; `OVER` si `spent > budget`; `FAST` si
  `percent > elapsedPercent + 10`; `OK` en el resto.
- `available = budget - spent` (puede ser negativo, `null` sin presupuesto);
  `overBy = max(spent - budget, 0)`.
- **Estado de categoría:** `NO_BUDGET` sin presupuesto efectivo; `OVER` si `spent > budget`;
  `AT_LIMIT` desde 90 % (incluye exactamente 100 %); `OK` en el resto.
- **Categorías listadas:** las que tienen gasto en el mes (aunque no estén en el presupuesto o
  estén desactivadas) y las activas seleccionadas aunque no tengan gasto. Orden: `spent` desc,
  luego nombre. `name` es el alias visible de la categoría.
- **Tarjetas:** todas las `ACTIVE` y las `INACTIVE`/`DELETED` con gasto en el mes. Orden:
  activas primero, luego `spent` desc. `ownLimit` es el límite propio de la tarjeta para ese
  mes (sólo texto, sin barra); `null` si no tiene.
- **Recientes:** los 3 gastos `ACTIVE`/`EXPENSE` más recientes del mes, en formato
  `ExpenseResponse` (cualquier moneda).

### Respuesta

```json
{
  "month": "2026-09",
  "timezone": "America/Guayaquil",
  "currency": "USD",
  "daysInMonth": 30,
  "dayOfMonth": 24,
  "daysLeft": 7,
  "resetsOn": "2026-10-01",
  "budget": 950.00,
  "spent": 812.40,
  "available": 137.60,
  "percent": 85,
  "elapsedPercent": 80,
  "pace": "OK",
  "overBy": 0,
  "otherCurrencyCount": 0,
  "categories": [
    {
      "categoryId": "UUID",
      "name": "Comida y restaurantes",
      "icon": "utensils",
      "colorDark": "#F59E0B",
      "colorLight": "#D97706",
      "budget": 100.00,
      "budgetSource": "GLOBAL",
      "spent": 104.50,
      "percent": 104,
      "overBy": 4.50,
      "status": "OVER"
    },
    {
      "categoryId": "UUID",
      "name": "Mascotas",
      "icon": "paw-print",
      "colorDark": "#FDBA74",
      "colorLight": "#C2410C",
      "budget": null,
      "budgetSource": null,
      "spent": 12.00,
      "percent": null,
      "overBy": 0,
      "status": "NO_BUDGET"
    }
  ],
  "cards": [
    {
      "cardId": "UUID",
      "alias": "Visa del día a día",
      "walletName": "VISA PLATINUM",
      "bankName": "Banco Pichincha C.A.",
      "bankLogoUrl": "https://...",
      "status": "ACTIVE",
      "spent": 640.10,
      "ownLimit": 500.00
    }
  ],
  "recent": [ExpenseResponse, ExpenseResponse, ExpenseResponse],
  "counts": { "activeCategories": 12, "activeCards": 2 }
}
```

`counts` sirve para los estados vacíos (sin tarjetas, sin categorías). Los montos llegan
como números; la app los formatea con `currency`.

### Cambios en `GET /api/v1/user/budgets/current`

Aditivos, sin quitar campos:

- `budgetAmount`, `availableAmount` y `percent` en la raíz, con las mismas reglas del resumen.
- En cada categoría: `effectiveAmount` (monto propio o global), `budgetSource`
  (`OWN`/`GLOBAL`/`null`), `percent` y `status`.
- `spentAmount` (raíz y categoría) ahora suma sólo gastos en la moneda del presupuesto
  (antes sumaba `amount_usd` de todas las monedas; con todo en USD el valor es el mismo).
- Las categorías desactivadas ya no aparecen en la lista aunque sigan marcadas.

## Tarjetas

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
- Colecciones Postman: `postman/Paktay-Auth.postman_collection.json` y `postman/Paktay-Business.postman_collection.json`

## Pendiente antes de publicar

- Crear Keycloak y PostgreSQL/Supabase de producción.
- Usar dominios HTTPS públicos y actualizar redirect URI/orígenes permitidos de Keycloak.
- Configurar secretos de producción fuera del repositorio.
- Configurar permisos nativos: `NSFaceIDUsageDescription` en iOS y `BiometricPrompt` en Android.
- No incluir secretos de Keycloak, Supabase ni tokens en el APK/IPA.
