# tiger

Oddpool-style prediction-market discovery and search service for Kalshi and
Polymarket metadata.

The service ingests exchange data into PostgreSQL, normalizes it into the
canonical `series -> events -> markets -> market_outcomes` hierarchy, and serves
read-only search APIs from local persisted data.

## Requirements

- **Java 21** (tiger targets `release 21`; JDK 26 from Homebrew alone will not compile)
- Maven via repo wrapper `./mvnw` (no global `mvn` required), or `brew install maven`
- PostgreSQL 15 or newer, or Docker Compose

## Start PostgreSQL

```sh
docker compose up -d postgres
```

## Configuration

Kalshi credentials and local settings live in the **tiger repo root** (gitignored):

| File | Purpose |
|------|---------|
| `.env` | DB URL, `KALSHI_*` vars, ingestion toggles |
| `secrets/kalshi_private.key` | RSA private key PEM |

Copy `.env.example` → `.env` and place your key at `secrets/kalshi_private.key`.
Spring Boot loads `.env` automatically via `spring.config.import` in `application.yml`.

```sh
cp .env.example .env
chmod 600 secrets/kalshi_private.key
```

Flyway runs the PostgreSQL schema migration automatically when the app starts.

## Run

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`.

## Test

```sh
bash scripts/test.sh
```

Or:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw test
```

If you see `command not found: mvn`, use **`./mvnw`** from the repo root (Maven Wrapper), not `mvn`.

## Ingest Polymarket

Polymarket ingestion uses the Gamma API (`/events`) for discovery metadata and nested
markets, maps into `events`, `markets`, and `market_outcomes`, and records runs in
`ingestion_runs` / `ingestion_state`.

Run all commands from the repo root with Java 21:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### Recommended first-time backfill

```sh
bash scripts/run-polymarket-catalog.sh
```

The catalog job pages through Gamma `/events` using `limit`/`offset`, ingesting one
page at a time. By default `MAX_PAGES=0`, which means it continues until Gamma
returns fewer than `PAGE_LIMIT` events. To run a bounded smoke test:

```sh
MAX_PAGES=1 PAGE_LIMIT=100 bash scripts/run-polymarket-catalog.sh
```

### Scripts

| Script | What it loads |
|--------|---------------|
| `scripts/run-polymarket-events.sh` | One Gamma `/events` page -> `events`, `markets`, `market_outcomes` |
| `scripts/run-polymarket-catalog.sh` | Full paginated Gamma `/events` backfill |
| `scripts/verify-polymarket-db.sh` | Row counts + samples via Docker Postgres (no local `psql`) |

### Verify data

```sh
bash scripts/verify-polymarket-db.sh
```

Reports counts for `events`, `markets`, and `market_outcomes`
(`exchange='polymarket'`), sample rows, and recent `ingestion_runs`.

With local `psql` (`brew install libpq`):

```sh
psql postgresql://postgres:postgres@localhost:5432/tiger -c \
  "SELECT COUNT(*) FROM markets WHERE exchange='polymarket';"
```

### Spring Boot flags (alternative to scripts)

Enable jobs via CLI instead of editing `.env`:

```sh
# One events page
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.polymarket-events.enabled=true \
  --tiger.ingestion.polymarket-events.limit=100 \
  --tiger.ingestion.polymarket-events.offset=0 \
  --tiger.ingestion.exit-on-complete=true"

# Full catalog
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.polymarket-catalog.enabled=true \
  --tiger.ingestion.polymarket-catalog.page-limit=100 \
  --tiger.ingestion.polymarket-catalog.start-offset=0 \
  --tiger.ingestion.polymarket-catalog.max-pages=0 \
  --tiger.ingestion.exit-on-complete=true"
```

## Ingest Kalshi

Kalshi ingestion uses the signed Trade API (`/trade-api/v2`), maps into the canonical
`series → events → markets → market_outcomes` tables, and records runs in
`ingestion_runs` / `ingestion_state`.

**Prerequisites:** `.env` and `secrets/kalshi_private.key` (see Configuration). Run all commands from the repo root with Java 21:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### Recommended order (first-time backfill)

1. **Series** — all series metadata  
2. **Catalog** — paginated `/events` (nested markets + YES/NO outcomes), then open `/markets`

```sh
bash scripts/run-kalshi-series.sh
bash scripts/run-kalshi-catalog.sh
```

`run-kalshi-catalog.sh` skips `/series` by default (you already loaded it). To refresh series in the same run:

```sh
REFRESH_SERIES=1 bash scripts/run-kalshi-catalog.sh
```

### Ongoing incremental sync (after first backfill)

Use **`run-kalshi-catalog-incremental.sh`** on a schedule (cron, etc.) instead of re-running the full catalog:

1. `GET /series?min_updated_ts=...` — only series changed since last run  
2. `GET /events?series_ticker=...` — bootstrap events for **newly inserted** series  
3. `GET /events?min_updated_ts=...` — event/market updates on existing series  
4. `GET /markets?status=open` — open markets catch-up  

