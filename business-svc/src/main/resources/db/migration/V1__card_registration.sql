create extension if not exists pgcrypto;

create type card_brand as enum ('visa', 'mastercard', 'amex', 'diners', 'other');
create type card_kind as enum ('credit', 'debit');

-- La identidad la administra Keycloak; este UUID viene del claim JWT `sub`.
create table profiles (
  id uuid primary key,
  display_name text not null,
  email text not null,
  timezone text not null default 'America/Guayaquil',
  locale text not null default 'es-EC',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table user_settings (
  user_id uuid primary key references profiles(id) on delete cascade,
  currency char(3) not null default 'USD',
  month_start_day smallint not null default 1 check (month_start_day between 1 and 28),
  budget_alerts_enabled boolean not null default true,
  biometric_lock_enabled boolean not null default false,
  appearance text not null default 'auto' check (appearance in ('light', 'dark', 'auto')),
  capture_enabled boolean not null default false,
  updated_at timestamptz not null default now()
);

create table banks (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  legal_name text not null,
  short_name text not null,
  monogram text not null,
  brand_color text not null check (brand_color ~ '^#[0-9A-Fa-f]{6}$'),
  brand_ink_on_light boolean not null default false,
  country_code char(2) not null default 'EC',
  logo_url text,
  active boolean not null default true
);

create table bank_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references profiles(id) on delete cascade,
  bank_id uuid not null references banks(id) on delete restrict,
  connected_at timestamptz not null default now(),
  active boolean not null default true,
  unique (user_id, bank_id)
);

-- Nunca se guarda el número completo de la tarjeta, solo sus últimos cuatro dígitos.
create table cards (
  id uuid primary key default gen_random_uuid(),
  bank_account_id uuid not null references bank_accounts(id) on delete cascade,
  brand card_brand not null default 'other',
  kind card_kind not null,
  last4 char(4) not null check (last4 ~ '^[0-9]{4}$'),
  nickname text,
  auto_detected boolean not null default false,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (bank_account_id, kind, last4)
);

create index bank_accounts_user_id_idx on bank_accounts(user_id);
create index cards_bank_account_id_idx on cards(bank_account_id);
