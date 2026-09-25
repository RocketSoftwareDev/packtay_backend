# Día 6b: correcciones del día 6 (para Codex)

La corrida `2026-09-24_1909-dia6` dejó 4 hallazgos. Se corrigieron en las ramas
`fix/dia6-correcciones` de backend y front (salen de `develop`):

1. V7 devuelve a `protect_expense_update` las reglas de V4 (la tarjeta de un gasto manual
   se puede cambiar, un anulado no se reactiva, período cerrado en la zona del usuario) sin
   perder la purga de V6.
2. auth-svc acepta `SMTP_FROM` como remitente (vacío = `SMTP_USER`).
3. Front: error TS2367 en `merchantRules.ts`.
4. Front: las 2 pruebas de Jest que fallaban.

**No cambies código.** Repite **toda** la guía `IALogs/instrucciones/dia6.md` con estas
diferencias:

- En backend y front usa `git checkout fix/dia6-correcciones && git pull --ff-only` en lugar
  de `feature/dia6`.
- `RUN=$(date +%Y-%m-%d_%H%M)-dia6b`.
- La sección "Reglas actuales antes de migrar" ya no aplica (V6 ya está en la base local):
  sáltala y guarda sólo `05b-reglas-despues.txt`.
- En el historial de Flyway (local y desde cero) debe aparecer V7.
- En el resumen, marca explícitamente estas cuatro líneas: "un manual sí cambia de tarjeta",
  "llegó un correo a la cuenta", tsc y Jest.

Subida igual que en el día 6, con el mensaje `chore(ialogs): prueba del día 6b ($RUN)`.
