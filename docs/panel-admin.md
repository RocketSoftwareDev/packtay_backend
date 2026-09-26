# Panel de administración

Plan y decisiones del panel web (`packtay_web_admin`). Diseño en Pencil: login, Resumen,
Usuarios (lista y detalle), Planes, Categorías, Bancos y tarjetas, Monedas y países,
Soporte (tickets, bloqueos y modal de bloqueo), Notificaciones, Auditoría y Estado del sistema.

## Decisiones (2026-09-25)

- **Sin despliegue por ahora.** Solo código. El dominio y Cloudflare se configuran después.
- **Web:** Next.js con login propio (2026-09-26): nadie ve que detrás está Keycloak. El servidor
  de Next (BFF) manda correo y contraseña a auth-svc, que pide el token con el cliente confidencial
  `paktay-admin-panel` y exige ADMIN. El token del panel dura 15 minutos (el del móvil, 8 horas).
- **El administrador no ve datos financieros** (gastos, montos, categorías del usuario).
- **Auditoría:** solo altas de usuario, acciones del administrador y cuentas eliminadas. Se
  dejan de registrar las acciones del usuario (gastos, tarjetas, reglas). Retención: 90 días
  hasta tener un VPS más grande. El cierre de cuenta se guarda con id anónimo, después de
  `purge_user`, porque la purga borra los eventos del usuario.
- **Bloquear una cuenta:** no borra nada. Marca `app_users.status`, desactiva la identidad
  en Keycloak y cierra sus sesiones. El móvil muestra "Tu cuenta fue bloqueada" sin motivo.
  El correo de aviso se decide después.
- **Soporte:** ruta pública `correo + motivo` que devuelve el id del ticket. Solo llegan a
  la bandeja los tickets con correo confirmado (enlace por correo). El formulario web lo hace
  otro desarrollador; aquí va el servicio. El detalle se guarda como conversación para un
  chat futuro.
- **Bloqueos:** correo exacto, dominio o IP. Alcance: tickets, registro y cuenta existente.
  Correos normalizados (minúsculas, sin alias `+`, sin puntos en Gmail). El límite por IP lo
  hace el backend por ahora, con `CF-Connecting-IP` (la API solo escucha en 127.0.0.1 y
  llega por el túnel). La regla de Cloudflare y Turnstile se agregan al desplegar.
- **Al eliminar una cuenta** se borran también sus tickets de soporte.

## Fases

| Fase | Contenido | Repos |
|---|---|---|
| 0 | Cliente `paktay-admin-web` en Keycloak (realm y `keycloak-init` idempotente) | backend |
| 1 | Usuarios: lista, detalle, cambio de plan, bloqueo; corregir el borrado del admin (hoy no purga); mensaje de cuenta bloqueada en el móvil | backend, móvil |
| 2 | Auditoría recortada y `GET /admin/audit` | backend |
| 3 | Soporte y bloqueos: contrato público primero, V9, correo en business-svc, límites, rutas de admin, bloqueo en el registro | backend |
| 4 | Web: login, menú, Usuarios, Soporte, Auditoría | web |
| 5 | Catálogos: categorías, bancos y ofertas, monedas y países | backend, web |
| 6 | Indicadores, notificaciones, estado del sistema | backend, web |
| 7 | Despliegue (pospuesto) | todos |
| 3-seg | Seguridad del panel: login propio + BFF con cookie HttpOnly, solo tokens del panel, fuerza bruta, cabeceras; TOTP pendiente (ver abajo) | backend, web, Keycloak |

La versión mínima son las fases 0 a 4. Cada fase se verifica en la Mac con
`IALogs/instrucciones/admin-faseN.md`.

## Estado

- **Fase 0** (`feature/admin-fase0-acceso`): cliente `paktay-admin-web`.
- **Fase 1 + 2 + 3 backend** (`feature/admin-fase1-roles-soporte`, incluye la fase 0), sin
  compilar todavía (se prueba con `IALogs/instrucciones/admin-fase1.md`):
  - V9: `normalize_email`, `admin_audit`, `support_tickets`, `support_messages`,
    `blocked_identities`; `purge_user` borra también tickets y eventos del usuario.
  - Roles: el acceso al panel es el rol ADMIN de Keycloak. Se asigna y quita desde el panel
    (`PUT/DELETE /api/v1/admin/users/{id}/roles/admin` en auth-svc). Nadie se lo quita a sí
    mismo y no se puede quitar el último administrador activo. Quitar el rol no invalida el
    token vigente: deja de servir cuando vence (15 minutos en el panel).
  - Rutas: ver Swagger de cada servicio (tags "Admin · …" y "Soporte público").
  - Se dejaron de escribir acciones del usuario en `audit_log`; la tabla se vacía sola en 90
    días y se borra en una migración posterior.

- **Fase 1** cerrada: IALogs `2026-09-26_1005-admin-fase1b` (escenario 57/57) y
  `2026-09-26_1031-admin-fase1c` (Maven en verde, 23 + 70 pruebas).
