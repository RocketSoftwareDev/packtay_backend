-- Paktay v0.15 — vigencia mensual del valor plantilla de presupuestos.
begin;

alter table user_budget_settings
    add column if not exists recurrence varchar(16) not null default 'THIS_MONTH';

alter table user_budget_settings
    drop constraint if exists user_budget_settings_recurrence_check;
alter table user_budget_settings
    add constraint user_budget_settings_recurrence_check
    check (recurrence in ('THIS_MONTH', 'MONTHLY'));

comment on column user_budget_settings.global_amount is
    'Valor plantilla aplicado individualmente a cada categoría heredada; no representa una bolsa ni una sumatoria.';
comment on column user_budget_settings.recurrence is
    'THIS_MONTH limita la plantilla al período actual; MONTHLY la replica al crear el siguiente período.';

commit;
