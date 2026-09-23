-- Paktay V2: quita lo que ningún código usa desde la limpieza del 2026-09-22.
--
-- - Cola de pendientes del atajo en servidor: la cola vive en el teléfono.
-- - Credencial del atajo y pagos no registrados: rutas eliminadas.
-- - Cuotas e ingresos: fuera de alcance por decisión de producto.
-- - Vista de resumen mensual: nadie la consulta.
-- - app_users.is_automatic y expenses.pending_movement_id: sin lectores.
--
-- Columnas que el código todavía lee (cards.last4, expenses.amount_usd,
-- expenses.exchange_rate_to_usd) se quitan en una migración posterior junto con su
-- cambio de código.

drop view if exists public.v_monthly_expense_summary;

-- Cuotas
drop table if exists public.installment_payment_allocations cascade;
drop table if exists public.installment_payments cascade;
drop table if exists public.installments cascade;
drop table if exists public.installment_plans cascade;
drop function if exists public.sync_installment_payment_status();
drop function if exists public.validate_installment_ownership();
drop function if exists public.validate_installment_plan_math();
drop function if exists public.validate_payment_allocations();
drop type if exists public.installment_payment_type;
drop type if exists public.installment_plan_status;
drop type if exists public.installment_status;

-- Cola de pendientes del atajo
alter table public.expenses drop column if exists pending_movement_id;
drop table if exists public.pending_movements cascade;
drop function if exists public.validate_pending_suggestions_ownership();
drop type if exists public.pending_movement_status;

-- Credencial del atajo, pagos no registrados e ingresos
drop table if exists public.shortcut_credentials cascade;
drop table if exists public.unregistered_payments cascade;
drop table if exists public.monthly_incomes cascade;

-- Preferencia sin lectores
alter table public.app_users drop column if exists is_automatic;
