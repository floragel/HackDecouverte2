-- Lync v3 — campus events, interest groups (run after 002)

-- ---------- Events (published by clubs / organizations) ----------
create table if not exists public.events (
  id              uuid primary key default gen_random_uuid(),
  university_id   uuid not null references public.universities(id),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  title           text not null,
  description     text not null default '',
  category        text not null default 'social' check (category in ('social','academic','sport','career','culture','tech')),
  starts_at       timestamptz not null,
  ends_at         timestamptz not null,
  place           text not null default '',
  cover_url       text,
  featured        boolean not null default false,   -- set server-side for paying clubs
  created_at      timestamptz default now()
);
create index if not exists events_upcoming_idx on public.events (university_id, starts_at);

create table if not exists public.event_rsvps (
  event_id   uuid references public.events(id) on delete cascade,
  profile_id uuid references public.profiles(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (event_id, profile_id)
);

-- PostgREST embeds `organizer:organizations(...)` through this FK name
alter table public.events drop constraint if exists events_organization_id_fkey;
alter table public.events add constraint events_organization_id_fkey foreign key (organization_id) references public.organizations(id) on delete cascade;

-- ---------- Interest groups ----------
create table if not exists public.groups (
  id            uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id),
  name          text not null,
  emoji         text not null default '✨',
  description   text not null default '',
  created_by    uuid references public.profiles(id) on delete set null,
  created_at    timestamptz default now()
);

create table if not exists public.group_members (
  group_id   uuid references public.groups(id) on delete cascade,
  profile_id uuid references public.profiles(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (group_id, profile_id)
);

alter table public.posts add column if not exists group_id uuid references public.groups(id) on delete cascade;

-- ---------- RLS ----------
alter table public.events        enable row level security;
alter table public.event_rsvps   enable row level security;
alter table public.groups        enable row level security;
alter table public.group_members enable row level security;

create or replace function public.my_university() returns uuid
language sql stable security definer set search_path = public as $$
  select university_id from profiles where id = auth.uid();
$$;

drop policy if exists "events read" on public.events;
create policy "events read" on public.events for select using (university_id = public.my_university());
drop policy if exists "events by org admins" on public.events;
create policy "events by org admins" on public.events for insert with check (
  featured = false and exists (select 1 from public.organization_members m where m.organization_id = events.organization_id and m.profile_id = auth.uid())
);
drop policy if exists "rsvp read" on public.event_rsvps;
create policy "rsvp read" on public.event_rsvps for select using (exists (select 1 from public.events e where e.id = event_id and e.university_id = public.my_university()));
drop policy if exists "rsvp self" on public.event_rsvps;
create policy "rsvp self" on public.event_rsvps for insert with check (profile_id = auth.uid());
drop policy if exists "rsvp delete self" on public.event_rsvps;
create policy "rsvp delete self" on public.event_rsvps for delete using (profile_id = auth.uid());

drop policy if exists "groups read" on public.groups;
create policy "groups read" on public.groups for select using (university_id = public.my_university());
drop policy if exists "groups create" on public.groups;
create policy "groups create" on public.groups for insert with check (university_id = public.my_university() and created_by = auth.uid());
drop policy if exists "members read" on public.group_members;
create policy "members read" on public.group_members for select using (exists (select 1 from public.groups g where g.id = group_id and g.university_id = public.my_university()));
drop policy if exists "members join self" on public.group_members;
create policy "members join self" on public.group_members for insert with check (profile_id = auth.uid());
drop policy if exists "members leave self" on public.group_members;
create policy "members leave self" on public.group_members for delete using (profile_id = auth.uid());