- **Fases 5 y 6** (`feature/admin-fase2-catalogos-indicadores`): catálogos (categorías agrupadas,
  bancos y ofertas, monedas y países), `metrics/summary`, notificaciones con push de prueba y
  `system/status`. Sin migraciones. El cliente `paktay-admin-web` pasa a `http://localhost:3000`
  (la web es Next, no Vite).
- **Fases 5 y 6 + seguridad paso 1** cerradas e integradas en `develop` (PR #25): IALogs
  `2026-09-26_1108-admin-fase2` (escenario completo en OK, oferta repetida 409, Flyway V1–V9) y
  `2026-09-26_1112-admin-seguridad1` (matriz de cabecera y CORS en OK). Maven 23 + 73 pruebas.
- **Regresión del panel:** un solo script, `IALogs/instrucciones/admin-panel.md`, contra `develop`
  (catálogos, indicadores, auditoría, cabecera y CORS). Reemplaza a `admin-fase2.md` y
  `admin-seguridad1.md`.

## Pendiente

- **Móvil:** con `403` y `code = ACCOUNT_BLOCKED` (business-svc) o un login rechazado por
  cuenta desactivada, cerrar sesión y mostrar "Tu cuenta fue bloqueada".
- **Web del formulario (otro desarrollador):** `POST /api/v1/public/support/tickets`
  con `{email, reason, website: ""}` → `202 {ticketId, status, message}`; `429` si pasa el
  límite. El enlace del correo apunta a `SUPPORT_VERIFY_LINK` (`{token}` se reemplaza).

## Fase 3 · Seguridad del panel (pedida el 2026-09-26)

Keycloak sigue siendo el emisor del JWT (RS256) y los dos servicios validan firma, emisor,
vencimiento y rol. No se hace un JWT propio. Lo que cambia es quién ve qué: la web es la parte
más expuesta y el móvil no tiene nada de administración.

**Paso 1 · hecho (PR #25):** cabecera `X-Paktay-Client` obligatoria en `/api/v1/admin/**` y CORS
separado para el formulario público (`PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS`).

**Paso 2 · login propio + BFF** (`feature/admin-login-propio` en backend y
`feature/login-propio-bff` en la web):

```
Navegador ──(cookie HttpOnly)──► Next /api/auth/*, /api/bff/*  ──(Bearer + X-Paktay-Client)──► auth-svc / business-svc
                                                      auth-svc ──(cliente confidencial)──► Keycloak
```

- **Login propio.** `POST /api/v1/admin/session/login|refresh|logout` en auth-svc (públicas, con
  la cabecera). Usa el cliente confidencial `paktay-admin-panel` (solo direct grant, secreto en
  `KEYCLOAK_ADMIN_PANEL_CLIENT_SECRET`). Sin rol ADMIN se cierra la sesión recién creada y se
  responde `401 INVALID_CREDENTIALS`, igual que con contraseña mala: nadie averigua qué correos
  son administradores. La renovación vuelve a exigir ADMIN. Cada inicio de sesión queda en la
  auditoría. Se elimina el cliente público `paktay-admin-web` (keycloak-init lo borra).
- **Solo tokens del panel.** `/api/v1/admin/**` exige `azp = paktay-admin-panel`
  (`403 ADMIN_TOKEN_REQUIRED`): un token de la app móvil no sirve en el panel aunque la cuenta
  tenga ADMIN. Interruptor: `PAKTAY_ADMIN_REQUIRE_PANEL_TOKEN`.
- **BFF.** El navegador solo habla con el servidor de Next. Tokens en cookies `HttpOnly`,
  `SameSite=Strict`, `Path=/api`, cifradas (AES-256-GCM, `ADMIN_SESSION_SECRET`). El proxy solo
  deja pasar `/api/v1/admin/**`. CSRF: SameSite + cabecera + mismo Origin.
- **Sesión corta.** Access 15 min, sesión inactiva 30 min, máxima 8 h (atributos del cliente).
- **Fuerza bruta.** Realm: bloqueo temporal tras 5 fallos (1 a 15 min). BFF: 10 intentos de
  login cada 5 min por IP. Afecta también al login del móvil, que es lo deseable.
- **Cabeceras en la web.** CSP con `connect-src 'self'` y `frame-ancestors 'none'`, HSTS en
  producción, `nosniff`, `no-referrer`, `Permissions-Policy`.
- **CORS.** Con BFF el panel ya no llama a la API desde el navegador: en producción
  `PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS` solo necesita lo que sí llame desde un navegador.

**Pendiente de seguridad:**
- **TOTP para ADMIN.** Con login propio lo pide nuestro formulario (Keycloak acepta el código en
  el direct grant con el flujo "Direct Grant - Conditional OTP").
- **Contraseña temporal.** Con direct grant, una contraseña marcada temporal deja la cuenta sin
  poder entrar ni al panel ni al móvil (Keycloak responde "Account is not fully set up"). El login
  del panel lo informa como `403 PASSWORD_CHANGE_REQUIRED`, pero la opción "contraseña temporal"
  del panel debería desaparecer o pasar por el PIN.
- **CSP con nonce** para quitar `'unsafe-inline'` de `script-src`.
- **Despliegue.** La web necesita servidor (Node o Cloudflare con OpenNext).
