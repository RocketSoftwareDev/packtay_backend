# Resumen dia8c-spike · ronda 3d

- Ejecución: 2026-09-30_2005-dia8c-spike
- Entorno: `kc-spike`, Keycloak 26.2, puerto 28190.
- Alcance: local desechable; no se tocaron los contenedores locales compartidos, producción ni el VPS.
- Funciones activas: `token-exchange:v1` y `admin-fine-grained-authz:v1`.

## Resultado

La ronda confirma el flujo final: el usuario se prepara por la API de administración con la cuenta de servicio de `paktay-auth-service`, se vincula o se crea antes del token exchange y el intercambio se realiza sin `audience`.

El usuario existente se encontró por el vínculo después de prepararlo, el POST de federación respondió 204, el token exchange respondió 200 con el mismo `sub` de la cuenta y la contraseña continuó funcionando con HTTP 200.

El usuario `tercero@fake.local` se creó sin contraseña en PAKTAY, se vinculó a `google`, recibió el rol `USER` y el cambio respondió 200. Sus claims contienen `USER`.

La renovación inmediata con `paktay-auth-service` respondió 200 y la renovación con el refresh token rotado también respondió 200. El logout respondió 204 y renovar después respondió 400.

La duración del access token fue `28800` segundos en token exchange, renovación y login normal. El refresh inicial fue `2592000` segundos; las renovaciones devolvieron `2591983` por el tiempo transcurrido.

## Roles temporales de la cuenta de servicio

Para ejecutar la ronda exclusivamente con la cuenta de servicio se otorgaron dentro de `kc-spike` los roles administrativos necesarios: `manage-users`, `query-users`, `view-users`, `manage-user-federated-identity` y `view-realm`. `view-realm` fue necesario para consultar el rol de realm `USER`. No se modificó ningún entorno persistente.

## Archivos

- `04-pruebas.txt`: resultados OK/FAIL.
- `04-buscar-vincular.json`: búsqueda, vínculo y comparación de `sub`.
- `04-tercero-claims.txt`: claims solicitados, sin tokens completos.
- `04-renovar-auth-1.json` y `04-renovar-auth-2.json`: rotación.
- `04-logout.json`: cierre de sesión y renovación posterior.
- `04-duraciones.json`: comparación de duraciones.
- `04-eventos.txt`: eventos sanitizados, sin tokens ni secretos.
