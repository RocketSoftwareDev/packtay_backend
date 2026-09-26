# Panel admin, fase 1b: repetir la fase 1 con la corrección de compilación (para Codex)

La corrida `2026-09-26_0957-admin-fase1-develop` no compiló: un error de tipos genéricos en
`auth-svc/.../KeycloakIdentityService.java:163` (business-svc quedó sin compilar). La
corrección está en la rama `fix/admin-fase1-compilacion`, que sale de `develop` (ya tiene las
fases 0 y 1 integradas).

## Qué hacer

Sigue **exactamente** `IALogs/instrucciones/admin-fase1.md` (secciones 1 a 4 y la entrega), con
dos cambios:

1. En la sección 1, en lugar de la rama de la fase 1, usa:

   ```bash
   git fetch origin && git checkout fix/admin-fase1-compilacion && git pull --ff-only
   export RUN=$(date +%Y-%m-%d_%H%M)-admin-fase1b
   ```

2. Si la compilación vuelve a fallar, guarda **todos** los `ERROR]` de Maven (no solo el
   primero) en `$LOGS/01b-mvn-error.txt`, de los dos módulos si llega a business-svc, y detente.

Las mismas reglas: nunca `main` ni `paktay-prod`, sin secretos en los logs, commit y push a
`develop` solo de `IALogs/logs/$RUN` con el mensaje `chore(ialogs): $RUN`.
