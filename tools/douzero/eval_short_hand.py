"""对手剩牌少时「他手里还有没有炸弹」的似然:改前 / 改后各跑一遍,按同一批实机请求对比。

改前 / 改后在不同代码目录各跑一遍本脚本(同一个 `--out`),再用 `--compare` 看 P(炸弹) 怎么变。
P 是按 Advisor.likelihood 加权的分法质量;均匀那一列只按分法里有几种摆法,是改前 / 改后都不变的参照。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... .venv/bin/python eval_short_hand.py --out before.json
  # 改后再跑一次 --out after.json
  .venv/bin/python eval_short_hand.py --compare before.json after.json
"""
import argparse
import json
import sys
import time
from collections import Counter
from itertools import combinations

import endgame
import server
import test_server
from server import Advisor, DECK, SEATS, WEIGHTS, cards, deal, minus, resolve

# 15:06 精英场那两条 /act 原样请求:我是地主,下家最后 4 张是 4 4 4 4
LIVE_HAND = ["BJ", "SJ", "2", "K", "K", "7", "7", "5", "5", "5"]
LIVE_BOTTOM = ["7", "K"]
LIVE_BEFORE_J = [["3", "8", "8", "8"], [], ["10", "10", "10", "7"], ["Q", "A", "A", "A"], [], [],
                 ["9", "9"], ["2", "2"], [], [], ["9", "8", "7", "6", "5"], [], [],
                 ["K", "Q", "J", "10", "9"], []]
LIVE_AFTER_J = LIVE_BEFORE_J + [[], ["J"], []]
LIVE_AUCTION = {"double": {"landlord_down": False, "landlord_up": False}}


def positions():
    """(名字, 实机请求, 说明)。炸弹那两条来自 2026-11-19 15:06 精英场,其余从 test_server 的实机请求里取。"""
    return [
        ("15:08:02 下家 4 张(实际 4 4 4 4)", dict(
            seat="landlord", hand=LIVE_HAND, bottom=LIVE_BOTTOM, history=LIVE_AFTER_J,
            auction=LIVE_AUCTION), "炸弹"),
        ("15:07:50 下家 5 张(实际 4 4 4 4 + J)", dict(
            seat="landlord", hand=LIVE_HAND, bottom=LIVE_BOTTOM, history=LIVE_BEFORE_J,
            auction=LIVE_AUCTION), "炸弹"),
        ("12:14:32 地主下家 5 张", test_server.EndgameBudgetTest.LIVE, "没炸"),
        ("10:53:07 地主 4 张(实际 2 2)", test_server.BombRestraintTest.BOMB, "没炸"),
        ("10:53:14 地主 4 张(实际 2 2)", test_server.BombRestraintTest.FOLLOW_UP, "没炸"),
        ("12:58:35 地主 5 张", test_server.FinishInTwoBombTest.LIVE, "没炸"),
        ("21:10:00 地主 5 张(实际 2 2 K Q SJ)", test_server.CloseCallRolloutTest.TYPES, "没炸"),
    ]


def enemy_splits(position):
    """把一条请求拆成:最短的那家对手的所有剩牌、另一家拿剩下的。地主的底牌还没打出去时留在地主手里。"""
    seat = position["seat"]
    hand = cards(position["hand"])
    bottom = cards(position.get("bottom", []))
    history = resolve(hand, [[str(c) if str(c).startswith("?") else cards([c])[0] for c in m]
                             for m in position["history"]])
    start = deal(seat, hand, bottom, history)
    played = {s: [] for s in SEATS}
    for i, move in enumerate(history):
        played[SEATS[i % 3]] += move
    left = {s: len(start[s]) - len(played[s]) for s in SEATS}
    current = {s: minus(start[s], played[s]) for s in SEATS}
    unseen = sorted(minus(DECK, current[seat] + sum(played.values(), [])))
    others = [s for s in SEATS if s != seat]
    short = min(others, key=lambda s: (left[s], SEATS.index(s)))
    far = others[0] if others[1] == short else others[1]
    kept = ()
    if seat != "landlord" and short == "landlord":
        kept = tuple(sorted(((Counter(bottom) - Counter(played["landlord"])) & Counter(unseen)).elements()))
    pool = list((Counter(unseen) - Counter(kept)).elements())
    need = left[short] - len(kept)
    if need < 0 or need + left[far] != len(pool):
        return None
    ways = Counter()
    for picked in combinations(range(len(pool)), need):
        chosen = set(picked)
        ha = tuple(sorted(kept + tuple(pool[i] for i in picked)))
        hb = tuple(sorted(pool[i] for i in range(len(pool)) if i not in chosen))
        ways[(ha, hb)] += 1
    return seat, history, short, far, left, current, ways, position.get("auction")


