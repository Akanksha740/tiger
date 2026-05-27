# AGENTS.md

Guidance for AI coding agents working on this SDLC project.

## Project Mission

Build an Oddpool-style prediction-market discovery and search platform for Kalshi and Polymarket data.

The application is a read-only metadata search system. It ingests exchange data, normalizes it into a canonical model, stores it in PostgreSQL, and serves search APIs from the local database or search index. User-facing search requests must not call Kalshi or Polymarket live.

## Source Of Truth

The technical specification for this project is:

- `/Users/Rahul/Downloads/oddpool_style_search_platform_technical_spec.docx`

Use that document as the product and architecture reference. If implementation details are missing from the repo, prefer the spec over ad hoc assumptions.

## Core Architecture

Use this shape unless the project owner explicitly changes direction:

1. Exchange ingestion workers poll Kalshi and Polymarket APIs.
2. Exchange-specific normalizers map raw payloads into the canonical model.
3. PostgreSQL is the source of truth.
4. Search API endpoints query PostgreSQL indexes and ranking logic.
5. Frontends, bots, analytics jobs, and downstream clients consume the Search API.

Do not introduce MongoDB or OpenSearch as the primary datastore. OpenSearch may be added later as a mirror if usage proves it is needed. TimescaleDB may be added later for historical price snapshots.

## Canonical Domain Model

Keep the hierarchy consistent:

```text
Series (optional parent)
  -> Event (required parent for markets)
       -> Market (tradable contract)
            -> Market outcomes
```

Important invariants:

- A market must always belong to exactly one event.
- An event may belong to one series, but `series_id` is nullable.
- A market may have a nullable `series_id` through its event.
- If an exchange exposes a market without a clean event, create a synthetic single-market event internally.
- Preserve raw exchange payloads in JSONB so mappings can be corrected later.
- Never rely on an unscoped source ID. Public IDs must include exchange scope, such as `kalshi:market:<source_id>`.

## Primary Data Store

Use PostgreSQL for the MVP.

Required PostgreSQL features:

- `pgcrypto` for UUID generation.
- `pg_trgm` for fuzzy matching and partial IDs.
- `unaccent` when text normalization needs it.
- JSONB columns for raw exchange payloads.
<!-- - Generated `search_text` and `search_vector` columns where appropriate. -->
- GIN indexes for full-text search, trigram search, arrays, and tags.

Core tables from the spec:

<!-- - `exchanges` -->
- `series`
- `events`
- `markets`
- `market_outcomes`
<!-- - `ingestion_state`
- `ingestion_runs` -->
- `market_price_snapshots` only when historical analytics are needed

## Public API Scope

Implement these endpoints according to the spec:

- `GET /search`
- `GET /search/series`
- `GET /search/events`
- `GET /search/markets`
- `GET /search/recent/events`
- `GET /search/recent/markets`
- `GET /search/events/{event_id}/markets`

Endpoint rules:

- Query local persisted data only.
- Validate `limit` and `offset` everywhere.
- Cap search endpoint limits at 100.
- Default searchable objects to `status=active` unless the endpoint or caller specifies otherwise.
- Support filters such as `exchange`, `category`, `status`, `series_id`, `min_volume`, `min_liquidity`, and date ranges where described by the spec.
- Return stable public IDs in API responses, not internal UUIDs.

## Search And Ranking

MVP search should use PostgreSQL full-text search plus trigram matching.

Ranking should favor:

- Exact source ID or ticker matches.
- Exact interval matches such as `15m`.
- Full-text relevance.
- Active status.
- Higher volume and liquidity.
- Recent discovery when relevant.

Queries such as `15m` must work through explicit interval metadata, not only through text search. Store normalized interval fields such as `interval_code`, `interval_seconds`, and aliases.

## Ingestion Rules

General ingestion order:

