#!/usr/bin/env bash
# Día 8c · deja el realm paktay listo para entrar con Apple y Google (token exchange).
#
# Requisitos del servidor Keycloak (26.2): arrancado con
#   --features=token-exchange:v1,admin-fine-grained-authz:v1
# Sin eso, los permisos de los pasos 3 y 4 no existen y el script falla.
#
# Idempotente: se puede repetir. Usa kcadm dentro del contenedor de Keycloak y python3 en
# la máquina para el JSON. Probado en kc-spike (corridas 1954 y 2005 del día 8c).
#
# Variables:
#   KC_CONTAINER        contenedor de Keycloak (con kcadm)            obligatorio
#   KC_ADMIN_USER       admin de master                               por defecto admin
#   KC_ADMIN_PASSWORD   su contraseña                                 obligatorio
#   KC_SERVER           URL de Keycloak vista desde el contenedor     por defecto http://localhost:8080
#   REALM               por defecto paktay
#   GOOGLE_CLIENT_ID    Client ID de iOS de Google (aud del ID token) vacío = no configura Google
#   APPLE_CLIENT_ID     bundle id de la app (aud del ID token)        vacío = no configura Apple
set -euo pipefail

: "${KC_CONTAINER:?Define KC_CONTAINER}"
: "${KC_ADMIN_PASSWORD:?Define KC_ADMIN_PASSWORD}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_SERVER="${KC_SERVER:-http://localhost:8080}"
REALM="${REALM:-paktay}"
GOOGLE_CLIENT_ID="${GOOGLE_CLIENT_ID:-}"
APPLE_CLIENT_ID="${APPLE_CLIENT_ID:-}"

KC="docker exec -i $KC_CONTAINER /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server "$KC_SERVER" --realm master --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" > /dev/null
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
id_of() { $KC get clients -r "$REALM" -q clientId="$1" --fields id --format csv --noquotes; }

RM=$(id_of realm-management)
AS=$(id_of paktay-auth-service)
test -n "$RM" && test -n "$AS"

# 1. Proveedor OIDC por cada uno que tenga Client ID. Solo valida tokens: nunca aparece en
#    una pantalla de Keycloak (hideOnLogin) y el secreto no se usa.
upsert_idp() {
  local alias="$1" issuer="$2" jwks="$3" auth="$4" token="$5" client_id="$6"
  local body
  body=$(python3 - "$alias" "$issuer" "$jwks" "$auth" "$token" "$client_id" <<'PY'
import json, sys
alias, issuer, jwks, auth, token, client_id = sys.argv[1:]
print(json.dumps({
    "alias": alias, "providerId": "oidc", "enabled": True, "trustEmail": True,
    "hideOnLogin": True, "storeToken": False, "linkOnly": False,
    "config": {
        "issuer": issuer, "jwksUrl": jwks, "useJwksUrl": "true", "validateSignature": "true",
        "authorizationUrl": auth, "tokenUrl": token, "clientId": client_id,
        "clientSecret": "sin-uso", "clientAuthMethod": "client_secret_post",
        "syncMode": "IMPORT", "pkceEnabled": "false",
    },
}))
PY
)
  if $KC get "identity-provider/instances/$alias" -r "$REALM" > /dev/null 2>&1; then
    echo "$body" | $KC update "identity-provider/instances/$alias" -r "$REALM" -f -
    echo "idp_updated alias=$alias"
  else
    echo "$body" | $KC create identity-provider/instances -r "$REALM" -f -
    echo "idp_created alias=$alias"
  fi
}

# 2. Política «paktay-auth-service puede cambiar tokens», con el UUID del cliente (con el
#    clientId no coincide con nadie y Keycloak deniega todo: corridas 1922 y 1943).
POLICY_NAME=auth-svc-puede-cambiar
BASE="clients/$RM/authz/resource-server"
POL=$($KC get "$BASE/policy" -r "$REALM" -q name="$POLICY_NAME" | J '(d[0]["id"] if d else "")')
if [ -z "$POL" ]; then
  POL=$($KC create "$BASE/policy/client" -r "$REALM" -s name="$POLICY_NAME" -s "clients=[\"$AS\"]" -i)
  echo "policy_created"
fi

# 3. Permiso token-exchange del proveedor con esa política, sin perder recurso ni scope.
grant_exchange() {
  local alias="$1"
  $KC update "identity-provider/instances/$alias/management/permissions" -r "$REALM" -s enabled=true > /dev/null
  local perm res sco
  perm=$($KC get "identity-provider/instances/$alias/management/permissions" -r "$REALM" | J 'd["scopePermissions"]["token-exchange"]')
  res=$($KC get "$BASE/policy/$perm/resources" -r "$REALM" | J 'json.dumps([r["_id"] for r in d])')
  sco=$($KC get "$BASE/policy/$perm/scopes" -r "$REALM" | J 'json.dumps([s["id"] for s in d])')
  $KC get "$BASE/permission/scope/$perm" -r "$REALM" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);d.update(resources=$res,scopes=$sco,policies=['$POL'],decisionStrategy='UNANIMOUS');print(json.dumps(d))" \
    | $KC update "$BASE/permission/scope/$perm" -r "$REALM" -f -
  echo "exchange_granted alias=$alias"
}

if [ -n "$GOOGLE_CLIENT_ID" ]; then
  upsert_idp google https://accounts.google.com https://www.googleapis.com/oauth2/v3/certs \
    https://accounts.google.com/o/oauth2/v2/auth https://oauth2.googleapis.com/token "$GOOGLE_CLIENT_ID"
  grant_exchange google
fi
if [ -n "$APPLE_CLIENT_ID" ]; then
  upsert_idp apple https://appleid.apple.com https://appleid.apple.com/auth/keys \
    https://appleid.apple.com/auth/authorize https://appleid.apple.com/auth/token "$APPLE_CLIENT_ID"
  grant_exchange apple
fi

# 4. La cuenta de servicio de auth-svc ya tiene manage-users, view-users, query-users y
#    view-realm (keycloak-init). Con eso busca por vínculo, vincula, crea y asigna USER.
echo "social_setup_ok google=$([ -n "$GOOGLE_CLIENT_ID" ] && echo on || echo off) apple=$([ -n "$APPLE_CLIENT_ID" ] && echo on || echo off)"
