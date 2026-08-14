begin;

alter table app_users add column if not exists email varchar(320);
alter table app_users add column if not exists display_name varchar(120);
alter table app_users add column if not exists avatar_url varchar(1000);
alter table app_users add column if not exists avatar_object_path varchar(500);
alter table app_users add column if not exists avatar_updated_at timestamptz;

commit;
