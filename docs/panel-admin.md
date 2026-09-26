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

## Pendiente

- **Móvil:** con `403` y `code = ACCOUNT_BLOCKED` (business-svc) o un login rechazado por
  cuenta desactivada, cerrar sesión y mostrar "Tu cuenta fue bloqueada".
- **Web del formulario (otro desarrollador):** `POST /api/v1/public/support/tickets`
  con `{email, reason, website: ""}` → `202 {ticketId, status, message}`; `429` si pasa el
  límite. El enlace del correo apunta a `SUPPORT_VERIFY_LINK` (`{token}` se reemplaza).
- Catálogos, indicadores, notificaciones y estado del sistema (fases 5 y 6).
- Keycloak podría además negar el login del cliente `paktay-admin-web` a quien no tenga ADMIN
  (flujo de autenticación con condición de rol). Hoy lo impiden el backend (403) y el panel.
