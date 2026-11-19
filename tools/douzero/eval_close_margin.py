"""CLOSE_MARGIN 0.1 / 0.15 的配对自对弈评测:同一副牌,主角分别用两种门槛打到底,配对比较赢的钱。

主角走完整顾问(残局搜索 + 现场模拟),座位轮流是地主 / 地主上家 / 地主下家;另外两家按 DouZero 分数最高出牌。
同一副牌两边共用发牌和座位,差别只来自 CLOSE_MARGIN。金额按斗地主算:赢 +2^k、输 −2^k,k 是这一局的炸弹 / 王炸个数。
顺带记主角每一手的决策耗时和进现场模拟的次数:门槛放宽后多出来的模拟不能把一手拖过线上一手 3.5 秒。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... .venv/bin/python eval_close_margin.py --deals 200 --workers 5
  # --deals 是牌局数,每副打两遍(0.1 / 0.15);冒烟:--deals 6 --workers 3
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

from server import Advisor, DECK, SEATS, WEIGHTS

LOW, HIGH = 0.1, 0.15
# 一手总预算 3.5 秒,超过 10 秒只可能是评测机被挂起(本机还有别的评测 / 线上服务在跑),不算算力:
# 单独计数,不污染最长耗时。
STALL_MS = 10000
_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS)
    return _ADVISOR


class Hero:
    """主角:每一手都走完整顾问(现场模拟默认开着),记下每手耗时和进模拟的次数。"""

    def __init__(self, seat):
        self.agent = advisor().agents[seat]
        self.seat = seat
        self.ms = []
        self.rollouts = 0
        self.effective = 0
        self.skipped = 0

    def act(self, infoset):
        if infoset.player_position != self.seat:
            raise RuntimeError(f"主角色不对: {infoset.player_position} != {self.seat}")
        report = {}
        started = time.perf_counter()
        move = self.agent.act(infoset, report)
        self.ms.append((time.perf_counter() - started) * 1000)
        if "rollout" in report:
            self.rollouts += 1
            # 局数不到下限的模拟不改出牌,是个空转;单独记下来,门槛放宽后多出来的模拟有多少是这个
            if report["rollout"]["games"] >= report["rollout"]["min_games"]:
                self.effective += 1
        if report.get("rollout_skipped"):
            self.skipped += 1
        # 线上 Advisor.act 会用 resolve 把历史按点数排好(swap_high_kickers 可能返回没排序的一手);
        # 这里在牌局里直接走 Agent,所以自己排一下,免得 Replay 拿没排序的一手去比合规出牌
        return sorted(move)


def play(seed, seat, margin):
    """从同一副牌打一局:主角按 [margin] 决定什么时候现场模拟,另外两家 plain DouZero。"""
    import server

    original = server.CLOSE_MARGIN
    deck = list(DECK)
    random.Random(seed).shuffle(deck)
    hero = Hero(seat)
    players = {s: hero if s == seat else advisor().plain[s] for s in SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]),
                        "three_landlord_cards": sorted(deck[17:20])})
    server.CLOSE_MARGIN = margin
    try:
        while not env.game_over:
            env.step()
    finally:
        server.CLOSE_MARGIN = original
    k = env.bomb_num
    amount = 2 ** k
    landlord_won = bool(env.num_wins["landlord"])
    hero_won = landlord_won if seat == "landlord" else not landlord_won
    return {
        "win": int(hero_won),
        "amount": amount if hero_won else -amount,
        "bombs": k,
        "calls": len(hero.ms),
        "ms": sum(hero.ms),
        "max_ms": max(hero.ms, default=0.0),
        "ms_each": [round(ms, 1) for ms in hero.ms],
        "rollouts": hero.rollouts,
        "effective": hero.effective,
        "skipped": hero.skipped,
    }


def work(job):
    seed, seat = job
    try:
        # 交替先跑哪一边,抵消同一进程里第一次前向的冷启动
        if seed % 2:
            low = play(seed, seat, LOW)
            high = play(seed, seat, HIGH)
        else:
            high = play(seed, seat, HIGH)
            low = play(seed, seat, LOW)
    except Exception as error:
        print(f"FAIL seed={seed} seat={seat} {type(error).__name__}: {error}", flush=True)
        raise
    return {"seed": seed, "seat": seat, "low": low, "high": high}


def ci95(diffs):
    n = len(diffs)
    if n == 0:
        return 0.0, 0.0, 0.0
    mean = statistics.mean(diffs)
    sd = statistics.stdev(diffs) if n > 1 else 0.0
    half = 1.96 * sd / math.sqrt(n)
    return mean, mean - half, mean + half


def percentile(sorted_values, q):
    if not sorted_values:
        return 0.0
    index = min(len(sorted_values) - 1, int(round(q * (len(sorted_values) - 1))))
    return sorted_values[index]


def side(rows, key):
    calls = sum(r[key]["calls"] for r in rows)
    each = sorted(ms for r in rows for ms in r[key]["ms_each"])
    steady = [ms for ms in each if ms <= STALL_MS]
    return {
        "amount": sum(r[key]["amount"] for r in rows),
        "wins": sum(r[key]["win"] for r in rows),
        "calls": calls,
        "rollouts": sum(r[key]["rollouts"] for r in rows),
        "rollout_share": sum(r[key]["rollouts"] for r in rows) / max(1, calls),
        "effective": sum(r[key]["effective"] for r in rows),
        "effective_share": sum(r[key]["effective"] for r in rows) / max(1, calls),
        "skipped": sum(r[key]["skipped"] for r in rows),
        "ms_per_move": sum(each) / max(1, calls),
        "ms_p50": percentile(steady, 0.5),
        "ms_p95": percentile(steady, 0.95),
        "max_ms": max(steady, default=0.0),
        "over_3500": sum(1 for ms in steady if ms > 3500),
        "stalled": len(each) - len(steady),
    }


def summarize(rows):
    diffs = [r["high"]["amount"] - r["low"]["amount"] for r in rows]
    mean, low_ci, high_ci = ci95(diffs)
    by_seat = {}
    for seat in SEATS:
        subset = [r for r in rows if r["seat"] == seat]
        by_seat[seat] = {
            "n": len(subset),
            "diff": ci95([r["high"]["amount"] - r["low"]["amount"] for r in subset]),
            "low": side(subset, "low"),
            "high": side(subset, "high"),
        }
    return {
        "n": len(rows),
        "low": side(rows, "low"),
        "high": side(rows, "high"),
        "diff": [mean, low_ci, high_ci],
        "diff_total": sum(diffs),
        "changed_amount": sum(1 for d in diffs if d),
        "by_seat": by_seat,
    }


def print_summary(summary):
    lo, hi = summary["low"], summary["high"]
    mean, low_ci, high_ci = summary["diff"]
    print(f"\n对局 {summary['n']} 副(每副 CLOSE_MARGIN {LOW} / {HIGH} 各一遍,配对)")
    print(f"{LOW}:赢 {lo['wins']} 副、总额 {lo['amount']:+d}")
    print(f"{HIGH}:赢 {hi['wins']} 副、总额 {hi['amount']:+d}")
    print(f"配对差:{HIGH} − {LOW} 总额 {summary['diff_total']:+d}、每副平均 {mean:+.3f} "
          f"(95% CI [{low_ci:+.3f},{high_ci:+.3f}]),金额不同的对局 {summary['changed_amount']} 副")
    print(f"主角决策 {lo['calls']} 手:现场模拟 {lo['rollouts']} 手 ({lo['rollout_share']:.1%}),"
          f"其中局数够线 {lo['effective']} 手 ({lo['effective_share']:.1%}),因时间不够跳过 {lo['skipped']} 手")
    print(f"       {hi['calls']} 手:现场模拟 {hi['rollouts']} 手 ({hi['rollout_share']:.1%},"
          f"多 {hi['rollout_share'] - lo['rollout_share']:+.1%}),其中局数够线 {hi['effective']} 手 "
          f"({hi['effective_share']:.1%}),因时间不够跳过 {hi['skipped']} 手")
    print(f"主角每手耗时:{LOW} 平均 {lo['ms_per_move']:.0f}ms、中位 {lo['ms_p50']:.0f}ms、"
          f"p95 {lo['ms_p95']:.0f}ms、最长 {lo['max_ms']:.0f}ms、超 3.5 秒 {lo['over_3500']} 手、"
          f"机器卡顿 {lo['stalled']} 手;{HIGH} 平均 {hi['ms_per_move']:.0f}ms、中位 {hi['ms_p50']:.0f}ms、"
          f"p95 {hi['ms_p95']:.0f}ms、最长 {hi['max_ms']:.0f}ms、超 3.5 秒 {hi['over_3500']} 手、"
          f"机器卡顿 {hi['stalled']} 手(线上一手总预算 3.5 秒)")
    for seat, d in summary["by_seat"].items():
        m, a, b = d["diff"]
        print(f"  {seat:13s} {d['n']:4d} 副  总额 {d['low']['amount']:+5d} -> {d['high']['amount']:+5d}"
              f"  平均差 {m:+.3f} [{a:+.3f},{b:+.3f}]  模拟 {d['low']['rollout_share']:.1%} -> "
              f"{d['high']['rollout_share']:.1%}  最长 {d['high']['max_ms']:.0f}ms")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=200, help="牌局数,每副打两遍")
    parser.add_argument("--workers", type=int, default=5, help="评测进程数,别超过 6(本机还有线上服务)")
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--seat", choices=("landlord", "up", "down", "all"), default="all")
    parser.add_argument("--out", default="eval_close_margin.json")
    args = parser.parse_args()

    started = time.time()
    rng = random.Random(args.seed)
    seats = {"landlord": ["landlord"], "up": ["landlord_up"], "down": ["landlord_down"],
             "all": SEATS}[args.seat]
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

    rows.sort(key=lambda r: r["seed"])
    summary = summarize(rows)
    print_summary(summary)
    with open(args.out, "w") as out:
        json.dump({"args": vars(args), "rows": rows, "summary": summary}, out, ensure_ascii=False, indent=1)
    print(f"\n{len(rows)} 副 -> {args.out}, {time.time() - started:.0f}s")


if __name__ == "__main__":
    mp.freeze_support()
    main()
