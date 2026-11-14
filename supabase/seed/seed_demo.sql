-- =====================================================================
-- Lync — DEMO SEED (fake data) for the real Supabase database
--
-- Run in Supabase › SQL Editor AFTER schema.sql, migrations/002 and 003.
-- Safe to re-run: it deletes and recreates its own rows only (fixed UUIDs).
--
-- Creates 13 fake Concordia students, 4 clubs, courses, connections,
-- posts (photo / text / poll / plan), likes, comments, stories,
-- events, interest groups and live map positions.
--
-- DEMO LOGIN (fake account, demo only — never a real person):
--   email:    alex.martin@live.concordia.ca
--   password: LyncDemo2026!
-- Every fake account uses the same password.
--
-- To plug YOUR own account into the fake network after signing up in the app:
--   select public.lync_demo_adopt('you@live.concordia.ca');
-- =====================================================================

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- cleanup
delete from auth.users where id::text like '11111111-0000-4000-8000-%';     -- cascades to profiles, posts, likes…
delete from public.organizations where id::text like '22222222-0000-4000-8000-%';  -- cascades to events, org posts
delete from public.groups where id::text like '66666666-0000-4000-8000-%';

-- ---------------------------------------------------------------- helpers
create or replace function pg_temp.uid(n int) returns uuid language sql immutable as
$$ select ('11111111-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.oid(n int) returns uuid language sql immutable as
$$ select ('22222222-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.pid(n int) returns uuid language sql immutable as
$$ select ('33333333-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.optid(n int) returns uuid language sql immutable as
$$ select ('44444444-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.eid(n int) returns uuid language sql immutable as
$$ select ('55555555-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.gid(n int) returns uuid language sql immutable as
$$ select ('66666666-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.sid(n int) returns uuid language sql immutable as
$$ select ('77777777-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid $$;
create or replace function pg_temp.uni() returns uuid language sql stable as
$$ select id from public.universities where slug = 'concordia' $$;
create or replace function pg_temp.img(key text) returns text language sql immutable as
$$ select 'https://images.unsplash.com/photo-' || key || '?w=900&q=80' $$;
-- today at HH:MM in Montreal
create or replace function pg_temp.mtl(days int, hh int, mm int default 0) returns timestamptz language sql stable as
$$ select (date_trunc('day', now() at time zone 'America/Montreal') + make_interval(days => days, hours => hh, mins => mm)) at time zone 'America/Montreal' $$;

create or replace function pg_temp.mkuser(n int, email text, full_name text, username text, program text, yr int,
                                          status text, interests text[], bio text, sharing public.location_sharing, code bigint)
returns void language plpgsql as $$
declare id uuid := pg_temp.uid(n);
begin
  insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
                          raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
                          confirmation_token, email_change, email_change_token_new, recovery_token)
  values ('00000000-0000-0000-0000-000000000000', id, 'authenticated', 'authenticated', email,
          extensions.crypt('LyncDemo2026!', extensions.gen_salt('bf')), now(),
          '{"provider":"email","providers":["email"]}', jsonb_build_object('full_name', full_name), now(), now(), '', '', '', '');
  insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
  values (gen_random_uuid(), id, id::text, jsonb_build_object('sub', id::text, 'email', email, 'email_verified', true), 'email', now(), now(), now());
  insert into public.profiles (id, university_id, username, full_name, program, year, bio, avatar_url, interests, status_key, location_sharing, lync_code)
  values (id, pg_temp.uni(), username, full_name, program, yr, bio,
          'https://i.pravatar.cc/300?u=' || username, interests, status, sharing, code);
end $$;

-- ---------------------------------------------------------------- people
select pg_temp.mkuser(1,  'alex.martin@live.concordia.ca',   'Alex Martin',     'alex.martin', 'Computer Science',       1, 'coffee', array['Coffee','Music','Hackathons'], 'First year CS, looking for study buddies', 'friends', 3534583304);
select pg_temp.mkuser(2,  'maya.tremblay@live.concordia.ca', 'Maya Tremblay',   'maya.t',      'Computer Science',       1, 'coffee', array['Climbing','UI design','Matcha'], 'Webster lib 6th floor regular', 'friends', 2011004457);
select pg_temp.mkuser(3,  'karim.haddad@live.concordia.ca',  'Karim Haddad',    'karim',       'Software Engineering',   2, 'study',  array['F1','Robotics'], 'Building robots that mostly work', 'friends', 1987650231);
select pg_temp.mkuser(4,  'lea.nguyen@live.concordia.ca',    'Léa Nguyen',      'lea.ng',      'Psychology',             1, 'lunch',  array['Photography','K-pop'], null, 'friends', 3001122334);
select pg_temp.mkuser(5,  'noah.gagnon@live.concordia.ca',   'Noah Gagnon',     'noahg',       'Finance',                3, null,     array['Basketball','Investing'], null, 'friends', 1122334455);
select pg_temp.mkuser(6,  'ines.benali@live.concordia.ca',   'Inès Benali',     'ines.b',      'Computer Science',       1, 'coffee', array['Hackathons','Piano'], 'Gym at 5, code at 6', 'friends_and_classmates', 2233445566);
select pg_temp.mkuser(7,  'lucas.roy@live.concordia.ca',     'Lucas Roy',       'lucasroy',    'Mechanical Engineering', 2, 'busy',   array['Cars','3D printing'], null, 'friends', 3344556677);
select pg_temp.mkuser(8,  'sofia.rossi@live.concordia.ca',   'Sofia Rossi',     'sofia.r',     'Design',                 1, null,     array['Film','Typography'], null, 'friends', 4011223344);
select pg_temp.mkuser(9,  'adam.pelletier@live.concordia.ca','Adam Pelletier',  'adamp',       'Computer Science',       1, 'study',  array['Gaming','Chess'], null, 'friends_and_classmates', 1555666777);
select pg_temp.mkuser(10, 'chloe.martin@live.concordia.ca',  'Chloé Martin',    'chloe.m',     'Communications',         2, null,     array['Podcasts','Thrifting'], null, 'friends', 2666777888);
select pg_temp.mkuser(11, 'yanis.cherif@live.concordia.ca',  'Yanis Cherif',    'yanis',       'Data Science',           1, 'lunch',  array['Soccer','AI'], null, 'friends_and_classmates', 3777888999);
select pg_temp.mkuser(12, 'emma.lefebvre@live.concordia.ca', 'Emma Lefebvre',   'emma.lf',     'Computer Science',       2, null,     array['Running','Startups'], null, 'friends', 1888999000);
select pg_temp.mkuser(13, 'omar.diallo@live.concordia.ca',   'Omar Diallo',     'omar.d',      'Electrical Engineering', 3, 'study',  array['Music production','Soccer'], null, 'friends', 2999000111);

-- ---------------------------------------------------------------- courses (F2026)
insert into public.courses (university_id, code, title) values
  (pg_temp.uni(), 'COMP 248', 'Object-Oriented Programming I'),
  (pg_temp.uni(), 'MATH 203', 'Differential & Integral Calculus I'),
  (pg_temp.uni(), 'ENGR 201', 'Professional Practice and Responsibility'),
  (pg_temp.uni(), 'COMP 228', 'System Hardware'),
  (pg_temp.uni(), 'SOEN 287', 'Web Programming'),
  (pg_temp.uni(), 'PSYC 200', 'Introductory Psychology')
on conflict (university_id, code) do nothing;

create or replace function pg_temp.cid(c text) returns uuid language sql stable as
$$ select id from public.courses where university_id = pg_temp.uni() and code = c $$;

insert into public.enrollments (profile_id, course_id, term) values
  (pg_temp.uid(1), pg_temp.cid('COMP 248'), 'F2026'), (pg_temp.uid(1), pg_temp.cid('MATH 203'), 'F2026'),
  (pg_temp.uid(1), pg_temp.cid('ENGR 201'), 'F2026'), (pg_temp.uid(1), pg_temp.cid('COMP 228'), 'F2026'),
  (pg_temp.uid(2), pg_temp.cid('COMP 248'), 'F2026'), (pg_temp.uid(2), pg_temp.cid('MATH 203'), 'F2026'), (pg_temp.uid(2), pg_temp.cid('COMP 228'), 'F2026'),
  (pg_temp.uid(6), pg_temp.cid('COMP 248'), 'F2026'), (pg_temp.uid(6), pg_temp.cid('MATH 203'), 'F2026'),
  (pg_temp.uid(9), pg_temp.cid('COMP 248'), 'F2026'), (pg_temp.uid(9), pg_temp.cid('COMP 228'), 'F2026'),
  (pg_temp.uid(11), pg_temp.cid('MATH 203'), 'F2026'),
  (pg_temp.uid(3), pg_temp.cid('ENGR 201'), 'F2026'), (pg_temp.uid(3), pg_temp.cid('SOEN 287'), 'F2026'),
  (pg_temp.uid(12), pg_temp.cid('SOEN 287'), 'F2026'), (pg_temp.uid(12), pg_temp.cid('COMP 228'), 'F2026'),
  (pg_temp.uid(4), pg_temp.cid('PSYC 200'), 'F2026')
on conflict do nothing;

-- ---------------------------------------------------------------- social graph
insert into public.connections (requester_id, addressee_id, status, source) values
  (pg_temp.uid(2), pg_temp.uid(1), 'accepted', 'qr'),
  (pg_temp.uid(1), pg_temp.uid(3), 'accepted', 'app'),
  (pg_temp.uid(4), pg_temp.uid(1), 'accepted', 'nfc'),
  (pg_temp.uid(1), pg_temp.uid(7), 'accepted', 'app'),
  (pg_temp.uid(8), pg_temp.uid(1), 'pending', 'app'),
  (pg_temp.uid(10), pg_temp.uid(1), 'pending', 'app'),
  (pg_temp.uid(2), pg_temp.uid(6), 'accepted', 'app'),
  (pg_temp.uid(3), pg_temp.uid(12), 'accepted', 'app'),
  (pg_temp.uid(9), pg_temp.uid(11), 'accepted', 'app');

insert into public.follows (follower_id, followee_id)
select pg_temp.uid(a), pg_temp.uid(b) from (values (1,2),(1,3),(1,4),(2,1),(3,1),(4,1),(6,1),(9,1),(12,1),(2,3),(6,2),(11,2),(13,3)) v(a,b);

-- ---------------------------------------------------------------- clubs
insert into public.organizations (id, university_id, name, handle, avatar_url, verified, plan) values
  (pg_temp.oid(1), pg_temp.uni(), 'Concordia Robotics', 'conu.robotics', 'https://i.pravatar.cc/300?u=robotics', true, 'partner'),
  (pg_temp.oid(2), pg_temp.uni(), 'HackConcordia',      'hackconcordia', 'https://i.pravatar.cc/300?u=hackconcordia', true, 'boost'),
  (pg_temp.oid(3), pg_temp.uni(), 'Film Society',       'conu.film',     'https://i.pravatar.cc/300?u=film', false, 'free'),
  (pg_temp.oid(4), pg_temp.uni(), 'Climbing Club',      'conu.climb',    'https://i.pravatar.cc/300?u=climb', false, 'free');
insert into public.organization_members (organization_id, profile_id) values (pg_temp.oid(1), pg_temp.uid(3)), (pg_temp.oid(2), pg_temp.uid(6));

-- ---------------------------------------------------------------- posts
insert into public.posts (id, author_id, organization_id, university_id, kind, image_url, caption, text_style, is_sponsored,
                          poll_question, poll_ends_at, plan_activity, plan_place, plan_starts_at, plan_spots, created_at) values
  (pg_temp.pid(1), pg_temp.uid(6), null, pg_temp.uni(), 'plan', null, 'Leg day, need motivation 😤', 0, false, null, null, 'gym', 'Le Gym, EV building', now() + interval '50 minutes', 4, now() - interval '4 minutes'),
  (pg_temp.pid(2), pg_temp.uid(2), null, pg_temp.uni(), 'photo', pg_temp.img('1521587760476-6c12a4b040da'), 'Study session at the Webster library, 6th floor. Pull up 📚', 0, false, null, null, null, null, null, null, now() - interval '12 minutes'),
  (pg_temp.pid(3), pg_temp.uid(3), null, pg_temp.uni(), 'poll', null, null, 0, false, 'Best study spot on campus?', now() + interval '20 hours', null, null, null, null, now() - interval '25 minutes'),
  (pg_temp.pid(4), null, pg_temp.oid(2), pg_temp.uni(), 'photo', pg_temp.img('1531482615713-2afd69097998'), 'HackDécouverte II is live! Good luck everyone 🚀', 0, true, null, null, null, null, null, null, now() - interval '40 minutes'),
  (pg_temp.pid(5), pg_temp.uid(4), null, pg_temp.uni(), 'text', null, 'Anyone else''s 8:45 cancelled or is it just me 🙃', 2, false, null, null, null, null, null, null, now() - interval '70 minutes'),
  (pg_temp.pid(6), pg_temp.uid(11), null, pg_temp.uni(), 'plan', null, 'MATH 203 midterm grind, bring snacks', 0, false, null, null, 'library', 'Webster library, 4th floor', now() + interval '2 hours', null, now() - interval '90 minutes'),
  (pg_temp.pid(7), pg_temp.uid(4), null, pg_temp.uni(), 'photo', pg_temp.img('1491002052546-bf38f186af56'), 'First snow on campus ❄️', 0, false, null, null, null, null, null, null, now() - interval '2 hours'),
  (pg_temp.pid(8), pg_temp.uid(3), null, pg_temp.uni(), 'photo', pg_temp.img('1485827404703-89b55fcc595e'), 'Robot arm finally picks up the cup. 3 weeks. Worth it.', 0, false, null, null, null, null, null, null, now() - interval '5 hours'),
  (pg_temp.pid(9), null, pg_temp.oid(3), pg_temp.uni(), 'photo', pg_temp.img('1489599849927-2ee91cede3ba'), 'Free screening tonight 7pm, H-110. Popcorn on us 🍿', 0, false, null, null, null, null, null, null, now() - interval '8 hours'),
  (pg_temp.pid(10), pg_temp.uid(7), null, pg_temp.uni(), 'photo', pg_temp.img('1477959858617-67f85cf4f1df'), 'Hall building at golden hour', 0, false, null, null, null, null, null, null, now() - interval '26 hours'),
  (pg_temp.pid(11), pg_temp.uid(12), null, pg_temp.uni(), 'poll', null, null, 0, false, 'Who''s down for a COMP 228 study group this week?', now() + interval '2 days', null, null, null, null, now() - interval '3 hours'),
  (pg_temp.pid(12), pg_temp.uid(13), null, pg_temp.uni(), 'plan', null, 'Pickup soccer, all levels', 0, false, null, null, 'sport', 'Loyola PERFORM field', pg_temp.mtl(1, 17), 10, now() - interval '6 hours');

insert into public.poll_options (id, post_id, text, position) values
  (pg_temp.optid(1), pg_temp.pid(3), 'Webster library', 0),
  (pg_temp.optid(2), pg_temp.pid(3), 'EV 2nd floor', 1),
  (pg_temp.optid(3), pg_temp.pid(3), 'Hall building cafés', 2),
  (pg_temp.optid(4), pg_temp.pid(3), 'Home 🛏️', 3),
  (pg_temp.optid(5), pg_temp.pid(11), 'Tuesday evening', 0),
  (pg_temp.optid(6), pg_temp.pid(11), 'Thursday lunch', 1),
  (pg_temp.optid(7), pg_temp.pid(11), 'Weekend', 2);

insert into public.poll_votes (post_id, option_id, profile_id)
select pg_temp.pid(3), pg_temp.optid(o), pg_temp.uid(u) from (values (2,1),(3,2),(4,4),(5,4),(6,1),(7,3),(8,1),(9,2),(10,4),(11,1),(12,1),(13,3)) v(u,o);
insert into public.poll_votes (post_id, option_id, profile_id)
select pg_temp.pid(11), pg_temp.optid(o), pg_temp.uid(u) from (values (2,5),(3,6),(9,5),(12,5)) v(u,o);

insert into public.plan_participants (post_id, profile_id)
select pg_temp.pid(p), pg_temp.uid(u) from (values (1,6),(1,9),(6,11),(6,2),(6,6),(6,9),(12,13),(12,5),(12,11)) v(p,u);

-- likes: each post gets likes from a slice of the students
insert into public.likes (post_id, profile_id)
select pg_temp.pid(p), pg_temp.uid(u)
from generate_series(1, 12) p, generate_series(2, 13) u
where (p * 7 + u * 3) % 5 <> 0 and not (p = 12 and u > 6);

insert into public.comments (post_id, author_id, text, created_at) values
  (pg_temp.pid(2), pg_temp.uid(6), 'saving me a seat?', now() - interval '8 minutes'),
  (pg_temp.pid(2), pg_temp.uid(9), 'omw 🏃', now() - interval '5 minutes'),
  (pg_temp.pid(8), pg_temp.uid(7), 'this is so cool', now() - interval '4 hours'),
  (pg_temp.pid(8), pg_temp.uid(12), 'demo it at the open lab!', now() - interval '3 hours'),
  (pg_temp.pid(1), pg_temp.uid(9), 'count me in', now() - interval '2 minutes'),
  (pg_temp.pid(5), pg_temp.uid(2), 'mine too lol', now() - interval '60 minutes');

-- ---------------------------------------------------------------- stories (last 24h)
insert into public.stories (id, author_id, image_url, text, text_style, created_at) values
  (pg_temp.sid(1), pg_temp.uid(2), pg_temp.img('1521587760476-6c12a4b040da'), '6th floor is packed', 0, now() - interval '30 minutes'),
  (pg_temp.sid(2), pg_temp.uid(3), null, 'Robotics demo at 4pm, EV-11', 2, now() - interval '50 minutes'),
  (pg_temp.sid(3), pg_temp.uid(3), pg_temp.img('1485827404703-89b55fcc595e'), null, 0, now() - interval '40 minutes'),
  (pg_temp.sid(4), pg_temp.uid(4), pg_temp.img('1491002052546-bf38f186af56'), '❄️❄️❄️', 0, now() - interval '2 hours'),
  (pg_temp.sid(5), pg_temp.uid(7), null, 'who has the ENGR 201 notes 🙏', 1, now() - interval '3 hours');

-- ---------------------------------------------------------------- events
insert into public.events (id, university_id, organization_id, title, description, category, starts_at, ends_at, place, cover_url, featured) values
  (pg_temp.eid(1), pg_temp.uni(), pg_temp.oid(1), 'Robotics open lab', 'Come see the rover we''re sending to the University Rover Challenge. Pizza after 6.', 'tech', pg_temp.mtl(0, 17), pg_temp.mtl(0, 20), 'EV 11.119', pg_temp.img('1485827404703-89b55fcc595e'), true),
  (pg_temp.eid(2), pg_temp.uni(), pg_temp.oid(3), 'Free movie night: Dune', 'Big screen, free popcorn, bring a friend.', 'culture', pg_temp.mtl(0, 19), pg_temp.mtl(0, 22), 'H-110', pg_temp.img('1489599849927-2ee91cede3ba'), false),
  (pg_temp.eid(3), pg_temp.uni(), pg_temp.oid(4), 'Bouldering for beginners', 'No experience needed, shoes provided. Meet in the lobby.', 'sport', pg_temp.mtl(1, 18), pg_temp.mtl(1, 20), 'Le Gym, EV building', pg_temp.img('1541339907198-e08756dedf3f'), false),
  (pg_temp.eid(4), pg_temp.uni(), pg_temp.oid(2), 'Hackathon info session', 'Everything about HackConcordia''s next events, teams and prizes.', 'tech', pg_temp.mtl(2, 12), pg_temp.mtl(2, 13), 'Webster Library, LB-322', pg_temp.img('1531482615713-2afd69097998'), false),
  (pg_temp.eid(5), pg_temp.uni(), pg_temp.oid(2), 'Career fair: tech & engineering', '60 companies hiring interns. Bring your CV.', 'career', pg_temp.mtl(4, 10), pg_temp.mtl(4, 16), 'JMSB atrium', pg_temp.img('1477959858617-67f85cf4f1df'), true);

insert into public.event_rsvps (event_id, profile_id)
select pg_temp.eid(e), pg_temp.uid(u) from (values (1,3),(1,6),(1,12),(1,9),(2,4),(2,10),(2,8),(3,2),(3,7),(4,6),(4,13),(5,3),(5,7),(5,9),(5,12),(5,13),(5,5)) v(e,u);

-- ---------------------------------------------------------------- interest groups
insert into public.groups (id, university_id, name, emoji, description, created_by) values
  (pg_temp.gid(1), pg_temp.uni(), 'Climbing crew', '🧗', 'Bouldering sessions every week, all levels.', pg_temp.uid(2)),
  (pg_temp.gid(2), pg_temp.uni(), 'F1 & motorsport', '🏎️', 'Race weekends, watch parties, hot takes.', pg_temp.uid(3)),
  (pg_temp.gid(3), pg_temp.uni(), 'K-pop dance', '💃', 'Learn choreos, perform at Shuffle.', pg_temp.uid(4)),
  (pg_temp.gid(4), pg_temp.uni(), 'Chess club', '♟️', 'Blitz at the library café, Thursdays.', pg_temp.uid(9)),
  (pg_temp.gid(5), pg_temp.uni(), 'Photo walks', '📷', 'Montreal photo walks, film and digital.', pg_temp.uid(4)),
  (pg_temp.gid(6), pg_temp.uni(), 'AI & machine learning', '🤖', 'Paper club, projects, Kaggle teams.', pg_temp.uid(11));

insert into public.group_members (group_id, profile_id)
select pg_temp.gid(g), pg_temp.uid(u) from (values (1,2),(1,7),(1,1),(2,3),(2,7),(2,5),(3,4),(3,10),(4,9),(4,13),(5,4),(5,8),(5,1),(6,11),(6,6),(6,12),(6,13)) v(g,u);

insert into public.posts (id, author_id, university_id, group_id, kind, caption, plan_activity, plan_place, plan_starts_at, plan_spots, image_url, created_at) values
  (pg_temp.pid(20), pg_temp.uid(2), pg_temp.uni(), pg_temp.gid(1), 'plan', 'Who''s in for a session tonight?', 'sport', 'Allez Up, Saint-Henri', pg_temp.mtl(0, 19), 6, null, now() - interval '3 hours'),
  (pg_temp.pid(21), pg_temp.uid(4), pg_temp.uni(), pg_temp.gid(5), 'photo', 'Golden hour on Sainte-Catherine', null, null, null, null, pg_temp.img('1477959858617-67f85cf4f1df'), now() - interval '26 hours'),
  (pg_temp.pid(22), pg_temp.uid(11), pg_temp.uni(), pg_temp.gid(6), 'text', 'Reading group this week: "Attention is all you need". Bring questions!', null, null, null, null, null, now() - interval '5 hours');
insert into public.plan_participants (post_id, profile_id) values (pg_temp.pid(20), pg_temp.uid(2)), (pg_temp.pid(20), pg_temp.uid(7));

-- ---------------------------------------------------------------- live map (fake positions on campus)
-- updated_at is set in the future so the demo map never looks stale (the app hides positions older than 2h).
insert into public.locations (profile_id, lat, lng, updated_at) values
  (pg_temp.uid(2), 45.49680, -73.57790, now() + interval '30 days'),   -- Webster Library
  (pg_temp.uid(3), 45.49560, -73.57820, now() + interval '30 days'),   -- EV building
  (pg_temp.uid(4), 45.49725, -73.57890, now() + interval '30 days'),   -- Hall building
  (pg_temp.uid(7), 45.49530, -73.57905, now() + interval '30 days'),   -- JMSB
  (pg_temp.uid(6), 45.49720, -73.57900, now() + interval '30 days'),   -- Hall building
  (pg_temp.uid(9), 45.45820, -73.64000, now() + interval '30 days'),   -- Loyola
  (pg_temp.uid(11), 45.49690, -73.57805, now() + interval '30 days')   -- Webster Library
on conflict (profile_id) do update set lat = excluded.lat, lng = excluded.lng, updated_at = excluded.updated_at;

-- ---------------------------------------------------------------- plug a real account into the fake network
create or replace function public.lync_demo_adopt(p_email text) returns text
language plpgsql security definer set search_path = public as $$
declare me uuid;
begin
  select u.id into me from auth.users u where lower(u.email) = lower(p_email);
  if me is null then return 'No account with this email. Sign up in the app first.'; end if;
  if not exists (select 1 from profiles where id = me) then return 'Account found but no profile yet. Finish onboarding in the app first.'; end if;
  insert into connections (requester_id, addressee_id, status, source) values
    ('11111111-0000-4000-8000-000000000002', me, 'accepted', 'qr'),
    (me, '11111111-0000-4000-8000-000000000003', 'accepted', 'app'),
    ('11111111-0000-4000-8000-000000000004', me, 'accepted', 'nfc'),
    (me, '11111111-0000-4000-8000-000000000007', 'accepted', 'app'),
    ('11111111-0000-4000-8000-000000000008', me, 'pending', 'app'),
    ('11111111-0000-4000-8000-000000000010', me, 'pending', 'app')
  on conflict do nothing;
  insert into enrollments (profile_id, course_id, term)
    select me, c.id, 'F2026' from courses c where c.code in ('COMP 248', 'MATH 203', 'ENGR 201', 'COMP 228')
  on conflict do nothing;
  insert into follows (follower_id, followee_id) values
    (me, '11111111-0000-4000-8000-000000000002'), (me, '11111111-0000-4000-8000-000000000003'),
    ('11111111-0000-4000-8000-000000000002', me), ('11111111-0000-4000-8000-000000000006', me)
  on conflict do nothing;
  return 'Done: connected to Maya, Karim, Léa, Lucas; 2 pending requests; enrolled in 4 courses.';
end $$;
revoke all on function public.lync_demo_adopt(text) from public, anon, authenticated;

select 'Lync demo seed done' as status,
       (select count(*) from public.profiles where id::text like '11111111-%') as students,
       (select count(*) from public.posts where id::text like '33333333-%') as posts,
       (select count(*) from public.events where id::text like '55555555-%') as events;
