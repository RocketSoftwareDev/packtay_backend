# Panel de administración

Plan y decisiones del panel web (`packtay_web_admin`). Diseño en Pencil: login, Resumen,
Usuarios (lista y detalle), Planes, Categorías, Bancos y tarjetas, Monedas y países,
Soporte (tickets, bloqueos y modal de bloqueo), Notificaciones, Auditoría y Estado del sistema.

## Decisiones (2026-09-25)

- **Sin despliegue por ahora.** Solo código. El dominio y Cloudflare se configuran después.
- **Web:** React + Vite + TypeScript, entrada con OIDC + PKCE (`oidc-client-ts`) contra el
  cliente `paktay-admin-web`. El token del panel dura 15 minutos (el del móvil, 8 horas).
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
| 3-seg | Seguridad del panel: BFF con cookie HttpOnly, audiencia del token, Keycloak niega el panel sin ADMIN, TOTP y fuerza bruta, cabeceras (ver abajo) | backend, web, Keycloak |

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
  `system/status`. Sin migraciones. Se prueba con `IALogs/instrucciones/admin-fase2.md`.
  El cliente `paktay-admin-web` pasa a `http://localhost:3000` (la web es Next, no Vite).

## Pendiente

- **Móvil:** con `403` y `code = ACCOUNT_BLOCKED` (business-svc) o un login rechazado por
  cuenta desactivada, cerrar sesión y mostrar "Tu cuenta fue bloqueada".
- **Web del formulario (otro desarrollador):** `POST /api/v1/public/support/tickets`
  con `{email, reason, website: ""}` → `202 {ticketId, status, message}`; `429` si pasa el
  límite. El enlace del correo apunta a `SUPPORT_VERIFY_LINK` (`{token}` se reemplaza).

## Fase 3 · Seguridad del panel (pedida el 2026-09-26)

Hoy ya hay JWT: Keycloak emite el token (OIDC + PKCE, RS256) y los dos servicios validan firma,
emisor, vencimiento y el rol ADMIN; el token del panel dura 15 minutos. No se hace un JWT propio.
Lo débil está en la web. Por orden de impacto:

1. **BFF (backend for frontend) en la web.** El servidor de Next hace el login con Keycloak
   (cliente confidencial) y guarda la sesión en una cookie `HttpOnly`, `Secure`,
   `SameSite=Strict` y cifrada. El navegador nunca ve el token: todas las llamadas pasan por
   rutas de Next (`/api/bff/...`) que agregan el `Bearer` y hablan con auth-svc y business-svc.
   Un XSS ya no puede robar el token (hoy está en `sessionStorage`). Protección CSRF con
   `SameSite=Strict` más una cabecera propia en las mutaciones. La renovación del token la hace
   el servidor con el refresh token, que tampoco sale de la cookie.
2. **Audiencia del token.** Las rutas `/api/v1/admin/**` aceptan solo tokens emitidos para el
   panel (`azp = paktay-admin-web`, o `aud` con un mapper de audiencia). Un token sacado con
   usuario y contraseña desde el cliente móvil deja de servir en el panel. Las pruebas de Codex
   necesitarán obtener el token del cliente del panel (flujo de pruebas separado).
3. **Keycloak niega el login del panel a quien no tiene ADMIN** (flujo del cliente con
   "Condition - user role" + "Deny access"): sin rol, no hay token.
4. **Segundo factor (TOTP) obligatorio para ADMIN** y **protección contra fuerza bruta** del realm
   (bloqueo temporal tras varios intentos fallidos).
5. **Cabeceras de seguridad en la web:** CSP estricta, `frame-ancestors 'none'`, HSTS,
   `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`.
6. **En producción:** CORS solo con el dominio del panel (con BFF el navegador ya no llama
   directo a la API), sesión del panel con tiempo máximo corto (por ejemplo 8 h) y cierre de
   sesión que también la cierre en Keycloak.
