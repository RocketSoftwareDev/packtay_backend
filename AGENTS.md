# Contrato de API y OpenAPI

Toda ruta REST nueva o modificada debe actualizarse en Swagger/OpenAPI dentro del mismo cambio.

- Documenta el propósito, parámetros, cuerpo, respuestas exitosas y errores con anotaciones OpenAPI (`@Tag`, `@Operation`, `@ApiResponse`, `@SecurityRequirement`) cuando no se infieran con claridad.
- Las rutas autenticadas deben declarar el esquema `bearerAuth`; las públicas deben indicarlo explícitamente en su descripción.
- Mantén accesibles `/v3/api-docs/**`, `/swagger-ui/**` y `/swagger-ui.html` en la configuración de seguridad.
- Verifica al terminar que ambos documentos estén disponibles y que Swagger UI cargue sin autenticación.

## Definición de terminado para rutas REST

Ningún agente debe considerar terminada una ruta nueva o modificada hasta completar estas verificaciones con el entorno Docker levantado:

- `auth-svc`: `/actuator/health`, `/v3/api-docs` y `/swagger-ui/index.html` deben responder HTTP 200 en el puerto 8081.
- `business-svc`: `/actuator/health`, `/v3/api-docs` y `/swagger-ui/index.html` deben responder HTTP 200 en el puerto 8082.
- La operación nueva o modificada debe aparecer en el documento OpenAPI del servicio correspondiente, con sus respuestas y requisito de autenticación correctos.
- Si alguna verificación falla, el cambio de la ruta queda incompleto y debe corregirse dentro del mismo trabajo.
