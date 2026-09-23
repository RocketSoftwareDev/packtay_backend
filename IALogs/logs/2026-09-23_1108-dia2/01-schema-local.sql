--
-- PostgreSQL database dump
--

\restrict jYYeY4N9mysNVDaD1Cw2y1M9Q1zpS1p0V0pyoICgG11gaTZQA2apGFlQbt5Bsln

-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: audit_action; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.audit_action AS ENUM (
    'CREATE',
    'UPDATE',
    'DEACTIVATE',
    'CONFIRM',
    'DISCARD',
    'CLOSE_PERIOD',
    'EXPORT'
);


--
-- Name: budget_scope; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.budget_scope AS ENUM (
    'CARD',
    'CATEGORY',
    'CARD_CATEGORY'
);


--
-- Name: card_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.card_status AS ENUM (
    'ACTIVE',
    'INACTIVE'
);


--
-- Name: category_origin; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.category_origin AS ENUM (
    'SYSTEM',
    'CUSTOM'
);


--
-- Name: expense_origin; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.expense_origin AS ENUM (
    'MANUAL',
    'AUTOMATIC'
);


--
-- Name: installment_payment_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.installment_payment_type AS ENUM (
    'INSTALLMENT',
    'EARLY_SETTLEMENT'
);


--
-- Name: installment_plan_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.installment_plan_status AS ENUM (
    'ACTIVE',
    'SETTLED'
);


--
-- Name: installment_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.installment_status AS ENUM (
    'PENDING',
    'PARTIALLY_PAID',
    'PAID',
    'SETTLED_EARLY'
);


--
-- Name: pending_movement_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.pending_movement_status AS ENUM (
    'PENDING',
    'CONFIRMED',
    'DISCARDED'
);


