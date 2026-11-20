"""配对评测现场模拟淘汰规则。在 tools/douzero 下用带 DouZero 的 Python 运行。

  DOUZERO_WEIGHTS=... python eval_halving.py --deals 12 --workers 4 --out /tmp/smoke.jsonl
  DOUZERO_WEIGHTS=... python eval_halving.py --deals 420 --workers 4 --out /tmp/halving.jsonl
  python eval_halving.py --summarize /tmp/halving.jsonl

每行是一副固定牌和主角座位的全方案结果，随对局完成追加。用同样的 --seed / --out 可续跑。
"""
import argparse
from contextlib import contextmanager, nullcontext
import json
import math
import multiprocessing as mp
from pathlib import Path
import random
import statistics
import time

from douzero.env.game import GameEnv

import server


RULES = {
    "S0": (12, None),
    "S1-20": (20, None),
    "S1-30": (30, None),
    "S2-0.25": (12, 0.25),
    "S2-0.50": (12, 0.50),
}
_ADVISOR = None

LIVE_REQUEST = {
    "seat": "landlord_down",
    "hand": ["2", "Q", "10", "10", "5", "4", "3", "3"],
    "bottom": [],
    "history": [["8", "8", "8", "3"], [], [], ["J", "J", "J", "7"], [], [], ["5", "5"],
                ["9", "9"], [], ["A", "A"], [], [], ["K", "K", "K", "9"], ["6", "6", "6", "6"],
                [], [], ["8"], ["10"], ["Q"], ["A"], ["2"], ["SJ"], ["BJ"], [], []],
    "auction": {"double": {"landlord": False, "landlord_up": False}},
}


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = server.Advisor(server.WEIGHTS)
    return _ADVISOR


class FirstRoundFloor:
    """把调度器的 `局数 >= floor` 转成首轮专用下限，第二轮仍用 S0。"""

    def __init__(self, first):
        self.minimum = first

    def __le__(self, games):
        return self.minimum <= games


@contextmanager
def policy(rule, record):
    floor, gap = RULES[rule]
    run = server.successive_halving
    cut = server.halve

    def halving(keys, own, scores, deal, play, budget, **kwargs):
        first = True
        round_floor = FirstRoundFloor(floor)

        def cut_round(alive, own, means, scores, protected):
            nonlocal first
            try:
                if first and gap is not None:
                    ranked = sorted(alive, key=lambda k: (-means[k], -scores[k], k))
                    lead = means[ranked[0]]
                    return [k for k in ranked if lead - means[k] <= gap or k in (own, protected)]
                return cut(alive, own, means, scores, protected)
            finally:
                first = False
                round_floor.minimum = RULES["S0"][0]

        server.halve = cut_round
        try:
            kwargs["min_round_games"] = round_floor
            result = run(keys, own, scores, deal, play, budget, **kwargs)
        finally:
            server.halve = cut
        if len(keys) >= 3:
            record.append([len(keys), budget, [[len(survivors), games] for survivors, games in result[3]]])
        return result

    server.successive_halving = halving
    try:
        yield
    finally:
        server.successive_halving = run


class Hero:
    def __init__(self, seat, shared_moves, record):
        self.seat = seat
        self.shared_moves = shared_moves
        self.record = record
        self.calls = 0
        self.ms = 0.0
        self.hit_ms = 0.0

    def act(self, infoset):
        self.calls += 1
        key = tuple(tuple(m) for m in infoset.card_play_action_seq)
        cached = self.shared_moves.get(key)
        if cached is not None:
            move, ms, hits = cached
            self.ms += ms
            if hits:
                self.hit_ms += ms
                self.record.extend(hits)
            return list(move)

        started = time.perf_counter()
        before = len(self.record)
        model = advisor().agents[self.seat]
        likelihood = advisor().likelihood(infoset.card_play_action_seq, self.seat)
        move = sorted(model.act(infoset, likelihood=likelihood))
        ms = (time.perf_counter() - started) * 1000
        hits = self.record[before:]
        self.ms += ms
        if hits:
            self.hit_ms += ms
        # 同副牌、同历史且规则没有改变该手的调度，共用一次真实决策和耗时。
        # 三候选只有一轮，局数下限和两种筛法都不起作用。
        if all(count == 3 for count, _, _ in hits):
            self.shared_moves[key] = (move, ms, hits)
        return move


def play(deck, seat, rule, shared_moves):
    record = []
    hero = Hero(seat, shared_moves, record)
    players = {s: hero if s == seat else advisor().plain[s] for s in server.SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]), "three_landlord_cards": sorted(deck[17:20])})
    advisor().short_plans.clear()
    with policy(rule, record):
        while not env.game_over:
            env.step()
    win = bool(env.num_wins["landlord" if seat == "landlord" else "farmer"])
    return {"win": int(win), "score": (1 if win else -1) * 2 ** env.bomb_num,
            "calls": hero.calls, "ms": hero.ms, "hit_ms": hero.hit_ms, "hits": record}


def work(job):
    index, seed, seat = job
    deck = list(server.DECK)
    rng = random.Random(seed)
    rng.shuffle(deck)
    rules = list(RULES)
    rng.shuffle(rules)
    shared_moves = {}
    results = {rule: play(deck, seat, rule, shared_moves) for rule in rules}
    return {"index": index, "seed": seed, "seat": seat, "results": results}


