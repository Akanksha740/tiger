CREATE TABLE market_orderbook_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id UUID NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
    exchange TEXT NOT NULL REFERENCES exchanges(code),
    source_market_id TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome_count INTEGER NOT NULL DEFAULT 0,
    book_count INTEGER NOT NULL DEFAULT 0,
    best_yes_bid NUMERIC(10, 6),
    best_yes_ask NUMERIC(10, 6),
    last_yes_price NUMERIC(10, 6),
    best_no_bid NUMERIC(10, 6),
    best_no_ask NUMERIC(10, 6),
    last_no_price NUMERIC(10, 6),
    volume NUMERIC(28, 8),
    liquidity NUMERIC(28, 8),
    orderbooks JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX market_orderbook_snapshots_market_time_idx
    ON market_orderbook_snapshots (market_id, captured_at DESC);

CREATE INDEX market_orderbook_snapshots_exchange_source_time_idx
    ON market_orderbook_snapshots (exchange, source_market_id, captured_at DESC);

CREATE INDEX market_orderbook_snapshots_captured_at_idx
    ON market_orderbook_snapshots (captured_at DESC);

CREATE INDEX market_orderbook_snapshots_orderbooks_gin_idx
    ON market_orderbook_snapshots USING GIN (orderbooks);
