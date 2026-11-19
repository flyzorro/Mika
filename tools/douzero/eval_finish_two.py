"""「两手出完」捷径改前 / 改后的配对自对弈评测:同一副牌两边各打一遍,主角地主 / 农民都走完整顾问,另外两家 plain DouZero。

改前 / 改后在不同代码目录各跑一遍本脚本、同一个 --seed,再用 --combine 把两份 JSON 按 (seed, seat) 配对。
金额按斗地主:主角赢 +2^k、输 −2^k,k 是这一局的炸弹 / 王炸个数。每副牌两边共用发牌,差别只来自捷径。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... .venv/bin/python eval_finish_two.py --deals 210 --workers 3 --out after.json
  # 改前那份在旧代码目录里跑同一个脚本、同一个 --seed,得到 before.json
  .venv/bin/python eval_finish_two.py --combine before.json after.json
  # 冒烟:--deals 30 --workers 2
"""
import argparse
import json
import math
import multiprocessing as mp
import os
import random
import statistics
import time

from douzero.env.game import GameEnv

import endgame
import server
from server import Advisor, DECK, SEATS, WEIGHTS

_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS)
    return _ADVISOR


class Hero:
    """主角:每一手都走完整顾问(残局搜索 + 两手出完 + 现场模拟),记下耗时。"""

    def __init__(self, seat):
        self.agent = advisor().agents[seat]
        self.seat = seat
        self.calls = 0
        self.ms = 0.0
        self.max_ms = 0.0

    def act(self, infoset):
        if infoset.player_position != self.seat:
            raise RuntimeError(f"主角色不对: {infoset.player_position} != {self.seat}")
        started = time.perf_counter()
        move = self.agent.act(infoset)
        elapsed = (time.perf_counter() - started) * 1000
        self.calls += 1
        self.ms += elapsed
        self.max_ms = max(self.max_ms, elapsed)
        return sorted(move)


def play(seed, seat):
    """从同一副牌打一局:主角 [seat] 按当前代码出牌,另外两家 plain DouZero。"""
    deck = list(DECK)
    random.Random(seed).shuffle(deck)
    hero = Hero(seat)
    players = {s: hero if s == seat else advisor().plain[s] for s in SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]),
                        "three_landlord_cards": sorted(deck[17:20])})
    # 捷径触发:finish_in_two 返回了一手(有够安全的两手出完);改前 / 改后都按这个数
    hits = 0
    original = endgame.finish_in_two

    def counted(*args, **kwargs):
        nonlocal hits
        result = original(*args, **kwargs)
        if result is not None:
            hits += 1
        return result

    endgame.finish_in_two = counted
    try:
        while not env.game_over:
            env.step()
    finally:
        endgame.finish_in_two = original
    k = env.bomb_num
    landlord_win = bool(env.num_wins["landlord"])
    hero_win = landlord_win if seat == "landlord" else not landlord_win
    return {
        "seed": seed,
        "seat": seat,
        "win": int(hero_win),
        "amount": (2 ** k) if hero_win else -(2 ** k),
        "bombs": k,
        "calls": hero.calls,
        "ms": hero.ms,
        "max_ms": hero.max_ms,
        "hits": hits,
    }


def work(job):
    seed, seat = job
    try:
        return play(seed, seat)
    except Exception as error:
        print(f"FAIL seed={seed} seat={seat} {type(error).__name__}: {error}", flush=True)
        raise


def ci95(values):
    n = len(values)
    if n == 0:
        return 0.0, 0.0, 0.0
    mean = statistics.mean(values)
    sd = statistics.stdev(values) if n > 1 else 0.0
    half = 1.96 * sd / math.sqrt(n)
    return mean, mean - half, mean + half


def summarize(rows):
    rows = sorted(rows, key=lambda r: (r["seat"], r["seed"]))
    by_seat = {}
    for seat in SEATS:
        subset = [r for r in rows if r["seat"] == seat]
        by_seat[seat] = {
            "n": len(subset),
            "amount": sum(r["amount"] for r in subset),
            "wins": sum(r["win"] for r in subset),
            "hits": sum(r["hits"] for r in subset),
            "ms_per_move": sum(r["ms"] for r in subset) / max(1, sum(r["calls"] for r in subset)),
            "max_ms": max((r["max_ms"] for r in subset), default=0.0),
        }
    return {
        "n": len(rows),
        "amount": sum(r["amount"] for r in rows),
        "wins": sum(r["win"] for r in rows),
        "hits": sum(r["hits"] for r in rows),
        "ms_per_move": sum(r["ms"] for r in rows) / max(1, sum(r["calls"] for r in rows)),
        "max_ms": max((r["max_ms"] for r in rows), default=0.0),
        "by_seat": by_seat,
    }


def print_summary(rows):
    s = summarize(rows)
    print(f"\n对局 {s['n']} 副(主角地主 / 农民都有,每副各打一遍)")
    print(f"赢 {s['wins']} 副、总额 {s['amount']:+d}")
    print(f"捷径触发 {s['hits']} 次")
    print(f"主角每手平均 {s['ms_per_move']:.1f}ms、单手里最长 {s['max_ms']:.1f}ms")
    for seat, d in s["by_seat"].items():
        print(f"  {seat:13s} {d['n']:4d} 副  总额 {d['amount']:+6d}  赢 {d['wins']:4d}  "
              f"捷径 {d['hits']:4d}  每手 {d['ms_per_move']:.1f}ms / 最长 {d['max_ms']:.1f}ms")
    return s


