# Resumen dia8c-spike

- Ejecución: 2026-09-30_1954-dia8c-spike
- Entorno: Keycloak desechable `kc-spike`, Keycloak 26.2, puerto 28190.
- Funciones activas: `token-exchange:v1` y `admin-fine-grained-authz:v1`.
- Alcance: local; no se tocaron los contenedores compartidos, producción ni el VPS.

## Resultado

El token exchange funcionó sin `audience` y también con `audience=paktay-mobile` (HTTP 200, con access y refresh token). El permiso del proveedor y el permiso del cliente destino quedaron asociados a `auth-svc-puede-cambiar`, usando el UUID de `paktay-auth-service`; la configuración está en `03-permisos.txt`.

El refresh directo con `paktay-mobile` devolvió HTTP 400: `Token client and authorized client don't match`. La alternativa con `paktay-auth-service` también devolvió HTTP 400 en esta prueba porque el refresh fue emitido para el cliente autorizado del intercambio; la app debe validar el flujo final de renovación con el cliente que figure en su configuración.

El vínculo de `existente@fake.local` falló con HTTP 400 y el evento `federated_identity_account_exists`; la contraseña existente sí continuó funcionando con HTTP 200. El segundo intercambio del usuario nuevo devolvió el mismo `sub`, sin duplicarlo. El token alterado fue rechazado con HTTP 400 y el cliente sin permiso con HTTP 403.

Se concedió temporalmente `manage-users` al service account del cliente de prueba para comprobar el borrado administrativo; el borrado respondió HTTP 204. Esa concesión existió solo dentro de `kc-spike`.

## Eventos

Los eventos sanitizados están en `03-eventos.txt`; no contienen tokens ni secretos.

## Pruebas detalladas

Ver `02-pruebas.txt`, `03-permisos.txt` y los archivos JSON resumidos de cada caso.
