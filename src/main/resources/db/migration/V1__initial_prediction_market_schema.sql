CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE OR REPLACE FUNCTION tiger_text_array_join(items TEXT[])
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
RETURNS NULL ON NULL INPUT
AS $$
    SELECT array_to_string(items, ' ')
$$;

CREATE TABLE exchanges (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    website_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO exchanges (code, name, website_url)
VALUES
    ('kalshi', 'Kalshi', 'https://kalshi.com'),
    ('polymarket', 'Polymarket', 'https://polymarket.com')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE series (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_series_id TEXT NOT NULL,
    source_ticker TEXT,
    slug TEXT,
    public_id TEXT GENERATED ALWAYS AS (exchange || ':series:' || source_series_id) STORED,
    title TEXT NOT NULL,
    subtitle TEXT,
    description TEXT,
    category TEXT,
    subcategory TEXT,
    tags TEXT[] NOT NULL DEFAULT '{}',
    aliases TEXT[] NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (status IN ('unopened', 'active', 'paused', 'closed', 'settled', 'archived', 'unknown')),
    source_status JSONB NOT NULL DEFAULT '{}'::jsonb,
    recurrence TEXT,
    frequency TEXT,
    interval_code TEXT,
    interval_seconds INTEGER,
    n_events_total INTEGER NOT NULL DEFAULT 0,
    n_events_active INTEGER NOT NULL DEFAULT 0,
    n_markets_total INTEGER NOT NULL DEFAULT 0,
    n_markets_active INTEGER NOT NULL DEFAULT 0,
    total_volume NUMERIC(28, 8),
    total_liquidity NUMERIC(28, 8),
    open_interest NUMERIC(28, 8),
    earliest_event_at TIMESTAMPTZ,
    latest_event_at TIMESTAMPTZ,
    image_url TEXT,
    icon_url TEXT,
    source_created_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    search_text TEXT GENERATED ALWAYS AS (
        coalesce(source_series_id, '') || ' ' ||
        coalesce(source_ticker, '') || ' ' ||
        coalesce(slug, '') || ' ' ||
        coalesce(title, '') || ' ' ||
        coalesce(subtitle, '') || ' ' ||
        coalesce(description, '') || ' ' ||
        coalesce(category, '') || ' ' ||
        coalesce(subcategory, '') || ' ' ||
        coalesce(interval_code, '') || ' ' ||
        tiger_text_array_join(tags) || ' ' ||
        tiger_text_array_join(aliases)
    ) STORED,
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(source_series_id, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(source_ticker, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(subtitle, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(category, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(interval_code, '')), 'A') ||
        setweight(to_tsvector('simple', tiger_text_array_join(tags)), 'B') ||
        setweight(to_tsvector('simple', tiger_text_array_join(aliases)), 'A')
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exchange, source_series_id)
);

CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_event_id TEXT NOT NULL,
    source_event_ticker TEXT,
    slug TEXT,
    public_id TEXT GENERATED ALWAYS AS (exchange || ':event:' || source_event_id) STORED,
    series_id UUID REFERENCES series(id) ON DELETE SET NULL,
    source_series_id TEXT,
    title TEXT NOT NULL,
    subtitle TEXT,
    description TEXT,
    category TEXT,
    subcategory TEXT,
    tags TEXT[] NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (status IN ('unopened', 'active', 'paused', 'closed', 'settled', 'archived', 'unknown')),
    source_status JSONB NOT NULL DEFAULT '{}'::jsonb,
    mutually_exclusive BOOLEAN,
    event_type TEXT,
    start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    open_at TIMESTAMPTZ,
    close_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    market_count INTEGER NOT NULL DEFAULT 0,
    active_market_count INTEGER NOT NULL DEFAULT 0,
    market_questions TEXT[] NOT NULL DEFAULT '{}',
    total_volume NUMERIC(28, 8),
    total_liquidity NUMERIC(28, 8),
    open_interest NUMERIC(28, 8),
    image_url TEXT,
    icon_url TEXT,
    source_created_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    search_text TEXT GENERATED ALWAYS AS (
        coalesce(source_event_id, '') || ' ' ||
        coalesce(source_event_ticker, '') || ' ' ||
        coalesce(source_series_id, '') || ' ' ||
        coalesce(slug, '') || ' ' ||
        coalesce(title, '') || ' ' ||
        coalesce(subtitle, '') || ' ' ||
        coalesce(description, '') || ' ' ||
        coalesce(category, '') || ' ' ||
        coalesce(subcategory, '') || ' ' ||
        tiger_text_array_join(tags) || ' ' ||
        tiger_text_array_join(market_questions)
    ) STORED,
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(source_event_id, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(source_event_ticker, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(source_series_id, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(subtitle, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(category, '')), 'B') ||
        setweight(to_tsvector('simple', tiger_text_array_join(tags)), 'B') ||
        setweight(to_tsvector('english', tiger_text_array_join(market_questions)), 'B')
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exchange, source_event_id)
);

CREATE TABLE markets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_market_id TEXT NOT NULL,
    source_market_ticker TEXT,
    condition_id TEXT,
    slug TEXT,
    public_id TEXT GENERATED ALWAYS AS (exchange || ':market:' || source_market_id) STORED,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    source_event_id TEXT NOT NULL,
    series_id UUID REFERENCES series(id) ON DELETE SET NULL,
    source_series_id TEXT,
    question TEXT NOT NULL,
    title TEXT,
    subtitle TEXT,
    description TEXT,
    rules_primary TEXT,
    rules_secondary TEXT,
    category TEXT,
    subcategory TEXT,
    tags TEXT[] NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (status IN ('unopened', 'active', 'paused', 'closed', 'settled', 'archived', 'unknown')),
    source_status JSONB NOT NULL DEFAULT '{}'::jsonb,
    market_type TEXT,
    outcome_type TEXT,
    last_yes_price NUMERIC(10, 6),
    last_no_price NUMERIC(10, 6),
    best_yes_bid NUMERIC(10, 6),
    best_yes_ask NUMERIC(10, 6),
    best_no_bid NUMERIC(10, 6),
    best_no_ask NUMERIC(10, 6),
    volume NUMERIC(28, 8),
    volume_24h NUMERIC(28, 8),
    liquidity NUMERIC(28, 8),
    open_interest NUMERIC(28, 8),
    price_source TEXT,
    start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    open_at TIMESTAMPTZ,
    close_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    settlement_value NUMERIC(10, 6),
    resolution_status TEXT,
    interval_code TEXT,
    interval_seconds INTEGER,
    image_url TEXT,
    icon_url TEXT,
    source_created_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    search_text TEXT GENERATED ALWAYS AS (
        coalesce(source_market_id, '') || ' ' ||
        coalesce(source_market_ticker, '') || ' ' ||
        coalesce(source_event_id, '') || ' ' ||
        coalesce(source_series_id, '') || ' ' ||
        coalesce(condition_id, '') || ' ' ||
        coalesce(slug, '') || ' ' ||
        coalesce(question, '') || ' ' ||
        coalesce(title, '') || ' ' ||
        coalesce(subtitle, '') || ' ' ||
        coalesce(description, '') || ' ' ||
        coalesce(rules_primary, '') || ' ' ||
        coalesce(category, '') || ' ' ||
        coalesce(subcategory, '') || ' ' ||
        coalesce(interval_code, '') || ' ' ||
        tiger_text_array_join(tags)
    ) STORED,
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(source_market_id, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(source_market_ticker, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(source_event_id, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(source_series_id, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(question, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(title, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(subtitle, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'C') ||
        setweight(to_tsvector('english', coalesce(rules_primary, '')), 'D') ||
        setweight(to_tsvector('simple', coalesce(category, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(interval_code, '')), 'A') ||
        setweight(to_tsvector('simple', tiger_text_array_join(tags)), 'B')
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exchange, source_market_id)
);

CREATE TABLE market_outcomes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id UUID NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_market_id TEXT NOT NULL,
    outcome_key TEXT NOT NULL,
    outcome_name TEXT NOT NULL,
    side TEXT,
    token_id TEXT,
    position INTEGER NOT NULL DEFAULT 0,
    last_price NUMERIC(10, 6),
    best_bid NUMERIC(10, 6),
    best_ask NUMERIC(10, 6),
    settlement_value NUMERIC(10, 6),
    source_created_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (market_id, outcome_key)
);

CREATE TABLE ingestion_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_name TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    endpoint TEXT,
    request_params JSONB NOT NULL DEFAULT '{}'::jsonb,
    cursor_value TEXT,
    last_source_updated_at TIMESTAMPTZ,
    last_discovered_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (status IN ('healthy', 'warning', 'failed', 'paused', 'unknown')),
    error_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exchange, source_name, entity_type)
);

CREATE TABLE ingestion_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_name TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('running', 'success', 'partial_success', 'failed')),
    cursor_before TEXT,
    cursor_after TEXT,
    fetched_count INTEGER NOT NULL DEFAULT 0,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    unchanged_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX series_exchange_status_idx ON series (exchange, status);
CREATE INDEX series_category_status_idx ON series (category, status);
CREATE INDEX series_interval_status_idx ON series (interval_code, status);
CREATE INDEX series_discovered_at_idx ON series (discovered_at DESC);
CREATE INDEX series_latest_event_at_idx ON series (latest_event_at DESC);
CREATE INDEX series_search_vector_idx ON series USING GIN (search_vector);
CREATE INDEX series_source_series_id_trgm_idx ON series USING GIN (source_series_id gin_trgm_ops);
CREATE INDEX series_title_trgm_idx ON series USING GIN (title gin_trgm_ops);
CREATE INDEX series_slug_trgm_idx ON series USING GIN (slug gin_trgm_ops);
CREATE INDEX series_tags_gin_idx ON series USING GIN (tags);
CREATE INDEX series_aliases_gin_idx ON series USING GIN (aliases);

CREATE INDEX events_exchange_status_idx ON events (exchange, status);
CREATE INDEX events_series_status_idx ON events (series_id, status);
CREATE INDEX events_source_series_status_idx ON events (exchange, source_series_id, status);
CREATE INDEX events_category_status_idx ON events (category, status);
CREATE INDEX events_discovered_at_idx ON events (discovered_at DESC);
CREATE INDEX events_end_at_idx ON events (end_at DESC);
CREATE INDEX events_total_volume_idx ON events (total_volume DESC);
CREATE INDEX events_total_liquidity_idx ON events (total_liquidity DESC);
CREATE INDEX events_search_vector_idx ON events USING GIN (search_vector);
CREATE INDEX events_source_event_id_trgm_idx ON events USING GIN (source_event_id gin_trgm_ops);
CREATE INDEX events_title_trgm_idx ON events USING GIN (title gin_trgm_ops);
CREATE INDEX events_slug_trgm_idx ON events USING GIN (slug gin_trgm_ops);
CREATE INDEX events_market_questions_gin_idx ON events USING GIN (market_questions);

CREATE INDEX markets_event_id_idx ON markets (event_id);
CREATE INDEX markets_series_status_idx ON markets (series_id, status);
CREATE INDEX markets_source_series_status_idx ON markets (exchange, source_series_id, status);
CREATE INDEX markets_source_event_idx ON markets (exchange, source_event_id);
CREATE INDEX markets_exchange_status_idx ON markets (exchange, status);
CREATE INDEX markets_category_status_idx ON markets (category, status);
CREATE INDEX markets_interval_status_idx ON markets (interval_code, status);
CREATE INDEX markets_discovered_at_idx ON markets (discovered_at DESC);
CREATE INDEX markets_close_at_idx ON markets (close_at DESC);
CREATE INDEX markets_settled_at_idx ON markets (settled_at DESC);
CREATE INDEX markets_volume_idx ON markets (volume DESC);
CREATE INDEX markets_liquidity_idx ON markets (liquidity DESC);
CREATE INDEX markets_search_vector_idx ON markets USING GIN (search_vector);
CREATE INDEX markets_source_market_id_trgm_idx ON markets USING GIN (source_market_id gin_trgm_ops);
CREATE INDEX markets_question_trgm_idx ON markets USING GIN (question gin_trgm_ops);
CREATE INDEX markets_slug_trgm_idx ON markets USING GIN (slug gin_trgm_ops);

CREATE INDEX market_outcomes_market_id_idx ON market_outcomes (market_id);
CREATE INDEX market_outcomes_exchange_source_market_idx ON market_outcomes (exchange, source_market_id);
CREATE INDEX market_outcomes_token_id_idx ON market_outcomes (token_id);
CREATE INDEX market_outcomes_name_trgm_idx ON market_outcomes USING GIN (outcome_name gin_trgm_ops);

CREATE INDEX ingestion_state_status_idx ON ingestion_state (status);
CREATE INDEX ingestion_state_last_success_idx ON ingestion_state (last_success_at DESC);
CREATE INDEX ingestion_runs_exchange_source_started_idx
    ON ingestion_runs (exchange, source_name, started_at DESC);
CREATE INDEX ingestion_runs_status_started_idx ON ingestion_runs (status, started_at DESC);
