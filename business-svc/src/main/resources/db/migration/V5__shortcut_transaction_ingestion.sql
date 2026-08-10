create table captured_notifications (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references profiles(id) on delete cascade,
  bank_id uuid not null references banks(id) on delete restrict, source_label text not null, title text,
  body text not null, posted_at timestamptz not null, captured_at timestamptz not null default now(),
  parsed_amount_cents bigint not null check (parsed_amount_cents > 0), parsed_currency char(3) not null,
  parsed_merchant_raw text not null, parsed_card_last4 char(4) not null check (parsed_card_last4 ~ '^[0-9]{4}$'),
  parsed_card_brand card_brand not null default 'other', parsed_card_kind card_kind not null,
  parse_confidence numeric(4,3) not null default 1 check (parse_confidence between 0 and 1)
);
create index captured_dedup_idx on captured_notifications (user_id, bank_id, parsed_amount_cents, parsed_card_last4, posted_at);

create table transactions (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references profiles(id) on delete cascade,
  amount_cents bigint not null check (amount_cents > 0), currency char(3) not null default 'USD',
  occurred_at timestamptz not null, month_key char(7) not null, bank_account_id uuid not null references bank_accounts(id) on delete restrict,
  card_id uuid references cards(id) on delete set null, merchant_raw text not null, display_label text not null,
  source text not null check (source in ('shortcut','manual')), review_status text not null default 'needs_review'
    check (review_status in ('needs_review','user_confirmed','ignored')),
  captured_notification_id uuid unique references captured_notifications(id) on delete set null,
  created_at timestamptz not null default now()
);
create index transactions_user_occurred_idx on transactions (user_id, occurred_at desc);
