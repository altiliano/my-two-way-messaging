# my-two-way-messaging

A small two-way messaging service built with **CQRS + Event Sourcing** in Kotlin
on the [http4k](https://www.http4k.org/) micro-framework. Conversations are
event-sourced: commands are turned into events by a decider, and read models are
derived by replaying those events.

> The current implementation stores everything in memory in a single JVM. For how
> this scales to durable, multi-instance, high-throughput operation, see
> [`docs/scaling.md`](docs/scaling.md).

## Requirements

- JDK 17+
- Gradle wrapper (included)

## Build and run

```sh
./gradlew run          # starts the server (default port 8080)
PORT=9000 ./gradlew run  # override the port
./gradlew test         # run the test suite
```

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/conversations` | Start a conversation (body: participants). Returns id + `ETag` version |
| `POST` | `/conversations/{id}/messages` | Post a message. Requires an `If-Match` version header |
| `GET`  | `/conversations/{id}` | Fetch a conversation with its messages (returns `ETag`) |
| `GET`  | `/users/{user}/conversations` | List a user's conversation ids |
| `GET`  | `/events` | The raw event log |

Writes use optimistic concurrency: the version is returned as an `ETag` and must
be echoed back in an `If-Match` header on the next `POST`.

## Project structure

```
src/main/kotlin/messaging/
├── Main.kt              entrypoint (SunHttp server, PORT env)
├── app/                 HTTP routing, command orchestration, JSON DTOs
├── domain/              commands, events, and the conversation decider/evolver
├── projection/          read models (conversation view, per-user conversations)
└── store/               EventStore interface + in-memory implementation
```

## Documentation

- [`docs/scaling.md`](docs/scaling.md) — scaling design: durability, pagination,
  snapshots, the outbox pattern, async projection/fan-out, sharding, SSE/WebSocket
  delivery, and a phased roadmap.
