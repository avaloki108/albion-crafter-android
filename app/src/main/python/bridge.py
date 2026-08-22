"""Android bridge facade for the Albion Crafter engine.

All functions are JSON-in / JSON-out and synchronous. The Kotlin side calls
them from a single background thread. Long-running operations take an
``op_id`` used for cancellation and push progress events by calling the
Java ``sink`` object (which must expose ``onEvent(String)``).

This module deliberately mirrors the composition and flows of the desktop
PySide6 UI (``main.py`` + ``ui/``) so both front-ends stay behaviorally
aligned. It never touches Qt.
"""

from __future__ import annotations

import json
import os
import threading
from dataclasses import fields as dataclass_fields
from datetime import UTC, datetime, timedelta

_STATE: dict[str, object] = {}
_INIT_LOCK = threading.Lock()
_CANCEL_EVENTS: dict[str, threading.Event] = {}
_CANCEL_LOCK = threading.Lock()

ANDROID_STATION_FEE_SEED_VERSION = 1
ANDROID_STATION_FEE_SEED_VERSION_KEY = "android_station_fee_seed_version"
ALLOW_STALE_STATION_FEES_KEY = "allow_stale_station_fees"

# User-entered station fees copied from the desktop database on 2026-08-21.
# Original observation timestamps are preserved so relaunching never makes old
# evidence look newly observed.
PACKAGED_STATION_FEE_SEED = (
    (
        "americas",
        "Bridgewatch",
        "alchemist_lab",
        930.0,
        "2026-08-21T22:01:01.719161+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "cook",
        480.0,
        "2026-08-21T22:01:03.665300+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "hunter_lodge",
        800.0,
        "2026-08-21T22:01:06.331360+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "mage_tower",
        810.0,
        "2026-08-21T22:01:08.863349+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "mill",
        0.0,
        "2026-08-21T22:01:10.584292+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "stonemason",
        400.0,
        "2026-08-21T22:01:14.026363+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "tanner",
        800.0,
        "2026-08-21T22:01:15.681453+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "toolmaker",
        830.0,
        "2026-08-21T22:01:17.695106+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "warrior_forge",
        820.0,
        "2026-08-21T22:01:19.426162+00:00",
    ),
    (
        "americas",
        "Bridgewatch",
        "weaver",
        830.0,
        "2026-08-21T22:01:22.869249+00:00",
    ),
    (
        "americas",
        "Fort Sterling",
        "hunter_lodge",
        800.0,
        "2026-08-21T22:01:25.074240+00:00",
    ),
)


class BridgeError(RuntimeError):
    pass


def _fail(message: str) -> None:
    raise BridgeError(message)


# --------------------------------------------------------------------------
# Lifecycle
# --------------------------------------------------------------------------


def startup(data_dir: str) -> str:
    """Set the engine data directory and initialize the database stack."""
    with _INIT_LOCK:
        os.environ["ALBION_CRAFTER_DATA_DIR"] = data_dir
        _ensure_stack()
    return json.dumps({"ok": True, "data_dir": data_dir})


def _ensure_stack() -> None:
    if _STATE.get("database") is not None:
        return

    from albion_crafter.database.catalog import CatalogRepository
    from albion_crafter.database.database import (
        Database,
        MarketPriceRepository,
        PriceOverrideRepository,
        SettingsRepository,
        default_database_path,
    )
    from albion_crafter.database.v3 import (
        CraftingProfileRepository,
        MarketHistoryRepository,
        StationFeeRepository,
    )
    from albion_crafter.database.v4 import (
        FindMoneyPreferencesRepository,
        PlanSnapshotRepository,
    )
    from albion_crafter.market.models import Region
    from albion_crafter.market.pricing import PriceResolver
    from albion_crafter.market.recipe_refresh import RecipePriceRefreshService
    from albion_crafter.opportunity.service import OpportunityScannerService

    database = Database(default_database_path())
    database.initialize()
    market_repository = MarketPriceRepository(database)
    override_repository = PriceOverrideRepository(database)
    catalog_repository = CatalogRepository(database)
    settings_repository = SettingsRepository(database)
    station_fee_repository = StationFeeRepository(database)
    crafting_profile_repository = CraftingProfileRepository(database)
    history_repository = MarketHistoryRepository(database)
    snapshot_repository = PlanSnapshotRepository(database)
    preferences_repository = FindMoneyPreferencesRepository(settings_repository)

    seeded_station_fee_count = _seed_android_station_fees(
        station_fee_repository,
        settings_repository,
    )

    resolver = PriceResolver(
        market_repository, override_repository, history_repository
    )
    refresh_service = RecipePriceRefreshService(
        market_repository,
        history_repository=history_repository,
    )
    scanner_service = OpportunityScannerService(
        catalog_repository,
        market_repository,
        override_repository,
        station_fee_repository,
        crafting_profile_repository,
        history_repository,
    )

    _STATE.update(
        {
            "database": database,
            "market_repository": market_repository,
            "override_repository": override_repository,
            "catalog_repository": catalog_repository,
            "settings_repository": settings_repository,
            "station_fee_repository": station_fee_repository,
            "crafting_profile_repository": crafting_profile_repository,
            "history_repository": history_repository,
            "snapshot_repository": snapshot_repository,
            "preferences_repository": preferences_repository,
            "seeded_station_fee_count": seeded_station_fee_count,
            "resolver": resolver,
            "refresh_service": refresh_service,
            "scanner_service": scanner_service,
        }
    )

    services: dict = {}

    def service_for_region(region) -> object:
        service = services.get(region)
        if service is None:
            service = _build_find_money_service(region)
            services[region] = service
        return service

    _STATE["service_factory"] = service_for_region
    _set_region(Region(str(settings_repository.get("region", Region.AMERICAS.value))))


