#!/usr/bin/env python3
"""
AutoEq Offline Converter

Output schema (autoeq_profiles.json):
{
  "version": 1,
  "source": "AutoEq",
  "generated_at": "<ISO-8601 UTC>",
  "profiles": [
    {
      "id": "sony_wh_1000xm4",
      "manufacturer": "Sony",
      "name": "WH-1000XM4",
      "normalized_name": "sony wh 1000xm4",
      "source": "AutoEq",
      "form": "over-ear",
      "preamp_db": -5.2,
      "peq_filters": [
        {"type":"PEAK","fc_hz":105.0,"q":1.41,"gain_db":-3.2}
      ],
      "tags": ["headphone"],
      "aliases": []
    }
  ]
}

Optional aliases file (autoeq_aliases.json):
{
  "version": 1,
  "source": "AutoEq",
  "aliases": {
    "sony wh1000xm4": "sony_wh_1000xm4"
  }
}
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional

SUPPORTED_TYPES = {"PEAK", "LOW_SHELF", "HIGH_SHELF"}
TYPE_MAP = {
    "PK": "PEAK",
    "PEQ": "PEAK",
    "PEAK": "PEAK",
    "LS": "LOW_SHELF",
    "LOWSHELF": "LOW_SHELF",
    "LOW_SHELF": "LOW_SHELF",
    "HS": "HIGH_SHELF",
    "HIGHSHELF": "HIGH_SHELF",
    "HIGH_SHELF": "HIGH_SHELF",
}

PREAMP_RE = re.compile(r"preamp\s*:\s*([+-]?\d+(?:\.\d+)?)", re.IGNORECASE)
FILTER_RE = re.compile(
    r"filter\s*\d+\s*:\s*(?:on\s+)?"
    r"(?P<type>[a-z_]+)"
    r"\s+fc\s+(?P<fc>[+-]?\d+(?:\.\d+)?)\s*hz"
    r"\s+gain\s+(?P<gain>[+-]?\d+(?:\.\d+)?)\s*db"
    r"\s+q\s+(?P<q>[+-]?\d+(?:\.\d+)?)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class PeqFilter:
    type: str
    fc_hz: float
    q: float
    gain_db: float


@dataclass(frozen=True)
class Profile:
    id: str
    manufacturer: str
    name: str
    normalized_name: str
    source: str
    form: str
    preamp_db: Optional[float]
    peq_filters: list[PeqFilter]
    tags: list[str]
    aliases: list[str]


@dataclass
class Stats:
    scanned: int = 0
    exported: int = 0
    skipped: int = 0
    unsupported_filters: int = 0
    skip_reasons: Counter[str] = None

    def __post_init__(self) -> None:
        if self.skip_reasons is None:
            self.skip_reasons = Counter()


def normalize_name(value: str) -> str:
    value = value.lower()
    value = re.sub(r"[^a-z0-9 ]", " ", value)
    value = re.sub(r"\s+", " ", value).strip()
    return value


def stable_id(manufacturer: str, name: str) -> str:
    return normalize_name(f"{manufacturer} {name}").replace(" ", "_")


def discover_parametric_files(root: Path) -> Iterable[Path]:
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        lower = path.name.lower()
        if lower == "parametriceq.txt" or lower.endswith("parametriceq.txt") or lower.endswith("parametric_eq.txt"):
            yield path


def infer_manufacturer_and_name(path: Path, root: Path) -> tuple[str, str]:
    rel = path.relative_to(root)
    parts = rel.parts

    # Common AutoEq layout: results/<source>/<form>/<model>/<model> ParametricEQ.txt
    if len(parts) >= 4:
        model = parts[-2].strip()
        first_token = model.split(" ")[0].strip() if model else "Unknown"
        manufacturer = first_token if first_token else "Unknown"
        return manufacturer, model

    # Legacy fallback layout: .../<manufacturer>/<model>/ParametricEQ.txt
    if len(parts) >= 3:
        return parts[-3].strip() or "Unknown", parts[-2].strip() or "Unknown"

    parent = path.parent.name
    return "Unknown", parent


def infer_form(path: Path, root: Path) -> str:
    rel = path.relative_to(root)
    parts = rel.parts
    if len(parts) >= 4:
        form = parts[-3].strip().lower()
        if form in {"in-ear", "over-ear", "earbud", "earbuds", "on-ear"}:
            return form
    return "unknown"


def parse_filters_and_preamp(text: str, stats: Stats) -> tuple[Optional[float], list[PeqFilter]]:
    preamp_match = PREAMP_RE.search(text)
    preamp = float(preamp_match.group(1)) if preamp_match else None

    filters: list[PeqFilter] = []
    for match in FILTER_RE.finditer(text):
        mapped_type = TYPE_MAP.get(match.group("type").upper())
        if mapped_type not in SUPPORTED_TYPES:
            stats.unsupported_filters += 1
            continue

        fc = float(match.group("fc"))
        gain = float(match.group("gain"))
        q = float(match.group("q"))

        # Conservative numeric validation.
        if not (0.0 < fc <= 24000.0):
            continue
        if not (0.0 < q <= 20.0):
            continue
        if not (-30.0 <= gain <= 30.0):
            continue

        filters.append(PeqFilter(type=mapped_type, fc_hz=round(fc, 4), q=round(q, 4), gain_db=round(gain, 4)))

    return preamp, filters


def parse_profile(file_path: Path, root: Path, stats: Stats) -> Optional[Profile]:
    stats.scanned += 1
    try:
        text = file_path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        stats.skipped += 1
        stats.skip_reasons["read_error"] += 1
        return None

    manufacturer, name = infer_manufacturer_and_name(file_path, root)
    form = infer_form(file_path, root)
    preamp, filters = parse_filters_and_preamp(text, stats)

    if not manufacturer.strip() or not name.strip():
        stats.skipped += 1
        stats.skip_reasons["missing_name"] += 1
        return None

    profile = Profile(
        id=stable_id(manufacturer, name),
        manufacturer=manufacturer.strip(),
        name=name.strip(),
        normalized_name=normalize_name(f"{manufacturer} {name}"),
        source="AutoEq",
        form=form,
        preamp_db=round(preamp, 4) if preamp is not None else None,
        peq_filters=filters,
        tags=["headphone"],
        aliases=[],
    )
    return profile


def dedupe_profiles(profiles: list[Profile]) -> list[Profile]:
    by_norm: dict[str, Profile] = {}
    for profile in profiles:
        existing = by_norm.get(profile.normalized_name)
        if existing is None:
            by_norm[profile.normalized_name] = profile
            continue
        # Keep the richer profile with more filters.
        keep = profile if len(profile.peq_filters) > len(existing.peq_filters) else existing
        by_norm[profile.normalized_name] = keep
    return list(by_norm.values())


def apply_filters(
    profiles: list[Profile],
    require_peq: bool,
    manufacturers: Optional[set[str]],
    top_n: Optional[int],
    dedupe: bool,
    stats: Stats,
) -> list[Profile]:
    output: list[Profile] = []
    for profile in profiles:
        if require_peq and not profile.peq_filters:
            stats.skipped += 1
            stats.skip_reasons["missing_peq"] += 1
            continue
        if manufacturers and normalize_name(profile.manufacturer) not in manufacturers:
            stats.skipped += 1
            stats.skip_reasons["manufacturer_filter"] += 1
            continue
        output.append(profile)

    if dedupe:
        before = len(output)
        output = dedupe_profiles(output)
        removed = before - len(output)
        if removed > 0:
            stats.skip_reasons["deduped"] += removed

    output.sort(key=lambda p: (p.manufacturer.lower(), p.name.lower()))

    if top_n is not None:
        output = output[:top_n]

    stats.exported = len(output)
    return output


def write_profiles(path: Path, profiles: list[Profile]) -> None:
    payload = {
        "version": 1,
        "source": "AutoEq",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "profiles": [asdict(p) for p in profiles],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=False), encoding="utf-8")


def write_aliases(path: Path, profiles: list[Profile]) -> None:
    alias_map: dict[str, str] = {}
    for p in profiles:
        for alias in p.aliases:
            norm_alias = normalize_name(alias)
            if norm_alias and norm_alias not in alias_map:
                alias_map[norm_alias] = p.id
    payload = {"version": 1, "source": "AutoEq", "aliases": alias_map}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True), encoding="utf-8")


def print_report(stats: Stats) -> None:
    print(f"total_scanned={stats.scanned}")
    print(f"total_exported={stats.exported}")
    print(f"total_skipped={stats.skipped}")
    print(f"unsupported_filter_count={stats.unsupported_filters}")
    if stats.skip_reasons:
        print("skip_reasons:")
        for reason, count in sorted(stats.skip_reasons.items()):
            print(f"  - {reason}: {count}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Convert AutoEq repository to compact Android JSON assets")
    parser.add_argument("--input", required=True, type=Path, help="Path to AutoEq repository root")
    parser.add_argument("--output", required=True, type=Path, help="Output file path for autoeq_profiles.json")
    parser.add_argument("--aliases-output", type=Path, help="Optional output file path for autoeq_aliases.json")
    parser.add_argument("--require-peq", action="store_true", help="Export only profiles containing valid PEQ data")
    parser.add_argument("--manufacturers", type=str, help="Comma-separated manufacturer allowlist")
    parser.add_argument("--top-n", type=int, help="Export only first N profiles after sorting")
    parser.add_argument("--dedupe", action="store_true", help="Deduplicate near-identical normalized names")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    stats = Stats()

    if not args.input.exists() or not args.input.is_dir():
        raise SystemExit(f"Input path does not exist or is not a directory: {args.input}")

    manufacturer_filter = None
    if args.manufacturers:
        manufacturer_filter = {normalize_name(value) for value in args.manufacturers.split(",") if value.strip()}

    parsed_profiles: list[Profile] = []
    for file_path in discover_parametric_files(args.input):
        profile = parse_profile(file_path=file_path, root=args.input, stats=stats)
        if profile is None:
            continue
        parsed_profiles.append(profile)

    exported = apply_filters(
        profiles=parsed_profiles,
        require_peq=args.require_peq,
        manufacturers=manufacturer_filter,
        top_n=args.top_n,
        dedupe=args.dedupe,
        stats=stats,
    )

    write_profiles(args.output, exported)
    if args.aliases_output:
        write_aliases(args.aliases_output, exported)

    print_report(stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