1. Fetch exchange objects with pagination or cursors.
2. Normalize status, IDs, timestamps, categories, interval metadata, and price fields.
3. Upsert series first when a source series exists.
4. Upsert events next with nullable `series_id`.
5. Upsert markets with non-null `event_id`.
6. Upsert outcomes for YES/NO sides or token IDs.
<!-- 7. Update aggregate counts and market questions on parent events.
8. Update aggregate counts and date boundaries on parent series. -->
<!-- 9. Record run status in `ingestion_runs` and health/cursors in `ingestion_state`. -->

Polymarket:

- Use Gamma API discovery data for events, markets, tags, series, search, and browsing.
- Fetch events with pagination and extract nested markets.
- Store original fields such as `outcomes`, `outcomePrices`, `conditionId`, `clobTokenIds`, `active`, `closed`, `archived`, `volume`, and `liquidity` in `raw_payload`.
- Keep `series_id` null when no series exists.

Kalshi:

- Fetch series, events, and markets from Kalshi APIs.
- Use series ticker as the source series key.
- Use event ticker as the event parent key.
- Use market ticker as the unique tradable market key.
- Store yes/no bid, ask, last price, volume, open interest, liquidity, and lifecycle timestamps.

## Status Normalization

Use canonical statuses:

- `unopened`
- `active`
- `paused`
- `closed`
- `settled`
- `archived`
- `unknown`

Keep unmapped source status details in `source_status`. When a status cannot be mapped safely, use `unknown` rather than guessing.

## Data Quality Checks

Preserve these checks in migrations, services, tests, or scheduled validation jobs:

- `markets.event_id` must never be null.
- `series_id` may be null on events and markets.
<!-- - Each `(exchange)` pair must be unique per entity type. -->
<!-- - Active events should not have zero markets unless the source genuinely exposes them that way. -->
<!-- - Active markets should not have stale `last_seen_at` beyond the configured freshness threshold. -->
<!-- - Prices should be between 0 and 1 when populated. -->
<!-- - Binary YES and NO prices should be close to 1, allowing for spread and fees. -->
- `discovered_at` should be set for every searchable object.
- `raw_payload` should be present for every ingested object.

## Implementation Phases

Follow this order unless the project owner reprioritizes:

1. Create PostgreSQL schema, extensions, constraints, and indexes.
2. Build Polymarket ingestion for events and nested markets.
<!-- 3. Build Kalshi ingestion for series, events, markets, and outcomes.
4. Implement `/search/markets`, `/search/events`, and `/search/events/{event_id}/markets`.
5. Add `/search/recent/markets` and `/search/recent/events`.
6. Add `/search/series` and interval normalization for queries such as `15m`.
7. Improve ranking, aliases, deduplication, admin observability, and freshness checks.
8. Add TimescaleDB snapshots and/or an OpenSearch mirror only if needed. -->

## Engineering Practices

- Keep changes small and aligned with the current phase.
- Prefer typed models, migrations, and query builders/ORM patterns already present in the repo.
- Do not hard-code credentials or API keys.
<!-- - Store external API cursors and ingestion health in the database. -->
- Make ingestion idempotent with upserts.
- Preserve source payloads for auditability.
- Add tests around normalizers, ID generation, status mapping, interval parsing, search filters, and pagination.
- For user-facing APIs, test default behavior and boundary cases such as empty results, high `limit`, invalid filters, and missing parents.

## Repository Notes

This repository is a Java 21 Spring Boot service using PostgreSQL, Flyway, and
plain JDBC.

Concrete commands:

- Start local PostgreSQL: `docker compose up -d postgres`
- Run the API service locally: `mvn spring-boot:run`
- Run database migrations: start the API; Flyway runs `src/main/resources/db/migration`
  automatically.
- Run Polymarket ingestion for one events page:
  `mvn spring-boot:run -Dspring-boot.run.arguments="--tiger.ingestion.polymarket-events.enabled=true --tiger.ingestion.exit-on-complete=true --tiger.ingestion.polymarket-events.limit=100 --tiger.ingestion.polymarket-events.offset=0"`
- Run unit tests: `mvn test`

Maven is required for Java build/test commands.
