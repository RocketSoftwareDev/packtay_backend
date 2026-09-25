# RUN - Día 6: HALLADO BUILD ROTO (2026-09-24_1901-dia6) - NO EJECUTADO

La prueba del día 6 se intentó pero se **detuvo a propósito** por un build roto en
`feature/dia6`. Decisión del dueño: solo reportar, sin tocar código.

## Qué falla

`./mvnw -B clean package` → **BUILD FAILURE** (57 tests, 1 fallo):

```
org.opentest4j.AssertionFailedError: expected: <123> but was: <123 456>
[ERROR]   MerchantKeyTest.siEmpiezaConNumerosUsaSusLetras:32 expected: <123> but was: <123 456>
[ERROR] Tests run: 57, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

## Causa (análisis, sin cambios)

- `MerchantKeyTest.siEmpiezaConNumerosUsaSusLetras:32` asume:
  `MerchantKey.ruleKey("123 456")` → `"123"`.
- `MerchantKey.ruleKey` (business-svc/src/main/java/ec/paktay/business/service/MerchantKey.java:31)
  devuelve `"123 456"` para ese input:
  - Primer `for`: la palabra "123" contiene dígitos → `break` inmediato, `kept` queda vacío.
  - Fallback (kept.isEmpty): quita dígitos de cada palabra; "123"→"" y "456"→"", nada se agrega.
  - Cae al `return normalized` (el texto completo) → "123 456".
- Inconsistencia: el test espera el primer bloque numérico cuando NO hay letras; la
  implementación devuelve el comercio completo. El caso `"7ELEVEN 1234"` → `"ELEVEN"` sí pasa.

## Consecuencias

Sin el jar compilado no se levantó la rama, no se aplicó V6, no se corrió el escenario
(dia6.py), ni los pasos de front. Los contenedores `paktay-local` siguieron como estaban
con el jar del día 5b; Mailpit no se creó (se preparó el comando, no se ejecutó).

No se commitó nada: el RUN queda únicamente con la evidencia del build (`01-mvn.txt`) y
este análisis para que el equipo arregle `MerchantKey` (o el test) y re-lance la prueba del
día 6.