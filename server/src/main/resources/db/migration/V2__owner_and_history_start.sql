-- SHA-256 of the secret key handed to the browser that created the document. Null for documents
-- made before owner keys existed; only the admin key can manage those.
alter table documents add column owner_key_hash text;

-- Ops and snapshots before this seq were removed by "clear history"; the snapshot at this seq
-- holds the full state, so loading never needs anything earlier.
alter table documents add column history_start bigint not null default 0;
