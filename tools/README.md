# Offline AutoEq Converter — tools/autoeq_converter.py

`autoeq_converter.py` converts a local AutoEq checkout into compact Android-friendly JSON assets
for use at runtime in the `:engine` module.

Compatibility entry point: `autoeq_convert.py` (same behavior, stable CLI).

Canonical AutoEq source repository: `https://github.com/jaakkopasanen/AutoEq`

`example_autoeq_profiles.json` is a one-profile schema/sample fixture only — it is **not** used at runtime.
Use the converter output as the real runtime dataset.

Companion doc: `README_autoeq_converter.md`

## What's Changed (2026-03-14)

- Converter-to-runtime handoff is explicitly linked to Kotlin DSP processed mode.
- Runtime mapping path is documented (`EqProfileMapper` -> `BiquadFilterChain`).
- DSP notes now focus on regeneration triggers and verification when testing processed A/B behavior.

---

## File naming in the AutoEq repo

The AutoEq repository names its parametric EQ files as:

```
{Model Name} ParametricEQ.txt
```

e.g. `Sony WH-1000XM4 ParametricEQ.txt`, `Bose QuietComfort 45 ParametricEQ.txt`.

The converter discovers all files whose lowercase name **ends with** `parametriceq.txt` or
`parametric_eq.txt`, so no manual path adjustment is needed.

---

## Bundled dataset stats

The bundled `engine/src/main/assets/autoeq_profiles.json` was generated from the AutoEq master repo:

| Stat              | Value    |
|-------------------|----------|
| Files scanned     | 8 850    |
| Profiles exported | 6 017    |
| Deduped (skipped) | 2 833    |

---

## Quick usage

```powershell
python tools/autoeq_converter.py `
  --input  "C:\path\to\AutoEq-master" `
  --output "engine\src\main\assets\autoeq_profiles.json" `
  --require-peq `
  --dedupe
```

> **Windows note — Python not in PATH**: Windows App Execution Aliases may intercept the `python`
> command and open the Store instead. Use the full executable path:
>
> ```powershell
> & "C:\Users\<you>\AppData\Local\Programs\Python\Python312\python.exe" `
>   tools\autoeq_converter.py `
>   --input  "C:\path\to\AutoEq-master" `
>   --output "engine\src\main\assets\autoeq_profiles.json" `
>   --require-peq --dedupe
> ```

---

## Optional flags

### Export an aliases map alongside profiles

```powershell
python tools/autoeq_converter.py `
  --input  "C:\path\to\AutoEq" `
  --output "engine\src\main\assets\autoeq_profiles.json" `
  --aliases-output "engine\src\main\assets\autoeq_aliases.json" `
  --require-peq
```

### Filter to selected manufacturers only

```powershell
python tools/autoeq_converter.py `
  --input  "C:\path\to\AutoEq" `
  --output "engine\src\main\assets\autoeq_profiles.json" `
  --manufacturers Sony,Bose,Sennheiser `
  --top-n 500 `
  --require-peq `
  --dedupe
```

### All CLI flags

| Flag                    | Default  | Description                                                             |
|-------------------------|----------|-------------------------------------------------------------------------|
| `--input`               | required | Path to AutoEq repository root                                          |
| `--output`              | required | Destination path for `autoeq_profiles.json`                             |
| `--aliases-output`      | none     | Optional destination for aliases map JSON                               |
| `--require-peq`         | false    | Skip profiles that have no PEAK-type filters                            |
| `--dedupe`              | false    | Skip profiles whose normalized name is already seen (keep first)        |
| `--manufacturers`       | all      | Comma-separated list: only include these brands (case-insensitive)      |
| `--top-n`               | all      | Limit total output profiles to the first N (after dedup + filtering)    |

The script prints a summary with scanned / exported / skipped counts and skip reasons.

---

## Runtime handoff

Place the generated file at `engine/src/main/assets/autoeq_profiles.json`.  
The `:engine` module loads it automatically on first use via `AutoEqProfileRepository`.

At runtime, matched PEAK bands are mapped by `engine/.../autoeq/EqProfileMapper.kt`
and applied in processed mode through `engine/.../analysis/BiquadFilterChain.kt`.

### Verify in the app

1. Build and run the app.
2. Tap **START ENGINE**.
3. Type a partial headphone name in the `Headphone name` field (e.g., `sony wh`).
4. Observe up to 6 suggestion cards appearing below the input (150 ms debounced typeahead).
5. Tap a suggestion to auto-fill, then tap **Apply**.
6. Confirm the `Last match` status line updates with the matched profile name and source.
7. Export session logs and verify `AUTOEQ_MANUAL_APPLY` rows include AutoEq telemetry fields
   (`AutoEqMatchedName`, `AutoEqMatchSource`, `AutoEqMatchConfidence`, `AutoEqFallbackUsed`).

### Typeahead vs Apply matching

| Action      | Matching strategy                        | Notes                                             |
|-------------|------------------------------------------|---------------------------------------------------|
| Typeahead   | Tiered scoring (7 tiers, typo-tolerant)  | Up to 6 results; brand-first + 1-edit tolerance   |
| Apply       | Conservative exact/alias match only      | No fuzzy — wrong profile is never applied silently |

---

## DSP Runtime Notes

Kotlin DSP output path is implemented in runtime. Regenerate `autoeq_profiles.json` whenever:

- parser logic changes in `tools/autoeq_converter.py`
- filter mapping behavior changes in `engine/.../autoeq/EqProfileMapper.kt`
- you switch between full dataset and manufacturer-filtered subsets for testing

Recommended verification after regeneration:

1. Build `:engine` + `:app` and run on device.
2. Start engine in B mode (processed).
3. Apply one known profile (for example, a popular Sony/Bose model).
4. Confirm `Last match` updates and processed path sounds different vs A mode.
5. Export logs and confirm `AUTOEQ_MANUAL_APPLY` telemetry fields are present.

If runtime size/performance becomes a concern during experimentation, test with `--top-n`
first, then re-run without `--top-n` for full-profile validation.