```sh
bash scripts/run-kalshi-catalog-incremental.sh
```

Events-only delta (no series/open-markets pass):

```sh
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-events.enabled=true \
  --tiger.ingestion.kalshi-events.incremental=true \
  --tiger.ingestion.exit-on-complete=true"
```

Catalog ingest can take several minutes (many paginated API calls). Ingestion processes **one API page at a time** (default **50 events/page** with nested markets) so the full catalog is not held in heap. If you still hit `OutOfMemoryError`, lower `tiger.ingestion.kalshi-events.page-limit` or set `with-nested-markets: false` and rely on the open-markets pass.

### Scripts

| Script | What it loads |
|--------|----------------|
| `scripts/run-kalshi-series.sh` | `GET /series` → `series` table |
| `scripts/run-kalshi-events.sh` | `GET /events?with_nested_markets=true` → `events`, `markets`, `market_outcomes` |
| `scripts/run-kalshi-catalog.sh` | Full events + outcomes, then `GET /markets?status=open` (first backfill) |
| `scripts/run-kalshi-catalog-incremental.sh` | Incremental series + events for new series + events delta + open markets |
| `scripts/verify-kalshi-db.sh` | row counts + samples via Docker Postgres (no local `psql`) |

### Verify data

```sh
bash scripts/verify-kalshi-db.sh
```

Reports counts for `series`, `events`, `markets`, and `market_outcomes` (`exchange='kalshi'`), sample rows, and recent `ingestion_runs`.

With local `psql` (`brew install libpq`):

```sh
psql postgresql://postgres:postgres@localhost:5432/tiger -c \
  "SELECT COUNT(*) FROM markets WHERE exchange='kalshi';"
```

### Spring Boot flags (alternative to scripts)

Enable jobs via CLI instead of editing `.env`:

```sh
# Series only
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-series.enabled=true \
  --tiger.ingestion.exit-on-complete=true"

# Events + nested markets + YES/NO outcomes only
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-events.enabled=true \
  --tiger.ingestion.exit-on-complete=true"

# Full catalog (events + open markets; optional series refresh)
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-catalog.enabled=true \
  --tiger.ingestion.kalshi-catalog.refresh-series=false \
  --tiger.ingestion.exit-on-complete=true"

# Incremental catalog sync (after first backfill)
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-catalog.enabled=true \
  --tiger.ingestion.kalshi-catalog.incremental=true \
  --tiger.ingestion.exit-on-complete=true"

# Open markets only (best after events ingest; creates placeholder events if needed)
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-open-markets.enabled=true \
  --tiger.ingestion.exit-on-complete=true"
```

### Environment variables (`.env`)

| Variable | Purpose |
|----------|---------|
| `KALSHI_ENV` | `prod` or `demo` |
| `KALSHI_KEY_ID` | API key id |
| `KALSHI_PRIVATE_KEY_PATH` | Path to PEM (default `secrets/kalshi_private.key`) |
| `TIGER_INGESTION_POLYMARKET_EVENTS_ENABLED` | Run one Polymarket `/events` page on startup |
| `TIGER_INGESTION_POLYMARKET_CATALOG_ENABLED` | Run Polymarket paginated `/events` catalog job |
| `TIGER_INGESTION_POLYMARKET_CATALOG_START_OFFSET` | Starting Gamma offset for catalog ingest |
| `TIGER_INGESTION_POLYMARKET_CATALOG_MAX_PAGES` | Catalog page cap; `0` means until short page |
| `TIGER_INGESTION_KALSHI_SERIES_ENABLED` | Run series job on startup |
| `TIGER_INGESTION_KALSHI_EVENTS_ENABLED` | Run events job on startup |
| `TIGER_INGESTION_KALSHI_OPEN_MARKETS_ENABLED` | Run open markets job on startup |
| `TIGER_INGESTION_KALSHI_CATALOG_ENABLED` | Run catalog job (events + open markets) |
| `TIGER_INGESTION_KALSHI_CATALOG_REFRESH_SERIES` | Also reload `/series` when catalog runs |
| `TIGER_INGESTION_KALSHI_CATALOG_INCREMENTAL` | Incremental catalog sync instead of full events scan |
| `TIGER_INGESTION_KALSHI_EVENTS_INCREMENTAL` | Poll `GET /events?min_updated_ts=...` instead of full scan |
| `TIGER_INGESTION_EXIT_ON_COMPLETE` | Exit JVM after one-shot ingest |

See `.env.example` for a full template.

### Code and schema

- Java package: `src/main/java/com/tiger/ingestion/kalshi/`
- DB schema: `src/main/resources/db/migration/V1__initial_prediction_market_schema.sql`

## Search API

```sh
curl "http://localhost:8080/search/markets?q=fed%20rate&limit=25"
curl "http://localhost:8080/search/events?series_id=KXBTC15M"
curl "http://localhost:8080/search/series?q=15m"
curl "http://localhost:8080/search/recent/markets?limit=25"
curl "http://localhost:8080/search/events/{event_id}/markets"
```
