"""配对自对弈，保留线上每手时限，逐副写 JSONL。

DOUZERO_WEIGHTS=... nice -n 15 python -I eval_control.py \
    --study rollout --deals 1000 --workers 6 --out /tmp/rollout.jsonl
python -I eval_control.py --study rollout --summarize /tmp/rollout.jsonl
"""
import argparse
import json
import math
import multiprocessing as mp
from pathlib import Path
import random
import subprocess
import sys
import time
from types import MethodType, ModuleType

from douzero.env.game import GameEnv

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server
import eval_rules


ARMS = {
    "rules": eval_rules.ARMS,
    "take-lead": ("take_on", "take_off"),
    "rollout": ("old_gate", "se_gate", "rollout_off"),
}
COMPARISONS = {
    "take-lead": (("take_on", "take_off"),),
    "rollout": (("se_gate", "old_gate"), ("rollout_off", "old_gate"), ("rollout_off", "se_gate")),
}
_take_lead = server.take_short_opponent_lead
_halving = server.successive_halving
_rollout_budgets = []
_advisor = None
_old_rollout = None


def observed_halving(keys, own, scores, deal, play, budget, **kwargs):
    started = time.monotonic()
    result = _halving(keys, own, scores, deal, play, budget, **kwargs)
    _rollout_budgets.append(time.monotonic() - started >= budget)
    return result


def init(base_source):
    global _advisor, _old_rollout
    server.torch.set_num_threads(1)
    _advisor = server.Advisor(server.WEIGHTS, rollout=False)
    server.successive_halving = observed_halving
    if base_source is not None:
        baseline = ModuleType("baseline")
        baseline.__file__ = server.__file__
        exec(compile(base_source, baseline.__file__, "exec"), baseline.__dict__)
        baseline.successive_halving = observed_halving
        _old_rollout = baseline.Agent.close_rollout


class Hero:
    def __init__(self, seat):
        self.seat = seat
        self.turns = {}
        self.triggers = {}

    def act(self, info):
        history = info.card_play_action_seq
        turn = len(history)
        report = {}
        likelihood = _advisor.likelihood(history, self.seat)
        _rollout_budgets.clear()
        started = time.monotonic()
        move = _advisor.agents[self.seat].act(info, report=report, likelihood=likelihood)
        elapsed = time.monotonic() - started
        self.turns[turn] = {
            "moves": 1,
            "act_s": elapsed,
            "over_budget": int(elapsed >= server.ACT_TOTAL_BUDGET_S),
            "rollouts": len(_rollout_budgets),
            "rollout_deadline": sum(_rollout_budgets),
            "rollout_low_n": int(bool(_rollout_budgets)
                                 and report.get("rollout", {}).get("games", 0) < server.ACT_ROLLOUT_MIN_GAMES),
            "rollout_no_time": int(report.get("rollout_skipped") == "no_time"),
        }
        if "take_lead" in report:
            self.triggers[turn] = {
                "turn": turn,
                "hands": info.all_handcards,
                "owner": info.last_pid,
                "rival": sorted(info.last_move),
                "bottom": info.three_landlord_cards,
            }
        return move


def play(deck, seat, arm, prefix=()):
    _advisor.short_plans.clear()
    server.endgame._MIN_PLAYS.clear()
    agent = _advisor.agents[seat]
    agent.rollout = arm in ("old_gate", "se_gate")
    agent.close_rollout = MethodType(_old_rollout if arm == "old_gate" else server.Agent.close_rollout, agent)
    server.take_short_opponent_lead = (lambda *_: None) if arm == "take_off" else _take_lead
    hero = Hero(seat)
    replay = server.Replay(prefix)
    players = {s: server._RolloutAgent(replay, hero if s == seat else _advisor.plain[s])
               for s in server.SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]), "three_landlord_cards": sorted(deck[17:20])})
    while not env.game_over:
        env.step()
    win = env.get_winner() == ("landlord" if seat == "landlord" else "farmer")
    return int(win), env.card_play_action_seq, hero


def timing(turns):
    totals = {name: sum(turn[name] for turn in turns) for name in (
        "moves", "act_s", "over_budget", "rollouts", "rollout_deadline", "rollout_low_n", "rollout_no_time",
    )}
    return {**totals, "max_s": max((turn["act_s"] for turn in turns), default=0)}


def work(job):
    seed, seat, study = job
    deck = list(server.DECK)
    rng = random.Random(seed)
    rng.shuffle(deck)
    result = {"seed": seed, "seat": seat, "timing": {}}
    if study == "rollout":
        order = list(ARMS[study])
        rng.shuffle(order)
        for arm in order:
            result[arm], _, hero = play(deck, seat, arm)
            result["timing"][arm] = timing(hero.turns.values())
        return result

    on, history, hero = play(deck, seat, "take_on")
    result.update(take_on=on, take_off=on, triggers=len(hero.triggers), changed=None)
    result["timing"]["take_on"] = timing(hero.turns.values())
    result["timing"]["take_off"] = result["timing"]["take_on"]
    if hero.triggers:
        # 规则首次返回出牌前，两组共用同一段决策，避免重复搜索的时限噪声。
        first = min(hero.triggers)
        off, other_history, other_hero = play(deck, seat, "take_off", prefix=history[:first])
        result["take_off"] = off
        common = [entry for turn, entry in hero.turns.items() if turn < first]
        result["timing"]["take_off"] = timing(common + list(other_hero.turns.values()))
        for turn, (taken, other) in enumerate(zip(history, other_history)):
            if taken != other:
                if turn in hero.triggers:
                    result["changed"] = {**hero.triggers[turn], "take_on": taken, "take_off": other}
                break
    return result


