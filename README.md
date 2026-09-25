# Paktay Backend

Servicios Java 17: `auth-svc` gestiona autenticación y cuentas; `business-svc`
gestiona las operaciones financieras y las migraciones de PostgreSQL.
Las identidades pertenecen al Keycloak Services externo, compartido con otros
proyectos. Este repositorio no levanta otra instancia ni otra base de Keycloak.

## Configuración y arranque

1. Copia `.env.example` a `.env` y completa las credenciales.
2. Configura `SHARED_KEYCLOAK_INTERNAL_URL` con la dirección de Keycloak Services
   accesible desde Docker. `KEYCLOAK_PUBLIC_URL` y `KEYCLOAK_ISSUER_URI` deben
   coincidir con la URL pública y el emisor del realm `paktay`.
3. Configura proyecto, puertos y volumen en el mismo `.env`.
4. Ejecuta `docker compose up -d --build`.

El único archivo Compose sirve para desarrollo y producción. Los secretos viven
en `.env`, excluido de Git y del contexto de construcción Docker. La imagen se
compila con Maven dentro de Docker. Para ejecutar las pruebas: `./mvnw test`.

Valores locales habituales: Auth `8081`, Business `8082`, PostgreSQL `5433` y
Keycloak externo `8180`. Para convivir con otro entorno, configura otro
`COMPOSE_PROJECT_NAME`, otros puertos y otro `BUSINESS_DATA_VOLUME`. En Linux,
la URL interna debe resolver al servicio de Keycloak desde los contenedores.

## Keycloak Services

El realm `paktay` debe existir en Keycloak Services con los clientes
`paktay-mobile` y `paktay-auth-service`, el usuario administrador de Paktay y
los roles correspondientes. `infra/keycloak/paktay-realm.json` contiene la
plantilla para aprovisionarlo en una instalación nueva.

Si necesitas sincronizar el secreto técnico, la contraseña del administrador de
Paktay, los tiempos de sesión y los permisos del cliente técnico, ejecuta:

```sh
docker compose --profile identity-setup run --rm keycloak-init
```

Este paso modifica el realm compartido y se ejecuta expresamente cuando cambian
esas credenciales; no se repite al arrancar los servicios. Los servicios Java
solo reciben el secreto de su cliente, no las credenciales de administración.

## Producción

Usa `.env` con las URLs HTTPS públicas, las credenciales del destino y el nombre
del volumen de negocio existente. Configura `BUSINESS_DATA_VOLUME` explícitamente
si el volumen proviene de un despliegue anterior. Los puertos se publican en
loopback por defecto; `API_BIND_ADDRESS` permite cambiar la interfaz de las APIs.

Con Cloudflare Tunnel, completa `CLOUDFLARE_TUNNEL_TOKEN` y ejecuta:

```sh
docker compose --profile tunnel up -d --build
```

Las rutas del túnel deben apuntar a `http://auth-svc:8081` y
`http://business-svc:8082`. El acceso público a Keycloak lo administra Keycloak
Services. Sin túnel, usa un proxy HTTPS del servidor hacia los puertos de las APIs.

Flyway aplica automáticamente las migraciones V1–V8 al arrancar `business-svc`.
Una base nueva recibe esquema y catálogos. Una base existente conserva sus datos
y aplica las migraciones pendientes; ver [database/README.md](database/README.md).
Para empezar de cero, usa un volumen nuevo y conserva el anterior como respaldo.

## Verificación

En los puertos configurados, ambos servicios deben responder HTTP 200 en:

- `/actuator/health`
- `/v3/api-docs`
- `/swagger-ui/index.html`

Los healthchecks comprueban Keycloak y, para Business, PostgreSQL. El correo no
condiciona la salud de Auth. Los avisos push se habilitan configurando
`FIREBASE_CREDENTIALS_HOST_FILE` con la ruta del JSON privado de Firebase en el
servidor; el archivo se monta en modo lectura y no se incorpora a la imagen.