def combine(before_path, after_path):
    with open(before_path) as f:
        before = json.load(f)
    with open(after_path) as f:
        after = json.load(f)
    key = lambda r: (r["seed"], r["seat"])  # noqa: E731
    old = {key(r): r for r in before["rows"]}
    new = {key(r): r for r in after["rows"]}
    if set(old) != set(new):
        raise SystemExit(f"两份对局对不上: 改前 {len(old)} 副、改后 {len(new)} 副")
    pairs = [(old[k], new[k]) for k in sorted(old)]
    diffs = [n["amount"] - o["amount"] for o, n in pairs]
    mean, low, high = ci95(diffs)
    print(f"\n配对 {len(pairs)} 副(同一副牌改前 / 改后各一遍)")
    print(f"改前:赢 {sum(o['win'] for o, _ in pairs)} 副、总额 {sum(o['amount'] for o, _ in pairs):+d}")
    print(f"改后:赢 {sum(n['win'] for _, n in pairs)} 副、总额 {sum(n['amount'] for _, n in pairs):+d}")
    print(f"配对差:总额 {sum(diffs):+d}、每副平均 {mean:+.3f} (95% CI [{low:+.3f},{high:+.3f}])")
    print(f"捷径触发:改前 {sum(o['hits'] for o, _ in pairs)} 次 / 改后 {sum(n['hits'] for _, n in pairs)} 次")
    print(f"金额变化的对局 {sum(1 for d in diffs if d)} 副")
    for seat in SEATS:
        subset = [(o, n) for o, n in pairs if o["seat"] == seat]
        seat_diffs = [n["amount"] - o["amount"] for o, n in subset]
        m, lo, hi = ci95(seat_diffs)
        print(f"  {seat:13s} {len(subset):4d} 副  改前 {sum(o['amount'] for o, _ in subset):+6d} / "
              f"改后 {sum(n['amount'] for _, n in subset):+6d}  平均差 {m:+.3f} [{lo:+.3f},{hi:+.3f}]  "
              f"捷径 {sum(o['hits'] for o, _ in subset)} -> {sum(n['hits'] for _, n in subset)}")
    old_max = max((o["max_ms"] for o, _ in pairs), default=0.0)
    new_max = max((n["max_ms"] for _, n in pairs), default=0.0)
    old_avg = sum(o["ms"] for o, _ in pairs) / max(1, sum(o["calls"] for o, _ in pairs))
    new_avg = sum(n["ms"] for _, n in pairs) / max(1, sum(n["calls"] for _, n in pairs))
    print(f"主角每手平均:改前 {old_avg:.1f}ms / 改后 {new_avg:.1f}ms;单手里最长:改前 {old_max:.1f}ms / 改后 {new_max:.1f}ms")
    return {"pairs": len(pairs), "diff": [sum(diffs), mean, low, high],
            "hits": [sum(o["hits"] for o, _ in pairs), sum(n["hits"] for _, n in pairs)]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=210, help="对局数,主角地主 / 上家 / 下家轮流")
    parser.add_argument("--workers", type=int, default=min(3, os.cpu_count() or 1))
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--shortcut-margin", type=float, default=None,
                        help="临时改 server.SHORTCUT_MARGIN(只改后那份有);不给就用代码里的常量")
    parser.add_argument("--combine", nargs=2, metavar=("BEFORE", "AFTER"), help="配对两份 JSON,不跑牌局")
    parser.add_argument("--out", default="eval_finish_two.json")
    args = parser.parse_args()

    if args.combine:
        combine(*args.combine)
        return

    if args.shortcut_margin is not None and hasattr(server, "SHORTCUT_MARGIN"):
        server.SHORTCUT_MARGIN = args.shortcut_margin

    started = time.time()
    rng = random.Random(args.seed)
    seats = list(SEATS)
    jobs = [(rng.randrange(10 ** 9), seats[i % len(seats)]) for i in range(args.deals)]
    rows = []
    if args.workers > 1:
        chunksize = max(1, len(jobs) // (args.workers * 4))
        with mp.Pool(args.workers) as pool:
            for i, row in enumerate(pool.imap_unordered(work, jobs, chunksize=chunksize), 1):
                rows.append(row)
                if i % 10 == 0:
                    print(f"{i}/{len(jobs)} 副, {time.time() - started:.0f}s", flush=True)
    else:
        for i, job in enumerate(jobs, 1):
            rows.append(work(job))
            if i % 5 == 0:
                print(f"{i}/{len(jobs)} 副, {time.time() - started:.0f}s", flush=True)

    rows.sort(key=lambda r: (r["seat"], r["seed"]))
    summary = print_summary(rows)
    with open(args.out, "w") as out:
        json.dump({"args": vars(args), "rows": rows, "summary": summary}, out, ensure_ascii=False, indent=1)
    print(f"\n{len(rows)} 副 -> {args.out}, {time.time() - started:.0f}s")


if __name__ == "__main__":
    mp.freeze_support()
    main()
