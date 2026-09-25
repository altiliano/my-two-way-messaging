# Scaling Design

How `my-two-way-messaging` can grow from an in-memory prototype into a durable,
horizontally scalable service — and what happens when a conversation holds
thousands of messages or the system handles millions of events.

## Overview

The service is a small **CQRS + Event Sourcing** messaging API written in Kotlin
on http4k. The domain is modelled as event-sourced conversations: commands are
turned into events by a decider (`decide`), and state is derived by replaying
those events (`evolve` / `replay`).

The **domain core is well factored and does not change** in any of the scaling
work below. Every scaling problem lives in three swappable seams:

1. The **`EventStore`** — how events are persisted (`store/EventStore.kt`).
2. The **projections** — how read models are built (`projection/`).
3. The **delivery transport** — how clients learn about new messages.

Today all three are a single-JVM, in-memory prototype. This document describes
the target design and a phased path to get there.

## Current architecture

```
Client ──HTTP──▶ MessagingApp (routing, error mapping)
                   │
                   ├─ write: MessagingService.handle()  ── read stream → replay → decide → append
                   │            └─▶ InMemoryEventStore (streams + global log, one lock)
                   │                     │ synchronous subscribe()
                   │                     ▼
                   └─ read:  ConversationProjection / UserConversationsProjection (in-memory maps)
```

- **Persistence:** none. All state lives in `mutableMap`s in one JVM and is lost
  on restart (`store/InMemoryEventStore.kt`).
- **Concurrency:** a single coarse `synchronized(lock)` serialises *every* write
  across *all* conversations; optimistic concurrency uses `version == event
  count`.
- **Projections:** updated **synchronously on the write thread** via
  `store.subscribe { ... }` wired up in `MessagingApp.init` (`app/MessagingApp.kt:46`).
- **Delivery:** pull-only. Clients poll `GET /conversations/{id}` and compare the
  `ETag`/version. There are no websockets, SSE, or push.

## The three scaling problems

### A. A conversation with thousands of messages

| Symptom | Root cause | Solution |
|---|---|---|
| Posting slows as the conversation grows | `handle()` reads and replays the **whole** stream on every write (`app/MessagingService.kt`) | **Snapshots**: persist `ConversationState` + version every N events; replay only the tail |
| Memory churn, trending toward O(n²) | `current.messages + MessageView(...)` copies the full list per post (`projection/ConversationProjection.kt`) | Store messages **incrementally** in a table; never rebuild the list in memory |
| Huge single responses | `GET /conversations/{id}` returns **all** messages at once (`app/Json.kt`) | **Cursor pagination**: `?afterVersion=&limit=`, return a `nextCursor` |

### B. Millions of events at rest

| Symptom | Root cause | Solution |
|---|---|---|
| Heap grows unbounded; everything lost on restart | `streams` + `log` held in memory (`store/InMemoryEventStore.kt`) | **Postgres append-only events table** for durability |
| One writer at a time, system-wide | single `synchronized(lock)` on append | **Per-stream** optimistic concurrency via a unique constraint |
| `/events` can OOM | `allEvents()` returns the entire log (`app/MessagingApp.kt:96`) | Paginate by global sequence: `?afterSeq=&limit=` |

### C. Millions of events per second (throughput)

| Symptom | Root cause | Solution |
|---|---|---|
| Cannot run more than one instance | state is not shared between processes | Shared Postgres → **stateless instances** behind a load balancer |
| Write latency coupled to projection work | synchronous subscribers inside `append` | **Outbox** + async projection workers |
| No horizontal partitioning | single node holds everything | **Shard** by `conversationId` |

## Target architecture

```
                    ┌────────────────────────────────────────┐
   Clients ──POST──▶│  Stateless http4k instances (N)         │
        ▲           │   command → decide → append (Postgres)  │
        │ SSE/WS    └───────────────┬────────────────────────┘
        │                           │ append event + outbox row (same tx)
        │                           ▼
        │                 ┌──────────────────┐
        │                 │  Postgres        │  events(seq, conv_id, version, type, payload)
        │                 │  events + outbox │  snapshots(conv_id, version, state)
        │                 │  + read tables   │  UNIQUE(conv_id, version)
        │                 └────────┬─────────┘
        │                          │ drain outbox
        │                          ▼
        │                 ┌──────────────────┐
        │                 │ Projection worker│──updates read tables + snapshots
        │                 └────────┬─────────┘
        │                          │ publishes
        └───────── notify ◀────────┘  (broker / Postgres LISTEN → SSE/WS fan-out)
```

### Postgres schema

```sql
-- append-only log; per-stream version enforces optimistic concurrency
CREATE TABLE events (
  seq        BIGSERIAL PRIMARY KEY,
  conv_id    TEXT        NOT NULL,
  version    INT         NOT NULL,
  type       TEXT        NOT NULL,
  payload    JSONB       NOT NULL,
  at         TIMESTAMPTZ NOT NULL,
  UNIQUE (conv_id, version)          -- replaces the global lock
);
CREATE INDEX ON events (conv_id, version);

CREATE TABLE snapshots (              -- fast replay for hot conversations
  conv_id  TEXT PRIMARY KEY,
  version  INT   NOT NULL,
  state    JSONB NOT NULL
);

CREATE TABLE messages (               -- read model, paginated
  conv_id    TEXT        NOT NULL,
  version    INT         NOT NULL,
  message_id TEXT        NOT NULL,
  sender     TEXT        NOT NULL,
  body       TEXT        NOT NULL,
  at         TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (conv_id, version)
);

CREATE TABLE outbox (                  -- async projection / fan-out
  seq       BIGINT  PRIMARY KEY REFERENCES events(seq),
  published BOOLEAN NOT NULL DEFAULT FALSE
);
```