def _seed_android_station_fees(station_fees, settings) -> int:
    """Seed the user's desktop snapshot once without replacing Android edits."""
    try:
        installed_version = int(
            settings.get(ANDROID_STATION_FEE_SEED_VERSION_KEY, 0)
        )
    except (TypeError, ValueError):
        installed_version = 0
    if installed_version >= ANDROID_STATION_FEE_SEED_VERSION:
        return 0

    from albion_crafter.core.provenance import Provenance
    from albion_crafter.core.stations import StationFeeObservation, StationType

    inserted = 0
    for (
        region,
        city,
        station_value,
        displayed_fee,
        observed_at_text,
    ) in PACKAGED_STATION_FEE_SEED:
        station_type = StationType(station_value)
        if station_fees.get(region, city, station_type) is not None:
            continue
        station_fees.set(
            StationFeeObservation(
                region=region,
                city=city,
                station_type=station_type,
                displayed_fee=displayed_fee,
                observed_at=datetime.fromisoformat(observed_at_text),
                provenance=Provenance.USER_OVERRIDE,
            )
        )
        inserted += 1

    setting_updates = {
        ANDROID_STATION_FEE_SEED_VERSION_KEY: ANDROID_STATION_FEE_SEED_VERSION,
    }
    if settings.get(ALLOW_STALE_STATION_FEES_KEY, None) is None:
        setting_updates[ALLOW_STALE_STATION_FEES_KEY] = True
    settings.set_many(setting_updates)
    return inserted


def _set_region(region) -> None:
    factory = _STATE["service_factory"]
    _STATE["region"] = region
    _STATE["service"] = factory(region)


def _build_find_money_service(region):
    from albion_crafter.market.backfill import MissingSellHistoryBackfillService
    from albion_crafter.market.history import AODPHistoryClient
    from albion_crafter.market.history_cache import CachedOutputHistoryService
    from albion_crafter.planning.current_refresh import CurrentMarketRefreshExecutor
    from albion_crafter.planning.preflight import FindMoneyPreflightPlanner
    from albion_crafter.planning.service import FindMoneyService

    return FindMoneyService(
        FindMoneyPreflightPlanner(
            _STATE["catalog_repository"],
            _STATE["market_repository"],
            _STATE["override_repository"],
            _STATE["station_fee_repository"],
            _STATE["crafting_profile_repository"],
            _STATE["history_repository"],
        ),
        _STATE["market_repository"],
        _STATE["override_repository"],
        _STATE["crafting_profile_repository"],
        _STATE["history_repository"],
        snapshots=_STATE["snapshot_repository"],
        current_refresh=CurrentMarketRefreshExecutor(
            _STATE["market_repository"],
            history_backfill=MissingSellHistoryBackfillService(
                _STATE["market_repository"],
                _STATE["history_repository"],
            ),
        ),
        history_refresh=CachedOutputHistoryService(
            AODPHistoryClient(region),
            _STATE["history_repository"],
        ),
    )


def _switch_region(region_value: str) -> None:
    from albion_crafter.market.models import Region

    region = Region(str(region_value))
    if _STATE.get("region") != region:
        _set_region(region)
        _STATE["settings_repository"].set_many({"region": region.value})


# --------------------------------------------------------------------------
# Cancellation plumbing
# --------------------------------------------------------------------------


def _cancel_event(op_id: str) -> threading.Event:
    with _CANCEL_LOCK:
        event = _CANCEL_EVENTS.get(op_id)
        if event is None:
            event = threading.Event()
            _CANCEL_EVENTS[op_id] = event
        return event


def _finish_op(op_id: str) -> None:
    with _CANCEL_LOCK:
        _CANCEL_EVENTS.pop(op_id, None)


def cancel(op_id: str) -> str:
    with _CANCEL_LOCK:
        event = _CANCEL_EVENTS.get(op_id)
    if event is not None:
        event.set()
    return json.dumps({"ok": True})


def _cancellation_check(op_id: str):
    event = _cancel_event(op_id)

    def check() -> bool:
        return event.is_set()

    return check


def _make_progress_forwarder(op_id: str, sink, kind: str):
    """Wrap a Java sink object into a Python progress callback."""

    def forward(progress) -> None:
        try:
            payload = _serialize_progress(progress)
        except Exception:
            payload = {"kind": kind, "op_id": op_id, "message": str(progress)}
        payload["kind"] = kind
        payload["op_id"] = op_id
        try:
            sink.onEvent(json.dumps(payload))
        except Exception:
            pass

    return forward


def _serialize_progress(progress) -> dict:
    payload: dict = {"message": getattr(progress, "message", "")}
    stage = getattr(progress, "stage", None)
    if stage is not None:
        payload["stage"] = str(getattr(stage, "value", stage))
    completed = getattr(progress, "completed", None)
    if completed is not None:
        payload["completed"] = completed
    total = getattr(progress, "total", None)
    if total is not None:
        payload["total"] = total
    for name in (
        "planned_batches",
        "completed_batches",
        "successful_batches",
        "failed_batches",
        "rows_received",
        "sides_updated",
        "phase",
    ):
        value = getattr(progress, name, None)
        if value is not None and not isinstance(value, object.__class__):
            payload[name] = value if isinstance(value, (int, float, str, bool)) else str(value)
    batch = getattr(progress, "current_batch", None)
    if batch is not None:
        payload["current_batch"] = str(batch)
    fraction = getattr(progress, "fraction", None)
    if callable(fraction):
        try:
            value = fraction()
            if value is None:
                value = fraction
        except Exception:
            value = None
        if isinstance(value, (int, float)):
            payload["fraction"] = float(value)
    return payload


# --------------------------------------------------------------------------
# Status / settings
# --------------------------------------------------------------------------


def get_status() -> str:
    _ensure_stack()
    catalog = _STATE["catalog_repository"]
    import_report = catalog.latest_import_report()
    settings = _STATE["settings_repository"]

    item_count = 0
    recipe_count = 0
    source_version = None
    finished_at = None
    if import_report is not None:
        item_count = getattr(import_report, "item_count", 0) or 0
        recipe_count = getattr(import_report, "recipe_count", 0) or 0
        source_version = getattr(import_report, "source_version", None)
        finished_at = getattr(import_report, "finished_at", None)
    if not item_count:
        try:
            item_count = catalog.item_count()
        except Exception:
            item_count = 0

    region = str(settings.get("region", "americas"))
    return json.dumps(
        {
            "ok": True,
            "catalog": {
                "item_count": item_count,
                "recipe_count": recipe_count,
                "source_version": source_version,
                "finished_at": finished_at.isoformat() if finished_at else None,
            },
            "region": region,
            "version": _engine_version(),
        }
    )


