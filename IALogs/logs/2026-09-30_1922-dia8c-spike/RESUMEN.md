# Día 8c - resultado

- RUN: 2026-09-30_1922-dia8c-spike
- Keycloak temporal: kc-spike, Keycloak 26.2.5, puerto 28190.
- La sintaxis --features=token-exchange:v1,admin-fine-grained-authz:v1 arrancó correctamente.
- Se montaron los realms paktay y fakeidp, el proveedor OIDC google, el flujo social-autolink, los usuarios fake y la cuenta existente.
- Resultado bloqueante: el intercambio externo a interno devolvió HTTP 403 con access_denied: Client not allowed to exchange. La política fine-grained del cliente quedó asociada al permiso token-exchange, pero Keycloak 26.2.5 siguió rechazando al cliente.
- Por ese bloqueo no se validaron creación, vinculación, renovación, rol ni borrado. El caso de firma alterada tampoco llegó a la validación criptográfica.
- No se escribieron tokens completos ni secretos en los logs.

## Conclusión

La prueba positiva queda FAIL por la denegación de permisos del cliente. No se modificó código de los repositorios ni se tocaron los contenedores locales compartidos o producción.
