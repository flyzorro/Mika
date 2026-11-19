"""农民压地主规则的自对弈评测:同一副牌,主角农民分别用「有 beat_landlord / 没有」两种顾问打到底,配对比较赢的钱。

主角是农民(默认地主上家与下家各一半),另外两家按 DouZero 分数最高出牌;主角每一手都走完整顾问
(残局搜索 + 现场模拟),所以这条规则在实战里什么时候触发、触发后顶不顶用,都按真实决策点算。
同一副牌两边共用发牌,差别只来自规则;金额按斗地主算:农民赢 +2^k、输 −2^k,k 是这一局的炸弹 / 王炸个数。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... .venv/bin/python eval_beat_landlord.py --deals 200 --workers 8
  # --deals 是主角农民的局数,每局打两遍(有规则 / 没有规则);冒烟:--deals 20 --workers 4
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

from server import Advisor, DECK, SEATS, WEIGHTS, move_label

_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS)
    return _ADVISOR


class Hero:
    """主角农民:每一手都走完整顾问(现场模拟默认开着),记下耗时和调用次数。"""

    def __init__(self, seat):
        self.agent = advisor().agents[seat]
        self.seat = seat
        self.ms = 0.0
        self.moves = 0

    def act(self, infoset):
        if infoset.player_position != self.seat:
            raise RuntimeError(f"主角色不对: {infoset.player_position} != {self.seat}")
        started = time.perf_counter()
        move = self.agent.act(infoset)
        self.ms += (time.perf_counter() - started) * 1000
        self.moves += 1
        # 线上 Advisor.act 会用 resolve 把历史按点数排好(swap_high_kickers 可能返回没排序的一手);
        # 这里在牌局里直接走 Agent,所以自己排一下,免得 Replay 拿没排序的一手去比合规出牌
        return sorted(move)


def play(seed, seat, rule_on):
    """从同一副牌打一局:主角农民按 [rule_on] 决定开不开压牌规则,另外两家 plain DouZero。"""
    import server

    original = server.beat_landlord
    deck = list(DECK)
    random.Random(seed).shuffle(deck)
    hero = Hero(seat)
    players = {s: hero if s == seat else advisor().plain[s] for s in SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]),
                        "three_landlord_cards": sorted(deck[17:20])})
    fired = 0
    firings = []

    def count(infoset, keys, means, scores, played, pairs, games):
        nonlocal fired
        move = original(infoset, keys, means, scores, played, pairs, games)
        if move is not None:
            fired += 1
            # 记下这个决策点的可见信息和两种选择,后面可以用更多发牌深算这一手的期望
            firings.append({
                "seat": infoset.player_position,
                "hand": list(infoset.player_hand_cards),
                "bottom": list(infoset.three_landlord_cards),
                "history": [list(m) for m in infoset.card_play_action_seq],
                "other": list(infoset.other_hand_cards),
                "left": dict(infoset.num_cards_left_dict),
                "played_cards": {s: list(c) for s, c in infoset.played_cards.items()},
                "beat": list(move),
                "labels": {move_label(k): [means[k], played[k]] for k in keys},
                "games": games,
            })
        return move

    server.beat_landlord = count if rule_on else (lambda *args, **kwargs: None)
    try:
        while not env.game_over:
            env.step()
    finally:
        server.beat_landlord = original
    k = env.bomb_num
    win = bool(env.num_wins["farmer"])
    return {
        "win": int(win),
        "amount": (2 ** k) if win else -(2 ** k),
        "bombs": k,
        "calls": hero.moves,
        "ms": hero.ms,
        "fired": fired,
        "firings": firings,
    }


def work(job):
    seed, seat = job
    try:
        # 交替先跑哪一边,抵消同一进程里第一次前向的冷启动
        if seed % 2:
            off = play(seed, seat, False)
            on = play(seed, seat, True)
        else:
            on = play(seed, seat, True)
            off = play(seed, seat, False)
    except Exception as error:
        print(f"FAIL seed={seed} seat={seat} {type(error).__name__}: {error}", flush=True)
        raise
    return {"seed": seed, "seat": seat, "on": on, "off": off,
            "firings": [{**f, "seed": seed} for f in on.pop("firings")]}


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
    fired = sum(r["on"]["fired"] for r in rows)
    changed = [r for r in rows if r["on"]["amount"] != r["off"]["amount"]]
    by_seat = {}
    for seat in ("landlord_up", "landlord_down"):
        subset = [r for r in rows if r["seat"] == seat]
        by_seat[seat] = {
            "n": len(subset),
            "on_amount": sum(r["on"]["amount"] for r in subset),
            "off_amount": sum(r["off"]["amount"] for r in subset),
            "diff": ci95([r["on"]["amount"] - r["off"]["amount"] for r in subset]),
            "wins": (sum(r["on"]["win"] for r in subset), sum(r["off"]["win"] for r in subset)),
        }
    return {
        "n": len(rows),
        "on_amount": sum(r["on"]["amount"] for r in rows),
        "off_amount": sum(r["off"]["amount"] for r in rows),
        "on_wins": sum(r["on"]["win"] for r in rows),
        "off_wins": sum(r["off"]["win"] for r in rows),
        "diff": [mean, low, high],
        "fired": fired,
        "changed_amount": len(changed),
        "on_ms_per_move": sum(r["on"]["ms"] for r in rows) / max(1, sum(r["on"]["calls"] for r in rows)),
        "off_ms_per_move": sum(r["off"]["ms"] for r in rows) / max(1, sum(r["off"]["calls"] for r in rows)),
        "by_seat": by_seat,
    }


def print_summary(rows):
    s = summarize(rows)
    mean, low, high = s["diff"]
    print(f"\n对局 {s['n']} 副(每副有规则 / 无规则各一遍,配对)")
    print(f"有规则:赢 {s['on_wins']} 副、总额 {s['on_amount']:+d}")
    print(f"无规则:赢 {s['off_wins']} 副、总额 {s['off_amount']:+d}")
    print(f"配对差:总额 {s['on_amount'] - s['off_amount']:+d}、每副平均 {mean:+.3f} "
          f"(95% CI [{low:+.3f},{high:+.3f}])")
    print(f"规则触发 {s['fired']} 次,金额变化的对局 {s['changed_amount']} 副")
    for seat, d in s["by_seat"].items():
        m, lo, hi = d["diff"]
        print(f"  {seat:13s} {d['n']:4d} 副  有规则 {d['on_amount']:+5d} / 无规则 {d['off_amount']:+5d}"
              f"  与无规则胜场 {d['wins'][0]}/{d['wins'][1]}  平均差 {m:+.3f} [{lo:+.3f},{hi:+.3f}]")
    print(f"主角每手耗时:有规则 {s['on_ms_per_move']:.1f}ms / 无规则 {s['off_ms_per_move']:.1f}ms")
    return s


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=200, help="主角农民的局数,每局打两遍")
    parser.add_argument("--workers", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument("--seed", type=int, default=20261118)
    parser.add_argument("--seat", choices=("up", "down", "both"), default="both")
    parser.add_argument("--out", default="eval_beat_landlord.json")
    args = parser.parse_args()

    started = time.time()
    rng = random.Random(args.seed)
    seats = {"up": ["landlord_up"], "down": ["landlord_down"],
             "both": ["landlord_up", "landlord_down"]}[args.seat]
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
    summary = print_summary(rows)
    firings = [f for r in rows for f in r["firings"]]
    print(f"触发决策点 {len(firings)} 个(存进 JSON,可用更多发牌深算)")
    json.dump({"args": vars(args), "rows": rows, "summary": summary, "firings": firings},
              open(args.out, "w"), ensure_ascii=False, indent=1)
    print(f"\n{len(rows)} 副 -> {args.out}, {time.time() - started:.0f}s")


if __name__ == "__main__":
    mp.freeze_support()
    main()