def _engine_version() -> str:
    from albion_crafter import __version__

    return str(__version__)


DEFAULT_SETTINGS_KEYS = (
    "region",
    "premium",
    "available_focus",
    "focus_enabled",
    "default_material_buy_city",
    "default_craft_city",
    "default_sell_city",
    "max_market_age_hours",
    "max_station_fee_age_hours",
    ALLOW_STALE_STATION_FEES_KEY,
)


SETTINGS_DEFAULTS = {
    "region": "americas",
    "premium": True,
    "available_focus": 10000,
    "focus_enabled": False,
    "default_material_buy_city": "Bridgewatch",
    "default_craft_city": "Bridgewatch",
    "default_sell_city": "Bridgewatch",
    "max_market_age_hours": 4,
    "max_station_fee_age_hours": 24,
    ALLOW_STALE_STATION_FEES_KEY: True,
}


def list_settings() -> str:
    _ensure_stack()
    settings = _STATE["settings_repository"]
    data = {
        key: settings.get(key, SETTINGS_DEFAULTS[key])
        for key in DEFAULT_SETTINGS_KEYS
    }
    data["premium"] = bool(data.get("premium", True))
    data["focus_enabled"] = bool(data.get("focus_enabled", False))
    data[ALLOW_STALE_STATION_FEES_KEY] = bool(
        data.get(ALLOW_STALE_STATION_FEES_KEY, True)
    )
    return json.dumps({"ok": True, "settings": data})


def save_settings(request_json: str) -> str:
    _ensure_stack()
    settings = _STATE["settings_repository"]
    request = json.loads(request_json)
    payload = {k: v for k, v in request.items() if k in DEFAULT_SETTINGS_KEYS}
    if "region" in payload and str(payload["region"]) != str(
        settings.get("region", "americas")
    ):
        _switch_region(str(payload["region"]))
        payload.pop("region", None)
    if payload:
        settings.set_many(payload)
    return json.dumps({"ok": True})


# --------------------------------------------------------------------------
# Static game data
# --------------------------------------------------------------------------


def update_static_data(op_id: str, force: bool, sink) -> str:
    _ensure_stack()
    import traceback

    from albion_crafter.data.static_importer import (
        StaticDataClient,
        StaticDataError,
    )
    from albion_crafter.database.database import default_data_directory

    forward = _make_progress_forwarder(op_id, sink, "static_data")
    repository = _STATE["catalog_repository"]

    class _ProgressProxy:
        def update(self, phase: str, message: str) -> None:
            forward(
                type(
                    "P",
                    (),
                    {
                        "message": message,
                        "phase": phase,
                        "stage": phase,
                        "fraction": None,
                    },
                )()
            )

    try:
        metadata = StaticDataClient().update_catalog(
            repository,
            default_data_directory() / "static-cache",
            force=force,
        )
    except StaticDataError as exc:
        return json.dumps({"ok": False, "error": f"Static catalog update rejected: {exc}"})
    except Exception as exc:  # noqa: BLE001 - boundary
        traceback.print_exc()
        return json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"})
    finally:
        _finish_op(op_id)

    report = repository.latest_import_report()
    return json.dumps(
        {
            "ok": True,
            "item_count": metadata.item_count,
            "recipe_count": metadata.recipe_count,
            "source_version": metadata.source_version,
            "report_finished_at": report.finished_at.isoformat()
            if report is not None and report.finished_at
            else None,
        }
    )


# --------------------------------------------------------------------------
# Catalog / calculator
# --------------------------------------------------------------------------


def catalog_search(query: str, limit: int) -> str:
    _ensure_stack()
    catalog = _STATE["catalog_repository"]
    matches = catalog.search_recipes(query, limit=int(limit))
    results = []
    for item in matches:
        results.append(
            {
                "item_id": item.item_id,
                "name": item.display_name,
                "tier": item.tier,
                "enchantment": item.enchantment,
                "category": getattr(item, "category", None),
            }
        )
    return json.dumps({"ok": True, "results": results})


def _get_recipe(item_id: str):
    recipe = _STATE["catalog_repository"].get_recipe(str(item_id))
    if recipe is None:
        _fail(f"recipe unavailable for {item_id}")
    return recipe