def wilson(wins, n, z=1.96):
    rate = wins / n
    center = rate + z * z / (2 * n)
    radius = z * math.sqrt((rate * (1 - rate) + z * z / (4 * n)) / n)
    return tuple(value / (1 + z * z / n) for value in (center - radius, center + radius))


def paired_interval(improved, worsened, n):
    # 97.5% Wilson 分别约束改善和变差，相减得保守的配对 95% 区间。
    better_low, better_high = wilson(improved, n, z=2.2414)
    worse_low, worse_high = wilson(worsened, n, z=2.2414)
    return better_low - worse_high, better_high - worse_low


def summarize_group(label, rows, study):
    n = len(rows)
    print(f"{label} n={n} pairs")
    if not n:
        return
    for arm in ARMS[study]:
        wins = sum(row[arm] for row in rows)
        low, high = wilson(wins, n)
        print(f"  {arm} {wins}/{n} = {wins/n:.2%} (95% Wilson CI {low:.2%}, {high:.2%})")
    for candidate, reference in COMPARISONS[study]:
        differences = [row[candidate] - row[reference] for row in rows]
        improved, worsened = differences.count(1), differences.count(-1)
        low, high = paired_interval(improved, worsened, n)
        print(f"  {candidate} vs {reference} {(improved-worsened)/n:+.2%}"
              f" (95% CI {low:+.2%}, {high:+.2%}), wins flipped +{improved}/-{worsened}")


def summarize(rows, study):
    if study == "rules":
        eval_rules.summarize(rows)
        return
    summarize_group("overall", rows, study)
    if not rows:
        return
    for seat in server.SEATS:
        summarize_group(seat, [row for row in rows if row["seat"] == seat], study)
    if study == "take-lead":
        triggered = [row for row in rows if row["triggers"]]
        summarize_group("take-lead triggered", triggered, study)
        print(f"take-lead trigger positions={sum(row['triggers'] for row in triggered)}")
        flipped = sorted((row for row in triggered if row["take_on"] != row["take_off"]),
                         key=lambda row: row["seed"])
        examples = [row for row in flipped if row["changed"]]
        print(f"flips whose first differing move fired take-lead={len(examples)}/{len(flipped)}")
        for row in examples[:10]:
            print(json.dumps({k: row[k] for k in ("seed", "seat", "take_on", "take_off", "changed")}))
    for arm in ARMS[study]:
        records = [row["timing"][arm] for row in rows]
        totals = {key: sum(record.get(key, 0) for record in records)
                  for key in timing(()) if key != "max_s"}
        totals["max_s"] = max(record["max_s"] for record in records)
        totals["act_s"] = round(totals["act_s"], 2)
        totals["max_s"] = round(totals["max_s"], 3)
        print(f"{arm} timing {json.dumps(totals)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study", required=True, choices=ARMS)
    parser.add_argument("--base", default="95af39b9")
    parser.add_argument("--seed", type=int, default=20261122)
    parser.add_argument("--deals", type=int, default=1000)
    parser.add_argument("--workers", type=int)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--summarize", type=Path)
    args = parser.parse_args()
    if args.workers is None:
        args.workers = 6 if args.study == "rules" else 8
    if args.summarize:
        summarize([json.loads(line) for line in args.summarize.read_text().splitlines()], args.study)
        return
    if args.out is None:
        parser.error("--out is required")
    if args.deals < 1 or args.workers < 1:
        parser.error("--deals and --workers must be positive")
    baseline = None
    if args.study == "rollout":
        baseline = subprocess.check_output(["git", "show", f"{args.base}:tools/douzero/server.py"], text=True)
    jobs = [(args.seed + i, server.SEATS[i % len(server.SEATS)], args.study) for i in range(args.deals)]
    initializer, initargs, worker = init, (baseline,), work
    if args.study == "rules":
        if args.workers > 6:
            parser.error("--study rules allows at most six workers")
        if Path(server.WEIGHTS).name != "douzero_WP":
            parser.error("--study rules requires DOUZERO_WEIGHTS pointing to baselines/douzero_WP")
        jobs = range(args.seed, args.seed + args.deals)
        initializer, initargs, worker = eval_rules.init, (), eval_rules.work
    rows = []
    started = time.monotonic()
    with args.out.open("x") as out, mp.Pool(args.workers, initializer=initializer, initargs=initargs) as pool:
        for row in pool.imap_unordered(worker, jobs):
            rows.append(row)
            print(json.dumps(row), file=out, flush=True)
    print(f"Wall time {time.monotonic() - started:.1f}s")
    summarize(rows, args.study)


if __name__ == "__main__":
    main()