--
-- Name: create_user_system_categories(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_user_system_categories(p_user_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
begin
    -- Intencionalmente vacío: POST /api/v1/user/categories/from-system/{id}
    -- materializa únicamente las subcategorías elegidas por el usuario.
    return;
end;
$$;


--
-- Name: FUNCTION create_user_system_categories(p_user_id uuid); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.create_user_system_categories(p_user_id uuid) IS 'Compatibilidad con instalaciones anteriores; no clona el catálogo automáticamente.';


--
-- Name: protect_expense_update(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_expense_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Los gastos no se pueden eliminar';
    end if;
    if new.user_id <> old.user_id or new.card_id <> old.card_id or new.occurred_at <> old.occurred_at then
        raise exception 'El usuario, la tarjeta y la fecha de un gasto son inmutables';
    end if;
    if old.origin = 'AUTOMATIC' and new.amount <> old.amount then
        raise exception 'No se puede modificar el monto de un gasto automático';
    end if;
    if exists (
        select 1 from financial_periods p
         where p.user_id = old.user_id
           and p.period_month = date_trunc('month', old.occurred_at)::date
           and p.closed_at is not null
    ) then
        raise exception 'No se puede modificar un gasto de un período cerrado';
    end if;
    return new;
end;
$$;


--
-- Name: reject_audit_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'audit_log es append-only';
end;
$$;


--
-- Name: seed_user_system_categories(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.seed_user_system_categories() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    perform create_user_system_categories(new.id);
    return new;
end;
$$;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    new.updated_at := now();
    return new;
end;
$$;


--
-- Name: sync_installment_payment_status(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sync_installment_payment_status() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare
    v_installment_id uuid := case when tg_op = 'DELETE' then old.installment_id else new.installment_id end;
    v_plan_id uuid;
    v_paid numeric(12,2);
    v_scheduled numeric(12,2);
    v_settled_early boolean;
begin
    select installment_plan_id, scheduled_amount into v_plan_id, v_scheduled
      from installments where id = v_installment_id;
    if not found then
        return null;
    end if;
    select coalesce(sum(amount), 0) into v_paid
      from installment_payment_allocations where installment_id = v_installment_id;
    select exists (
        select 1 from installment_payment_allocations a
        join installment_payments ip on ip.id = a.payment_id
         where a.installment_id = v_installment_id
           and ip.payment_type = 'EARLY_SETTLEMENT'
    ) into v_settled_early;
    update installments
       set status = case when v_paid = 0 then 'PENDING'::installment_status
                         when v_paid < v_scheduled then 'PARTIALLY_PAID'::installment_status
                         when v_settled_early then 'SETTLED_EARLY'::installment_status
                         else 'PAID'::installment_status end,
           settled_at = case when v_paid >= v_scheduled then now() else null end
     where id = v_installment_id;
    update installment_plans p
       set status = case when not exists (
                                select 1 from installments i
                                 where i.installment_plan_id = v_plan_id
                                   and i.status in ('PENDING', 'PARTIALLY_PAID')
                            ) then 'SETTLED'::installment_plan_status
                         else 'ACTIVE'::installment_plan_status end,
           settled_at = case when not exists (
                                select 1 from installments i
                                 where i.installment_plan_id = v_plan_id
                                   and i.status in ('PENDING', 'PARTIALLY_PAID')
                            ) then now() else null end
     where p.id = v_plan_id;
    return null;
end;
$$;


--
-- Name: validate_budget_ownership(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_budget_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if not exists (select 1 from financial_periods p where p.id = new.period_id and p.user_id = new.user_id) then
        raise exception 'El período no pertenece al usuario';
    end if;
    if new.card_id is not null and not exists (select 1 from cards c where c.id = new.card_id and c.user_id = new.user_id) then
        raise exception 'La tarjeta no pertenece al usuario del presupuesto';
    end if;
    if new.category_id is not null and not exists (select 1 from user_categories c where c.id = new.category_id and c.user_id = new.user_id) then
        raise exception 'La categoría no pertenece al usuario del presupuesto';
    end if;
    return new;
end;
$$;


--
-- Name: validate_category_rule_ownership(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_category_rule_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if not exists (
        select 1 from user_categories c
         where c.id = new.category_id and c.user_id = new.user_id
    ) then
        raise exception 'La categoría no pertenece al usuario de la selección';
    end if;
    return new;
end;
$$;


--
-- Name: validate_expense_ownership(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_expense_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if not exists (select 1 from cards c where c.id = new.card_id and c.user_id = new.user_id) then
        raise exception 'La tarjeta no pertenece al usuario del gasto';
    end if;
    if not exists (select 1 from user_categories c where c.id = new.category_id and c.user_id = new.user_id) then
        raise exception 'La categoría no pertenece al usuario del gasto';
    end if;
    if tg_op = 'INSERT' and not exists (select 1 from cards c where c.id = new.card_id and c.status = 'ACTIVE') then
        raise exception 'No se puede registrar un gasto en una tarjeta inactiva';
    end if;
    return new;
end;
$$;


--
-- Name: validate_installment_ownership(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_installment_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if tg_table_name = 'installment_plans' then
        if not exists (
            select 1 from expenses e
             where e.id = new.expense_id
               and e.user_id = new.user_id
               and e.amount = new.original_amount
        ) then
            raise exception 'El gasto no pertenece al usuario o el monto del plan no coincide con el gasto';
        end if;
    elsif tg_table_name = 'installments' then
        if not exists (select 1 from installment_plans p where p.id = new.installment_plan_id and p.user_id = new.user_id) then
            raise exception 'El plan no pertenece al usuario de la cuota';
        end if;
    elsif tg_table_name = 'installment_payments' then
        if not exists (select 1 from installment_plans p where p.id = new.installment_plan_id and p.user_id = new.user_id) then
            raise exception 'El plan no pertenece al usuario del pago';
        end if;
    elsif tg_table_name = 'installment_payment_allocations' then
        if not exists (
            select 1
              from installment_payments ip
              join installments i on i.id = new.installment_id
             where ip.id = new.payment_id
               and ip.user_id = new.user_id
               and i.user_id = new.user_id
               and i.installment_plan_id = ip.installment_plan_id
        ) then
            raise exception 'La asignación debe pertenecer al mismo usuario y plan de cuotas';
        end if;
    end if;
    return new;
end;
$$;


--
-- Name: validate_installment_plan_math(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_installment_plan_math() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare
    v_plan_id uuid;
    v_expected_count integer;
    v_original_amount numeric(12,2);
    v_actual_count integer;
    v_min_sequence integer;
    v_max_sequence integer;
    v_total numeric(12,2);
begin
    v_plan_id := case when tg_table_name = 'installment_plans' then new.id
                      when tg_op = 'DELETE' then old.installment_plan_id
                      else new.installment_plan_id end;
    select installment_count, original_amount
      into v_expected_count, v_original_amount
      from installment_plans where id = v_plan_id;
    if not found then
        return null; -- plan eliminado en cascada
    end if;
    select count(*), min(sequence_number), max(sequence_number), coalesce(sum(scheduled_amount), 0)
      into v_actual_count, v_min_sequence, v_max_sequence, v_total
      from installments where installment_plan_id = v_plan_id;
    if v_actual_count <> v_expected_count
       or v_min_sequence <> 1
       or v_max_sequence <> v_expected_count
       or v_total <> v_original_amount then
        raise exception 'Las cuotas deben ser consecutivas y sumar exactamente el monto original';
    end if;
    return null;
end;
$$;


--
-- Name: validate_payment_allocations(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_payment_allocations() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare
    v_payment_id uuid := case when tg_op = 'DELETE' then old.payment_id else new.payment_id end;
    v_payment_total numeric(12,2);
    v_allocated_total numeric(12,2);
    v_plan_id uuid;
begin
    select total_amount, installment_plan_id into v_payment_total, v_plan_id
      from installment_payments where id = v_payment_id;
    if not found then
        return null; -- comprobante eliminado en cascada
    end if;
    select coalesce(sum(amount), 0) into v_allocated_total
      from installment_payment_allocations where payment_id = v_payment_id;
    if v_allocated_total <> v_payment_total then
        raise exception 'La suma de asignaciones debe coincidir con el total pagado';
    end if;
    if exists (
        select 1
          from installments i
          join installment_payment_allocations a on a.installment_id = i.id
         where i.installment_plan_id = v_plan_id
         group by i.id, i.scheduled_amount
        having sum(a.amount) > i.scheduled_amount
    ) then
        raise exception 'Un pago no puede exceder el valor programado de una cuota';
    end if;
    return null;
end;
$$;


--
-- Name: validate_pending_suggestions_ownership(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_pending_suggestions_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new.suggested_card_id is not null and not exists (
        select 1 from cards c where c.id = new.suggested_card_id and c.user_id = new.user_id
    ) then
        raise exception 'La tarjeta sugerida no pertenece al usuario';
    end if;
    if new.suggested_category_id is not null and not exists (
        select 1 from user_categories c where c.id = new.suggested_category_id and c.user_id = new.user_id
    ) then
        raise exception 'La categoría sugerida no pertenece al usuario';
    end if;
    return new;
end;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: app_users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_users (
    id uuid NOT NULL,
    email character varying(320),
    display_name character varying(120),
    avatar_url character varying(1000),
    avatar_object_path character varying(500),
    avatar_updated_at timestamp with time zone,
    is_automatic boolean DEFAULT false NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    deactivated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT app_users_check CHECK (((((status)::text = 'ACTIVE'::text) AND (deactivated_at IS NULL)) OR (((status)::text = 'INACTIVE'::text) AND (deactivated_at IS NOT NULL)))),
    CONSTRAINT app_users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);


--
-- Name: TABLE app_users; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.app_users IS 'Usuario local de Paktay identificado por el claim sub de Keycloak; no almacena credenciales ni JWT.';


--
-- Name: COLUMN app_users.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.app_users.id IS 'UUID sub emitido por Keycloak.';


--
-- Name: COLUMN app_users.is_automatic; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.app_users.is_automatic IS 'Preferencia que habilita o deshabilita el procesamiento automático para el usuario.';


--
-- Name: COLUMN app_users.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.app_users.status IS 'Estado de acceso administrado por el backend: ACTIVE o INACTIVE.';


--
-- Name: COLUMN app_users.deactivated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.app_users.deactivated_at IS 'Fecha y hora de desactivación de la cuenta.';


--
-- Name: COLUMN app_users.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.app_users.created_at IS 'Fecha y hora de creación del registro local.';


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    actor_user_id uuid,
    subject_user_id uuid NOT NULL,
    entity_type character varying(60) NOT NULL,
    entity_id uuid,
    action public.audit_action NOT NULL,
    before_value jsonb,
    after_value jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE audit_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.audit_log IS 'Bitácora append-only de acciones relevantes; no permite actualización ni eliminación.';


--
-- Name: COLUMN audit_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.id IS 'Identificador interno de la entrada de auditoría.';


--
-- Name: COLUMN audit_log.actor_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.actor_user_id IS 'Usuario que ejecutó la acción; puede ser nulo para procesos técnicos.';


--
-- Name: COLUMN audit_log.subject_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.subject_user_id IS 'Usuario dueño de los datos afectados.';


--
-- Name: COLUMN audit_log.entity_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.entity_type IS 'Nombre lógico de la entidad afectada.';


--
-- Name: COLUMN audit_log.entity_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.entity_id IS 'Identificador de la entidad afectada cuando exista.';


--
-- Name: COLUMN audit_log.action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.action IS 'Tipo de operación realizada.';


--
-- Name: COLUMN audit_log.before_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.before_value IS 'Instantánea JSON anterior a una modificación.';


--
-- Name: COLUMN audit_log.after_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.after_value IS 'Instantánea JSON posterior a una modificación.';


--
-- Name: COLUMN audit_log.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_log.created_at IS 'Fecha y hora inmutable del evento de auditoría.';


--
-- Name: bank_card_offerings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bank_card_offerings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    bank_id uuid NOT NULL,
    card_type character varying(10) NOT NULL,
    brand character varying(20),
    source_url character varying(500),
    verified_at date NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT bank_card_offerings_brand_check CHECK (((brand)::text = ANY ((ARRAY['VISA'::character varying, 'MASTERCARD'::character varying, 'DINERS'::character varying, 'DISCOVER'::character varying, 'AMEX'::character varying])::text[]))),
    CONSTRAINT bank_card_offerings_card_type_check CHECK (((card_type)::text = ANY ((ARRAY['DEBIT'::character varying, 'CREDIT'::character varying])::text[]))),
    CONSTRAINT bank_card_offerings_check CHECK (((((card_type)::text = 'DEBIT'::text) AND (brand IS NULL)) OR (((card_type)::text = 'CREDIT'::text) AND (brand IS NOT NULL))))
);


--
-- Name: TABLE bank_card_offerings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bank_card_offerings IS 'Tipos y marcas de tarjeta permitidos por entidad; débito no registra marca.';


--
-- Name: banks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.banks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(120) NOT NULL,
    normalized_name character varying(120) NOT NULL,
    logo_url character varying(1000),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE banks; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.banks IS 'Catálogo de bancos autorizados y disponibles para asociar tarjetas.';


--
-- Name: COLUMN banks.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.banks.id IS 'Identificador interno del banco.';


--
-- Name: COLUMN banks.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.banks.name IS 'Nombre legal o comercial mostrado al usuario.';


--
-- Name: COLUMN banks.normalized_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.banks.normalized_name IS 'Nombre normalizado único para integración y búsquedas.';


--
-- Name: COLUMN banks.logo_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.banks.logo_url IS 'URL HTTPS de una imagen PNG con el logo del banco para mostrar en el frontend.';


--
-- Name: COLUMN banks.active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.banks.active IS 'Indica si el banco está disponible para nuevas tarjetas.';


--
-- Name: COLUMN banks.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.banks.created_at IS 'Fecha y hora de alta del banco en el catálogo.';


--
-- Name: budget_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_allocations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    period_id uuid NOT NULL,
    scope public.budget_scope NOT NULL,
    card_id uuid,
    category_id uuid,
    amount numeric(12,2) NOT NULL,
    currency_code character(3) DEFAULT 'USD'::bpchar NOT NULL,
    exchange_rate_to_usd numeric(18,8) DEFAULT 1 NOT NULL,
    amount_usd numeric(14,2) GENERATED ALWAYS AS (round((amount * exchange_rate_to_usd), 2)) STORED,
    alert_threshold numeric(4,3) DEFAULT 0.900 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT budget_allocations_alert_threshold_check CHECK (((alert_threshold > (0)::numeric) AND (alert_threshold <= (1)::numeric))),
    CONSTRAINT budget_allocations_amount_check CHECK ((amount >= (0)::numeric)),
    CONSTRAINT budget_allocations_check CHECK ((((scope = 'CARD'::public.budget_scope) AND (card_id IS NOT NULL) AND (category_id IS NULL)) OR ((scope = 'CATEGORY'::public.budget_scope) AND (card_id IS NULL) AND (category_id IS NOT NULL)) OR ((scope = 'CARD_CATEGORY'::public.budget_scope) AND (card_id IS NOT NULL) AND (category_id IS NOT NULL)))),
    CONSTRAINT budget_allocations_exchange_rate_to_usd_check CHECK ((exchange_rate_to_usd > (0)::numeric))
);


--
-- Name: TABLE budget_allocations; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.budget_allocations IS 'Presupuestos informativos mensuales por tarjeta, categoría o combinación tarjeta-categoría.';


--
-- Name: COLUMN budget_allocations.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.id IS 'Identificador interno de la asignación presupuestaria.';


--
-- Name: COLUMN budget_allocations.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.user_id IS 'Usuario propietario del presupuesto.';


--
-- Name: COLUMN budget_allocations.period_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.period_id IS 'Período mensual del presupuesto.';


--
-- Name: COLUMN budget_allocations.scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.scope IS 'Alcance CARD, CATEGORY o CARD_CATEGORY.';


--
-- Name: COLUMN budget_allocations.card_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.card_id IS 'Tarjeta objetivo; obligatoria según el alcance.';


--
-- Name: COLUMN budget_allocations.category_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.category_id IS 'Categoría objetivo; obligatoria según el alcance.';


--
-- Name: COLUMN budget_allocations.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.amount IS 'Monto planeado en la moneda seleccionada.';


--
-- Name: COLUMN budget_allocations.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.currency_code IS 'Moneda del presupuesto.';


--
-- Name: COLUMN budget_allocations.exchange_rate_to_usd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.exchange_rate_to_usd IS 'Tasa histórica aplicada para reportar el presupuesto en USD.';


--
-- Name: COLUMN budget_allocations.amount_usd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.amount_usd IS 'Equivalente en USD calculado y almacenado por PostgreSQL.';


--
-- Name: COLUMN budget_allocations.alert_threshold; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.alert_threshold IS 'Porcentaje consumido que dispara una alerta, por defecto 90 por ciento.';


--
-- Name: COLUMN budget_allocations.active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.active IS 'Indica si el presupuesto participa en cálculos y alertas.';


--
-- Name: COLUMN budget_allocations.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.created_at IS 'Fecha y hora de creación.';


--
-- Name: COLUMN budget_allocations.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_allocations.updated_at IS 'Fecha y hora de última modificación.';


--
-- Name: cards; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cards (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    bank_id uuid NOT NULL,
    card_type character varying(20) DEFAULT 'DEBIT'::character varying NOT NULL,
    name character varying(80) NOT NULL,
    last4 character(4),
    color_dark character(7) NOT NULL,
    color_light character(7) NOT NULL,
    default_currency_code character(3) DEFAULT 'USD'::bpchar NOT NULL,
    status public.card_status DEFAULT 'ACTIVE'::public.card_status NOT NULL,
    deactivated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    credit_brand character varying(20),
    alias character varying(80),
    CONSTRAINT cards_alias_check CHECK (((alias IS NULL) OR (btrim((alias)::text) <> ''::text))),
    CONSTRAINT cards_card_type_check CHECK (((card_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[]))),
    CONSTRAINT cards_check CHECK ((((status = 'ACTIVE'::public.card_status) AND (deactivated_at IS NULL)) OR ((status = 'INACTIVE'::public.card_status) AND (deactivated_at IS NOT NULL)))),
    CONSTRAINT cards_color_dark_check CHECK ((color_dark ~ '^#[0-9A-Fa-f]{6}$'::text)),
    CONSTRAINT cards_color_light_check CHECK ((color_light ~ '^#[0-9A-Fa-f]{6}$'::text)),
    CONSTRAINT cards_credit_brand_check CHECK (((((card_type)::text = 'DEBIT'::text) AND (credit_brand IS NULL)) OR (((card_type)::text = 'CREDIT'::text) AND ((credit_brand)::text = ANY ((ARRAY['VISA'::character varying, 'MASTERCARD'::character varying, 'DINERS'::character varying, 'DISCOVER'::character varying, 'AMEX'::character varying, 'OTHER'::character varying])::text[]))))),
    CONSTRAINT cards_last4_check CHECK (((last4 IS NULL) OR (last4 ~ '^[0-9]{4}$'::text))),
    CONSTRAINT cards_name_check CHECK ((btrim((name)::text) <> ''::text))
);


--
-- Name: TABLE cards; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cards IS 'Tarjetas registradas por el usuario; solo se conserva banco, alias y últimos cuatro dígitos.';


--
-- Name: COLUMN cards.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.id IS 'Identificador interno de la tarjeta.';


--
-- Name: COLUMN cards.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.user_id IS 'Usuario propietario de la tarjeta.';


--
-- Name: COLUMN cards.bank_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.bank_id IS 'Banco emisor seleccionado del catálogo.';


--
-- Name: COLUMN cards.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.name IS 'Nombre estable que entrega Wallet/Shortcut y se usa para identificar la tarjeta.';


--
-- Name: COLUMN cards.last4; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.last4 IS 'Últimos cuatro dígitos opcionales; nunca se almacena el número completo.';


--
-- Name: COLUMN cards.color_dark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.color_dark IS 'Color hexadecimal de la tarjeta para tema oscuro.';


--
-- Name: COLUMN cards.color_light; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.color_light IS 'Color hexadecimal de la tarjeta para tema claro.';


--
-- Name: COLUMN cards.default_currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.default_currency_code IS 'Moneda predeterminada propuesta al registrar gastos de la tarjeta.';


--
-- Name: COLUMN cards.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.status IS 'Estado ACTIVE o INACTIVE; una tarjeta inactiva no recibe gastos nuevos.';


--
-- Name: COLUMN cards.deactivated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.deactivated_at IS 'Fecha y hora de desactivación; no permite reactivación.';


--
-- Name: COLUMN cards.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.created_at IS 'Fecha y hora de registro de la tarjeta.';


--
-- Name: COLUMN cards.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.updated_at IS 'Fecha y hora de última modificación permitida.';


--
-- Name: COLUMN cards.credit_brand; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.credit_brand IS 'Marca seleccionada para crédito; siempre nula en débito.';


--
-- Name: COLUMN cards.alias; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cards.alias IS 'Apodo visual opcional elegido por el usuario; no participa en la identificación.';


--
-- Name: currencies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.currencies (
    code character(3) NOT NULL,
    numeric_code character(3) NOT NULL,
    name character varying(80) NOT NULL,
    symbol character varying(8) NOT NULL,
    decimal_places smallint DEFAULT 2 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    is_base_currency boolean DEFAULT false NOT NULL,
    CONSTRAINT currencies_code_check CHECK ((code ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT currencies_decimal_places_check CHECK (((decimal_places >= 0) AND (decimal_places <= 4))),
    CONSTRAINT currencies_numeric_code_check CHECK ((numeric_code ~ '^[0-9]{3}$'::text))
);


--
-- Name: TABLE currencies; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.currencies IS 'Catálogo de monedas ISO 4217 disponibles para registrar importes.';


--
-- Name: COLUMN currencies.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.code IS 'Código alfabético ISO 4217, por ejemplo USD.';


--
-- Name: COLUMN currencies.numeric_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.numeric_code IS 'Código numérico ISO 4217.';


--
-- Name: COLUMN currencies.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.name IS 'Nombre legible de la moneda.';


--
-- Name: COLUMN currencies.symbol; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.symbol IS 'Símbolo usado para mostrar la moneda.';


--
-- Name: COLUMN currencies.decimal_places; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.decimal_places IS 'Cantidad de decimales admitidos por la moneda.';


--
-- Name: COLUMN currencies.active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.active IS 'Indica si la moneda puede usarse en nuevos registros.';


--
-- Name: COLUMN currencies.is_base_currency; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.currencies.is_base_currency IS 'Indica la moneda base única de reportes y presupuestos: USD.';


--
-- Name: expenses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.expenses (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    idempotency_key uuid,
    card_id uuid NOT NULL,
    category_id uuid NOT NULL,
    pending_movement_id uuid,
    origin public.expense_origin NOT NULL,
    amount numeric(12,2) NOT NULL,
    currency_code character(3) DEFAULT 'USD'::bpchar NOT NULL,
    exchange_rate_to_usd numeric(18,8) DEFAULT 1 NOT NULL,
    amount_usd numeric(14,2) GENERATED ALWAYS AS (round((amount * exchange_rate_to_usd), 2)) STORED,
    merchant_raw character varying(180) NOT NULL,
    merchant_normalized character varying(180),
    normalization_version smallint,
    occurred_at timestamp with time zone NOT NULL,
    is_recurring boolean DEFAULT false NOT NULL,
    recurrence_day smallint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT expenses_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT expenses_check CHECK (((is_recurring AND (recurrence_day IS NOT NULL)) OR ((NOT is_recurring) AND (recurrence_day IS NULL)))),
    CONSTRAINT expenses_exchange_rate_to_usd_check CHECK ((exchange_rate_to_usd > (0)::numeric)),
    CONSTRAINT expenses_merchant_raw_check CHECK ((btrim((merchant_raw)::text) <> ''::text)),
    CONSTRAINT expenses_recurrence_day_check CHECK (((recurrence_day >= 1) AND (recurrence_day <= 31)))
);


--
-- Name: TABLE expenses; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.expenses IS 'Gastos confirmados del usuario, manuales o automáticos; nunca se eliminan.';


--
-- Name: COLUMN expenses.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.id IS 'Identificador interno del gasto.';


--
-- Name: COLUMN expenses.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.user_id IS 'Usuario propietario del gasto.';


--
-- Name: COLUMN expenses.card_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.card_id IS 'Tarjeta usada en el gasto.';


--
-- Name: COLUMN expenses.category_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.category_id IS 'Categoría asignada al gasto.';


--
-- Name: COLUMN expenses.pending_movement_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.pending_movement_id IS 'Movimiento de origen cuando el gasto llegó automáticamente.';


--
-- Name: COLUMN expenses.origin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.origin IS 'Origen MANUAL o AUTOMATIC; el monto automático es inmutable.';


--
-- Name: COLUMN expenses.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.amount IS 'Monto original del consumo.';


--
-- Name: COLUMN expenses.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.currency_code IS 'Moneda del consumo.';


--
-- Name: COLUMN expenses.exchange_rate_to_usd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.exchange_rate_to_usd IS 'Tasa histórica usada para convertir el gasto a USD.';


--
-- Name: COLUMN expenses.amount_usd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.amount_usd IS 'Equivalente en USD calculado y almacenado por PostgreSQL.';


--
-- Name: COLUMN expenses.merchant_raw; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.merchant_raw IS 'Comercio o descripción capturada originalmente.';


--
-- Name: COLUMN expenses.merchant_normalized; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.merchant_normalized IS 'Comercio normalizado para sugerir categorías.';


--
-- Name: COLUMN expenses.normalization_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.normalization_version IS 'Versión de normalización aplicada al comercio.';


--
-- Name: COLUMN expenses.occurred_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.occurred_at IS 'Fecha y hora del consumo; no es editable.';


--
-- Name: COLUMN expenses.is_recurring; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.is_recurring IS 'Indica si el usuario marcó el gasto como recurrente.';


--
-- Name: COLUMN expenses.recurrence_day; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.recurrence_day IS 'Día mensual propuesto para la recurrencia, entre 1 y 31.';


--
-- Name: COLUMN expenses.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.created_at IS 'Fecha y hora de creación del gasto.';


--
-- Name: COLUMN expenses.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.expenses.updated_at IS 'Fecha y hora de última modificación permitida.';


--
-- Name: financial_periods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.financial_periods (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    period_month date NOT NULL,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT financial_periods_period_month_check CHECK ((period_month = (date_trunc('month'::text, (period_month)::timestamp with time zone))::date))
);


--
-- Name: TABLE financial_periods; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.financial_periods IS 'Períodos contables mensuales del usuario usados por ingresos, presupuestos y cierres.';


--
-- Name: COLUMN financial_periods.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.financial_periods.id IS 'Identificador interno del período.';


--
-- Name: COLUMN financial_periods.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.financial_periods.user_id IS 'Usuario propietario del período mensual.';


--
-- Name: COLUMN financial_periods.period_month; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.financial_periods.period_month IS 'Primer día del mes que representa el período.';


--
-- Name: COLUMN financial_periods.closed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.financial_periods.closed_at IS 'Fecha y hora de cierre; impide editar gastos de ese mes.';


--
-- Name: COLUMN financial_periods.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.financial_periods.created_at IS 'Fecha y hora de creación del período.';


--
-- Name: installment_payment_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.installment_payment_allocations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    installment_id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT installment_payment_allocations_amount_check CHECK ((amount > (0)::numeric))
);


--
-- Name: TABLE installment_payment_allocations; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.installment_payment_allocations IS 'Distribución de un comprobante de pago entre una o varias cuotas.';


--
-- Name: COLUMN installment_payment_allocations.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payment_allocations.id IS 'Identificador interno de la asignación.';


--
-- Name: COLUMN installment_payment_allocations.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payment_allocations.user_id IS 'Usuario propietario de la asignación.';


--
-- Name: COLUMN installment_payment_allocations.payment_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payment_allocations.payment_id IS 'Comprobante de pago del que proviene el monto.';


--
-- Name: COLUMN installment_payment_allocations.installment_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payment_allocations.installment_id IS 'Cuota a la que se aplica el monto.';


--
-- Name: COLUMN installment_payment_allocations.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payment_allocations.amount IS 'Valor del comprobante aplicado a esta cuota.';


--
-- Name: COLUMN installment_payment_allocations.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payment_allocations.created_at IS 'Fecha y hora de creación de la asignación.';


--
-- Name: installment_payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.installment_payments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    installment_plan_id uuid NOT NULL,
    payment_type public.installment_payment_type DEFAULT 'INSTALLMENT'::public.installment_payment_type NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    paid_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT installment_payments_total_amount_check CHECK ((total_amount > (0)::numeric))
);


--
-- Name: TABLE installment_payments; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.installment_payments IS 'Comprobantes de pagos manuales declarados contra un plan de cuotas.';


--
-- Name: COLUMN installment_payments.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.id IS 'Identificador interno del comprobante de pago.';


--
-- Name: COLUMN installment_payments.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.user_id IS 'Usuario que declara el pago.';


--
-- Name: COLUMN installment_payments.installment_plan_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.installment_plan_id IS 'Plan de cuotas que recibe el pago.';


--
-- Name: COLUMN installment_payments.payment_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.payment_type IS 'Pago normal de cuota o liquidación anticipada.';


--
-- Name: COLUMN installment_payments.total_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.total_amount IS 'Monto total declarado en el comprobante.';


--
-- Name: COLUMN installment_payments.paid_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.paid_at IS 'Fecha y hora declarada del pago.';


--
-- Name: COLUMN installment_payments.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_payments.created_at IS 'Fecha y hora de registro en Paktay.';


--
-- Name: installment_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.installment_plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    expense_id uuid NOT NULL,
    original_amount numeric(12,2) NOT NULL,
    interest_rate numeric(8,4) DEFAULT 0 NOT NULL,
    installment_count integer NOT NULL,
    first_due_date date NOT NULL,
    status public.installment_plan_status DEFAULT 'ACTIVE'::public.installment_plan_status NOT NULL,
    settled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT installment_plans_check CHECK ((((status = 'ACTIVE'::public.installment_plan_status) AND (settled_at IS NULL)) OR ((status = 'SETTLED'::public.installment_plan_status) AND (settled_at IS NOT NULL)))),
    CONSTRAINT installment_plans_installment_count_check CHECK ((installment_count > 0)),
    CONSTRAINT installment_plans_interest_rate_check CHECK ((interest_rate = (0)::numeric)),
    CONSTRAINT installment_plans_original_amount_check CHECK ((original_amount > (0)::numeric))
);


--
-- Name: TABLE installment_plans; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.installment_plans IS 'Plan sin interés que divide un gasto confirmado en cuotas mensuales.';


--
-- Name: COLUMN installment_plans.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.id IS 'Identificador interno del plan.';


--
-- Name: COLUMN installment_plans.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.user_id IS 'Usuario propietario del plan.';


--
-- Name: COLUMN installment_plans.expense_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.expense_id IS 'Gasto original diferido; solo puede tener un plan.';


--
-- Name: COLUMN installment_plans.original_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.original_amount IS 'Monto original del gasto, igual al monto del gasto asociado.';


--
-- Name: COLUMN installment_plans.interest_rate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.interest_rate IS 'Tasa de interés; en v0.1 debe ser cero.';


--
-- Name: COLUMN installment_plans.installment_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.installment_count IS 'Cantidad de cuotas declarada por el usuario, sin máximo.';


--
-- Name: COLUMN installment_plans.first_due_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.first_due_date IS 'Fecha de la primera cuota programada.';


--
-- Name: COLUMN installment_plans.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.status IS 'Estado ACTIVE o SETTLED del plan.';


--
-- Name: COLUMN installment_plans.settled_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.settled_at IS 'Fecha y hora en que todas las cuotas quedaron pagadas o liquidadas.';


--
-- Name: COLUMN installment_plans.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installment_plans.created_at IS 'Fecha y hora de creación del plan.';


--
-- Name: installments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.installments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    installment_plan_id uuid NOT NULL,
    sequence_number integer NOT NULL,
    due_date date NOT NULL,
    scheduled_amount numeric(12,2) NOT NULL,
    status public.installment_status DEFAULT 'PENDING'::public.installment_status NOT NULL,
    settled_at timestamp with time zone,
    CONSTRAINT installments_check CHECK ((((status = ANY (ARRAY['PAID'::public.installment_status, 'SETTLED_EARLY'::public.installment_status])) AND (settled_at IS NOT NULL)) OR ((status = ANY (ARRAY['PENDING'::public.installment_status, 'PARTIALLY_PAID'::public.installment_status])) AND (settled_at IS NULL)))),
    CONSTRAINT installments_scheduled_amount_check CHECK ((scheduled_amount > (0)::numeric)),
    CONSTRAINT installments_sequence_number_check CHECK ((sequence_number > 0))
);


--
-- Name: TABLE installments; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.installments IS 'Cuotas programadas de un plan; una fila representa una obligación mensual.';


--
-- Name: COLUMN installments.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.id IS 'Identificador interno de la cuota.';


--
-- Name: COLUMN installments.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.user_id IS 'Usuario propietario de la cuota.';


--
-- Name: COLUMN installments.installment_plan_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.installment_plan_id IS 'Plan de cuotas al que pertenece.';


--
-- Name: COLUMN installments.sequence_number; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.sequence_number IS 'Número consecutivo de cuota dentro del plan.';


--
-- Name: COLUMN installments.due_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.due_date IS 'Fecha programada de la cuota.';


--
-- Name: COLUMN installments.scheduled_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.scheduled_amount IS 'Monto programado de la cuota.';


--
-- Name: COLUMN installments.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.status IS 'Estado PENDING, PARTIALLY_PAID, PAID o SETTLED_EARLY.';


--
-- Name: COLUMN installments.settled_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.installments.settled_at IS 'Fecha y hora en que la cuota quedó cubierta.';


--
-- Name: monthly_incomes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monthly_incomes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    period_id uuid NOT NULL,
    card_id uuid,
    amount numeric(12,2) NOT NULL,
    currency_code character(3) DEFAULT 'USD'::bpchar NOT NULL,
    exchange_rate_to_usd numeric(18,8) DEFAULT 1 NOT NULL,
    amount_usd numeric(14,2) GENERATED ALWAYS AS (round((amount * exchange_rate_to_usd), 2)) STORED,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT monthly_incomes_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT monthly_incomes_exchange_rate_to_usd_check CHECK ((exchange_rate_to_usd > (0)::numeric))
);


--
-- Name: TABLE monthly_incomes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.monthly_incomes IS 'Ingresos mensuales declarados por el usuario, globales o vinculados a una tarjeta.';


--
-- Name: COLUMN monthly_incomes.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.id IS 'Identificador interno del ingreso.';


--
-- Name: COLUMN monthly_incomes.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.user_id IS 'Usuario propietario del ingreso.';


--
-- Name: COLUMN monthly_incomes.period_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.period_id IS 'Período mensual al que pertenece el ingreso.';


--
-- Name: COLUMN monthly_incomes.card_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.card_id IS 'Tarjeta asociada al ingreso; nulo para ingreso global.';


--
-- Name: COLUMN monthly_incomes.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.amount IS 'Monto original declarado por el usuario.';


--
-- Name: COLUMN monthly_incomes.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.currency_code IS 'Moneda del monto original.';


--
-- Name: COLUMN monthly_incomes.exchange_rate_to_usd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.exchange_rate_to_usd IS 'Tasa histórica de una unidad de la moneda a USD.';


--
-- Name: COLUMN monthly_incomes.amount_usd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.amount_usd IS 'Equivalente en USD calculado y almacenado por PostgreSQL.';


--
-- Name: COLUMN monthly_incomes.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.created_at IS 'Fecha y hora de creación.';


--
-- Name: COLUMN monthly_incomes.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.monthly_incomes.updated_at IS 'Fecha y hora de última modificación.';


--
-- Name: password_pins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_pins (
    email character varying(320) NOT NULL,
    purpose character varying(10) NOT NULL,
    user_id character varying(64),
    pin_hash character varying(100),
    expires_at bigint DEFAULT 0 NOT NULL,
    sent_at bigint DEFAULT 0 NOT NULL,
    window_at bigint DEFAULT 0 NOT NULL,
    requests integer DEFAULT 0 NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    token_hash character varying(64),
    token_expires_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: pending_movements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pending_movements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    source character varying(16) NOT NULL,
    raw_payload jsonb NOT NULL,
    raw_text text,
    parsed_amount numeric(12,2),
    parsed_currency_code character(3) DEFAULT 'USD'::bpchar NOT NULL,
    merchant_raw character varying(180),
    merchant_normalized character varying(180),
    normalization_version smallint,
    bank_id uuid,
    last4 character(4),
    occurred_at timestamp with time zone,
    suggested_card_id uuid,
    suggested_category_id uuid,
    parse_error character varying(300),
    status public.pending_movement_status DEFAULT 'PENDING'::public.pending_movement_status NOT NULL,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    card_name character varying(80),
    CONSTRAINT pending_movements_check CHECK ((((status = 'PENDING'::public.pending_movement_status) AND (resolved_at IS NULL)) OR ((status = ANY (ARRAY['CONFIRMED'::public.pending_movement_status, 'DISCARDED'::public.pending_movement_status])) AND (resolved_at IS NOT NULL)))),
    CONSTRAINT pending_movements_last4_check CHECK (((last4 IS NULL) OR (last4 ~ '^[0-9]{4}$'::text))),
    CONSTRAINT pending_movements_parsed_amount_check CHECK ((parsed_amount > (0)::numeric)),
    CONSTRAINT pending_movements_source_check CHECK (((source)::text = ANY ((ARRAY['IOS_SHORTCUT'::character varying, 'ANDROID_NOTIFICATION'::character varying])::text[])))
);


--
-- Name: TABLE pending_movements; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.pending_movements IS 'Bandeja persistente de eventos recibidos por Shortcut o Android antes de confirmar o descartar el gasto.';


--
-- Name: COLUMN pending_movements.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.id IS 'Identificador interno del movimiento pendiente.';


--
-- Name: COLUMN pending_movements.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.user_id IS 'Usuario dueño del evento recibido.';


--
-- Name: COLUMN pending_movements.idempotency_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.idempotency_key IS 'UUID único enviado por el móvil para evitar duplicar el mismo evento.';


--
-- Name: COLUMN pending_movements.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.source IS 'Canal de origen: IOS_SHORTCUT o ANDROID_NOTIFICATION.';


--
-- Name: COLUMN pending_movements.raw_payload; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.raw_payload IS 'Carga original recibida del canal, preservada para trazabilidad.';


--
-- Name: COLUMN pending_movements.raw_text; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.raw_text IS 'Texto original de la notificación o Shortcut cuando exista.';


--
-- Name: COLUMN pending_movements.parsed_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.parsed_amount IS 'Monto extraído; puede ser nulo si el evento no se pudo parsear.';


--
-- Name: COLUMN pending_movements.parsed_currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.parsed_currency_code IS 'Moneda extraída o propuesta para el movimiento.';


--
-- Name: COLUMN pending_movements.merchant_raw; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.merchant_raw IS 'Nombre de comercio extraído sin normalizar.';


--
-- Name: COLUMN pending_movements.merchant_normalized; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.merchant_normalized IS 'Nombre de comercio normalizado para buscar selecciones previas.';


--
-- Name: COLUMN pending_movements.normalization_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.normalization_version IS 'Versión de la normalización aplicada al comercio.';


--
-- Name: COLUMN pending_movements.bank_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.bank_id IS 'Banco detectado; puede ser nulo si no se logró identificar.';


--
-- Name: COLUMN pending_movements.last4; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.last4 IS 'Últimos cuatro dígitos detectados; puede ser nulo si no se logró extraer.';


--
-- Name: COLUMN pending_movements.occurred_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.occurred_at IS 'Fecha y hora detectada del consumo.';


--
-- Name: COLUMN pending_movements.suggested_card_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.suggested_card_id IS 'Tarjeta del usuario propuesta por banco y últimos cuatro.';


--
-- Name: COLUMN pending_movements.suggested_category_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.suggested_category_id IS 'Categoría propuesta por una selección previa del usuario.';


--
-- Name: COLUMN pending_movements.parse_error; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.parse_error IS 'Razón técnica cuando el evento no pudo parsearse completamente.';


--
-- Name: COLUMN pending_movements.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.status IS 'Estado PENDING, CONFIRMED o DISCARDED.';


--
-- Name: COLUMN pending_movements.resolved_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.resolved_at IS 'Fecha y hora de confirmación o descarte.';


--
-- Name: COLUMN pending_movements.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.created_at IS 'Fecha y hora de recepción del evento.';


--
-- Name: COLUMN pending_movements.card_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.pending_movements.card_name IS 'Nombre de tarjeta recibido del Shortcut y usado para sugerir una tarjeta existente.';


--
-- Name: shortcut_credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shortcut_credentials (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash character(64) NOT NULL,
    token_hint character varying(12) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    CONSTRAINT shortcut_credentials_check CHECK (((active AND (revoked_at IS NULL)) OR ((NOT active) AND (revoked_at IS NOT NULL))))
);


--
-- Name: TABLE shortcut_credentials; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.shortcut_credentials IS 'Credenciales revocables que vinculan una instalación personal de Apple Shortcut con un usuario.';


--
-- Name: COLUMN shortcut_credentials.token_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.shortcut_credentials.token_hash IS 'SHA-256 hexadecimal del secreto; el bearer original sólo se entrega al crearlo.';


--
-- Name: COLUMN shortcut_credentials.token_hint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.shortcut_credentials.token_hint IS 'Últimos caracteres visibles para que el usuario reconozca la conexión sin revelar el secreto.';


--
-- Name: system_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(80) NOT NULL,
    normalized_name character varying(80) NOT NULL,
    icon character varying(60) NOT NULL,
    color_dark character(7) NOT NULL,
    color_light character(7) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    display_order smallint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    parent_code character varying(50) NOT NULL,
    parent_name character varying(80) NOT NULL,
    CONSTRAINT system_categories_color_dark_check CHECK ((color_dark ~ '^#[0-9A-Fa-f]{6}$'::text)),
    CONSTRAINT system_categories_color_light_check CHECK ((color_light ~ '^#[0-9A-Fa-f]{6}$'::text)),
    CONSTRAINT system_categories_display_order_check CHECK ((display_order > 0)),
    CONSTRAINT system_categories_name_check CHECK ((btrim((name)::text) <> ''::text))
);


--
-- Name: TABLE system_categories; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_categories IS 'Catálogo global de categorías predeterminadas administrado exclusivamente por Admin.';


--
-- Name: COLUMN system_categories.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.id IS 'Identificador interno de la categoría predeterminada.';


--
-- Name: COLUMN system_categories.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.name IS 'Nombre visible de la categoría predeterminada.';


--
-- Name: COLUMN system_categories.normalized_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.normalized_name IS 'Nombre único normalizado para búsquedas y duplicados.';


--
-- Name: COLUMN system_categories.active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.active IS 'Indica si se copia a usuarios nuevos y se ofrece para uso.';


--
-- Name: COLUMN system_categories.display_order; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.display_order IS 'Orden de visualización dentro del catálogo.';


--
-- Name: COLUMN system_categories.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.created_at IS 'Fecha y hora de creación en el catálogo global.';


--
-- Name: COLUMN system_categories.parent_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.parent_code IS 'Identificador del grupo general usado para filtrar el catálogo administrativo; no se asigna al usuario.';


--
-- Name: COLUMN system_categories.parent_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_categories.parent_name IS 'Nombre del grupo general usado sólo en la exploración; el usuario guarda la subcategoría.';


--
-- Name: unregistered_payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.unregistered_payments (
    id bigint NOT NULL,
    amount numeric(12,2) NOT NULL,
    merchant character varying(180) NOT NULL,
    card_name character varying(120) NOT NULL,
    device_id character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT unregistered_payments_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT unregistered_payments_card_name_check CHECK ((btrim((card_name)::text) <> ''::text)),
    CONSTRAINT unregistered_payments_device_id_check CHECK (((device_id IS NULL) OR (btrim((device_id)::text) <> ''::text))),
    CONSTRAINT unregistered_payments_merchant_check CHECK ((btrim((merchant)::text) <> ''::text))
);


--
-- Name: TABLE unregistered_payments; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.unregistered_payments IS 'Pagos recibidos desde el celular que todavía no están vinculados con usuario ni tarjeta registrada.';


--
-- Name: COLUMN unregistered_payments.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.unregistered_payments.id IS 'Identificador numérico secuencial asignado por PostgreSQL.';


--
-- Name: COLUMN unregistered_payments.device_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.unregistered_payments.device_id IS 'Identificador opcional del dispositivo, reservado para una integración futura.';


--
-- Name: unregistered_payments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.unregistered_payments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: unregistered_payments_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.unregistered_payments_id_seq OWNED BY public.unregistered_payments.id;


--
-- Name: user_budget_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_budget_settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    period_id uuid NOT NULL,
    global_amount numeric(12,2),
    currency_code character(3) DEFAULT 'USD'::bpchar NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    recurrence character varying(16) DEFAULT 'THIS_MONTH'::character varying NOT NULL,
    CONSTRAINT user_budget_settings_global_amount_check CHECK ((global_amount > (0)::numeric)),
    CONSTRAINT user_budget_settings_recurrence_check CHECK (((recurrence)::text = ANY ((ARRAY['THIS_MONTH'::character varying, 'MONTHLY'::character varying])::text[])))
);


--
-- Name: TABLE user_budget_settings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_budget_settings IS 'Presupuesto global mensual compartido por las categorías que el usuario seleccionó.';


--
-- Name: COLUMN user_budget_settings.global_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_budget_settings.global_amount IS 'Valor plantilla aplicado individualmente a cada categoría heredada; no representa una bolsa ni una sumatoria.';


--
-- Name: COLUMN user_budget_settings.recurrence; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_budget_settings.recurrence IS 'THIS_MONTH limita la plantilla al período actual; MONTHLY la replica al crear el siguiente período.';


--
-- Name: user_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    system_category_id uuid,
    origin public.category_origin NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(80) NOT NULL,
    normalized_name character varying(80) NOT NULL,
    icon character varying(60) NOT NULL,
    color_dark character(7) NOT NULL,
    color_light character(7) NOT NULL,
    sort_order smallint NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    alias character varying(80) NOT NULL,
    CONSTRAINT user_categories_alias_check CHECK ((btrim((alias)::text) <> ''::text)),
    CONSTRAINT user_categories_check CHECK ((((origin = 'SYSTEM'::public.category_origin) AND (system_category_id IS NOT NULL)) OR ((origin = 'CUSTOM'::public.category_origin) AND (system_category_id IS NULL)))),
    CONSTRAINT user_categories_color_dark_check CHECK ((color_dark ~ '^#[0-9A-Fa-f]{6}$'::text)),
    CONSTRAINT user_categories_color_light_check CHECK ((color_light ~ '^#[0-9A-Fa-f]{6}$'::text)),
    CONSTRAINT user_categories_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT user_categories_sort_order_check CHECK ((sort_order > 0))
);


--
-- Name: TABLE user_categories; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_categories IS 'Categorías que un usuario usa en sus gastos: copias de sistema o categorías propias.';


--
-- Name: COLUMN user_categories.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.id IS 'Identificador interno de la categoría del usuario.';


--
-- Name: COLUMN user_categories.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.user_id IS 'Usuario propietario de la categoría.';


--
-- Name: COLUMN user_categories.system_category_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.system_category_id IS 'Categoría global de origen; es nulo en categorías propias.';


--
-- Name: COLUMN user_categories.origin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.origin IS 'Origen SYSTEM para copia predeterminada o CUSTOM para creación del usuario.';


--
-- Name: COLUMN user_categories.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.name IS 'Nombre visible, editable por el usuario.';


--
-- Name: COLUMN user_categories.normalized_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.normalized_name IS 'Nombre normalizado único dentro del usuario.';


--
-- Name: COLUMN user_categories.active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.active IS 'Indica si puede asignarse a nuevos gastos; no borra historial.';


--
-- Name: COLUMN user_categories.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.created_at IS 'Fecha y hora de creación de la categoría del usuario.';


--
-- Name: COLUMN user_categories.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.updated_at IS 'Fecha y hora de última modificación.';


--
-- Name: COLUMN user_categories.alias; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_categories.alias IS 'Nombre visual editable por el usuario sin modificar el nombre base del catálogo.';


--
-- Name: user_category_budgets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_category_budgets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    period_id uuid NOT NULL,
    category_id uuid NOT NULL,
    individual_amount numeric(12,2),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_category_budgets_individual_amount_check CHECK ((individual_amount > (0)::numeric))
);


--
-- Name: TABLE user_category_budgets; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_category_budgets IS 'Categorías incluidas en presupuesto; individual_amount reemplaza el global sólo para esa categoría.';


--
-- Name: user_consumption_selections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_consumption_selections (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    consumption_name character varying(180) NOT NULL,
    merchant_normalized character varying(180) NOT NULL,
    normalization_version smallint DEFAULT 1 NOT NULL,
    category_id uuid NOT NULL,
    active boolean DEFAULT true NOT NULL,
    selection_count integer DEFAULT 1 NOT NULL,
    last_selected_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_consumption_selections_merchant_normalized_check CHECK ((btrim((merchant_normalized)::text) <> ''::text)),
    CONSTRAINT user_consumption_selections_normalization_version_check CHECK ((normalization_version > 0)),
    CONSTRAINT user_consumption_selections_selection_count_check CHECK ((selection_count > 0))
);


--
-- Name: TABLE user_consumption_selections; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_consumption_selections IS 'Elección persistida que relaciona el nombre de un consumo recibido con una categoría del mismo usuario.';


--
-- Name: COLUMN user_consumption_selections.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.id IS 'Identificador interno de la selección.';


--
-- Name: COLUMN user_consumption_selections.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.user_id IS 'Usuario propietario de la selección.';


--
-- Name: COLUMN user_consumption_selections.consumption_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.consumption_name IS 'Nombre original del consumo, por ejemplo STARBUCKS QUICENTRO.';


--
-- Name: COLUMN user_consumption_selections.merchant_normalized; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.merchant_normalized IS 'Nombre normalizado usado para reconocer consumos similares.';


--
-- Name: COLUMN user_consumption_selections.normalization_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.normalization_version IS 'Versión del algoritmo de normalización aplicado.';


--
-- Name: COLUMN user_consumption_selections.category_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.category_id IS 'Categoría seleccionada por el usuario para ese consumo.';


--
-- Name: COLUMN user_consumption_selections.active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.active IS 'Indica si la sugerencia automática sigue vigente.';


--
-- Name: COLUMN user_consumption_selections.selection_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.selection_count IS 'Número de veces que la selección ha sido utilizada.';


--
-- Name: COLUMN user_consumption_selections.last_selected_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.last_selected_at IS 'Fecha y hora del último uso de la selección.';


--
-- Name: COLUMN user_consumption_selections.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.created_at IS 'Fecha y hora de creación de la selección.';


--
-- Name: COLUMN user_consumption_selections.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consumption_selections.updated_at IS 'Fecha y hora de última modificación.';


--
-- Name: user_devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_devices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    platform character varying(16) NOT NULL,
    device_name character varying(120) NOT NULL,
    biometric_enabled boolean DEFAULT false NOT NULL,
    last_authenticated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_devices_platform_check CHECK (((platform)::text = ANY ((ARRAY['ios'::character varying, 'android'::character varying])::text[])))
);


--
-- Name: TABLE user_devices; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_devices IS 'Dispositivos registrados por usuario; almacena preferencias, nunca datos biométricos.';


--
-- Name: v_monthly_expense_summary; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_monthly_expense_summary AS
 SELECT user_id,
    period_month,
    category_id,
    card_id,
    sum(amount) AS spent_amount,
    count(*) AS expense_count
   FROM ( SELECT e.user_id,
            (date_trunc('month'::text, e.occurred_at))::date AS period_month,
            e.category_id,
            e.card_id,
            e.amount_usd AS amount
           FROM public.expenses e
          WHERE (NOT (EXISTS ( SELECT 1
                   FROM public.installment_plans p
                  WHERE (p.expense_id = e.id))))
        UNION ALL
         SELECT e.user_id,
            (date_trunc('month'::text, (i.due_date)::timestamp with time zone))::date AS period_month,
            e.category_id,
            e.card_id,
            round((i.scheduled_amount * e.exchange_rate_to_usd), 2) AS amount
           FROM ((public.installments i
             JOIN public.installment_plans p ON ((p.id = i.installment_plan_id)))
             JOIN public.expenses e ON ((e.id = p.expense_id)))) x
  GROUP BY user_id, period_month, category_id, card_id;


--
-- Name: unregistered_payments id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.unregistered_payments ALTER COLUMN id SET DEFAULT nextval('public.unregistered_payments_id_seq'::regclass);


--
-- Name: app_users app_users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_pkey PRIMARY KEY (id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: bank_card_offerings bank_card_offerings_bank_id_card_type_brand_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_card_offerings
    ADD CONSTRAINT bank_card_offerings_bank_id_card_type_brand_key UNIQUE NULLS NOT DISTINCT (bank_id, card_type, brand);


--
-- Name: bank_card_offerings bank_card_offerings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_card_offerings
    ADD CONSTRAINT bank_card_offerings_pkey PRIMARY KEY (id);


--
-- Name: banks banks_normalized_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.banks
    ADD CONSTRAINT banks_normalized_name_key UNIQUE (normalized_name);


--
-- Name: banks banks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.banks
    ADD CONSTRAINT banks_pkey PRIMARY KEY (id);


--
-- Name: budget_allocations budget_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_allocations
    ADD CONSTRAINT budget_allocations_pkey PRIMARY KEY (id);


--
-- Name: cards cards_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cards
    ADD CONSTRAINT cards_pkey PRIMARY KEY (id);


--
-- Name: currencies currencies_numeric_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currencies
    ADD CONSTRAINT currencies_numeric_code_key UNIQUE (numeric_code);


--
-- Name: currencies currencies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currencies
    ADD CONSTRAINT currencies_pkey PRIMARY KEY (code);


--
-- Name: expenses expenses_pending_movement_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_pending_movement_id_key UNIQUE (pending_movement_id);


--
-- Name: expenses expenses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_pkey PRIMARY KEY (id);


--
-- Name: financial_periods financial_periods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.financial_periods
    ADD CONSTRAINT financial_periods_pkey PRIMARY KEY (id);


--
-- Name: financial_periods financial_periods_user_id_period_month_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.financial_periods
    ADD CONSTRAINT financial_periods_user_id_period_month_key UNIQUE (user_id, period_month);


--
-- Name: installment_payment_allocations installment_payment_allocations_payment_id_installment_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payment_allocations
    ADD CONSTRAINT installment_payment_allocations_payment_id_installment_id_key UNIQUE (payment_id, installment_id);


--
-- Name: installment_payment_allocations installment_payment_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payment_allocations
    ADD CONSTRAINT installment_payment_allocations_pkey PRIMARY KEY (id);


--
-- Name: installment_payments installment_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payments
    ADD CONSTRAINT installment_payments_pkey PRIMARY KEY (id);


--
-- Name: installment_plans installment_plans_expense_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_plans
    ADD CONSTRAINT installment_plans_expense_id_key UNIQUE (expense_id);


--
-- Name: installment_plans installment_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_plans
    ADD CONSTRAINT installment_plans_pkey PRIMARY KEY (id);


--
-- Name: installments installments_installment_plan_id_sequence_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installments
    ADD CONSTRAINT installments_installment_plan_id_sequence_number_key UNIQUE (installment_plan_id, sequence_number);


--
-- Name: installments installments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installments
    ADD CONSTRAINT installments_pkey PRIMARY KEY (id);


--
-- Name: monthly_incomes monthly_incomes_period_id_card_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_incomes
    ADD CONSTRAINT monthly_incomes_period_id_card_id_key UNIQUE (period_id, card_id);


--
-- Name: monthly_incomes monthly_incomes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_incomes
    ADD CONSTRAINT monthly_incomes_pkey PRIMARY KEY (id);


--
-- Name: password_pins password_pins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_pins
    ADD CONSTRAINT password_pins_pkey PRIMARY KEY (email, purpose);


--
-- Name: pending_movements pending_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_pkey PRIMARY KEY (id);


--
-- Name: pending_movements pending_movements_user_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_user_id_idempotency_key_key UNIQUE (user_id, idempotency_key);


--
-- Name: shortcut_credentials shortcut_credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shortcut_credentials
    ADD CONSTRAINT shortcut_credentials_pkey PRIMARY KEY (id);


--
-- Name: shortcut_credentials shortcut_credentials_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shortcut_credentials
    ADD CONSTRAINT shortcut_credentials_token_hash_key UNIQUE (token_hash);


--
-- Name: system_categories system_categories_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_categories
    ADD CONSTRAINT system_categories_code_key UNIQUE (code);


--
-- Name: system_categories system_categories_display_order_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_categories
    ADD CONSTRAINT system_categories_display_order_key UNIQUE (display_order);


--
-- Name: system_categories system_categories_normalized_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_categories
    ADD CONSTRAINT system_categories_normalized_name_key UNIQUE (normalized_name);


--
-- Name: system_categories system_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_categories
    ADD CONSTRAINT system_categories_pkey PRIMARY KEY (id);


--
-- Name: unregistered_payments unregistered_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.unregistered_payments
    ADD CONSTRAINT unregistered_payments_pkey PRIMARY KEY (id);


--
-- Name: user_budget_settings user_budget_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_budget_settings
    ADD CONSTRAINT user_budget_settings_pkey PRIMARY KEY (id);


--
-- Name: user_budget_settings user_budget_settings_user_id_period_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_budget_settings
    ADD CONSTRAINT user_budget_settings_user_id_period_id_key UNIQUE (user_id, period_id);


--
-- Name: user_categories user_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_categories
    ADD CONSTRAINT user_categories_pkey PRIMARY KEY (id);


--
-- Name: user_categories user_categories_user_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_categories
    ADD CONSTRAINT user_categories_user_id_code_key UNIQUE (user_id, code);


--
-- Name: user_categories user_categories_user_id_normalized_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_categories
    ADD CONSTRAINT user_categories_user_id_normalized_name_key UNIQUE (user_id, normalized_name);


--
-- Name: user_category_budgets user_category_budgets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_category_budgets
    ADD CONSTRAINT user_category_budgets_pkey PRIMARY KEY (id);


--
-- Name: user_category_budgets user_category_budgets_user_id_period_id_category_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_category_budgets
    ADD CONSTRAINT user_category_budgets_user_id_period_id_category_id_key UNIQUE (user_id, period_id, category_id);


--
-- Name: user_consumption_selections user_consumption_selections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_consumption_selections
    ADD CONSTRAINT user_consumption_selections_pkey PRIMARY KEY (id);


--
-- Name: user_consumption_selections user_consumption_selections_user_id_merchant_normalized_nor_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_consumption_selections
    ADD CONSTRAINT user_consumption_selections_user_id_merchant_normalized_nor_key UNIQUE (user_id, merchant_normalized, normalization_version);


--
-- Name: user_devices user_devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_devices
    ADD CONSTRAINT user_devices_pkey PRIMARY KEY (id);


--
-- Name: user_devices user_devices_user_id_device_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_devices
    ADD CONSTRAINT user_devices_user_id_device_id_key UNIQUE (user_id, device_id);


--
-- Name: audit_log_subject_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_log_subject_created_idx ON public.audit_log USING btree (subject_user_id, created_at DESC);


--
-- Name: budget_card_category_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX budget_card_category_uq ON public.budget_allocations USING btree (period_id, card_id, category_id) WHERE (scope = 'CARD_CATEGORY'::public.budget_scope);


--
-- Name: budget_card_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX budget_card_uq ON public.budget_allocations USING btree (period_id, card_id) WHERE (scope = 'CARD'::public.budget_scope);


--
-- Name: budget_category_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX budget_category_uq ON public.budget_allocations USING btree (period_id, category_id) WHERE (scope = 'CATEGORY'::public.budget_scope);


--
-- Name: cards_active_identity_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX cards_active_identity_uq ON public.cards USING btree (user_id, lower(btrim((name)::text))) WHERE (status = 'ACTIVE'::public.card_status);


--
-- Name: cards_user_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX cards_user_status_idx ON public.cards USING btree (user_id, status);


--
-- Name: currencies_single_base_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX currencies_single_base_uq ON public.currencies USING btree (is_base_currency) WHERE is_base_currency;


--
-- Name: expenses_card_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX expenses_card_date_idx ON public.expenses USING btree (user_id, card_id, occurred_at);


--
-- Name: expenses_category_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX expenses_category_date_idx ON public.expenses USING btree (user_id, category_id, occurred_at);


--
-- Name: expenses_user_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX expenses_user_date_idx ON public.expenses USING btree (user_id, occurred_at);


--
-- Name: expenses_user_idempotency_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX expenses_user_idempotency_uq ON public.expenses USING btree (user_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: installment_payment_allocations_installment_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX installment_payment_allocations_installment_idx ON public.installment_payment_allocations USING btree (installment_id);


--
-- Name: installment_payments_plan_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX installment_payments_plan_idx ON public.installment_payments USING btree (installment_plan_id, paid_at);


--
-- Name: installments_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX installments_due_idx ON public.installments USING btree (due_date, status);


--
-- Name: monthly_income_global_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX monthly_income_global_uq ON public.monthly_incomes USING btree (period_id) WHERE (card_id IS NULL);


--
-- Name: pending_movements_queue_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX pending_movements_queue_idx ON public.pending_movements USING btree (user_id, status, created_at);


--
-- Name: shortcut_credentials_active_user_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX shortcut_credentials_active_user_uq ON public.shortcut_credentials USING btree (user_id) WHERE active;


--
-- Name: shortcut_credentials_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX shortcut_credentials_user_idx ON public.shortcut_credentials USING btree (user_id, created_at DESC);


--
-- Name: unregistered_payments_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX unregistered_payments_created_idx ON public.unregistered_payments USING btree (created_at DESC);


--
-- Name: user_categories_system_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX user_categories_system_uq ON public.user_categories USING btree (user_id, system_category_id) WHERE (system_category_id IS NOT NULL);


--
-- Name: user_category_budgets_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_category_budgets_active_idx ON public.user_category_budgets USING btree (user_id, period_id, active);


--
-- Name: user_devices_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_devices_user_id_idx ON public.user_devices USING btree (user_id);


--
-- Name: app_users app_users_seed_system_categories; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_users_seed_system_categories AFTER INSERT ON public.app_users FOR EACH ROW EXECUTE FUNCTION public.seed_user_system_categories();


--
-- Name: audit_log audit_log_no_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON public.audit_log FOR EACH ROW EXECUTE FUNCTION public.reject_audit_mutation();


--
-- Name: audit_log audit_log_no_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON public.audit_log FOR EACH ROW EXECUTE FUNCTION public.reject_audit_mutation();


--
-- Name: budget_allocations budgets_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER budgets_set_updated_at BEFORE UPDATE ON public.budget_allocations FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: budget_allocations budgets_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER budgets_validate_ownership BEFORE INSERT OR UPDATE ON public.budget_allocations FOR EACH ROW EXECUTE FUNCTION public.validate_budget_ownership();


--
-- Name: cards cards_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER cards_set_updated_at BEFORE UPDATE ON public.cards FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: expenses expenses_no_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER expenses_no_delete BEFORE DELETE ON public.expenses FOR EACH ROW EXECUTE FUNCTION public.protect_expense_update();


--
-- Name: expenses expenses_protect_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER expenses_protect_update BEFORE UPDATE ON public.expenses FOR EACH ROW EXECUTE FUNCTION public.protect_expense_update();


--
-- Name: expenses expenses_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER expenses_set_updated_at BEFORE UPDATE ON public.expenses FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: expenses expenses_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER expenses_validate_ownership BEFORE INSERT OR UPDATE ON public.expenses FOR EACH ROW EXECUTE FUNCTION public.validate_expense_ownership();


--
-- Name: monthly_incomes incomes_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER incomes_set_updated_at BEFORE UPDATE ON public.monthly_incomes FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: installment_payment_allocations installment_payment_allocations_sync_status; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER installment_payment_allocations_sync_status AFTER INSERT OR DELETE OR UPDATE ON public.installment_payment_allocations FOR EACH ROW EXECUTE FUNCTION public.sync_installment_payment_status();


--
-- Name: installment_payment_allocations installment_payment_allocations_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER installment_payment_allocations_validate_ownership BEFORE INSERT OR UPDATE ON public.installment_payment_allocations FOR EACH ROW EXECUTE FUNCTION public.validate_installment_ownership();


--
-- Name: installment_payment_allocations installment_payment_allocations_validate_totals; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER installment_payment_allocations_validate_totals AFTER INSERT OR DELETE OR UPDATE ON public.installment_payment_allocations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_payment_allocations();


--
-- Name: installment_payments installment_payments_validate_allocations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER installment_payments_validate_allocations AFTER INSERT OR UPDATE ON public.installment_payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_payment_allocations();


--
-- Name: installment_payments installment_payments_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER installment_payments_validate_ownership BEFORE INSERT OR UPDATE ON public.installment_payments FOR EACH ROW EXECUTE FUNCTION public.validate_installment_ownership();


--
-- Name: installment_plans installment_plans_validate_math; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER installment_plans_validate_math AFTER INSERT OR UPDATE ON public.installment_plans DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_installment_plan_math();


--
-- Name: installment_plans installment_plans_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER installment_plans_validate_ownership BEFORE INSERT OR UPDATE ON public.installment_plans FOR EACH ROW EXECUTE FUNCTION public.validate_installment_ownership();


--
-- Name: installments installments_validate_math; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER installments_validate_math AFTER INSERT OR DELETE OR UPDATE ON public.installments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.validate_installment_plan_math();


--
-- Name: installments installments_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER installments_validate_ownership BEFORE INSERT OR UPDATE ON public.installments FOR EACH ROW EXECUTE FUNCTION public.validate_installment_ownership();


--
-- Name: pending_movements pending_movements_validate_suggestions; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER pending_movements_validate_suggestions BEFORE INSERT OR UPDATE ON public.pending_movements FOR EACH ROW EXECUTE FUNCTION public.validate_pending_suggestions_ownership();


--
-- Name: user_categories user_categories_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER user_categories_set_updated_at BEFORE UPDATE ON public.user_categories FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: user_consumption_selections user_consumption_selections_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER user_consumption_selections_set_updated_at BEFORE UPDATE ON public.user_consumption_selections FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: user_consumption_selections user_consumption_selections_validate_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER user_consumption_selections_validate_ownership BEFORE INSERT OR UPDATE ON public.user_consumption_selections FOR EACH ROW EXECUTE FUNCTION public.validate_category_rule_ownership();


--
-- Name: audit_log audit_log_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.app_users(id);


--
-- Name: audit_log audit_log_subject_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_subject_user_id_fkey FOREIGN KEY (subject_user_id) REFERENCES public.app_users(id);


--
-- Name: bank_card_offerings bank_card_offerings_bank_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_card_offerings
    ADD CONSTRAINT bank_card_offerings_bank_id_fkey FOREIGN KEY (bank_id) REFERENCES public.banks(id);


--
-- Name: budget_allocations budget_allocations_card_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_allocations
    ADD CONSTRAINT budget_allocations_card_id_fkey FOREIGN KEY (card_id) REFERENCES public.cards(id);


--
-- Name: budget_allocations budget_allocations_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_allocations
    ADD CONSTRAINT budget_allocations_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.user_categories(id);


--
-- Name: budget_allocations budget_allocations_currency_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_allocations
    ADD CONSTRAINT budget_allocations_currency_code_fkey FOREIGN KEY (currency_code) REFERENCES public.currencies(code);


--
-- Name: budget_allocations budget_allocations_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_allocations
    ADD CONSTRAINT budget_allocations_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.financial_periods(id);


--
-- Name: budget_allocations budget_allocations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_allocations
    ADD CONSTRAINT budget_allocations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: cards cards_bank_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cards
    ADD CONSTRAINT cards_bank_id_fkey FOREIGN KEY (bank_id) REFERENCES public.banks(id);


--
-- Name: cards cards_default_currency_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cards
    ADD CONSTRAINT cards_default_currency_code_fkey FOREIGN KEY (default_currency_code) REFERENCES public.currencies(code);


--
-- Name: cards cards_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cards
    ADD CONSTRAINT cards_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: expenses expenses_card_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_card_id_fkey FOREIGN KEY (card_id) REFERENCES public.cards(id);


--
-- Name: expenses expenses_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.user_categories(id);


--
-- Name: expenses expenses_currency_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_currency_code_fkey FOREIGN KEY (currency_code) REFERENCES public.currencies(code);


--
-- Name: expenses expenses_pending_movement_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_pending_movement_id_fkey FOREIGN KEY (pending_movement_id) REFERENCES public.pending_movements(id);


--
-- Name: expenses expenses_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expenses
    ADD CONSTRAINT expenses_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: financial_periods financial_periods_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.financial_periods
    ADD CONSTRAINT financial_periods_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: installment_payment_allocations installment_payment_allocations_installment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payment_allocations
    ADD CONSTRAINT installment_payment_allocations_installment_id_fkey FOREIGN KEY (installment_id) REFERENCES public.installments(id);


--
-- Name: installment_payment_allocations installment_payment_allocations_payment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payment_allocations
    ADD CONSTRAINT installment_payment_allocations_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES public.installment_payments(id);


--
-- Name: installment_payment_allocations installment_payment_allocations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payment_allocations
    ADD CONSTRAINT installment_payment_allocations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: installment_payments installment_payments_installment_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payments
    ADD CONSTRAINT installment_payments_installment_plan_id_fkey FOREIGN KEY (installment_plan_id) REFERENCES public.installment_plans(id);


--
-- Name: installment_payments installment_payments_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_payments
    ADD CONSTRAINT installment_payments_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: installment_plans installment_plans_expense_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_plans
    ADD CONSTRAINT installment_plans_expense_id_fkey FOREIGN KEY (expense_id) REFERENCES public.expenses(id);


--
-- Name: installment_plans installment_plans_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installment_plans
    ADD CONSTRAINT installment_plans_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: installments installments_installment_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installments
    ADD CONSTRAINT installments_installment_plan_id_fkey FOREIGN KEY (installment_plan_id) REFERENCES public.installment_plans(id);


--
-- Name: installments installments_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installments
    ADD CONSTRAINT installments_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: monthly_incomes monthly_incomes_card_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_incomes
    ADD CONSTRAINT monthly_incomes_card_id_fkey FOREIGN KEY (card_id) REFERENCES public.cards(id);


--
-- Name: monthly_incomes monthly_incomes_currency_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_incomes
    ADD CONSTRAINT monthly_incomes_currency_code_fkey FOREIGN KEY (currency_code) REFERENCES public.currencies(code);


--
-- Name: monthly_incomes monthly_incomes_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_incomes
    ADD CONSTRAINT monthly_incomes_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.financial_periods(id);


--
-- Name: monthly_incomes monthly_incomes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_incomes
    ADD CONSTRAINT monthly_incomes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: pending_movements pending_movements_bank_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_bank_id_fkey FOREIGN KEY (bank_id) REFERENCES public.banks(id);


--
-- Name: pending_movements pending_movements_parsed_currency_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_parsed_currency_code_fkey FOREIGN KEY (parsed_currency_code) REFERENCES public.currencies(code);


--
-- Name: pending_movements pending_movements_suggested_card_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_suggested_card_id_fkey FOREIGN KEY (suggested_card_id) REFERENCES public.cards(id);


--
-- Name: pending_movements pending_movements_suggested_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_suggested_category_id_fkey FOREIGN KEY (suggested_category_id) REFERENCES public.user_categories(id);


--
-- Name: pending_movements pending_movements_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_movements
    ADD CONSTRAINT pending_movements_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: shortcut_credentials shortcut_credentials_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shortcut_credentials
    ADD CONSTRAINT shortcut_credentials_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;


--
-- Name: user_budget_settings user_budget_settings_currency_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_budget_settings
    ADD CONSTRAINT user_budget_settings_currency_code_fkey FOREIGN KEY (currency_code) REFERENCES public.currencies(code);


--
-- Name: user_budget_settings user_budget_settings_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_budget_settings
    ADD CONSTRAINT user_budget_settings_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.financial_periods(id);


--
-- Name: user_budget_settings user_budget_settings_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_budget_settings
    ADD CONSTRAINT user_budget_settings_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: user_categories user_categories_system_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_categories
    ADD CONSTRAINT user_categories_system_category_id_fkey FOREIGN KEY (system_category_id) REFERENCES public.system_categories(id);


--
-- Name: user_categories user_categories_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_categories
    ADD CONSTRAINT user_categories_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: user_category_budgets user_category_budgets_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_category_budgets
    ADD CONSTRAINT user_category_budgets_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.user_categories(id);


--
-- Name: user_category_budgets user_category_budgets_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_category_budgets
    ADD CONSTRAINT user_category_budgets_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.financial_periods(id);


--
-- Name: user_category_budgets user_category_budgets_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_category_budgets
    ADD CONSTRAINT user_category_budgets_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: user_consumption_selections user_consumption_selections_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_consumption_selections
    ADD CONSTRAINT user_consumption_selections_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.user_categories(id);


--
-- Name: user_consumption_selections user_consumption_selections_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_consumption_selections
    ADD CONSTRAINT user_consumption_selections_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id);


--
-- Name: user_devices user_devices_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_devices
    ADD CONSTRAINT user_devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict jYYeY4N9mysNVDaD1Cw2y1M9Q1zpS1p0V0pyoICgG11gaTZQA2apGFlQbt5Bsln

