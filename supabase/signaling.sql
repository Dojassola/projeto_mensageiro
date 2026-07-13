create table if not exists public.signals (
    id bigint generated always as identity primary key,
    session_id text not null check (length(session_id) between 32 and 100),
    sender_peer_id text not null check (length(sender_peer_id) between 20 and 100),
    receiver_peer_id text not null check (length(receiver_peer_id) between 20 and 100),
    type text not null check (type in ('OFFER', 'ANSWER', 'ICE')),
    payload text not null check (length(payload) <= 65535),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '5 minutes')
);

alter table public.signals enable row level security;
revoke all on public.signals from anon, authenticated;

create or replace function public.publish_signal(
    p_session_id text,
    p_sender_peer_id text,
    p_receiver_peer_id text,
    p_type text,
    p_payload text
) returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if length(p_payload) > 65535 then
        raise exception 'payload too large';
    end if;

    delete from public.signals where expires_at < now();
    if (select count(*) from public.signals where session_id = p_session_id) >= 256 then
        raise exception 'session signal limit reached';
    end if;
    insert into public.signals(session_id, sender_peer_id, receiver_peer_id, type, payload)
    values (p_session_id, p_sender_peer_id, p_receiver_peer_id, p_type, p_payload);
end;
$$;

create or replace function public.read_signals(p_receiver_peer_id text)
returns table(id bigint, payload text)
language sql
security definer
set search_path = public
as $$
    with picked as (
        select s.id
        from public.signals s
        where s.receiver_peer_id = p_receiver_peer_id
          and s.expires_at >= now()
        order by s.id
        limit 256
    ), consumed as (
        delete from public.signals s
        using picked
        where s.id = picked.id
        returning s.id, s.payload
    )
    select consumed.id, consumed.payload from consumed order by consumed.id;
$$;

revoke all on function public.publish_signal(text, text, text, text, text) from public;
revoke all on function public.read_signals(text) from public;
grant execute on function public.publish_signal(text, text, text, text, text) to anon;
grant execute on function public.read_signals(text) to anon;
