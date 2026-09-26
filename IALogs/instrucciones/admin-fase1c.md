# Panel admin, fase 1c: pruebas de Maven con el agente de Mockito (para Codex)

La corrida `2026-09-26_1005-admin-fase1b` pasó todo el escenario, pero `./mvnw clean package`
falló en `auth-svc`: 16 pruebas con "Could not initialize plugin: MockMaker" / "JDK does not
supply a working agent attachment mechanism". Por eso las pruebas de business-svc no corrieron.

La rama `fix/pruebas-mockito-agente` carga Mockito como agente al arrancar surefire (pom raíz).
Solo hay que correr Maven: **no hace falta Docker**.

## Pasos

```bash
cd packtay_backend
git fetch origin && git checkout fix/pruebas-mockito-agente && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-admin-fase1c
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
java -version > "$LOGS/00-java.txt" 2>&1
./mvnw -B clean package > "$LOGS/01-mvn-full.log" 2>&1; echo "exit=$?" >> "$LOGS/01-mvn-full.log"
grep -E "Tests run:|BUILD|FAIL|ERROR\]" "$LOGS/01-mvn-full.log" | head -n 300 > "$LOGS/01-mvn.txt"
```

Esperado: `BUILD SUCCESS` en los dos módulos. En auth-svc deben aparecer `PasswordPinServiceTest`
(15), `PasswordMailServiceTest` (1), `KeycloakIdentityServiceTest` e `IdentityRulesTest`; en
business-svc `SupportEmailsTest`, `SupportRateLimiterTest` y `SupportTicketRulesTest`, además de
las pruebas que ya existían.

Si alguna prueba falla por una aserción (no por Mockito), anota cuál y su mensaje en el resumen.

## Entrega

`RESUMEN.md` con la versión de Java, el conteo de pruebas por módulo y el resultado. Commit y push
a `develop` solo de `IALogs/logs/$RUN` con el mensaje `chore(ialogs): $RUN`.

Nota para el dueño (no para Codex): en la corrida 1b, `PAKTAY_ADMIN_PASSWORD` de `.env.local` no
cumplía la política de Keycloak y Codex la completó solo en memoria. Conviene actualizar ese
valor en la Mac para que `keycloak-init` no falle.
