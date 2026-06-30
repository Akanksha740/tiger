-- Kalshi markets use rules_primary/rules_secondary instead of description.
UPDATE markets
SET description = trim(both FROM concat_ws(
        E'\n\n',
        nullif(btrim(rules_primary), ''),
        nullif(btrim(rules_secondary), '')
    ))
WHERE exchange = 'kalshi'
  AND (
        coalesce(btrim(rules_primary), '') <> ''
        OR coalesce(btrim(rules_secondary), '') <> ''
      );

ALTER TABLE markets
    DROP COLUMN IF EXISTS rules_primary,
    DROP COLUMN IF EXISTS rules_secondary;