def calculator_evaluate(request_json: str) -> str:
    """Evaluate one recipe. Mirrors CalculatorView.calculate()."""
    _ensure_stack()
    from albion_crafter.core.calculator import CraftCalculator
    from albion_crafter.core.crafting_profile import focus_skill_mapping_for_recipe
    from albion_crafter.core.freshness import FreshnessPolicy
    from albion_crafter.core.models import CraftingContext, SaleMethod
    from albion_crafter.core.stations import station_type_for_item
    from albion_crafter.market.models import MarketSide, Region

    request = json.loads(request_json)
    settings = _STATE["settings_repository"]

    item_id = str(request.get("item_id", ""))
    recipe = _get_recipe(item_id)

    region = Region(str(request.get("region", settings.get("region", "americas"))))
    material_city = str(request.get("material_city", "Bridgewatch"))
    sell_city = str(request.get("sell_city", "Bridgewatch"))
    craft_city = str(request.get("craft_city", material_city))
    crafts = int(request.get("crafts", 1))
    quality = int(request.get("quality", 1))
    use_focus = bool(request.get("use_focus", False))
    premium = bool(request.get("premium", settings.get("premium", True)))
    sale_method = SaleMethod(str(request.get("sale_method", "sell_order")))

    market_policy = FreshnessPolicy(
        timedelta(hours=int(settings.get("max_market_age_hours", 4)))
    )
    output_side = (
        MarketSide.BUY_ORDER if sale_method is SaleMethod.INSTANT_SELL else MarketSide.SELL_ORDER
    )

    resolver = _STATE["resolver"]
    snapshot = resolver.resolve(
        recipe,
        buy_city=material_city,
        sell_city=sell_city,
        region=region,
        quality=quality,
        freshness_policy=market_policy,
        material_side=MarketSide.SELL_ORDER,
        output_side=output_side,
    )

    station_type = station_type_for_item(recipe.output)
    station_observation = None
    if station_type is not None:
        station_observation = _STATE["station_fee_repository"].get(
            region, craft_city, station_type
        )
    max_station_fee_age_hours = int(settings.get("max_station_fee_age_hours", 24))
    allow_stale_station_fees = bool(
        settings.get(ALLOW_STALE_STATION_FEES_KEY, True)
    )
    station_policy = (
        None
        if allow_stale_station_fees
        else FreshnessPolicy(timedelta(hours=max_station_fee_age_hours))
    )
    profile = _STATE["crafting_profile_repository"].load()
    fce_resolution = profile.resolve(focus_skill_mapping_for_recipe(recipe))

    context = CraftingContext(
        craft_city=craft_city,
        sell_city=sell_city,
        crafts=crafts,
        output_quality=quality,
        use_focus=use_focus,
        premium=premium,
        sale_method=sale_method,
        profile=profile,
        material_buy_city=material_city,
        station_fee_observation=station_observation,
        station_fee_freshness_policy=station_policy,
    )
    calculator = CraftCalculator()
    result = calculator.calculate(
        recipe,
        snapshot.material_prices,
        snapshot.output_price,
        context,
        data_quality=snapshot.actionability,
    )

    return json.dumps(
        {
            "ok": True,
            "result": _serialize_craft_result(result),
            "material_prices": {
                k: v for k, v in snapshot.material_prices.items()
            },
            "output_price": snapshot.output_price,
            "actionability": _serialize_actionability(snapshot.actionability),
            "station_fee_evidence": _station_evidence(
                region,
                station_type,
                station_observation,
                max_age_hours=max_station_fee_age_hours,
                allow_stale=allow_stale_station_fees,
            ),
            "fce_evidence": _fce_evidence(fce_resolution),
            "station_type": station_type.value if station_type else None,
        }
    )


def _serialize_craft_result(result) -> dict:
    skip = {"actionability"}
    payload = {}
    for f in dataclass_fields(result):
        if f.name in skip:
            continue
        value = getattr(result, f.name)
        payload[f.name] = _jsonable(value)
    payload["actionability"] = _serialize_actionability(result.actionability)
    return payload


def _serialize_actionability(assessment) -> dict:
    if assessment is None:
        return {"status": "unknown"}
    return {
        "status": str(getattr(assessment, "status", "unknown")),
        "reasons": [
            str(r) for r in (getattr(assessment, "reasons", ()) or ())
        ],
    }


def _station_evidence(
    region,
    station_type,
    observation,
    *,
    max_age_hours: int,
    allow_stale: bool,
) -> dict:
    if observation is None:
        return {
            "present": False,
            "station": station_type.display_name if station_type else None,
            "allow_stale": allow_stale,
        }
    from albion_crafter.core.freshness import Freshness, FreshnessPolicy

    now = datetime.now(UTC)
    freshness = FreshnessPolicy(timedelta(hours=max_age_hours)).classify(
        observation.observed_at,
        now=now,
    )
    age_hours = max((now - observation.observed_at).total_seconds() / 3600.0, 0.0)
    return {
        "present": True,
        "station": observation.station_type.display_name,
        "city": observation.city,
        "displayed_fee": observation.displayed_fee,
        "observed_at": observation.observed_at.isoformat(),
        "age_hours": age_hours,
        "freshness": freshness.value,
        "allow_stale": allow_stale,
        "usable": freshness is not Freshness.FUTURE
        and (freshness is not Freshness.STALE or allow_stale),
    }


def _fce_evidence(resolution) -> dict:
    return {
        "fce": resolution.focus_cost_efficiency,
        "source": str(getattr(resolution, "source", "unknown")),
        "provenance": str(getattr(resolution, "provenance", "unknown")),
        "skill_level": getattr(resolution, "skill_level", None),
    }


def calculator_refresh_prices(op_id: str, request_json: str, sink) -> str:
    _ensure_stack()
    from albion_crafter.market.models import Region, MarketSide
    from albion_crafter.core.models import SaleMethod
    from albion_crafter.market.recipe_refresh import RecipePriceRefreshRequest

    request = json.loads(request_json)
    settings = _STATE["settings_repository"]
    item_id = str(request.get("item_id", ""))
    recipe = _get_recipe(item_id)

    region = Region(str(request.get("region", settings.get("region", "americas"))))
    material_city = str(request.get("material_city", "Bridgewatch"))
    sell_city = str(request.get("sell_city", "Bridgewatch"))
    quality = int(request.get("quality", 1))
    sale_method = SaleMethod(str(request.get("sale_method", "sell_order")))
    output_side = (
        MarketSide.BUY_ORDER
        if sale_method is SaleMethod.INSTANT_SELL
        else MarketSide.SELL_ORDER
    )

    refresh_request = RecipePriceRefreshRequest(
        recipe=recipe,
        region=region,
        material_city=material_city,
        sell_city=sell_city,
        output_quality=quality,
        material_side=MarketSide.SELL_ORDER,
        output_side=output_side,
    )
    forward = _make_progress_forwarder(op_id, sink, "calculator_refresh")
    service = _STATE["refresh_service"]
    try:
        result = service.refresh(
            refresh_request,
            is_cancelled=_cancellation_check(op_id),
            on_progress=forward,
        )
    finally:
        _finish_op(op_id)
    return json.dumps(
        {
            "ok": True,
            "cancelled": bool(getattr(result, "cancelled", False)),
            "batches_completed": getattr(result, "batches_completed", 0),
            "batches_failed": getattr(result, "batches_failed", 0),
        }
    )


# --------------------------------------------------------------------------
# Market data
# --------------------------------------------------------------------------


OUTER_ROYAL_CITIES = (
    "Bridgewatch",
    "Fort Sterling",
    "Lymhurst",
    "Martlock",
    "Thetford",
)


