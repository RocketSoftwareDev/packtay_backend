# Regresión local de develop

- Corrida: 2026-09-30_1625-regresion-develop
- Backend develop: `a71a27f9d13b2a19bd0213c2fe74e559d743e946`
- Web admin develop: `97038956f4e5f107daa8c3d1fd5b1315fc2ae4a0`
- Mobile develop: `fefaab29ce657440fe449902871f7823169a8e9a`
- Entorno: `paktay-local` y `keycloakservices` local.
- Producción y VPS: no utilizados.

## Resultados

- Backend `./mvnw -B test`: OK.
- Mobile `npx tsc --noEmit`: OK.
- Mobile `npx jest --ci`: OK.
- Mobile `npm run lint`: OK.
- Web local `/login`: HTTP 200.
- Auth local health: HTTP 200.
- Business local health: HTTP 200.
- Keycloak local realm `paktay`: HTTP 200.

La guía VPS nueva quedó sin ejecutar porque requiere inspeccionar o preparar producción; esta corrida se mantuvo exclusivamente local.
