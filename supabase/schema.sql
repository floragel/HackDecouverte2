-- =====================================================================
-- Campus — Supabase schema (social network part)
-- Run once in Supabase Dashboard > SQL Editor.
--
-- Model:
--   follows      = Instagram side (one-way, see someone's posts)
--   connections  = LinkedIn side (request -> accept). Accepted = "friends".
--   locations    = Snap Map side. Only friends (and optionally classmates)
--                  can read your position. Enforced by RLS, not by the app.
--   organizations + posts.is_sponsored = business model (clubs pay to promote)
-- =====================================================================

create extension if not exists "pgcrypto";

-- ---------- Universities (drives the app's colors) ----------
create table public.universities (
  id              uuid primary key default gen_random_uuid(),
  slug            text unique not null,          -- 'concordia'
  name            text not null,
  short_name      text not null,
  email_domain    text not null,                 -- 'concordia.ca' (also matches live.concordia.ca)
  primary_color   text not null,                 -- '#912338'
  secondary_color text not null,
  campus_lat      double precision not null,
  campus_lng      double precision not null,
  created_at      timestamptz default now()
);

insert into public.universities (slug, name, short_name, email_domain, primary_color, secondary_color, campus_lat, campus_lng)
values ('concordia', 'Concordia University', 'Concordia', 'concordia.ca', '#912338', '#D4A12A', 45.4972, -73.5790);

-- ---------- Profiles ----------
create type public.location_sharing as enum ('off', 'friends', 'friends_and_classmates');

create table public.profiles (
  id               uuid primary key references auth.users(id) on delete cascade,
  university_id    uuid not null references public.universities(id),
  username         text unique not null,
  full_name        text not null,
  program          text,
  year             smallint,
  bio              text,
  avatar_url       text,
  interests        text[] default '{}',
  status_key       text,                          -- 'coffee' | 'study' | 'lunch' | 'busy' | null
  location_sharing public.location_sharing not null default 'friends',
  created_at       timestamptz default now()
);

-- ---------- Courses ----------
create table public.courses (
  id            uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id),
  code          text not null,                    -- 'COMP 248'
  title         text not null,
  unique (university_id, code)
);

create table public.enrollments (
  profile_id uuid references public.profiles(id) on delete cascade,
  course_id  uuid references public.courses(id) on delete cascade,
  term       text not null,                       -- 'F2026'
  primary key (profile_id, course_id, term)
);

-- ---------- Social graph ----------
create table public.follows (
  follower_id uuid references public.profiles(id) on delete cascade,
  followee_id uuid references public.profiles(id) on delete cascade,
  created_at  timestamptz default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);

create type public.connection_status as enum ('pending', 'accepted');

create table public.connections (
  requester_id uuid references public.profiles(id) on delete cascade,
  addressee_id uuid references public.profiles(id) on delete cascade,
  status       public.connection_status not null default 'pending',
  source       text default 'app',                -- 'app' | 'qr' | 'nfc'
  created_at   timestamptz default now(),
  primary key (requester_id, addressee_id),
  check (requester_id <> addressee_id)
);

-- ---------- Clubs / businesses (paying side) ----------
create table public.organizations (
  id            uuid primary key default gen_random_uuid(),
  university_id uuid not null references public.universities(id),
  name          text not null,
  handle        text unique not null,
  avatar_url    text,
  verified      boolean default false,
  plan          text not null default 'free',     -- 'free' | 'boost' | 'partner'
  created_at    timestamptz default now()
);

create table public.organization_members (
  organization_id uuid references public.organizations(id) on delete cascade,
  profile_id      uuid references public.profiles(id) on delete cascade,
  role            text not null default 'admin',
  primary key (organization_id, profile_id)
);

-- ---------- Posts ----------
create table public.posts (
  id              uuid primary key default gen_random_uuid(),
  author_id       uuid references public.profiles(id) on delete cascade,
  organization_id uuid references public.organizations(id) on delete cascade,
  university_id   uuid not null references public.universities(id),
  image_url       text,
  caption         text,
  is_sponsored    boolean not null default false, -- only set server-side after payment
  created_at      timestamptz default now(),
  check (author_id is not null or organization_id is not null)
);
create index posts_feed_idx on public.posts (university_id, created_at desc);

create table public.likes (
  post_id    uuid references public.posts(id) on delete cascade,
  profile_id uuid references public.profiles(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (post_id, profile_id)
);

-- ---------- Live location (one row per user, upserted) ----------
create table public.locations (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  lat        double precision not null,
  lng        double precision not null,
  updated_at timestamptz not null default now()
);

-- =====================================================================
-- Helper functions (security definer so RLS policies can call them)
-- =====================================================================
create or replace function public.are_friends(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from connections
    where status = 'accepted'
      and ((requester_id = a and addressee_id = b) or (requester_id = b and addressee_id = a))
  );
$$;

create or replace function public.share_a_course(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from enrollments e1
    join enrollments e2 on e1.course_id = e2.course_id and e1.term = e2.term
    where e1.profile_id = a and e2.profile_id = b
  );
$$;

create or replace function public.same_university(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select (select university_id from profiles where id = a) = (select university_id from profiles where id = b);
$$;

-- Students in my courses this term, with the number of shared courses
create or replace function public.classmates(term_code text)
returns table (profile_id uuid, shared_count bigint)
language sql stable security definer set search_path = public as $$
  select e2.profile_id, count(*) as shared_count
  from enrollments e1
  join enrollments e2 on e1.course_id = e2.course_id and e1.term = e2.term
  where e1.profile_id = auth.uid() and e2.profile_id <> auth.uid() and e1.term = term_code
  group by e2.profile_id
  order by shared_count desc;
$$;

-- =====================================================================
-- Row Level Security
-- =====================================================================
alter table public.universities         enable row level security;
alter table public.profiles             enable row level security;
alter table public.courses              enable row level security;
alter table public.enrollments          enable row level security;
alter table public.follows              enable row level security;
alter table public.connections          enable row level security;
alter table public.organizations        enable row level security;
alter table public.organization_members enable row level security;
alter table public.posts                enable row level security;
alter table public.likes                enable row level security;
alter table public.locations            enable row level security;

create policy "universities readable" on public.universities for select using (true);
create policy "courses readable"      on public.courses      for select using (true);
create policy "orgs readable"         on public.organizations for select using (true);

-- Profiles: visible to students of the same university
create policy "profiles same uni" on public.profiles for select
  using (id = auth.uid() or public.same_university(auth.uid(), id));
create policy "profiles insert self" on public.profiles for insert with check (id = auth.uid());
create policy "profiles update self" on public.profiles for update using (id = auth.uid());

-- Enrollments: yours, or people you share a course with
create policy "enrollments read" on public.enrollments for select
  using (profile_id = auth.uid() or public.share_a_course(auth.uid(), profile_id));
create policy "enrollments write self" on public.enrollments for all
  using (profile_id = auth.uid()) with check (profile_id = auth.uid());

-- Follows
create policy "follows read" on public.follows for select using (true);
create policy "follows write self" on public.follows for insert with check (follower_id = auth.uid());
create policy "follows delete self" on public.follows for delete using (follower_id = auth.uid());

-- Connections: only the two people involved
create policy "connections read own" on public.connections for select
  using (auth.uid() in (requester_id, addressee_id));
create policy "connections request" on public.connections for insert
  with check (requester_id = auth.uid() and status = 'pending');
create policy "connections accept" on public.connections for update
  using (addressee_id = auth.uid()) with check (addressee_id = auth.uid());
create policy "connections remove" on public.connections for delete
  using (auth.uid() in (requester_id, addressee_id));

-- Posts: same university feed. Users can never self-flag a post as sponsored.
create policy "posts read same uni" on public.posts for select
  using (university_id = (select university_id from public.profiles where id = auth.uid()));
create policy "posts insert self" on public.posts for insert
  with check (author_id = auth.uid() and is_sponsored = false);
create policy "posts delete self" on public.posts for delete using (author_id = auth.uid());

create policy "likes read" on public.likes for select using (true);
create policy "likes write self" on public.likes for insert with check (profile_id = auth.uid());
create policy "likes delete self" on public.likes for delete using (profile_id = auth.uid());

-- Locations: THE privacy-critical policy.
create policy "locations read" on public.locations for select using (
  profile_id = auth.uid()
  or exists (
    select 1 from public.profiles p
    where p.id = locations.profile_id
      and (
        (p.location_sharing in ('friends', 'friends_and_classmates') and public.are_friends(auth.uid(), p.id))
        or (p.location_sharing = 'friends_and_classmates' and public.share_a_course(auth.uid(), p.id))
      )
  )
);
create policy "locations upsert self" on public.locations for insert with check (profile_id = auth.uid());
create policy "locations update self" on public.locations for update using (profile_id = auth.uid());
create policy "locations delete self" on public.locations for delete using (profile_id = auth.uid());

-- Positions older than 2h are treated as stale by the app; this keeps the table clean.
-- (Schedule with pg_cron: select cron.schedule('purge-locations', '*/30 * * * *', $$delete from public.locations where updated_at < now() - interval '2 hours'$$);)

-- Realtime for the friends map
alter publication supabase_realtime add table public.locations;

-- ---------- Storage ----------
insert into storage.buckets (id, name, public) values ('avatars', 'avatars', true), ('posts', 'posts', true)
on conflict do nothing;
create policy "upload own folder" on storage.objects for insert to authenticated
  with check (bucket_id in ('avatars', 'posts') and (storage.foldername(name))[1] = auth.uid()::text);

-- =====================================================================
-- v2 — Lync code, polls, plans, comments, stories
-- (safe to run after the v1 part above)
-- =====================================================================

-- Lync code: unique random 32-bit number drawn on the card and sent over NFC
create or replace function public.random_lync_code() returns bigint language plpgsql as $$
declare c bigint;
begin
  loop
    c := floor(random() * 4294967294)::bigint + 1;
    exit when not exists (select 1 from public.profiles where lync_code = c);
  end loop;
  return c;
end $$;

alter table public.profiles add column if not exists lync_code bigint unique default public.random_lync_code();

-- Post kinds
alter table public.posts add column if not exists kind text not null default 'photo'
  check (kind in ('photo', 'text', 'poll', 'plan'));
alter table public.posts add column if not exists text_style smallint not null default 0;
alter table public.posts add column if not exists poll_question text;
alter table public.posts add column if not exists poll_ends_at timestamptz;
alter table public.posts add column if not exists plan_activity text;      -- gym | library | coffee | lunch | study | sport | party | other
alter table public.posts add column if not exists plan_place text;
alter table public.posts add column if not exists plan_starts_at timestamptz;
alter table public.posts add column if not exists plan_spots int;

create table if not exists public.poll_options (
  id       uuid primary key default gen_random_uuid(),
  post_id  uuid not null references public.posts(id) on delete cascade,
  text     text not null,
  position smallint not null default 0
);

create table if not exists public.poll_votes (
  post_id    uuid references public.posts(id) on delete cascade,
  option_id  uuid not null references public.poll_options(id) on delete cascade,
  profile_id uuid references public.profiles(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (post_id, profile_id)               -- one vote per person
);

create table if not exists public.plan_participants (
  post_id    uuid references public.posts(id) on delete cascade,
  profile_id uuid references public.profiles(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (post_id, profile_id)
);

create table if not exists public.comments (
  id         uuid primary key default gen_random_uuid(),
  post_id    uuid not null references public.posts(id) on delete cascade,
  author_id  uuid not null references public.profiles(id) on delete cascade,
  text       text not null check (char_length(text) between 1 and 300),
  created_at timestamptz default now()
);

create table if not exists public.stories (
  id         uuid primary key default gen_random_uuid(),
  author_id  uuid not null references public.profiles(id) on delete cascade,
  image_url  text,
  text       text,
  text_style smallint not null default 0,
  created_at timestamptz default now()               -- the app shows the last 24h
);

create table if not exists public.story_views (
  story_id  uuid references public.stories(id) on delete cascade,
  viewer_id uuid references public.profiles(id) on delete cascade,
  primary key (story_id, viewer_id)
);

alter table public.poll_options      enable row level security;
alter table public.poll_votes        enable row level security;
alter table public.plan_participants enable row level security;
alter table public.comments          enable row level security;
alter table public.stories           enable row level security;
alter table public.story_views       enable row level security;

-- helper: can I see this post? (same university, like the posts policy)
create or replace function public.can_see_post(p uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from posts where id = p
    and university_id = (select university_id from profiles where id = auth.uid()));
$$;

create policy "poll options read" on public.poll_options for select using (public.can_see_post(post_id));
create policy "poll options write by author" on public.poll_options for insert
  with check (exists (select 1 from public.posts where id = post_id and author_id = auth.uid()));

create policy "votes read" on public.poll_votes for select using (public.can_see_post(post_id));
create policy "votes self" on public.poll_votes for insert with check (profile_id = auth.uid() and public.can_see_post(post_id));

create policy "plan read" on public.plan_participants for select using (public.can_see_post(post_id));
create policy "plan join self" on public.plan_participants for insert with check (profile_id = auth.uid() and public.can_see_post(post_id));
create policy "plan leave self" on public.plan_participants for delete using (profile_id = auth.uid());

create policy "comments read" on public.comments for select using (public.can_see_post(post_id));
create policy "comments write self" on public.comments for insert with check (author_id = auth.uid() and public.can_see_post(post_id));
create policy "comments delete self" on public.comments for delete using (author_id = auth.uid());

-- Stories: visible to people of the same university
create policy "stories read" on public.stories for select using (author_id = auth.uid() or public.same_university(auth.uid(), author_id));
create policy "stories write self" on public.stories for insert with check (author_id = auth.uid());
create policy "stories delete self" on public.stories for delete using (author_id = auth.uid());

create policy "story views self" on public.story_views for all using (viewer_id = auth.uid()) with check (viewer_id = auth.uid());