def market_coverage(cities_json: str | None = None) -> str:
    _ensure_stack()
    from albion_crafter.market.coverage import MarketCoverageService
    from albion_crafter.market.models import Region
    from albion_crafter.market.sync import RoyalMarketUniverseService

    catalog = _STATE["catalog_repository"]
    market = _STATE["market_repository"]
    universe = RoyalMarketUniverseService(catalog).derive()
    if cities_json:
        cities = tuple(json.loads(cities_json))
    else:
        cities = OUTER_ROYAL_CITIES
    region = _STATE["region"]
    if not isinstance(region, Region):
        region = Region(str(region))
    coverage = MarketCoverageService(market)
    summary = coverage.summary(
        region,
        cities,
        universe.item_ids,
        as_of=datetime.now(UTC),
    )
    per_city = [
        {
            "city": entry.city,
            "market_rows": entry.market_rows,
            "observed_within_2h": entry.observed_within_2h,
            "observed_within_4h": entry.observed_within_4h,
            "observed_within_24h": entry.observed_within_24h,
            "no_usable_price": entry.no_usable_price,
        }
        for entry in (getattr(summary, "per_city", ()) or ())
    ]
    return json.dumps(
        {
            "ok": True,
            "expected_rows": summary.expected_rows,
            "market_rows": summary.market_rows,
            "observed_within_2h": summary.observed_within_2h,
            "observed_within_4h": summary.observed_within_4h,
            "observed_within_24h": summary.observed_within_24h,
            "observed_older_than_24h": summary.observed_older_than_24h,
            "no_usable_price": summary.no_usable_price,
            "oldest_observation_at": summary.oldest_observation_at.isoformat()
            if summary.oldest_observation_at
            else None,
            "newest_observation_at": summary.newest_observation_at.isoformat()
            if summary.newest_observation_at
            else None,
            "per_city": per_city,
        }
    )


def market_sync(op_id: str, region_value: str, cities_json: str, sink) -> str:
    _ensure_stack()
    from albion_crafter.market.models import Region
    from albion_crafter.market.sync import (
        RoyalMarketSyncService,
        RoyalMarketUniverseService,
    )

    region = Region(str(region_value))
    cities = tuple(json.loads(cities_json))
    forward = _make_progress_forwarder(op_id, sink, "market_sync")

    service = RoyalMarketSyncService(
        RoyalMarketUniverseService(_STATE["catalog_repository"]),
        _STATE["market_repository"],
        history_backfill=_build_history_backfill(),
    )
    try:
        result = service.synchronize(
            region,
            cities,
            is_cancelled=_cancellation_check(op_id),
            on_progress=forward,
        )
    finally:
        _finish_op(op_id)
    return json.dumps(
        {
            "ok": True,
            "cancelled": bool(result.cancelled),
            "item_count": len(result.item_ids),
            "records": getattr(result, "records_saved", None) or 0,
            "failed_batches": getattr(result, "failed_batches", 0),
        }
    )


def _build_history_backfill():
    from albion_crafter.market.backfill import MissingSellHistoryBackfillService

    return MissingSellHistoryBackfillService(
        _STATE["market_repository"],
        _STATE["history_repository"],
    )


# --------------------------------------------------------------------------
# Planner (Find Me Money)
# --------------------------------------------------------------------------


def _constraints_from_request(request: dict):
    from albion_crafter.core.models import ActionKind, SaleMethod
    from albion_crafter.market.models import Region
    from albion_crafter.planning.models import (
        ArbitrageScope,
        FindMoneyConstraints,
        MinimumLiquidity,
        TransportPolicy,
    )

    def city_tuple(value) -> tuple[str, ...]:
        if isinstance(value, str):
            parts = [part.strip() for part in value.split(",") if part.strip()]
            return tuple(parts)
        return tuple(str(v).strip() for v in (value or ()) if str(v).strip())

    def int_set(value) -> frozenset[int]:
        if value is None:
            return None
        return frozenset(int(v) for v in value)

    def str_set(value) -> frozenset[str]:
        if value is None:
            return None
        return frozenset(str(v).strip() for v in value if str(v).strip())

    settings = _STATE["settings_repository"]
    kwargs: dict = {
        "available_silver": int(request.get("available_silver", 1_000_000)),
        "available_focus": int(request.get("available_focus", 10_000)),
        "region": Region(str(request.get("region", "americas"))),
        "silver_reserve": int(request.get("silver_reserve", 0)),
        "focus_reserve": int(request.get("focus_reserve", 0)),
        "premium": bool(request.get("premium", True)),
        "item_query": str(request.get("item_query", "")),
        "material_cities": city_tuple(request.get("material_cities", ("Bridgewatch",))),
        "craft_cities": city_tuple(request.get("craft_cities", ("Bridgewatch",))),
        "sell_cities": city_tuple(request.get("sell_cities", ("Bridgewatch",))),
        "use_focus": bool(request.get("use_focus", False)),
        "max_market_age": timedelta(hours=int(request.get("max_market_age_hours", 4))),
        "max_station_fee_age": timedelta(
            hours=int(
                request.get(
                    "max_station_fee_age_hours",
                    settings.get("max_station_fee_age_hours", 24),
                )
            )
        ),
        "allow_stale_station_fees": bool(
            request.get(
                ALLOW_STALE_STATION_FEES_KEY,
                settings.get(ALLOW_STALE_STATION_FEES_KEY, True),
            )
        ),
        "minimum_profit": request.get("minimum_profit"),
        "minimum_roi": request.get("minimum_roi"),
        "sale_method": SaleMethod(str(request.get("sale_method", "sell_order"))),
        "transport_policy": TransportPolicy(
            str(request.get("transport_policy", "acknowledged_uncosted"))
        ),
        "transport_cost_per_craft": request.get("transport_cost_per_craft"),
        "per_item_craft_cap": int(request.get("per_item_craft_cap", 10)),
        "historical_volume_share": request.get("historical_volume_share", 0.20),
        "history_enabled": bool(request.get("history_enabled", True)),
        "history_shortlist_limit": int(request.get("history_shortlist_limit", 200)),
        "force_current_price_refresh": bool(
            request.get("force_current_price_refresh", False)
        ),
    }

    tiers = int_set(request.get("tiers"))
    if tiers is not None:
        kwargs["tiers"] = tiers
    enchantments = int_set(request.get("enchantments"))
    if enchantments is not None:
        kwargs["enchantments"] = enchantments
    categories = str_set(request.get("categories"))
    if categories is not None:
        kwargs["categories"] = categories

    action_kinds_raw = request.get("action_kinds")
    if action_kinds_raw is not None:
        kinds = frozenset(ActionKind(str(v)) for v in action_kinds_raw)
        if kinds:
            kwargs["action_kinds"] = kinds

    refining_raw = request.get("refining_families")
    if refining_raw is not None:
        families = frozenset(str(v) for v in refining_raw if str(v).strip())
        if families:
            kwargs["refining_families"] = families

    kwargs["arbitrage_scope"] = ArbitrageScope(
        str(request.get("arbitrage_scope", "all_production_outputs"))
    )
    kwargs["arbitrage_source_cities"] = city_tuple(
        request.get("arbitrage_source_cities")
    ) or (
        "Bridgewatch",
        "Fort Sterling",
        "Lymhurst",
        "Martlock",
        "Thetford",
    )
    kwargs["arbitrage_destination_cities"] = city_tuple(
        request.get("arbitrage_destination_cities")
    ) or (
        "Bridgewatch",
        "Fort Sterling",
        "Lymhurst",
        "Martlock",
        "Thetford",
    )
    minimum_liquidity = request.get("minimum_liquidity")
    if minimum_liquidity:
        kwargs["minimum_liquidity"] = MinimumLiquidity(str(minimum_liquidity))

    return FindMoneyConstraints(**kwargs)


