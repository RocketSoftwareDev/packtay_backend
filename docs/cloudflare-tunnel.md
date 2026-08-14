# Publicar Paktay desde la Mac con Cloudflare Tunnel

El túnel se ejecuta como un contenedor dentro de la misma red de Docker Compose. Cloudflare
termina HTTPS y `cloudflared` conecta cada hostname con su servicio interno; PostgreSQL no se
publica.

## Hostnames públicos

Configura tres aplicaciones públicas en el túnel administrado de Cloudflare:

| Hostname de ejemplo | Servicio interno |
| --- | --- |
| `paktaykeycloak.rocketsoftwarecore.com` | `http://keycloak:8080` |
| `auth.example.com` | `http://auth-svc:8081` |
| `api.example.com` | `http://business-svc:8082` |

Agrega al final una regla de respaldo con servicio `http_status:404`.

## Variables locales

En `.env`, sin confirmar el token en Git:

```dotenv
CLOUDFLARE_TUNNEL_TOKEN=<token entregado por Cloudflare>
KEYCLOAK_PUBLIC_URL=https://login.example.com
KEYCLOAK_ISSUER_URI=https://login.example.com/realms/paktay
```

## Inicio

```bash
docker compose -f docker-compose.yml -f docker-compose.cloudflare.yml \
  --profile tunnel up --build -d
```

El frontend móvil debe utilizar `https://auth.example.com` para autenticación y
`https://api.example.com` para negocio. Keycloak publicará sus endpoints con
`https://login.example.com`; nunca debe configurarse `localhost` en una compilación móvil.

## Comprobaciones

```bash
curl https://auth.example.com/actuator/health
curl https://api.example.com/actuator/health
curl https://login.example.com/realms/paktay/.well-known/openid-configuration
```

Swagger queda disponible en `/swagger-ui/index.html` en los hostnames `auth` y `api`.
Para un entorno público real conviene limitar Swagger con una política de Cloudflare Access
basada en ruta, sin aplicar esa política a los endpoints consumidos por la aplicación móvil.
