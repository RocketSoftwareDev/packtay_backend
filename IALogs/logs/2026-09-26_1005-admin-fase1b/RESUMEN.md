# Fase 1b del panel admin en entorno local

| Verificación | Resultado |
|---|---|
| Rama probada | `fix/admin-fase1-compilacion`, basada en `develop` |
| Secretos | `.env.local` usado; no se escribieron valores sensibles en logs |
| Maven con tests | FALLO solo en `PasswordPinServiceTest`: 16 errores de inicialización de Mockito |
| Maven `-DskipTests` | OK en `auth-svc` y `business-svc` |
| `keycloak-init` local | OK en Keycloak local `28180` |
| Escenario fase 1 | OK; todos los checks reportaron `OK` |
| Salud, OpenAPI y Swagger | OK; 200 en auth `28081` y business `28082` |
| Flyway local | OK; V1 a V9 con `success = t` |
| `paktay-fresh` desde cero | OK; V1 a V9 con `success = t`; volumen eliminado al terminar |

Notas:

- Solo se usaron `paktay-local`, `paktay-fresh`, Keycloak local `28180` y `mailpit-1` local.
- La base persistente de Keycloak local tenía secretos incompatibles; se reinicializó únicamente el volumen local `paktay-local_keycloak-data`.
- `PAKTAY_ADMIN_PASSWORD` de `.env.local` no cumplía la política local de Keycloak. Para esta corrida se añadió `A1` únicamente en memoria del proceso; el archivo local no se imprimió ni se subió.
- No se tocó `main`, `paktay-prod` ni sus volúmenes.
