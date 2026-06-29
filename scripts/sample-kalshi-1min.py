#!/usr/bin/env python3
"""Sample Kalshi catalog API traffic for 60s (same endpoints as tiger ingest)."""
from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
except ImportError:
    print("Install cryptography: pip install cryptography", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
API_PREFIX = "/trade-api/v2"
DURATION_SEC = 60
EVENT_PAGE_LIMIT = 50


def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if not path.is_file():
        return env
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        env[key.strip()] = value.strip()
    return env


def base_url(env_name: str) -> str:
    env = (env_name or "demo").lower()
    if env in ("prod", "production"):
        return "https://api.elections.kalshi.com"
    return "https://demo-api.kalshi.co"


def sign_request(key_id: str, private_key, method: str, signed_path: str) -> dict[str, str]:
    timestamp_ms = str(int(time.time() * 1000))
    message = timestamp_ms + method.upper() + signed_path
    signature = private_key.sign(
        message.encode("utf-8"),
        padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32),
        hashes.SHA256(),
    )
    return {
        "KALSHI-ACCESS-KEY": key_id,
        "KALSHI-ACCESS-TIMESTAMP": timestamp_ms,
        "KALSHI-ACCESS-SIGNATURE": base64.b64encode(signature).decode("ascii"),
        "Accept": "application/json",
    }


def load_private_key(path: Path):
    data = path.read_bytes()
    return serialization.load_pem_private_key(data, password=None)


def kalshi_get(base: str, signed_path: str, query: dict, key_id: str, private_key) -> tuple[bytes, dict]:
    qs = urllib.parse.urlencode(query)
    url = f"{base}{signed_path}" + (f"?{qs}" if qs else "")
    headers = sign_request(key_id, private_key, "GET", signed_path)
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = resp.read()
    return body, json.loads(body)


