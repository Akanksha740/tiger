# tiger

Oddpool-style prediction-market discovery and search service for Kalshi and
Polymarket metadata.

The service ingests exchange data into PostgreSQL, normalizes it into the
canonical `series -> events -> markets -> market_outcomes` hierarchy, and serves
read-only search APIs from local persisted data.

## Requirements

- Java 21 or newer
- Maven 3.9 or newer
- PostgreSQL 15 or newer, or Docker Compose

## Start PostgreSQL

```sh
docker compose up -d postgres
```

## Configuration

Copy `.env.example` into your shell environment or export equivalent variables:

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/tiger
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
```

Flyway runs the PostgreSQL schema migration automatically when the app starts.

## Run

```sh
mvn spring-boot:run
```

The API starts on `http://localhost:8080`.

## Test

```sh
mvn test
```

## Ingest Polymarket Events

```sh
mvn spring-boot:run -Dspring-boot.run.arguments="--tiger.ingestion.polymarket-events.enabled=true --tiger.ingestion.exit-on-complete=true --tiger.ingestion.polymarket-events.limit=100 --tiger.ingestion.polymarket-events.offset=0"
```

## Search API

```sh
curl "http://localhost:8080/search/markets?q=fed%20rate&limit=25"
curl "http://localhost:8080/search/events?series_id=KXBTC15M"
curl "http://localhost:8080/search/series?q=15m"
curl "http://localhost:8080/search/recent/markets?limit=25"
curl "http://localhost:8080/search/events/{event_id}/markets"
```
