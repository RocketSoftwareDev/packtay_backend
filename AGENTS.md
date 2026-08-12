# Contrato de API y OpenAPI

Toda ruta REST nueva o modificada debe actualizarse en Swagger/OpenAPI dentro del mismo cambio.

- Documenta el propósito, parámetros, cuerpo, respuestas exitosas y errores con anotaciones OpenAPI (`@Tag`, `@Operation`, `@ApiResponse`, `@SecurityRequirement`) cuando no se infieran con claridad.
- Las rutas autenticadas deben declarar el esquema `bearerAuth`; las públicas deben indicarlo explícitamente en su descripción.
- Mantén accesibles `/v3/api-docs/**`, `/swagger-ui/**` y `/swagger-ui.html` en la configuración de seguridad.
- Verifica al terminar que ambos documentos estén disponibles y que Swagger UI cargue sin autenticación.