def planner_preflight(constraints_json: str) -> str:
    _ensure_stack()
    service = _STATE["service"]
    constraints = _constraints_from_request(json.loads(constraints_json))
    preflight = service.preflight(constraints)
    summary = preflight.summary
    return json.dumps(
        {
            "ok": True,
            "summary": {
                "supported_catalog_recipes": summary.supported_catalog_recipes,
                "matched_recipes": summary.matched_recipes,
            },
            "eligible_routes": len(preflight.eligible) + len(preflight.arbitrage_routes),
            "rejection_counts": [
                {"reason": k, "count": v} for k, v in preflight.rejection_counts
            ],
        }
    )


def planner_run(op_id: str, constraints_json: str, sink) -> str:
    _ensure_stack()
    service = _STATE["service"]
    constraints = _constraints_from_request(json.loads(constraints_json))
    preflight = service.preflight(constraints)
    forward = _make_progress_forwarder(op_id, sink, "planner")
    try:
        result = service.execute(
            preflight,
            refresh_current=True,
            refresh_history=True,
            cancelled=_cancellation_check(op_id),
            progress=forward,
        )
    finally:
        _finish_op(op_id)
    return json.dumps({"ok": True, "result": _serialize_run_result(result)})


def _serialize_run_result(result) -> dict:
    payload: dict = {
        "cancelled": bool(result.cancelled),
        "rejection_counts": [
            {"reason": k, "count": v} for k, v in (result.rejection_counts or ())
        ],
    }
    snapshot = result.snapshot
    if snapshot is None:
        payload["snapshot"] = None
        return payload

    actions = []
    for action in snapshot.actions:
        route = action.route
        actions.append(
            {
                "kind": action.action_kind.value,
                "display_name": action.display_name,
                "quantity": action.quantity,
                "pre_revenue_cash_required": action.pre_revenue_cash_required,
                "expected_profit": action.expected_profit,
                "material_city": getattr(route, "material_city", None),
                "production_city": getattr(route, "production_city", None),
                "buy_city": getattr(route, "buy_city", None),
                "sell_city": getattr(route, "sell_city", None),
            }
        )

    plan_roi = (
        None
        if snapshot.total_pre_revenue_cash <= 0
        else snapshot.total_expected_profit / snapshot.total_pre_revenue_cash
    )
    search_stats = {}
    initial = result.initial_evaluation
    if initial is not None:
        candidates = list(initial.candidates)
        search_stats = {
            "fully_priced": len(candidates),
            "profitable": sum(
                1
                for candidate in candidates
                if max(
                    candidate.economics.nonfocused_profit_per_craft,
                    candidate.economics.focused_profit_per_craft or -(10**30),
                )
                > 0
            ),
        }

    payload["snapshot"] = {
        "snapshot_id": getattr(snapshot, "snapshot_id", None),
        "plan_status": snapshot.plan_status.value,
        "optimizer_status": snapshot.optimizer.status.value,
        "total_expected_profit": snapshot.total_expected_profit,
        "total_pre_revenue_cash": snapshot.total_pre_revenue_cash,
        "silver_remaining": snapshot.silver_remaining,
        "plan_roi": plan_roi,
        "action_count": len(snapshot.actions),
        "actions": actions,
        "search": search_stats,
        "generated_at": snapshot.generated_at.isoformat()
        if getattr(snapshot, "generated_at", None)
        else None,
    }
    return payload


def planner_recent_snapshots() -> str:
    _ensure_stack()
    snapshots = _STATE["snapshot_repository"]
    summaries = snapshots.list_summaries()
    results = []
    for summary in summaries:
        results.append(
            {
                "snapshot_id": summary.snapshot_id,
                "created_at": summary.created_at.isoformat()
                if summary.created_at
                else None,
                "expected_profit": getattr(summary, "expected_profit", None),
                "action_count": getattr(summary, "action_count", None),
                "plan_status": str(
                    getattr(getattr(summary, "plan_status", None), "value", "")
                ),
            }
        )
    return json.dumps({"ok": True, "snapshots": results})


def planner_load_snapshot(snapshot_id) -> str:
    _ensure_stack()
    snapshots = _STATE["snapshot_repository"]
    snapshot = snapshots.load(int(snapshot_id))
    if snapshot is None:
        return json.dumps({"ok": False, "error": "snapshot not found"})
    return json.dumps({"ok": True, "snapshot": _serialize_snapshot_detail(snapshot)})


