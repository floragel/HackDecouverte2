-- Run this in Supabase > SQL Editor if you already ran the first version of schema.sql.

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