def mean_se(values):
    return statistics.mean(values), statistics.stdev(values) / math.sqrt(len(values)) if len(values) > 1 else None


def difference(values, m=1):
    mean, se = mean_se(values)
    error = f"{m * se:.3f}" if se is not None else "—"
    return f"{m * mean:+.3f} ± {error}"


def summarize(rows):
    paired = [row for row in rows if row["results"]["S0"]["hits"]]
    print(f"已完成 {len(rows)} 副，S0 触发（初始候选 ≥3）{len(paired)} 副")
    heading = ("| 主角 | 方案 | N | 触发 | ≥4 手 | 胜率 | 均分 | Δ胜率 pp ± SE | "
               "Δ均分 ± SE | 每手 ms | 触发手 ms |")
    print(heading)
    print("|" + "---|" * 11)
    for label, seats in (("合计", server.SEATS), ("地主", ["landlord"]),
                         ("农民", ["landlord_up", "landlord_down"])):
        pairs = [row["results"] for row in paired if row["seat"] in seats]
        if not pairs:
            continue
        for rule in RULES:
            trials = [p[rule] for p in pairs]
            hits = [h for t in trials for h in t["hits"]]
            win = statistics.mean(t["win"] for t in trials)
            score = statistics.mean(t["score"] for t in trials)
            dw = difference([p[rule]["win"] - p["S0"]["win"] for p in pairs], 100)
            ds = difference([p[rule]["score"] - p["S0"]["score"] for p in pairs])
            ms = sum(t["ms"] for t in trials) / sum(t["calls"] for t in trials)
            hit_ms = f"{sum(t['hit_ms'] for t in trials) / len(hits):.1f}" if hits else "—"
            print(f"| {label} | {rule} | {len(pairs)} | {len(hits)} | {sum(h[0] >= 4 for h in hits)} "
                  f"| {win:.2%} | {score:+.3f} | {dw} | {ds} | {ms:.1f} | {hit_ms} |")
    print("\n| 方案 | 初始候选 | 触发 | 首轮每手局数中位数 [最小,最大] | 首轮剩余手数范围 "
          "| 次轮每手局数中位数 | 次轮剩余手数范围 | 决赛新增每手局数中位数 |")
    print("|" + "---|" * 8)
    for rule in RULES:
        hits = [h for row in paired for h in row["results"][rule]["hits"]]
        for count in sorted({h[0] for h in hits if h[0] >= 4}):
            rounds = [h[2] for h in hits if h[0] == count]
            games = [r[0][1] for r in rounds]
            left = [r[0][0] for r in rounds]
            second = [r[1] for r in rounds if len(r) == 3]
            extra = (f"{statistics.median(g for _, g in second):g} | "
                     f"{min(n for n, _ in second)}–{max(n for n, _ in second)}") if second else "— | —"
            print(f"| {rule} | {count} | {len(rounds)} | {statistics.median(games):g} "
                  f"[{min(games)},{max(games)}] | {min(left)}–{max(left)} | {extra} "
                  f"| {statistics.median(r[-1][1] for r in rounds):g} |")


def read_rows(path):
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def replay_live():
    for rule in ("current", *RULES):
        advisor().short_plans.clear()
        report = {}
        started = time.perf_counter()
        with nullcontext() if rule == "current" else policy(rule, []):
            move = advisor().act(**{**LIVE_REQUEST, "report": report,
                                    "auction": server.parse_auction(LIVE_REQUEST["auction"])})
        print(json.dumps({"rule": rule, "play": server.move_label(move),
                          "ms": round((time.perf_counter() - started) * 1000), **report},
                         ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deals", type=int, default=420)
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=4)
    parser.add_argument("--seed", type=int, default=20261120)
    parser.add_argument("--out", type=Path, default=Path("eval_halving.jsonl"))
    parser.add_argument("--summarize", type=Path)
    parser.add_argument("--replay-live", action="store_true")
    args = parser.parse_args()
    if args.replay_live:
        replay_live()
        return
    if args.summarize:
        if not args.summarize.is_file():
            parser.error(f"找不到评测结果 {args.summarize}")
        summarize(read_rows(args.summarize))
        return
    if args.deals <= 0:
        parser.error("--deals 必须大于 0")

    rng = random.Random(args.seed)
    jobs = [(i, rng.randrange(10 ** 9), server.SEATS[i % len(server.SEATS)]) for i in range(args.deals)]
    rows = read_rows(args.out)
    done = set()
    for row in rows:
        index = row["index"]
        if not 0 <= index < len(jobs) or jobs[index] != (index, row["seed"], row["seat"]) or index in done:
            parser.error(f"{args.out} 与 --seed / --deals 不匹配或有重复记录")
        done.add(index)
    todo = [job for job in jobs if job[0] not in done]
    started = time.perf_counter()
    if todo:
        with args.out.open("a", buffering=1) as out, mp.Pool(args.workers) as pool:
            for row in pool.imap_unordered(work, todo):
                out.write(json.dumps(row) + "\n")
                rows.append(row)
                if len(rows) <= 4 or len(rows) % 10 == 0:
                    print(f"{len(rows)}/{len(jobs)} 副，{time.perf_counter() - started:.0f}s", flush=True)
    summarize(rows)


if __name__ == "__main__":
    main()
