"""残局「似然下限 + 炸弹翻倍保守」的自对弈评测:同一副牌,主角分别用改前 / 改后的顾问打到底,配对比较赢的钱。

主角每一手都走完整顾问(残局搜索 + 似然 + 现场模拟),另外两家按 DouZero 分数最高出牌;主角地主、地主上、
地主下三个座位轮着来。金额按斗地主算:赢 +2^k、输 −2^k,k 是这一局的炸弹 / 王炸个数。

改前 = 关掉这次的两条改动:LIKELIHOOD_FLOOR = 0(逐分法 exp 权重,原来的样子)且 ENDGAME_BOMB_CHECK = False。
改后 = 现在的常量(或 --floor 指定试算)。同一副牌两边共用发牌,差别只来自这两条规则。

注意:对手是 plain DouZero,几乎不拆炸弹、也从不做残局搜索,自对弈会低估这条修正的好处——
实机那两手的损失(地主拆过 2 2 2 2 还握着 2 2)在对手身上很难复现。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... .venv/bin/python eval_bomb_restraint.py --deals 240 --workers 3
  # --deals 是总副数(三个座位轮着当主角);冒烟:--deals 12 --workers 2 --floor 0.05
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

_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS)
    return _ADVISOR


class Hero:
    """主角:每一手都走完整 Agent.act(残局搜索带 likelihood),记下耗时和炸弹决定被改掉的次数。"""

    def __init__(self, seat):
        self.agent = advisor().agents[seat]
        self.seat = seat
        self.ms = 0.0
        self.cpu_ms = 0.0
        self.moves = 0
        self.max_ms = 0.0
        self.max_cpu_ms = 0.0
        self.over_budget = 0
        self.vetoes = 0

    def act(self, infoset):
        if infoset.player_position != self.seat:
            raise RuntimeError(f"主角色不对: {infoset.player_position} != {self.seat}")
        started = time.perf_counter()
        cpu_started = time.process_time()
        report = {}
        history = [list(m) for m in infoset.card_play_action_seq]
        move = self.agent.act(infoset, report, likelihood=advisor().likelihood(history, self.seat, None))
        elapsed = (time.perf_counter() - started) * 1000
        cpu_elapsed = (time.process_time() - cpu_started) * 1000
        self.ms += elapsed
        self.cpu_ms += cpu_elapsed
        self.moves += 1
        self.max_ms = max(self.max_ms, elapsed)
        self.max_cpu_ms = max(self.max_cpu_ms, cpu_elapsed)
        if elapsed > 3500 and cpu_elapsed < 3500:
            # 评测进程被 SIGSTOP 过,墙钟不算数;CPU 时间才是这一手真实的计算耗时
            self.over_budget += 1
        if report.get("bomb_veto"):
            self.vetoes += 1
        # 线上 Advisor.act 会用 resolve 把历史按点数排好(swap_high_kickers 可能返回没排序的一手);
        # 这里在牌局里直接走 Agent,所以自己排一下,免得 Replay 拿没排序的一手去比合规出牌
        return sorted(move)


def play(seed, seat, on, floor):
    """从同一副牌打一局:主角按 [on] 决定用改后还是改前的规则,另外两家 plain DouZero。"""
    import server

    server.LIKELIHOOD_FLOOR = floor if on else 0.0
    server.ENDGAME_BOMB_CHECK = on
    deck = list(DECK)
    random.Random(seed).shuffle(deck)
    hero = Hero(seat)
    players = {s: hero if s == seat else advisor().plain[s] for s in SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]),
                        "three_landlord_cards": sorted(deck[17:20])})
    while not env.game_over:
        env.step()
    k = env.bomb_num
    win = bool(env.num_wins["landlord" if seat == "landlord" else "farmer"])
    return {
        "win": int(win),
        "amount": (2 ** k) if win else -(2 ** k),
        "bombs": k,
        "calls": hero.moves,
        "ms": hero.ms,
        "cpu_ms": hero.cpu_ms,
        "max_ms": hero.max_ms,
        "max_cpu_ms": hero.max_cpu_ms,
        "paused": hero.over_budget,
        "vetoes": hero.vetoes,
    }


def work(job):
    seed, seat, floor = job
    try:
        # 交替先跑哪一边,抵消同一进程里第一次前向的冷启动
        if seed % 2:
            off = play(seed, seat, False, floor)
            on = play(seed, seat, True, floor)
        else:
            on = play(seed, seat, True, floor)
            off = play(seed, seat, False, floor)
    except Exception as error:
        print(f"FAIL seed={seed} seat={seat} {type(error).__name__}: {error}", flush=True)
        raise
    return {"seed": seed, "seat": seat, "on": on, "off": off}


def ci95(diffs):
    n = len(diffs)
    if n == 0:
        return 0.0, 0.0, 0.0
    mean = statistics.mean(diffs)
    sd = statistics.stdev(diffs) if n > 1 else 0.0
    half = 1.96 * sd / math.sqrt(n)
    return mean, mean - half, mean + half


def summarize(rows):
    diffs = [r["on"]["amount"] - r["off"]["amount"] for r in rows]
    mean, low, high = ci95(diffs)
    by_seat = {}
    for seat in SEATS:
        subset = [r for r in rows if r["seat"] == seat]
        by_seat[seat] = {
            "n": len(subset),
            "on_amount": sum(r["on"]["amount"] for r in subset),
            "off_amount": sum(r["off"]["amount"] for r in subset),
            "diff": ci95([r["on"]["amount"] - r["off"]["amount"] for r in subset]),
            "wins": (sum(r["on"]["win"] for r in subset), sum(r["off"]["win"] for r in subset)),
            "vetoes": sum(r["on"]["vetoes"] for r in subset),
        }
    return {
        "n": len(rows),
        "on_amount": sum(r["on"]["amount"] for r in rows),
        "off_amount": sum(r["off"]["amount"] for r in rows),
        "on_wins": sum(r["on"]["win"] for r in rows),
        "off_wins": sum(r["off"]["win"] for r in rows),
        "diff": [mean, low, high],
        "changed_amount": len([r for r in rows if r["on"]["amount"] != r["off"]["amount"]]),
        "vetoes": sum(r["on"]["vetoes"] for r in rows),
        "on_max_ms": max(r["on"]["max_ms"] for r in rows),
        "off_max_ms": max(r["off"]["max_ms"] for r in rows),
        "on_max_cpu_ms": max(r["on"]["max_cpu_ms"] for r in rows),
        "off_max_cpu_ms": max(r["off"]["max_cpu_ms"] for r in rows),
        "paused_moves": sum(r["on"]["paused"] + r["off"]["paused"] for r in rows),
        "on_ms_per_move": sum(r["on"]["ms"] for r in rows) / max(1, sum(r["on"]["calls"] for r in rows)),
        "off_ms_per_move": sum(r["off"]["ms"] for r in rows) / max(1, sum(r["off"]["calls"] for r in rows)),
        "on_cpu_per_move": sum(r["on"]["cpu_ms"] for r in rows) / max(1, sum(r["on"]["calls"] for r in rows)),
        "off_cpu_per_move": sum(r["off"]["cpu_ms"] for r in rows) / max(1, sum(r["off"]["calls"] for r in rows)),
        "by_seat": by_seat,
    }


def print_summary(rows):
    s = summarize(rows)
    mean, low, high = s["diff"]
    print(f"\n对局 {s['n']} 副(同一副牌改前 / 改后各一遍,配对)")
    print(f"改后:赢 {s['on_wins']} 副、总额 {s['on_amount']:+d}")
    print(f"改前:赢 {s['off_wins']} 副、总额 {s['off_amount']:+d}")
    print(f"配对差:总额 {s['on_amount'] - s['off_amount']:+d}、每副平均 {mean:+.3f} "
          f"(95% CI [{low:+.3f},{high:+.3f}])")
    print(f"金额变化的对局 {s['changed_amount']} 副;残局炸弹决定被改掉 {s['vetoes']} 次")
    print(f"每手最长耗时(CPU):改后 {s['on_max_cpu_ms']:.0f}ms / 改前 {s['off_max_cpu_ms']:.0f}ms"
          f"(总预算 3.5 秒);墙钟最长 改后 {s['on_max_ms']:.0f}ms / 改前 {s['off_max_ms']:.0f}ms"
          f",其中被 SIGSTOP 拉长的 {s['paused_moves']} 手")
    for seat, d in s["by_seat"].items():
        m, lo, hi = d["diff"]
        print(f"  {seat:13s} {d['n']:4d} 副  改后 {d['on_amount']:+6d} / 改前 {d['off_amount']:+6d}"
              f"  胜场 {d['wins'][0]}/{d['wins'][1]}  平均差 {m:+.3f} [{lo:+.3f},{hi:+.3f}]"
              f"  改掉炸弹 {d['vetoes']} 次")
    print(f"主角每手平均耗时:改后 {s['on_cpu_per_move']:.1f}ms CPU / 改前 {s['off_cpu_per_move']:.1f}ms CPU")
    return s


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=240, help="总副数,三个座位轮着当主角")
    parser.add_argument("--workers", type=int, default=min(3, os.cpu_count() or 1))
    parser.add_argument("--floor", type=float, default=None, help="试算用的似然下限;不传就用 server 里的常量")
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--out", default="eval_bomb_restraint.json")
    args = parser.parse_args()
    import server
    floor = server.LIKELIHOOD_FLOOR if args.floor is None else args.floor

    started = time.time()
    rng = random.Random(args.seed)
    seats = SEATS
    jobs = [(rng.randrange(10 ** 9), seats[i % len(seats)], floor) for i in range(args.deals)]
    rows = []
    if args.workers > 1:
        chunksize = max(1, len(jobs) // (args.workers * 4))
        with mp.Pool(args.workers) as pool:
            for i, row in enumerate(pool.imap_unordered(work, jobs, chunksize=chunksize), 1):
                rows.append(row)
                if i % 5 == 0:
                    print(f"{i}/{len(jobs)} 副, {time.time() - started:.0f}s", flush=True)
    else:
        for i, job in enumerate(jobs, 1):
            rows.append(work(job))
            if i % 5 == 0:
                print(f"{i}/{len(jobs)} 副, {time.time() - started:.0f}s", flush=True)

    rows.sort(key=lambda r: r["seed"])
    summary = print_summary(rows)
    with open(args.out, "w") as out:
        json.dump({"args": {**vars(args), "floor": floor}, "rows": rows, "summary": summary},
                  out, ensure_ascii=False, indent=1)
    print(f"\n{len(rows)} 副 -> {args.out}, {time.time() - started:.0f}s")


if __name__ == "__main__":
    mp.freeze_support()
    main()
