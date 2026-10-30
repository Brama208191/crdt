create table documents (
    id         uuid primary key,
    title      text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Append-only log of every op the server accepted, in arrival order.
-- Insert: (counter, site) is the new char's id, (ref_counter, ref_site) the char it follows.
-- Delete: (counter, site) is the target id; ref and value are null.
create table operations (
    doc_id      uuid        not null references documents (id) on delete cascade,
    seq         bigint      not null,
    kind        char(1)     not null check (kind in ('i', 'd')),
    counter     bigint      not null,
    site        text        not null,
    ref_counter bigint,
    ref_site    text,
    value       text,
    created_at  timestamptz not null default now(),
    primary key (doc_id, seq)
);

-- Full CRDT state (including tombstones) after op `seq`; lets reloads skip replaying the whole log.
create table snapshots (
    doc_id     uuid        not null references documents (id) on delete cascade,
    seq        bigint      not null,
    state      text        not null,
    content    text        not null,
    created_at timestamptz not null default now(),
    primary key (doc_id, seq)
);