def _serialize_snapshot_detail(snapshot) -> dict:
    actions = []
    for action in snapshot.actions:
        route = action.route
        actions.append(
            {
                "kind": action.action_kind.value,
                "display_name": action.display_name,
                "quantity": action.quantity,
                "pre_revenue_cash_required": action.pre_revenue_cash_required,
                "expected_profit": action.expected_profit,
                "material_city": getattr(route, "material_city", None),
                "production_city": getattr(route, "production_city", None),
                "buy_city": getattr(route, "buy_city", None),
                "sell_city": getattr(route, "sell_city", None),
            }
        )
    return {
        "snapshot_id": getattr(snapshot, "snapshot_id", None),
        "plan_status": snapshot.plan_status.value,
        "optimizer_status": snapshot.optimizer.status.value,
        "total_expected_profit": snapshot.total_expected_profit,
        "total_pre_revenue_cash": snapshot.total_pre_revenue_cash,
        "silver_remaining": snapshot.silver_remaining,
        "action_count": len(snapshot.actions),
        "actions": actions,
    }


# --------------------------------------------------------------------------
# Craft scanner
# --------------------------------------------------------------------------


def scanner_run(op_id: str, constraints_json: str, sink) -> str:
    _ensure_stack()
    from albion_crafter.market.models import Region
    from albion_crafter.opportunity.models import ScanConstraints

    request = json.loads(constraints_json)

    def city_tuple(value) -> tuple[str, ...]:
        if isinstance(value, str):
            return tuple(
                part.strip() for part in value.split(",") if part.strip()
            )
        return tuple(str(v).strip() for v in (value or ()) if str(v).strip())

    settings = _STATE["settings_repository"]
    constraints = ScanConstraints(
        region=Region(
            str(request.get("region", settings.get("region", "americas")))
        ),
        craft_cities=city_tuple(request.get("craft_cities", ("Bridgewatch",))),
        sell_cities=city_tuple(request.get("sell_cities", ("Bridgewatch",))),
        material_city=request.get("material_city") or None,
        text=str(request.get("text", "")),
        tier_min=request.get("tier_min"),
        tier_max=request.get("tier_max"),
        enchantments=tuple(int(v) for v in (request.get("enchantments") or ())),
        crafting_categories=city_tuple(request.get("categories")),
        use_focus=bool(request.get("use_focus", False)),
        available_focus=float(request.get("available_focus", 0)),
        premium=bool(request.get("premium", True)),
        maximum_station_fee_age=timedelta(
            hours=int(settings.get("max_station_fee_age_hours", 24))
        ),
        allow_stale_station_fees=bool(
            settings.get(ALLOW_STALE_STATION_FEES_KEY, True)
        ),
        actionable_only=bool(request.get("actionable_only", True)),
        minimum_profit=request.get("minimum_profit"),
        minimum_roi=request.get("minimum_roi"),
    )
    forward = _make_progress_forwarder(op_id, sink, "scanner")
    service = _STATE["scanner_service"]
    try:
        snapshot = service.scan(constraints, progress=forward)
    finally:
        _finish_op(op_id)

    opportunities = []
    for opportunity in snapshot.opportunities[:200]:
        calculation = opportunity.calculation
        opportunities.append(
            {
                "item_id": opportunity.recipe.output.item_id,
                "name": getattr(opportunity.recipe.output, "display_name", None)
                or opportunity.recipe.output.item_id,
                "profit": calculation.profit,
                "roi": calculation.roi,
                "upfront_capital": calculation.upfront_capital_required,
                "material_city": opportunity.material_city,
                "craft_city": opportunity.craft_city,
                "sell_city": opportunity.sell_city,
                "tier": getattr(opportunity.recipe.output, "tier", None),
                "enchantment": getattr(opportunity.recipe.output, "enchantment", None),
            }
        )
    return json.dumps(
        {
            "ok": True,
            "scan_time": snapshot.scan_time.isoformat(),
            "count": len(snapshot.opportunities),
            "opportunities": opportunities,
            "rejection_class_counts": [
                {"reason": k, "count": v}
                for k, v in (snapshot.rejection_class_counts or {}).items()
            ]
            if isinstance(snapshot.rejection_class_counts, dict)
            else [
                {"reason": k, "count": v}
                for k, v in (snapshot.rejection_class_counts or ())
            ],
        }
    )


# --------------------------------------------------------------------------
# Station fees / crafting profile
# --------------------------------------------------------------------------


def station_fees_list(region_value: str) -> str:
    _ensure_stack()
    fees = _STATE["station_fee_repository"].list_all(str(region_value))
    results = []
    for observation in fees:
        results.append(
            {
                "region": observation.region,
                "city": observation.city,
                "station_type": observation.station_type.value,
                "station_name": observation.station_type.display_name,
                "displayed_fee": observation.displayed_fee,
                "observed_at": observation.observed_at.isoformat(),
                "provenance": str(getattr(observation, "provenance", "")),
            }
        )
    return json.dumps({"ok": True, "fees": results})


def station_fee_set(fee_json: str) -> str:
    _ensure_stack()
    from albion_crafter.core.stations import StationFeeObservation, StationType

    request = json.loads(fee_json)
    observed_at = datetime.fromisoformat(str(request["observed_at"]))
    if observed_at.tzinfo is None:
        observed_at = observed_at.replace(tzinfo=UTC)
    observation = StationFeeObservation(
        region=str(request["region"]),
        city=str(request["city"]),
        station_type=StationType(str(request["station_type"])),
        displayed_fee=float(request["displayed_fee"]),
        observed_at=observed_at,
    )
    _STATE["station_fee_repository"].set(observation)
    return json.dumps({"ok": True})


def station_fee_remove(fee_json: str) -> str:
    _ensure_stack()
    from albion_crafter.core.stations import StationType

    request = json.loads(fee_json)
    removed = _STATE["station_fee_repository"].remove(
        str(request["region"]),
        str(request["city"]),
        StationType(str(request["station_type"])),
    )
    return json.dumps({"ok": True, "removed": bool(removed)})


