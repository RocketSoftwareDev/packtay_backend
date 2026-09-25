# Contraseñas por PIN de correo

PAKTAY usa Spring Mail con el mismo host, puerto, usuario y contraseña SMTP local de WeSafeGo. El remitente visible es PAKTAY. Los secretos permanecen en `.env`, ignorado por Git; no se incluyen en la aplicación móvil. Para puerto 465 usar SMTP_SECURE=true; para STARTTLS usar SMTP_SECURE=false y SMTP_STARTTLS=true.

## API

- POST `/api/v1/auth/password-reset/request`: público, `{email}`. Respuesta genérica exista o no una cuenta. Solo envía si existe una identidad habilitada con ese correo.
- POST `/api/v1/auth/password-reset/verify`: público, `{email,pin}`. Valida el PIN y devuelve `{resetToken,expiresIn:600}`.
- POST `/api/v1/auth/password-reset/complete`: público, `{email,resetToken,newPassword}`.
- POST `/api/v1/auth/password/pin`: requiere bearerAuth, cuerpo vacío. El destinatario se obtiene de la identidad autenticada.
- POST `/api/v1/auth/password/verify`: requiere bearerAuth, `{pin}`. Devuelve `{resetToken,expiresIn:600}`.
- PUT `/api/v1/auth/password`: requiere bearerAuth, `{resetToken,newPassword}`. Solo acepta una autorización emitida por el paso de validación. Actualizar la app junto con el backend.

PIN aleatorio de seis dígitos, hash BCrypt, 15 minutos de vigencia, 5 intentos, un envío por minuto y 5 por hora por correo y finalidad. Un reenvío reemplaza el PIN previo. Las finalidades de recuperación y cambio son independientes. La fila se bloquea en PostgreSQL para impedir consumos simultáneos. Los intentos incorrectos se confirman aun cuando la API responde un error. La validación consume el PIN sin cambiar la contraseña y emite un token aleatorio de 256 bits, almacenado solo como hash SHA-256. El token vence en 10 minutos, queda ligado al correo, cuenta y finalidad, y se invalida con un reenvío. El paso final cambia la contraseña en Keycloak antes de consumir el token; un fallo del proveedor permite reintentar. Keycloak y PostgreSQL no comparten una transacción distribuida.

La búsqueda usa correo exacto en Keycloak; como respaldo consulta `app_users` y resuelve su ID en Keycloak. Un perfil local con correo antiguo, inactivo o sin identidad vigente no permite recuperar otra cuenta ni crea una identidad nueva. La recuperación no modifica movimientos ni otros datos financieros. No revoca las sesiones ya abiertas.

`auth-svc` necesita la conexión BUSINESS_DB_* a la base de PAKTAY. Al iniciar crea de manera idempotente `password_pins` mediante `password-schema.sql`, también sobre volúmenes existentes. No requiere ejecutar los scripts de inicialización completos de la base. Los errores de SMTP no se registran con credenciales ni PIN.

## Verificación

Pruebas Java: `MAVEN_USER_HOME=/private/tmp/paktay-m2 sh ./mvnw --batch-mode --no-transfer-progress -pl auth-svc -am test`.
Pruebas móviles: `npx jest --runInBand --watchman=false __tests__/AuthScreens.test.tsx __tests__/ProfileSettingsScreens.test.tsx __tests__/passwordApi.test.ts`; `npx tsc --noEmit`.

Referencias: [Spring Mail](https://docs.spring.io/spring-boot/3.5/reference/io/email.html), [Keycloak Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html).

## Presentación

Ambas pantallas muestran tres pasos: envío, validación y nueva contraseña. Los campos de contraseña solo aparecen tras la validación real del servidor. La autorización se mantiene en memoria de la pantalla y se descarta al reiniciar o completar. El correo incluye HTML con cabecera PAKTAY, tarjeta naranja para el PIN y alternativa de texto plano; se basa en la estructura de tablas y estilos en línea de WeSafeGo.