The existing `EventStore` interface (`store/EventStore.kt`) maps almost 1:1 onto
this schema. `append` becomes an `INSERT`, and a `UNIQUE(conv_id, version)`
violation *is* the `ConcurrencyConflict` — no application-level lock needed.

## Key concepts

### Sharding

**Sharding** means splitting one big dataset across multiple independent stores
so no single machine has to hold or handle all of it. Each piece is a "shard".

The natural shard key here is `conversationId`, because a conversation is a
self-contained consistency boundary: all events for one conversation must stay
together and in order, but different conversations never need to be
transactionally consistent with each other.

```
conversationId ──hash──▶ shard number
  conv "abc..." → shard 0 → Postgres node A
  conv "def..." → shard 1 → Postgres node B
  conv "ghi..." → shard 2 → Postgres node C
```

A common rule is `shard = hash(conversationId) % numberOfShards`. Every read and
write for a conversation always lands on the same shard, so per-stream ordering
and the `UNIQUE(conv_id, version)` constraint keep working unchanged.

**Tradeoffs (why it is the last phase):**

- Cross-conversation queries (e.g. "all conversations for a user") may now span
  shards and need a separate lookup table or a scatter-gather query.
- Changing the shard count moves data around; consistent hashing or pre-splitting
  into many logical shards softens this.
- More database nodes to run, monitor, and back up.

Reach for sharding only once a single well-indexed Postgres genuinely runs out of
headroom — most systems go a long way on one node first.

### The outbox pattern

Once persistence is Postgres and projections/delivery live elsewhere, the write
path has a **dual write** problem: it must save the event *and* notify the
projection/broker, but a database and a broker cannot share one transaction. If
the event commits and the process crashes before the notify, the read side never
learns about the message — it is lost forever.

The outbox pattern fixes this by writing an "someone must publish this" marker
into an **`outbox` table in the same transaction** as the event:

```sql
BEGIN;
  INSERT INTO events (conv_id, version, type, payload, at) VALUES (...);  -- the truth
  INSERT INTO outbox (seq) VALUES (currval);                             -- publish marker
COMMIT;   -- both succeed or both roll back; no dual write
```

The write path never touches the broker, so writes stay fast and cannot fail
because a downstream system is down.

### Async projection and fan-out

A separate background **worker** (off the write path) continuously drains the
outbox:

```
loop:
  rows = SELECT * FROM outbox WHERE published = false ORDER BY seq LIMIT 100
  for each row:
      event = load event by seq
      → update read tables (messages, snapshots)   ← projection
      → push to connected SSE / WebSocket clients   ← fan-out
      → publish to broker (Kafka/SQS) if used       ← fan-out
      UPDATE outbox SET published = true WHERE seq = ...
```

- **Async projection** = building read models (`messages`, `snapshots`) *after*
  the write commits, in the worker rather than the request handler. The client's
  `POST` returns as soon as the event is durable; the read model catches up
  milliseconds later. This decouples write latency from projection work — the
  coupling that today's synchronous `store.subscribe` creates.
- **Fan-out** = delivering one event to the many places that care: every SSE or
  WebSocket client watching that conversation, plus any downstream consumers. One
  event fans out to N recipients.

**The one rule — at-least-once, so be idempotent.** A worker can crash after
publishing but before marking `published = true`, so an event may be delivered
twice. Every consumer must key on the event's `seq` (or `conv_id` + `version`) so
reprocessing is a no-op. This is why `outbox.seq` is the primary key.

## Delivery: polling to SSE/WebSocket

- **SSE (server-sent events)** — recommended first. One-way server → client push
  over plain HTTP, a good fit for "a new message arrived". Clients subscribe to
  `GET /conversations/{id}/stream`; sending stays a normal `POST`.
- **WebSocket** — only if bidirectional low-latency is needed (typing indicators,
  presence). More moving parts.
- **Fan-out source** — the projection worker publishes each `MessagePosted` to a
  broker or via Postgres `LISTEN/NOTIFY`; each instance holds the SSE/WS
  connections for its clients and pushes matching events.
- **Reconnect** — keep the `ETag`/version as the resync mechanism; a client that
  reconnects replays from `afterVersion`.

## Phased roadmap

Each phase is independently shippable and testable (test-first). Phases 1–3 alone
handle "thousands of messages" and "millions of events at rest"; 4–6 are for the
"millions of events per second" throughput tier.

| Phase | Change | Unlocks |
|---|---|---|
| 1 | Durable Postgres `EventStore` (append-only, per-stream version constraint) | Persistence + horizontal instances |
| 2 | Cursor pagination on `GET /conversations/{id}` and `/events` | Thousand-message conversations & huge logs |
| 3 | Incremental `messages` projection + snapshots | Kills O(n²) copies and slow replay |
| 4 | Outbox + async projection worker | Decouples write latency; enables multi-instance |
| 5 | SSE delivery (`/conversations/{id}/stream`), replacing polling | Real-time two-way messaging |
| 6 | Shard by `conversationId`, multi-instance | Throughput to millions of events |

## Key risks

- **Ordering** — rely on `events.seq` (global) and `version` (per-stream) for
  ordering; never wall-clock `at`.
- **Idempotency** — projection workers must be idempotent; key on `seq`.
- **Snapshot invalidation** — version snapshots by schema so they can be rebuilt
  when event shapes change.
- **SSE backpressure** — a slow client must not stall fan-out; buffer per
  connection and drop/reconnect with replay.
