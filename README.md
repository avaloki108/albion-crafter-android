# Albion Crafter Android

Native Android (Kotlin/Compose) port of [albion-crafter](~/Projects/albion-crafter)
with the full Python engine packaged unmodified via [Chaquopy](https://chaquo.com/chaquopy/).

Same behavior as the desktop app: Find Me Money planner (craft / refine / arbitrage),
Production Calculator, Craft Scanner, Royal Market Sync, Settings — all offline-first,
explicit-network, immutable-snapshot semantics preserved because it is literally the
same engine code.

## Architecture

```text
app/src/main/
├── java/com/dokholliday/albioncrafter/
│   ├── MainActivity.kt          app scaffold, bottom nav, first-run status
│   ├── PythonBridge.kt          single-thread Chaquopy executor, JSON in/out
│   └── ui/                      Compose screens (Plan, Calc, Scan, Market, Settings)
└── python/
    ├── bridge.py                JSON facade over the engine (no Qt)
    └── albion_crafter/          engine copy — DO NOT EDIT HERE (see sync script)
```

- The engine source of truth is `~/Projects/albion-crafter`. After changing it, run
  `scripts/sync-engine.sh` to re-copy the Qt-free packages (`ui/` and `main.py` are
  excluded) into the app.
- `bridge.py` mirrors the desktop composition root (repos + FindMoneyService +
  RoyalMarketSyncService + PriceResolver + scanner). Verify changes headless with
  `scripts/test_bridge_headless.py` (uses the real desktop DB copy).
- Data lives in app-private storage (`getDir("albion-crafter")`) via
  `ALBION_CRAFTER_DATA_DIR`. First run requires Market → Update Static Game Data
  (~80MB, WiFi) before planning works.
- The 11 station fees saved in the desktop database on 2026-08-21 are packaged as
  a one-time Android seed. Missing rows are copied on the first launch after this
  seed is introduced; existing Android values and later edits are not overwritten.
  Normal `adb install -r` updates preserve them. A fresh install seeds them again.
- Settings → **Keep saved station fees usable when stale** defaults on. Their real
  observation timestamps remain visible and stale values are advisory; the app
  never relabels them as newly observed just to keep them usable.

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 9.2.1, Kotlin 2.4, Compose BOM 2026.05, Chaquopy 17 (Python 3.13),
Gradle 9.5.1. Python for bytecode compilation comes from uv's cpython-3.13.

Release for phone only: add `ndk { abiFilters += "arm64-v8a" }` only, and
`assembleRelease` (needs a signing config) to cut APK size roughly in half.
