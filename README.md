# CRDT Collaborative Editor

[![CI](https://github.com/Brama208191/crdt/actions/workflows/ci.yml/badge.svg)](https://github.com/Brama208191/crdt/actions/workflows/ci.yml)

**Live demo:** https://crdt-production-39f2.up.railway.app — open a document in two tabs and type in both.
*(The demo sleeps when nobody is using it, so the first load can take a few seconds.)*

A real-time collaborative text editor built on a **custom RGA (Replicated Growable Array) CRDT**.
Concurrent edits from any number of users merge deterministically: no locks, no central
sequencer, and no last-write-wins data loss.

**Stack:** Java 21 · Spring Boot · STOMP over WebSockets · PostgreSQL · Flyway · React · TypeScript · Slate.js · Docker

## Features

- Live multi-user editing with presence (who's online)
- Edits converge even when messages arrive out of order or twice
- Cursor stays put while others edit elsewhere in the document
- No lost edits: local ops stay queued until the server acknowledges them, and are resent after a disconnect (the server drops duplicates)
- Large pastes are sent in chunks
- Abuse limits for a public demo: 100k characters per document, 20 new documents per IP per hour
- Version history with preview and one-click restore
- Crash recovery from periodic snapshots plus the op log

## Architecture

```
 Browser tab (React + Slate)                 Spring Boot                       PostgreSQL
┌──────────────────────────┐   STOMP/WS    ┌─────────────────────────┐      ┌──────────────┐
│ Slate editor             │  ops batch →  │ SyncController          │      │ documents    │
│   ↕ text diff            │ ───────────── │   ↓                     │      │ operations   │ append-only log
│ RGA replica (TypeScript) │ ← new ops     │ DocumentService         │ ───→ │ snapshots    │ every 50 ops
│ outbox (unsent ops)      │   broadcast   │   RGA replica (Java)    │      └──────────────┘
└──────────────────────────┘               │ PresenceService         │
                                           └─────────────────────────┘
```

The server is just another replica. It applies incoming ops, appends the new ones to the log,
and broadcasts them. Every client converges because the CRDT, not the server, decides the order.

## How the CRDT works

[`Rga.java`](server/src/main/java/dev/crdt/editor/crdt/Rga.java) · [`rga.ts`](client/src/crdt/rga.ts)

- Every character gets a unique id `(lamportCounter, siteId)`. Ids are totally ordered: counter first, then site id.
- **Insert** says "put `c` right after the character with id `X`". If several replicas insert after `X`
  concurrently, the one with the larger id goes first. Lamport counters guarantee that anything typed
  after a character has a larger id, so whole words never interleave.
- **Delete** only marks a character as a tombstone, so later ops can still refer to it.
- Ops whose dependency hasn't arrived yet are buffered, and duplicates are ignored. Applying ops is
  therefore commutative and idempotent, which is why replicas converge without coordination.

**Why not last-write-wins or locking?** LWW silently drops one user's work. Locking adds latency and
breaks offline editing. Operational Transformation also works, but it needs a central server to
transform ops against each other. A CRDT pushes the merge logic into the data structure itself.

**Trade-offs:** tombstones are never removed, so a document's state grows with its total edit count.
The server keeps live documents in memory, which assumes a single instance. Scaling out would need a
shared broker (e.g. Redis/RabbitMQ relay) and a sticky owner per document.

## Persistence and recovery

- `operations` is an append-only log of every accepted op.
- `snapshots` stores the full CRDT state, tombstones included, every 50 ops.
- Loading a document after a restart = latest snapshot + replay the ops after it.
- **Restore** never rewrites history. It emits ordinary ops that turn the current text into the old
  version, so connected clients merge it like any other edit, and the restore itself becomes a new version.

## Testing

| Test | What it proves |
|---|---|
| [`RgaTest`](server/src/test/java/dev/crdt/editor/crdt/RgaTest.java) | Concurrent inserts and deletes, out-of-order delivery, idempotency, snapshot round-trip |
| [`ConvergenceFuzzTest`](server/src/test/java/dev/crdt/editor/crdt/ConvergenceFuzzTest.java) | 5 replicas × 2,000 random edits over a network that reorders and duplicates messages all end up identical (5 seeds) |
| [`DocumentServiceIntegrationTest`](server/src/test/java/dev/crdt/editor/document/DocumentServiceIntegrationTest.java) | Real Postgres via Testcontainers: crash recovery, duplicate filtering, restore |
| [`rga.test.ts`](client/src/crdt/rga.test.ts) | The TypeScript replica passes the same convergence fuzz test, plus cursor anchoring |

```bash
cd server && ./mvnw test      # needs Docker running for Testcontainers
cd client && npm test
```

## Running locally

Everything in Docker:

```bash
docker compose --profile full up --build
# open http://localhost:8080
```

Hot-reload development:

```bash
docker compose up -d db                 # Postgres only
cd server && ./mvnw spring-boot:run     # API on :8080
cd client && npm install && npm run dev # UI on :5173, proxies to :8080
```

## Deployment

Runs on [Railway](https://railway.com) as one Docker container plus a Railway Postgres database,
for about $1 to $3 a month. The app sleeps when idle and wakes on the next visit. The image runs as
a non-root user with a capped heap, and every response carries security headers (CSP, HSTS,
`nosniff`, frame and referrer policies).

The only setting is `DATABASE_URL`, taken from the Railway Postgres service. Flyway creates the
tables on the first start.

See [docs/deployment.md](docs/deployment.md) for the first-deploy guide, the costs, and how to
try the production image locally.