def json_len(node) -> int:
    return len(json.dumps(node, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def main() -> None:
    env = load_env(REPO_ROOT / ".env")
    key_id = env.get("KALSHI_KEY_ID", os.environ.get("KALSHI_KEY_ID", ""))
    key_path = env.get("KALSHI_PRIVATE_KEY_PATH", "secrets/kalshi_private.key")
    kalshi_env = env.get("KALSHI_ENV", "prod")
    if not key_id:
        print("Missing KALSHI_KEY_ID in .env", file=sys.stderr)
        sys.exit(1)
    pk_path = Path(key_path)
    if not pk_path.is_absolute():
        pk_path = REPO_ROOT / pk_path
    private_key = load_private_key(pk_path)
    base = base_url(kalshi_env)

    stats = {
        "api_response_bytes": 0,
        "event_pages": 0,
        "events": 0,
        "markets": 0,
        "outcomes": 0,
        "event_json_bytes": 0,
        "market_json_bytes": 0,
        "series": 0,
        "series_json_bytes": 0,
    }

    # One /series call (catalog optional path)
    signed = API_PREFIX + "/series"
    body, payload = kalshi_get(
        base,
        signed,
        {"include_product_metadata": "true", "include_volume": "true"},
        key_id,
        private_key,
    )
    stats["api_response_bytes"] += len(body)
    series_list = payload.get("series") or []
    stats["series"] = len(series_list)
    for s in series_list:
        stats["series_json_bytes"] += json_len(s)

    print(f"Sampling /events (nested markets) for {DURATION_SEC}s against {base} ...")
    deadline = time.monotonic() + DURATION_SEC
    cursor: str | None = None
    while time.monotonic() < deadline:
        query: dict[str, str | int] = {
            "limit": EVENT_PAGE_LIMIT,
            "with_nested_markets": "true",
        }
        if cursor:
            query["cursor"] = cursor
        signed = API_PREFIX + "/events"
        body, payload = kalshi_get(base, signed, query, key_id, private_key)
        stats["api_response_bytes"] += len(body)
        stats["event_pages"] += 1
        events = payload.get("events") or []
        for event in events:
            stats["events"] += 1
            stats["event_json_bytes"] += json_len(event)
            markets = event.get("markets") or []
            for market in markets:
                stats["markets"] += 1
                stats["market_json_bytes"] += json_len(market)
                stats["outcomes"] += 2  # tiger stores YES + NO per market
        cursor = payload.get("cursor")
        if not cursor:
            print("Reached end of events catalog during sample window.")
            break

    elapsed = DURATION_SEC if time.monotonic() >= deadline else DURATION_SEC - (deadline - time.monotonic())
    elapsed = min(DURATION_SEC, max(elapsed, 1))

    def per_min(value: int | float) -> float:
        return value * 60.0 / elapsed

    # Postgres heap estimate from normalized columns (no raw_payload / search columns).
    HEAP_OVERHEAD = {
        "series": 1.4,
        "events": 1.6,
        "markets": 1.8,
        "outcomes": 1.3,
    }
    INDEX_OVERHEAD = {
        "series": 1.5,
        "events": 1.7,
        "markets": 1.9,
        "outcomes": 1.4,
    }
    OUTCOME_JSON_BYTES = 120  # small outcome row overhead

    def stored_bytes(json_bytes: int, count: int, entity: str) -> int:
        if count == 0:
            return 0
        avg_json = json_bytes / count
        heap = count * avg_json * HEAP_OVERHEAD[entity]
        return int(heap * INDEX_OVERHEAD[entity])

    series_store = stored_bytes(stats["series_json_bytes"], stats["series"], "series")
    events_store = stored_bytes(stats["event_json_bytes"], stats["events"], "events")
    markets_store = stored_bytes(stats["market_json_bytes"], stats["markets"], "markets")
    outcomes_store = int(
        stats["outcomes"] * OUTCOME_JSON_BYTES * HEAP_OVERHEAD["outcomes"] * INDEX_OVERHEAD["outcomes"]
    )
    sample_store = series_store + events_store + markets_store + outcomes_store

    mins_per_month = 30 * 24 * 60
    sample_store_per_min = sample_store * 60.0 / elapsed
    api_bytes_per_min = stats["api_response_bytes"] * 60.0 / elapsed

    events_per_min = per_min(stats["events"])
    markets_per_min = per_min(stats["markets"])
    outcomes_per_min = per_min(stats["outcomes"])

    # If sample hit end of catalog, scale to full catalog in one snapshot
    full_catalog_note = ""
    if not cursor and stats["events"] > 0:
        full_snapshot = sample_store
        full_catalog_note = "full_catalog_in_sample"
    else:
        # Did not finish catalog in 60s — extrapolate snapshot size from paging rate
        # Assume similar density for remaining pages (conservative)
        full_snapshot = sample_store_per_min * (stats["events"] / max(events_per_min, 1)) * (
            events_per_min / max(stats["events"], 1)
        )
        # Better: if we have pages/min and events/page, estimate total events from typical Kalshi catalog
        # Use events fetched rate to project: snapshot = sample_store scaled by (catalog completion time)
        pages_per_min = per_min(stats["event_pages"])
        if pages_per_min > 0 and stats["events"] > 0:
            events_per_page = stats["events"] / stats["event_pages"]
            # Extrapolate: minutes to crawl entire catalog ~= until cursor null; unknown total pages
            # Use markets/min as primary storage driver for 30-day NEW market creation
            pass
        full_snapshot = None

    report = {
        "elapsed_sec": round(elapsed, 1),
        "kalshi_env": kalshi_env,
        "api_base": base,
        "cursor_exhausted_in_window": not cursor,
        "counts": {
            "series": stats["series"],
            "event_pages": stats["event_pages"],
            "events": stats["events"],
            "markets": stats["markets"],
            "outcomes": stats["outcomes"],
        },
        "per_minute": {
            "api_response_bytes": int(api_bytes_per_min),
            "events": round(events_per_min, 1),
            "markets": round(markets_per_min, 1),
            "outcomes": round(outcomes_per_min, 1),
            "event_pages": round(per_min(stats["event_pages"]), 1),
        },
        "avg_json_bytes": {
            "series": round(stats["series_json_bytes"] / max(stats["series"], 1)),
            "event": round(stats["event_json_bytes"] / max(stats["events"], 1)),
            "market": round(stats["market_json_bytes"] / max(stats["markets"], 1)),
        },
        "estimated_pg_store_bytes_sample_window": sample_store,
        "estimated_pg_store_per_minute": int(sample_store_per_min),
        "monthly_scenarios": {},
    }

    # Scenario A: continuous ingest at sampled rate for 30 days (upper bound / streaming)
    report["monthly_scenarios"]["continuous_at_sample_rate"] = int(sample_store_per_min * mins_per_month)

    # Scenario B: one full catalog snapshot
    if not cursor:
        report["monthly_scenarios"]["one_full_catalog_snapshot"] = sample_store
        report["monthly_scenarios"]["one_full_catalog_snapshot_pretty"] = _pretty(sample_store)
    else:
        backfill_minutes_options = [10, 20, 30, 45, 60, 90, 120]
        report["monthly_scenarios"]["one_full_catalog_by_backfill_duration"] = {
            f"{mins}_min": {
                "bytes": int(sample_store_per_min * mins),
                "pretty": _pretty(int(sample_store_per_min * mins)),
            }
            for mins in backfill_minutes_options
        }
        report["extrapolation_note"] = (
            "Catalog did not finish in 60s. Multiply estimated_pg_store_per_minute "
            "by observed or expected full backfill duration (minutes)."
        )

    # Scenario C: daily full re-ingest for 30 days
    if not cursor:
        base_snap = sample_store
    else:
        base_snap = int(sample_store_per_min * 30)  # assume ~30 min full backfill as mid estimate
    # Updates: ~15% JSON churn per day on same rows (metadata refresh)
    daily_update_bytes = int(base_snap * 0.15)
    report["monthly_scenarios"]["daily_full_reingest_30_days"] = int(
        base_snap + daily_update_bytes * 29
    )
    report["monthly_scenarios"]["daily_full_reingest_30_days_pretty"] = _pretty(
        report["monthly_scenarios"]["daily_full_reingest_30_days"]
    )

    # Scenario D: new market creation only (interval contracts) — markets/min at sample rate * month
    # Only meaningful if sample reflects NEW rows; during catalog crawl this is total fetch rate
    # Use open markets endpoint rate separately — skip, use fraction of markets/min as new
    new_markets_per_min = markets_per_min * 0.05  # assume 5% of crawl are net-new rows on re-run
    new_market_store_per_min = sample_store_per_min * 0.05 if stats["markets"] else 0
    report["monthly_scenarios"]["net_new_markets_5pct_of_crawl_rate"] = int(
        new_market_store_per_min * mins_per_month
    )
    report["monthly_scenarios"]["net_new_markets_5pct_pretty"] = _pretty(
        report["monthly_scenarios"]["net_new_markets_5pct_of_crawl_rate"]
    )

    report["overhead_factors_used"] = {
        "heap_overhead": HEAP_OVERHEAD,
        "index_overhead": INDEX_OVERHEAD,
        "outcome_json_bytes": OUTCOME_JSON_BYTES,
        "note": "Derived from sampled JSON + schema overhead factors, not from existing DB size",
    }

    print(json.dumps(report, indent=2))


def _pretty(n: int) -> str:
    if n >= 1024 ** 3:
        return f"{n / 1024**3:.2f} GB"
    if n >= 1024 ** 2:
        return f"{n / 1024**2:.1f} MB"
    return f"{n / 1024:.1f} KB"


if __name__ == "__main__":
    main()
