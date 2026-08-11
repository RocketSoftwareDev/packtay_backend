FROM quay.io/keycloak/keycloak:26.2

# El realm se importa únicamente durante el primer arranque de una base vacía.
COPY infra/keycloak/paktay-realm.json /opt/keycloak/data/import/paktay-realm.json

ENTRYPOINT ["/bin/sh", "-c"]

# Render expone el puerto mediante PORT. El valor 10000 permite iniciar también
# cuando esa variable no existe fuera de Render.
CMD ["exec /opt/keycloak/bin/kc.sh start --import-realm --http-port=${PORT:-10000}"]
