create table user_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references profiles(id) on delete cascade,
  device_id uuid not null,
  platform text not null check (platform in ('ios', 'android')),
  device_name text not null,
  biometric_enabled boolean not null default false,
  last_authenticated_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, device_id)
);

create index user_devices_user_id_idx on user_devices(user_id);
