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

## 3b. Segunda ronda (la corrida `1922` respondió 403 «Client not allowed to exchange»)

Mismo Keycloak desechable, mismas reglas. Antes de repetir las pruebas del paso 3:

1. Arranca `kc-spike` con, además de las funciones, `--log-level=INFO,org.keycloak.events:DEBUG`
   y activa en `paktay` el registro de eventos de usuario con detalles. Cada 403 debe dejar en los
   logs del contenedor el evento `TOKEN_EXCHANGE_ERROR` con su motivo; guárdalos en
   `03-eventos.txt` (sin tokens).
2. **Permiso sobre el cliente de destino.** Al pedir `audience=paktay-mobile`, Keycloak también
   exige que `paktay-mobile` permita el cambio: activa los permisos finos en el cliente
   `paktay-mobile` y asocia la misma política de cliente (la que incluye a
   `paktay-auth-service`) a su permiso `token-exchange`.
3. Guarda en `03-permisos.txt` la configuración de autorización del cliente `realm-management`:
   los permisos `token-exchange` del proveedor `google` y de `paktay-mobile`, con su política
   asociada, la estrategia de decisión y el `clientId` que incluye cada política.
4. Prueba en este orden y anota el código y el motivo del evento en cada caso:
   - **a)** el cambio **sin** `audience` (el token sale para `paktay-auth-service`). Si da 200, el
     permiso del proveedor está bien y el problema era el del destino;
   - **b)** el cambio **con** `audience=paktay-mobile`.
5. Si (b) da 200, repite **todas** las pruebas del paso 3. Si solo da 200 (a), repite el paso 3
   sin `audience` y en la prueba 2 renueva con `paktay-auth-service`. Eso significa que la app
   renovará a través de auth-svc.

## 3c. Tercera ronda: comandos exactos (la corrida `1943` repitió el 403 también sin `audience`)

Sin `audience` también falló, así que el permiso que falla es el del **proveedor**. En la
corrida `1943` faltaron `03-eventos.txt` y `03-permisos.txt`: **son obligatorios**. Sin ellos la
corrida no sirve.

Dos errores típicos que estos comandos evitan:
- la política de cliente necesita el **UUID** del cliente, no `paktay-auth-service`;
- actualizar el permiso sin `resources` y `scopes` lo deja sin recurso y lo deniega todo.

Con `kc-spike` ya montado (pasos 1 y 2), y `KC_ADMIN_PW` con la contraseña del admin:

```bash
KC="docker exec -i kc-spike /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server http://localhost:8080 --realm master --user admin --password "$KC_ADMIN_PW"
id_of() { $KC get clients -r paktay -q clientId="$1" --fields id --format csv --noquotes; }
RM=$(id_of realm-management); AS=$(id_of paktay-auth-service); MOB=$(id_of paktay-mobile)
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

$KC update events/config -r paktay -s eventsEnabled=true -s 'enabledEventTypes=[]'
$KC update identity-provider/instances/google/management/permissions -r paktay -s enabled=true
$KC update clients/$MOB/management/permissions -r paktay -s enabled=true
P_IDP=$($KC get identity-provider/instances/google/management/permissions -r paktay | J 'd["scopePermissions"]["token-exchange"]')
P_MOB=$($KC get clients/$MOB/management/permissions -r paktay | J 'd["scopePermissions"]["token-exchange"]')

POL=$($KC create clients/$RM/authz/resource-server/policy/client -r paktay \
  -s name=auth-svc-puede-cambiar -s "clients=[\"$AS\"]" -i)

for P in $P_IDP $P_MOB; do
  BASE=clients/$RM/authz/resource-server
  RES=$($KC get $BASE/policy/$P/resources -r paktay | J 'json.dumps([r["_id"] for r in d])')
  SCO=$($KC get $BASE/policy/$P/scopes -r paktay | J 'json.dumps([s["id"] for s in d])')
  $KC get $BASE/permission/scope/$P -r paktay \
    | python3 -c "import sys,json;d=json.load(sys.stdin);d.update(resources=$RES,scopes=$SCO,policies=['$POL'],decisionStrategy='UNANIMOUS');print(json.dumps(d))" \
    | $KC update $BASE/permission/scope/$P -r paktay -f -
  { echo "== permiso $P"; $KC get $BASE/permission/scope/$P -r paktay
    echo "-- recursos"; $KC get $BASE/policy/$P/resources -r paktay
    echo "-- políticas"; $KC get $BASE/policy/$P/associatedPolicies -r paktay; } >> "$LOGS/03-permisos.txt"
done
{ echo "== política"; $KC get clients/$RM/authz/resource-server/policy/client/$POL -r paktay; echo "AS=$AS"; } >> "$LOGS/03-permisos.txt"
```

En `03-permisos.txt` cada permiso tiene que mostrar **un recurso**, el scope `token-exchange` y
la política `auth-svc-puede-cambiar`, cuyo `clients` contiene el valor de `AS`.

Repite el cambio sin `audience` y con `audience=paktay-mobile`. Después de cada uno:

```bash
$KC get events -r paktay -q type=TOKEN_EXCHANGE_ERROR >> "$LOGS/03-eventos.txt"
```

Si sigue el 403, el campo `details` del evento dice qué comprobación falló: cópialo tal cual en el
resumen. Si da 200, sigue con el paso 5 de la ronda 3b.

## 4. Resumen

`RESUMEN.md`: sintaxis de funciones que sirvió, cada prueba con su resultado, qué cliente
renueva el token, y cualquier ajuste de configuración que hizo falta. Si el cambio no funciona,
explica el error exacto de Keycloak. Commit `chore(ialogs): prueba token exchange ($RUN)` y push
a `develop`.