def has_bomb(hand):
    counts = Counter(hand)
    return any(rank < 20 and n >= 4 for rank, n in counts.items()) or (counts[20] and counts[30])


def mass(ways, likes, counted):
    """[likes] 里面「最短对手手里有炸弹」占多少;[counted] 为假时每种不同的剩牌算一个假设。"""
    total = bomb = 0.0
    for ((ha, _), count), like in zip(ways.items(), likes):
        weight = (count if counted else 1.0) * like
        total += weight
        if has_bomb(ha):
            bomb += weight
    return bomb / total if total else 0.0


def measure(position, advisor):
    parsed = enemy_splits(position)
    if parsed is None:
        raise SystemExit(f"枚举不完: {position['seat']}")
    seat, history, short, far, left, current, ways, raw_auction = parsed
    auction = server.parse_auction(raw_auction)
    splits = [{seat: tuple(current[seat]), short: ha, far: hb} for (ha, hb) in ways]
    uniform = mass(ways, [1.0] * len(ways), True)
    started = time.monotonic()
    # 残局 / 原来的路径:两家对手的出牌都算,按摆法数加权
    full = advisor.likelihood(history, seat, auction)(splits, time.monotonic() + 120)
    # 现场模拟的短牌路径:只看牌最少那家的出牌,每种剩牌算一个假设
    short_likes = advisor.likelihood(history, seat, None, seats=[short])(splits, time.monotonic() + 120)
    elapsed = time.monotonic() - started
    return {
        "seat": seat,
        "short_seat": short,
        "cards_left": left[short],
        "splits": len(ways),
        "full": mass(ways, full, True),
        "short_mass": mass(ways, short_likes, False),
        "uniform": uniform,
        "elapsed": elapsed,
    }


def run(out):
    advisor = Advisor(WEIGHTS, rollout=False)
    rows = []
    for name, position, kind in positions():
        result = measure(position, advisor)
        result.update(name=name, kind=kind)
        rows.append(result)
        print(f"{name:38s} 剩 {result['cards_left']} 张 分法 {result['splits']:5d}  "
              f"full={result['full']:.4f} short={result['short_mass']:.4f} "
              f"(均匀 {result['uniform']:.4f})  {result['elapsed']:.2f}s", flush=True)
    with open(out, "w") as f:
        json.dump({"rows": rows}, f, ensure_ascii=False, indent=1)
    print(f"-> {out}")


def compare(before_path, after_path):
    with open(before_path) as f:
        before = {row["name"]: row for row in json.load(f)["rows"]}
    with open(after_path) as f:
        after = {row["name"]: row for row in json.load(f)["rows"]}
    if set(before) != set(after):
        raise SystemExit("两份结果对不上")
    print(f"{'请求':40s} {'剩':>2s} {'改前':>7s} {'改后full':>8s} {'改后short':>9s} {'均匀':>7s}  类别")
    for name in after:
        old, new = before[name], after[name]
        was = old.get("full", old.get("weighted", 0.0))
        full = new.get("full", new.get("weighted", 0.0))
        print(f"{name:40s} {new['cards_left']:2d} {was:7.4f} {full:8.4f} {new['short_mass']:9.4f} "
              f"{new['uniform']:7.4f}  {new['kind']}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="eval_short_hand.json")
    parser.add_argument("--compare", nargs=2, metavar=("BEFORE", "AFTER"))
    args = parser.parse_args()
    if args.compare:
        compare(*args.compare)
    else:
        run(args.out)


if __name__ == "__main__":
    sys.exit(main())
