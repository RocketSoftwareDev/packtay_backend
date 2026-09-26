# Panel admin, seguridad 1

| Verificación | Resultado |
|---|---|
| Rama | `feature/admin-seguridad-cors-cabecera` |
| Maven auth-svc | OK: 23 pruebas, 0 fallos |
| Maven business-svc | OK: 73 pruebas, 0 fallos |
| Build | `BUILD SUCCESS` |
| Matriz de cabecera | OK: admin requiere `X-Paktay-Client: admin-web`; móvil y rutas públicas no cambian |
| CORS local | OK: panel `localhost:3000` permitido y dominio ajeno rechazado |
| CORS simulado | OK: `admin.paktay.test` solo admin; `soporte.paktay.test` solo público |

La copia temporal del script corrigió el truncamiento de la respuesta JSON del login para poder leer el JWT en memoria; no se imprimieron tokens. No se tocó producción.
