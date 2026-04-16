# AutoEq Offline Converter (Phase-1)

This document describes the offline conversion path used by Phase-1.

## Purpose

Convert a local AutoEq checkout into a compact Android runtime dataset.

- Converter runs on desktop/offline only.
- Android app consumes bundled JSON assets only.
- Runtime does not parse raw AutoEq repository files.

## Scripts

- `tools/autoeq_converter.py` — primary maintained converter implementation
- `tools/autoeq_convert.py` — compatibility entry point (same behavior)

## Minimal command

```powershell
python tools/autoeq_convert.py --input "C:\path\to\AutoEq" --output "engine\src\main\assets\autoeq_profiles.json" --require-peq --dedupe
```

## Runtime-safe behavior

- malformed entries are skipped
- unsupported filters are counted and skipped
- conservative matching remains runtime-side (exact/alias/fallback)

## Output schema (profile)

```json
{
  "id": "sony_wh_1000xm4",
  "manufacturer": "Sony",
  "name": "WH-1000XM4",
  "normalized_name": "sony wh 1000xm4",
  "source": "AutoEq",
  "form": "over-ear",
  "preamp_db": -5.2,
  "peq_filters": [
    {"type": "PEAK", "fc_hz": 105.0, "q": 1.41, "gain_db": -3.2}
  ],
  "aliases": []
}
```

## Recommended runtime destinations

- `engine/src/main/assets/autoeq_profiles.json` (primary)
- `tools/autoeq_profiles.json` (optional local test artifact)

