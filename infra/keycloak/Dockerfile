FROM quay.io/keycloak/keycloak:26.2

# El realm se importa únicamente durante el primer arranque de una base vacía.
COPY infra/keycloak/paktay-realm.json /opt/keycloak/data/import/paktay-realm.json

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
