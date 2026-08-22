#!/usr/bin/env python3
"""Headless desktop test of bridge.py against a copy of the real database.

Usage: python scripts/test_bridge_headless.py
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "app" / "src" / "main" / "python"))

SOURCE_DB = Path.home() / ".local/share/albion-crafter/albion-crafter.db"


class Sink:
    def __init__(self) -> None:
        self.events: list[str] = []

    def onEvent(self, event: str) -> None:
        self.events.append(event)
        try:
            payload = json.loads(event)
        except Exception:
            payload = {"raw": event}
        kind = payload.get("kind", "?")
        message = payload.get("message", "")
        fraction = payload.get("fraction")
        suffix = f" {fraction:.0%}" if isinstance(fraction, float) else ""
        print(f"  [progress:{kind}] {message}{suffix}")


def check(name: str, raw: str, expect_ok: bool = True) -> dict:
    payload = json.loads(raw)
    status = "OK " if payload.get("ok") is expect_ok else "FAIL"
    print(f"{status} {name}")
    if payload.get("ok") is not expect_ok:
        print(f"     payload: {json.dumps(payload)[:600]}")
        raise SystemExit(f"{name} failed")
    return payload


def main() -> int:
    workdir = Path(tempfile.mkdtemp(prefix="ac-bridge-test-"))
    if SOURCE_DB.exists():
        shutil.copy2(SOURCE_DB, workdir / "albion-crafter.db")
        with sqlite3.connect(workdir / "albion-crafter.db") as connection:
            connection.execute("DELETE FROM station_fees")
            connection.execute(
                "DELETE FROM settings WHERE key IN (?, ?)",
                (
                    "android_station_fee_seed_version",
                    "allow_stale_station_fees",
                ),
            )
            # A newer Android edit must win over the packaged desktop seed.
            connection.execute(
                """INSERT INTO station_fees (
                       region, city, station_type, displayed_fee, observed_at, provenance
                   ) VALUES (?, ?, ?, ?, ?, ?)""",
                (
                    "americas",
                    "Bridgewatch",
                    "toolmaker",
                    999.0,
                    datetime.now(UTC).isoformat(),
                    "user_override",
                ),
            )
        print(f"Using copy of real DB in {workdir}")
    else:
        print(f"WARNING: no real DB at {SOURCE_DB}; testing with empty database")

    import bridge  # noqa: E402

    check("startup", bridge.startup(str(workdir)))

    status = check("get_status", bridge.get_status())
    print(f"     catalog: {status['catalog']['item_count']} items, "
          f"{status['catalog']['recipe_count']} recipes")

    settings = check("list_settings", bridge.list_settings())
    print(f"     settings: {json.dumps(settings['settings'])}")

    search = check("catalog_search", bridge.catalog_search("bag", 10))
    print(f"     matches: {len(search['results'])}")
    first_item = search["results"][0]["item_id"] if search["results"] else None
    print(f"     first: {first_item}")

    coverage = check("market_coverage", bridge.market_coverage())
    print(f"     expected={coverage['expected_rows']} rows={coverage['market_rows']} "
          f"2h={coverage['observed_within_2h']} 24h={coverage['observed_within_24h']}")

    fees = check("station_fees_list", bridge.station_fees_list("americas"))
    print(f"     fees: {len(fees['fees'])}")
    assert len(fees["fees"]) == 11
    by_key = {
        (fee["city"], fee["station_type"]): fee
        for fee in fees["fees"]
    }
    assert by_key[("Bridgewatch", "alchemist_lab")]["displayed_fee"] == 930.0
    assert by_key[("Bridgewatch", "alchemist_lab")]["observed_at"] == (
        "2026-08-21T22:01:01.719161+00:00"
    )
    assert by_key[("Fort Sterling", "hunter_lodge")]["displayed_fee"] == 800.0
    assert by_key[("Bridgewatch", "toolmaker")]["displayed_fee"] == 999.0
    assert settings["settings"]["allow_stale_station_fees"] is True

    from albion_crafter.core.stations import StationType

    alchemist_observation = bridge._STATE["station_fee_repository"].get(
        "americas",
        "Bridgewatch",
        StationType.ALCHEMIST_LAB,
    )
    stale_evidence = bridge._station_evidence(
        "americas",
        None,
        alchemist_observation,
        max_age_hours=1,
        allow_stale=True,
    )
    assert stale_evidence["freshness"].lower() == "stale"
    assert stale_evidence["usable"] is True

    removed = check(
        "station_fee_remove",
        bridge.station_fee_remove(
            json.dumps(
                {
                    "region": "americas",
                    "city": "Bridgewatch",
                    "station_type": "alchemist_lab",
                }
            )
        ),
    )
    assert removed["removed"] is True
    assert bridge._seed_android_station_fees(
        bridge._STATE["station_fee_repository"],
        bridge._STATE["settings_repository"],
    ) == 0
    after_remove = json.loads(bridge.station_fees_list("americas"))
    assert len(after_remove["fees"]) == 10
    check(
        "station_fee_restore",
        bridge.station_fee_set(
            json.dumps(
                {
                    "region": "americas",
                    "city": "Bridgewatch",
                    "station_type": "alchemist_lab",
                    "displayed_fee": 930.0,
                    "observed_at": "2026-08-21T22:01:01.719161+00:00",
                }
            )
        ),
    )

    profile = check("crafting_profile_get", bridge.crafting_profile_get())
    print(f"     focus: {profile['available_focus']} skills: {len(profile['skill_levels'])}")

    matrix = check("refining_matrix_get", bridge.refining_matrix_get())
    filled = {k: v for k, v in matrix["levels"].items() if v is not None}
    print(f"     matrix filled: {len(filled)} complete: {matrix['complete_families']}")

    snapshots = check("planner_recent_snapshots", bridge.planner_recent_snapshots())
    print(f"     snapshots: {len(snapshots['snapshots'])}")

    constraints = {
        "available_silver": 1_000_000,
        "available_focus": 10_000,
        "region": "americas",
        "material_cities": ["Bridgewatch"],
        "craft_cities": ["Bridgewatch"],
        "sell_cities": ["Bridgewatch"],
        "tiers": [4, 5, 6],
        "action_kinds": ["craft", "refine"],
    }
    pre = check("planner_preflight", bridge.planner_preflight(json.dumps(constraints)))
    print(f"     eligible routes: {pre['eligible_routes']}")
    print(f"     rejections: {json.dumps(pre['rejection_counts'])[:300]}")

    if first_item:
        calc_request = {
            "item_id": first_item,
            "region": "americas",
            "material_city": "Bridgewatch",
            "craft_city": "Bridgewatch",
            "sell_city": "Bridgewatch",
            "crafts": 10,
            "quality": 1,
            "premium": True,
            "use_focus": False,
            "sale_method": "sell_order",
        }
        calc = check("calculator_evaluate", bridge.calculator_evaluate(json.dumps(calc_request)))
        result = calc["result"]
        print(f"     profit={result.get('profit')} roi={result.get('roi')} "
              f"warnings={result.get('warnings')}")
        print(f"     station evidence: {json.dumps(calc.get('station_fee_evidence'))[:200]}")
        print(f"     fce evidence: {json.dumps(calc.get('fce_evidence'))}")

    # Scan (offline, cache-only)
    scan_constraints = {
        "region": "americas",
        "craft_cities": ["Bridgewatch"],
        "sell_cities": ["Bridgewatch"],
        "text": "",
        "tier_min": 4,
        "tier_max": 6,
        "premium": True,
        "actionable_only": False,
    }
    scan = check("scanner_run", bridge.scanner_run("test-scan", json.dumps(scan_constraints), Sink()))
    print(f"     opportunities: {scan['count']}")

    print("\nALL BRIDGE CHECKS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
