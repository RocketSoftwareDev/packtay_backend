# Día 8c · prueba previa: sesión sin contraseña con token exchange (para Codex)

Antes de escribir el login con Apple y Google hay que comprobar que Keycloak 26.2 convierte
el token de un proveedor externo en una sesión de PAKTAY (**token exchange de externo a
interno**). Es una función en vista previa, así que se prueba en un **Keycloak desechable**,
nunca en el local compartido (28180) ni en el del VPS.

En vez de Apple y Google reales se usa un **proveedor de mentira**: un segundo realm
(`fakeidp`) del mismo Keycloak desechable que emite ID tokens firmados, como haría Google.

**No cambies código de los repos.** Todo va a `IALogs/logs/`.

## Reglas

- Contenedor propio: `kc-spike`, imagen `quay.io/keycloak/keycloak:26.2` (la misma versión que
  producción), `start-dev`, puerto `28190`. Contraseña de admin aleatoria (`openssl rand`), sin
  escribirla en logs. Al terminar, `docker rm -f kc-spike`.
- No toques `keycloakservices-local`, `paktay-local`, `paktay-prod` ni el VPS.
- Ningún token completo en logs: solo los claims que se piden (decodificados) y los códigos HTTP.
- `export RUN=$(date +%Y-%m-%d_%H%M)-dia8c-spike`, `LOGS=$PWD/IALogs/logs/$RUN` desde
  `packtay_backend` en `develop`.

## 1. Arranque

Arranca con `--features=token-exchange:v1,admin-fine-grained-authz:v1`. Si no arranca con esa
sintaxis, prueba `--features=token-exchange,admin-fine-grained-authz` y anota cuál sirvió. Guarda en
`01-features.txt` la lista de funciones activas que devuelve `GET /admin/serverinfo`
(solo nombre, tipo y si está activa).

## 2. Montaje

1. Importa el realm `paktay` desde `infra/keycloak/paktay-realm.json`. Pon un secreto nuevo al
   cliente `paktay-auth-service`.
2. Crea el realm `fakeidp` con un cliente público `fake-app` (direct grant activo) y dos
   usuarios con correo verificado: `nuevo@fake.local` y `existente@fake.local`.
3. En `paktay`, crea un proveedor de identidad OIDC con alias `google` que apunte a `fakeidp`:
   issuer, `jwksUrl`, validar firma con JWKS, `clientId=fake-app`, `trustEmail=true`, y un
   **first broker login** propio (`social-autolink`) que no pida revisar el perfil y **vincule
   automáticamente** si ya existe un usuario con ese correo («Detect existing broker user» +
   «Automatically set existing user»).
4. Da a `paktay-auth-service` permiso de token exchange sobre ese proveedor, con los permisos
   finos v1 (política de cliente sobre el permiso `token-exchange` del proveedor).
5. En `paktay`, crea el usuario `existente@fake.local` **con contraseña**, antes de las pruebas.

## 3. Pruebas (`02-pruebas.txt`, una línea `OK`/`FAIL` por prueba)

Para cada una, pide un ID token a `fakeidp` (password grant, `scope=openid email profile`) y
cámbialo en `paktay` con `paktay-auth-service`:

```text
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
subject_token=<id_token de fakeidp>
subject_issuer=google
subject_token_type=urn:ietf:params:oauth:token-type:jwt   (si falla, prueba ...:id_token y anota)
requested_token_type=urn:ietf:params:oauth:token-type:refresh_token
audience=paktay-mobile
```

1. **Usuario nuevo** (`nuevo@fake.local`): responde 200 con access y refresh token. Anota los
   claims `iss`, `azp`, `aud`, `sub`, `email`, `realm_access.roles` del access token. Se creó el
   usuario en `paktay` con el correo y un vínculo federado `google`, y **no tiene contraseña**
   (sus credenciales no incluyen `password`).
2. **Renovar desde el móvil:** con ese refresh token, `grant_type=refresh_token` y
   `client_id=paktay-mobile` (cliente público, como hace la app) responde 200. Si no, prueba
   renovar con `paktay-auth-service` y anota cuál funcionó: decide si la app renueva directo en
   Keycloak o a través de auth-svc.
3. **Vincular por correo** (`existente@fake.local`): responde 200, el `sub` es el **mismo** id del
   usuario que ya existía, ahora tiene vínculo `google`, y su contraseña sigue sirviendo
   (password grant con `paktay-mobile` en 200).
4. **Segunda vez:** repetir el cambio del usuario nuevo devuelve el mismo `sub` (no duplica).
5. **Token falso:** un ID token con la firma alterada responde 400 o 401.
6. **Proveedor sin permiso:** el mismo cambio con otro cliente del realm responde 403.
7. **Rol:** si el realm asigna `USER` por defecto, el usuario nuevo lo tiene; si no, anótalo
   (auth-svc tendrá que asignarlo).
8. **Borrar:** eliminar el usuario nuevo por la API de admin con la cuenta de servicio de
   `paktay-auth-service` responde 204.

## 4. Resumen

`RESUMEN.md`: sintaxis de funciones que sirvió, cada prueba con su resultado, qué cliente
renueva el token, y cualquier ajuste de configuración que hizo falta. Si el cambio no funciona,
explica el error exacto de Keycloak. Commit `chore(ialogs): prueba token exchange ($RUN)` y push
a `develop`.