def crafting_profile_get() -> str:
    _ensure_stack()
    profile = _STATE["crafting_profile_repository"].load()
    skill_levels = [
        {
            "skill_key": entry.skill_key,
            "level": entry.level,
        }
        for entry in profile.skill_levels
    ]
    overrides = [
        {
            "mapping_key": entry.mapping_key,
            "focus_cost_efficiency": entry.focus_cost_efficiency,
        }
        for entry in profile.manual_fce_overrides
    ]
    return json.dumps(
        {
            "ok": True,
            "available_focus": profile.available_focus,
            "skill_levels": skill_levels,
            "manual_fce_overrides": overrides,
            "complete_groups": sorted(profile.complete_groups),
            "assume_zero_for_unspecified": profile.assume_zero_for_unspecified,
        }
    )


def crafting_profile_save(profile_json: str) -> str:
    _ensure_stack()
    from albion_crafter.core.crafting_profile import (
        CraftingSkillLevel,
        CraftingSkillProfile,
        ManualFocusEfficiencyOverride,
    )
    from albion_crafter.core.models import Provenance

    request = json.loads(profile_json)
    existing = _STATE["crafting_profile_repository"].load()
    existing_levels = {level.skill_key: level for level in existing.skill_levels}

    merged: list[CraftingSkillLevel] = []
    seen_keys: set[str] = set()
    for entry in (request.get("skill_levels") or ()):
        skill_key = str(entry["skill_key"])
        if skill_key in seen_keys:
            continue
        seen_keys.add(skill_key)
        level_value = entry.get("level")
        level = None if level_value is None else int(level_value)
        if skill_key in existing_levels:
            prior = existing_levels[skill_key]
            merged.append(
                CraftingSkillLevel(
                    skill_key=prior.skill_key,
                    crafting_group=prior.crafting_group,
                    level=level,
                    mutual_fce_per_level=prior.mutual_fce_per_level,
                    provenance=prior.provenance,
                )
            )
        else:
            merged.append(
                CraftingSkillLevel(
                    skill_key=skill_key,
                    crafting_group=str(entry["crafting_group"]),
                    level=level,
                    mutual_fce_per_level=float(entry.get("mutual_fce_per_level", 30.0)),
                    provenance=Provenance.USER_PROFILE,
                )
            )

    overrides: list[ManualFocusEfficiencyOverride] = []
    for entry in (request.get("manual_fce_overrides") or ()):
        overrides.append(
            ManualFocusEfficiencyOverride(
                mapping_key=str(entry["mapping_key"]),
                focus_cost_efficiency=float(entry["focus_cost_efficiency"]),
                entered_at=datetime.now(UTC),
                provenance=Provenance.USER_OVERRIDE,
            )
        )

    profile = CraftingSkillProfile(
        available_focus=float(request.get("available_focus", existing.available_focus)),
        skill_levels=tuple(merged),
        manual_fce_overrides=tuple(overrides),
        complete_groups=frozenset(request.get("complete_groups") or ()),
        assume_zero_for_unspecified=bool(
            request.get("assume_zero_for_unspecified", existing.assume_zero_for_unspecified)
        ),
    )
    _STATE["crafting_profile_repository"].save(profile)
    return json.dumps({"ok": True})


REFINING_FAMILIES = ("ore", "wood", "hide", "fiber", "rock")
REFINING_TIERS = (4, 5, 6, 7, 8)


def refining_matrix_get() -> str:
    _ensure_stack()
    profile = _STATE["crafting_profile_repository"].load()
    levels = {level.skill_key: level for level in profile.skill_levels}
    matrix = {}
    for family in REFINING_FAMILIES:
        for tier in REFINING_TIERS:
            level = levels.get(f"refining:{family}:t{tier}")
            matrix[f"{family}:t{tier}"] = (
                None if level is None or level.level is None else level.level
            )
    complete = [family for family in REFINING_FAMILIES if f"refining:{family}" in profile.complete_groups]
    return json.dumps(
        {
            "ok": True,
            "levels": matrix,
            "complete_families": complete,
            "available_focus": profile.available_focus,
            "assume_zero_for_unspecified": profile.assume_zero_for_unspecified,
        }
    )


def refining_matrix_save(request_json: str) -> str:
    _ensure_stack()
    from albion_crafter.core.crafting_profile import (
        CraftingSkillLevel,
        CraftingSkillProfile,
    )
    from albion_crafter.core.models import Provenance

    request = json.loads(request_json)
    existing = _STATE["crafting_profile_repository"].load()
    levels_input = request.get("levels") or {}

    skills = [
        level
        for level in existing.skill_levels
        if not level.crafting_group.startswith("refining:")
    ]
    for family in REFINING_FAMILIES:
        for tier in REFINING_TIERS:
            value = levels_input.get(f"{family}:t{tier}")
            if value is None or str(value).strip() == "":
                continue
            level = int(value)
            if not 0 <= level <= 100:
                _fail(f"refining levels must be between 0 and 100 (got {level})")
            skills.append(
                CraftingSkillLevel(
                    f"refining:{family}:t{tier}",
                    f"refining:{family}",
                    level,
                    30,
                    Provenance.USER_PROFILE,
                )
            )

    complete_groups = {
        group
        for group in existing.complete_groups
        if not group.startswith("refining:")
    }
    complete_groups.update(
        f"refining:{family}"
        for family in (request.get("complete_families") or ())
    )
    profile = CraftingSkillProfile(
        available_focus=float(
            request.get("available_focus", existing.available_focus)
        ),
        skill_levels=tuple(skills),
        manual_fce_overrides=existing.manual_fce_overrides,
        complete_groups=frozenset(complete_groups),
        assume_zero_for_unspecified=bool(
            request.get(
                "assume_zero_for_unspecified",
                existing.assume_zero_for_unspecified,
            )
        ),
    )
    _STATE["crafting_profile_repository"].save(profile)
    return json.dumps({"ok": True})


# --------------------------------------------------------------------------
# JSON helpers
# --------------------------------------------------------------------------


def _jsonable(value):
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, dict):
        return {str(k): _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, timedelta):
        return value.total_seconds()
    return str(value)
