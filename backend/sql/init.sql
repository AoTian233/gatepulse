create extension if not exists "pgcrypto";

create table if not exists public.device_rules (
  id uuid primary key default gen_random_uuid(),
  device_token text not null,
  package_name text not null,
  allowed boolean not null default false,
  expires_at bigint,
  note text,
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create unique index if not exists uq_device_rules_token_pkg
  on public.device_rules (device_token, package_name);

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists trg_touch_device_rules_updated_at on public.device_rules;
create trigger trg_touch_device_rules_updated_at
before update on public.device_rules
for each row execute function public.touch_updated_at();

alter table public.device_rules enable row level security;

drop policy if exists "deny_all_device_rules" on public.device_rules;
create policy "deny_all_device_rules"
on public.device_rules
for all
to public
using (false)
with check (false);
