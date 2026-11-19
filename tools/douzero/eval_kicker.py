"""「换带牌」规则改前 / 改后的配对自对弈评测:同一副牌两边各打一遍,主角按当前代码走完整顾问,
另外两家 plain DouZero。改前 / 改后在不同代码目录各跑一遍本脚本、同一个 --seed,再用 --combine 按 (seed, seat) 配对。

金额按斗地主:主角赢 +2^k、输 −2^k,k 是这一局的炸弹 / 王炸个数。每副牌两边共用发牌,差别只来自带牌规则。
同时统计三带一 / 飞机带单被规则改写的次数(swap_high_kickers 返回值与原选择不同)。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... nice -n 15 .venv/bin/python eval_kicker.py --deals 600 --workers 3 --out after.json
  # 改前那份在旧代码目录里跑同一个脚本、同一个 --seed,得到 before.json
  .venv/bin/python eval_kicker.py --combine before.json after.json
  # 冒烟:--deals 12 --workers 2
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

import server
from server import Advisor, DECK, SEATS, WEIGHTS

_ADVISOR = None
_SWAP = server.Agent.swap_high_kickers
_HITS = {"kickers": 0, "swaps": 0}


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS)
    return _ADVISOR


def counted_swap(self, infoset, move, ok):
    """数这一手有没有三带一 / 飞机带单的带牌,以及规则有没有把它换掉。"""
    if server.single_kickers(move):
        _HITS["kickers"] += 1
    result = _SWAP(self, infoset, move, ok)
    if sorted(result) != sorted(move):
        _HITS["swaps"] += 1
    return result


# macOS 默认 spawn:子进程重新 import 本模块,必须在这里打桩,main() 里的改动传不过去
server.Agent.swap_high_kickers = counted_swap


class Hero:
    """主角:每一手都走完整顾问(残局搜索 + 两手出完 + 现场模拟)。"""

    def __init__(self, seat):
        self.agent = advisor().agents[seat]
        self.seat = seat

    def act(self, infoset):
        if infoset.player_position != self.seat:
            raise RuntimeError(f"主角色不对: {infoset.player_position} != {self.seat}")
        return sorted(self.agent.act(infoset))


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
    before = dict(_HITS)
    while not env.game_over:
        env.step()
    k = env.bomb_num
    landlord_win = bool(env.num_wins["landlord"])
    hero_win = landlord_win if seat == "landlord" else not landlord_win
    return {
        "seed": seed,
        "seat": seat,
        "win": int(hero_win),
        "amount": (2 ** k) if hero_win else -(2 ** k),
        "bombs": k,
        "kickers": _HITS["kickers"] - before["kickers"],
        "swaps": _HITS["swaps"] - before["swaps"],
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
            "kickers": sum(r["kickers"] for r in subset),
            "swaps": sum(r["swaps"] for r in subset),
        }
    return {
        "n": len(rows),
        "amount": sum(r["amount"] for r in rows),
        "wins": sum(r["win"] for r in rows),
        "kickers": sum(r["kickers"] for r in rows),
        "swaps": sum(r["swaps"] for r in rows),
        "by_seat": by_seat,
    }


def print_summary(rows):
    s = summarize(rows)
    print(f"\n对局 {s['n']} 副(主角地主 / 上家 / 下家轮流)")
    print(f"赢 {s['wins']} 副、总额 {s['amount']:+d}")
    print(f"三带一 / 飞机带单 {s['kickers']} 手,规则换掉 {s['swaps']} 手")
    for seat, d in s["by_seat"].items():
        print(f"  {seat:13s} {d['n']:4d} 副  总额 {d['amount']:+6d}  赢 {d['wins']:4d}  "
              f"三带一 {d['kickers']:4d} 手 / 换掉 {d['swaps']:4d} 手")
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
    print(f"三带一 / 飞机带单:改前 {sum(o['kickers'] for o, _ in pairs)} 手、换掉 "
          f"{sum(o['swaps'] for o, _ in pairs)} 手;改后 {sum(n['kickers'] for _, n in pairs)} 手、换掉 "
          f"{sum(n['swaps'] for _, n in pairs)} 手")
    print(f"金额变化的对局 {sum(1 for d in diffs if d)} 副")
    for seat in SEATS:
        subset = [(o, n) for o, n in pairs if o["seat"] == seat]
        seat_diffs = [n["amount"] - o["amount"] for o, n in subset]
        m, lo, hi = ci95(seat_diffs)
        print(f"  {seat:13s} {len(subset):4d} 副  改前 {sum(o['amount'] for o, _ in subset):+6d} / "
              f"改后 {sum(n['amount'] for _, n in subset):+6d}  平均差 {m:+.3f} [{lo:+.3f},{hi:+.3f}]  "
              f"换掉 {sum(o['swaps'] for o, _ in subset)} -> {sum(n['swaps'] for _, n in subset)}")
    return {"pairs": len(pairs), "diff": [sum(diffs), mean, low, high],
            "swaps": [sum(o["swaps"] for o, _ in pairs), sum(n["swaps"] for _, n in pairs)]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=420,
                        help="对局数,偶数副主角当地主、奇数副轮换两个农民座位;420 副是地主 / 农民各 210 副")
    parser.add_argument("--workers", type=int, default=min(3, os.cpu_count() or 1))
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--combine", nargs=2, metavar=("BEFORE", "AFTER"), help="配对两份 JSON,不跑牌局")
    parser.add_argument("--out", default="eval_kicker.json")
    args = parser.parse_args()

    if args.combine:
        combine(*args.combine)
        return

    started = time.time()
    rng = random.Random(args.seed)
    farmers = ["landlord_down", "landlord_up"]
    jobs = [(rng.randrange(10 ** 9), "landlord" if i % 2 == 0 else farmers[(i // 2) % 2])
            for i in range(args.deals)]
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
