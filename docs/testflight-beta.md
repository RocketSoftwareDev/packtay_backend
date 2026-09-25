# Beta en TestFlight con `develop`

Cómo pasar a todos los testers a la versión nueva (días 1 a 7 + Firebase) sin perder sus
datos. La parte técnica la ejecuta Codex en la Mac con `IALogs/instrucciones/testflight.md`;
esta guía dice qué hace cada uno y qué pasa si algo sale mal.

## Decisión tomada

- Producción (`paktay-prod` en la Mac, detrás de Cloudflare) pasa de `main` a `develop`.
  Los testers conservan su cuenta, tarjetas, categorías y gastos.
- El TestFlight actual (build de `main`) deja de funcionar bien contra el servidor nuevo:
  todos deben instalar el build nuevo. Por eso se expira el build viejo al publicar el nuevo.
- `main` no se toca: sigue siendo el código del build viejo, por si hay que volver atrás.
- La infraestructura (VPS, CI) y los pagos quedan para el final, cuando los testers aprueben.
  Mientras tanto la Mac tiene que estar prendida y con Docker corriendo.

## Qué hace cada uno

| Paso | Quién | Qué |
|---|---|---|
| 0 | Dueño | Integrar a `develop` el PR de `feature/firebase-push` (front) |
| 1 | Codex | Respaldo completo de la base de producción, fuera de Docker y fuera del repo |
| 2 | Codex | Revisar cómo está la base de producción (historial de Flyway y esquema) |
| 3 | Codex | **Ensayo:** restaurar el respaldo en `paktay-fresh` y migrar hasta V8. Si falla, se detiene aquí |
| 4 | Codex | Desplegar `develop` en `paktay-prod` y comprobar salud por las URLs públicas |
| 5 | Codex | Build de TestFlight desde `develop` (versión nueva) y subirlo |
| 6 | Dueño | En App Store Connect: textos de «Qué probar», grupo de testers, expirar el build viejo |
| 7 | Dueño | Probar en su iPhone: instalar, entrar, y que llegue un aviso de presupuesto |
| 8 | Testers | Actualizar desde TestFlight y probar la lista de abajo |

## Si algo sale mal

- **El ensayo falla (paso 3):** producción no se tocó. Codex sube el error a IALogs y se
  corrige en código antes de volver a intentar.
- **El despliegue falla o la app nueva no funciona (paso 4 o 7):** vuelta atrás con el
  respaldo del paso 1 y el código de `main` (comandos exactos en las instrucciones de Codex,
  sección «Vuelta atrás»). Los gastos que los testers hayan registrado entre el despliegue y la
  vuelta atrás se perderían, por eso el paso 7 se hace apenas termina el 5.

## Qué pedirles a los testers

Texto para «Qué probar» en TestFlight:

> PAKTAY beta nueva. Tu cuenta y tus gastos siguen ahí. Prueba:
> 1. Entrar con tu correo y ver tu Inicio del mes (presupuesto, categorías, tarjetas).
> 2. Tocar una categoría o una tarjeta del Inicio: abre sus movimientos del mes.
> 3. Pagar con Wallet: si el comercio ya lo asignaste antes, se guarda solo; si es nuevo,
>    queda en «Por revisar». Asígnalo y la próxima vez se guarda solo.
> 4. Un pago muy grande (más de 500 USD o 3 veces lo normal) queda en «Por revisar»;
>    prueba «Fue en otra moneda».
> 5. Agregar un gasto a mano con fecha de hoy, ayer u otro día (hasta 7 días atrás).
> 6. Editar un gasto y anularlo.
> 7. En Categorías, entrar a una categoría y mover un comercio a otra.
> 8. Poner «Mi límite» a una tarjeta y ver el aviso al acercarte al 90 %.
> 9. Revisar Perfil › Avisos y aceptar las notificaciones.
> 10. Opcional (con una cuenta de prueba, no la tuya): Perfil › Eliminar mi cuenta.
> Reporta con captura desde TestFlight (toma una captura de pantalla y toca «Enviar a
> TestFlight»). Cuenta qué hiciste, qué esperabas y qué pasó.

## Checklist del dueño en el iPhone (paso 7)

1. Instalar el build nuevo desde TestFlight y entrar con tu cuenta.
2. Aceptar las notificaciones (onboarding o Perfil › Avisos).
3. En una categoría con presupuesto, agregar un gasto a mano que la lleve por encima del
   90 %. Debe llegar «<Categoría> llegó al 90 %» en unos segundos.
4. Si no llega: en Firebase › Messaging › «Notificación de prueba» no hay token a mano (en
   TestFlight no se escribe en el registro), así que avisa y Codex revisa los logs del
   servidor (`budget_alert … outcome=SENT|FAILED|INVALID_TOKEN`, `budget_alert_no_device`,
   `push_sender_ready enabled=…`).
5. Pagar con Wallet en un comercio nuevo: debe llegar «Pago por revisar» (aviso local del
   iPhone, no depende de Firebase).
