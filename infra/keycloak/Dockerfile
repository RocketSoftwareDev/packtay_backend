FROM quay.io/keycloak/keycloak:26.2

# Termina TLS en el proxy (Cloudflare Tunnel); Keycloak atiende HTTP dentro del contenedor.
# Se optimiza durante el build para no reconstruirse en cada arranque.
ENV KC_DB=postgres \
    KC_HTTP_ENABLED=true \
    KC_PROXY_HEADERS=xforwarded \
    KC_HEALTH_ENABLED=true \
    JAVA_OPTS_KC_HEAP="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=70"

RUN /opt/keycloak/bin/kc.sh build

# El realm se importa únicamente durante el primer arranque de una base vacía.
COPY infra/keycloak/paktay-realm.json /opt/keycloak/data/import/paktay-realm.json

ENTRYPOINT ["/bin/sh", "-c"]

# El entorno de despliegue puede fijar el puerto con PORT; el valor 10000 permite
# iniciar también cuando esa variable no existe.
CMD ["exec /opt/keycloak/bin/kc.sh start --optimized --import-realm --http-port=${PORT:-10000}"]
