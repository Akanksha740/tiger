DROP INDEX IF EXISTS series_search_vector_idx;
DROP INDEX IF EXISTS events_search_vector_idx;
DROP INDEX IF EXISTS markets_search_vector_idx;

ALTER TABLE series
    DROP COLUMN IF EXISTS search_text,
    DROP COLUMN IF EXISTS search_vector,
    DROP COLUMN IF EXISTS raw_payload;

ALTER TABLE events
    DROP COLUMN IF EXISTS search_text,
    DROP COLUMN IF EXISTS search_vector,
    DROP COLUMN IF EXISTS raw_payload;

ALTER TABLE markets
    DROP COLUMN IF EXISTS search_text,
    DROP COLUMN IF EXISTS search_vector,
    DROP COLUMN IF EXISTS raw_payload;

ALTER TABLE market_outcomes
    DROP COLUMN IF EXISTS raw_payload;

DROP FUNCTION IF EXISTS tiger_text_array_join(TEXT[]);
