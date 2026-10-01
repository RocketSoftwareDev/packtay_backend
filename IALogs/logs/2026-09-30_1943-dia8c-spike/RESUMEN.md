# Día 8c - resultado

- RUN: 2026-09-30_1943-dia8c-spike
- Keycloak temporal: kc-spike, Keycloak 26.2.5, puerto 28190.
- La sintaxis --features=token-exchange:v1,admin-fine-grained-authz:v1 arrancó correctamente.
- Se montaron paktay y fakeidp, el proveedor OIDC google, el flujo social-autolink, dos usuarios fake y el usuario existente de Paktay.
- El intercambio externo a interno volvió a responder HTTP 403: access_denied / Client not allowed to exchange.
- La política fine-grained del cliente se creó y se asoció al permiso token-exchange del proveedor, pero Keycloak 26.2.5 siguió rechazando al cliente.
- Las pruebas de creación, vinculación, renovación, rol y borrado quedaron bloqueadas por ese 403.
- No se guardaron tokens completos, secretos ni contraseñas en los logs.

## Conclusión

La prueba positiva queda FAIL por la denegación de permisos del cliente. No se modificó código de los repositorios ni se tocaron los contenedores locales compartidos o producción.
