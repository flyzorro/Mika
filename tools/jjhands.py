#!/usr/bin/env python3
"""Summarize JJ JSONL files or directories copied from files/jjhands/."""

import argparse
from collections import Counter
import json
from pathlib import Path
from statistics import mean
import sys


def load(paths):
    seen = set()
    for path in paths:
        files = sorted(path.rglob("*.jsonl")) if path.is_dir() else [path]
        for file in files:
            with file.open(encoding="utf-8") as stream:
                for number, line in enumerate(stream, 1):
                    try:
                        row = json.loads(line)
                        if not isinstance(row, dict) or not isinstance(row.get("end"), str):
                            raise ValueError("missing hand end reason")
                        key = row["session"], row["hand"]
                        if key not in seen:
                            seen.add(key)
                            yield row
                    except (ValueError, KeyError, TypeError) as exc:
                        print(f"{file}:{number}: skipped invalid record ({exc})", file=sys.stderr)


def rate(rows):
    known = [r["win"] for r in rows if type(r.get("win")) is bool]
    wins = sum(known)
    value = f"{wins / len(known):.1%}" if known else "n/a"
    return f"{value} ({wins}/{len(known)}, {len(rows) - len(known)} unknown)"


def summarize(records):
    rows = list(records)
    completed = [r for r in rows if r["end"] not in ("redeal", "stopped", "replaced")]
    roles = [r for r in completed if r.get("role") is not None]
    landlords = sum(r["role"] == "landlord" for r in roles)
    def bid(row):
        return (row.get("bid") or {}).get("level")

    bids = Counter(bid(r) for r in rows)
    print(f"Hands: {len(completed)} completed, {len(rows)} deals observed")
    print("End reasons:", dict(sorted(Counter(r["end"] for r in rows).items())))
    print("Overall win rate:", rate(completed))
    for role in ("landlord", "landlord_up", "landlord_down", None):
        print(f"  {role or 'unknown role'}:", rate([r for r in completed if r.get("role") == role]))
    share = f"{landlords / len(roles):.1%}" if roles else "n/a"
    print(f"Landlord rate: {share} ({landlords}/{len(roles)} known roles)")
    print("Bids (all deals):", ", ".join(f"{k if k is not None else 'unknown'}={bids[k]}"
                                          for k in [0, 1, 2, 3, None]))
    print("Win rate when bidding 3:", rate([r for r in completed if bid(r) == 3]))
    factors = [r["multiplier"] for r in completed if r.get("multiplier") is not None]
    average = f"{mean(factors):g}" if factors else "n/a"
    print(f"Average multiplier: {average} ({len(factors)}/{len(completed)} readable)")
    counts, breaks = Counter(), Counter()
    for r in rows:
        counts.update(r.get("failures", {}))
        breaks.update(r.get("tracker_breaks", {}))
    print("Failures (all deals):", dict(sorted(counts.items())))
    print("Tracker breaks (all deals):", dict(sorted(breaks.items())))
    for failed in (True, False):
        group = [r for r in completed if bool(r.get("failures") or r.get("tracker_breaks")) == failed]
        print(f"Win rate {'with' if failed else 'without'} failures:", rate(group))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    summarize(load(parser.parse_args().paths))
