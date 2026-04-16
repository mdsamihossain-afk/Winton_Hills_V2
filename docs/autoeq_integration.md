# AutoEq Integration

## Scope

AutoEq is used as an **offline dataset source only**.
Android runtime consumes a compact bundled JSON asset produced by converter tools.

## Data path

`AutoEq repo -> converter -> filtered JSON -> bundled asset -> ProfileRepository -> matcher -> mapper -> DSP`

## Matching policy (safe/conservative)

Runtime apply flow:
1. exact normalized-name match
2. exact alias match
3. neutral fallback if no safe match

Typeahead suggestions may use broader ranking, but actual apply remains conservative.

## Filter support

Supported runtime filter types:
- `PEAK`
- `LOW_SHELF`
- `HIGH_SHELF`

If engine mapping does not support a filter type in current phase, it is skipped safely.

## Telemetry hooks

Profile apply telemetry includes:
- requested_name
- normalized_name
- matched_profile_name
- match_type
- fallback_used

