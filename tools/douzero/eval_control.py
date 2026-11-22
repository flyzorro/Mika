"""配对自对弈：现场时限下比较当前分支与指定的基线提交。

DOUZERO_WEIGHTS=... nice -n 15 python -I eval_control.py \
    --base d1c9b6d5 --deals 96 --workers 8 --out /tmp/control.jsonl
python -I eval_control.py --summarize /tmp/control.jsonl
"""
import argparse
import json
import math
import multiprocessing as mp
from pathlib import Path
import random
import statistics
import subprocess
import sys
from types import ModuleType

from douzero.env.game import GameEnv

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server


_advisors = {}
VERSIONS = ("old", "new", "no_rollout")


def init(base_source):
    baseline = ModuleType("baseline")
    baseline.__file__ = server.__file__
    exec(compile(base_source, baseline.__file__, "exec"), baseline.__dict__)
    server.torch.set_num_threads(1)
    _advisors["old"] = baseline.Advisor(server.WEIGHTS)
    _advisors["new"] = server.Advisor(server.WEIGHTS)
    _advisors["no_rollout"] = server.Advisor(server.WEIGHTS, rollout=False)


class Hero:
    def __init__(self, advisor, seat):
        self.advisor = advisor
        self.seat = seat
        self.short_history = None
        self.takes_lead = 0

    def act(self, info):
        history = info.card_play_action_seq
        opponents = ("landlord_down", "landlord_up") if self.seat == "landlord" else ("landlord",)
        if self.short_history is None and any(0 < info.num_cards_left_dict[p] <= 2 for p in opponents):
            self.short_history = [list(move) for move in history]
        report = {}
        likelihood = self.advisor.likelihood(history, self.seat)
        move = self.advisor.agents[self.seat].act(info, report=report, likelihood=likelihood)
        self.takes_lead += "take_lead" in report
        return move


def play(deck, seat, version, prefix=()):
    advisor = _advisors[version]
    advisor.short_plans.clear()
    server.endgame._MIN_PLAYS.clear()
    hero = Hero(advisor, seat)
    replay = server.Replay(prefix)
    players = {s: server._RolloutAgent(replay, hero if s == seat else advisor.plain[s])
               for s in server.SEATS}
    env = GameEnv(players)
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]), "three_landlord_cards": sorted(deck[17:20])})
    while not env.game_over:
        env.step()
    win = int(bool(env.num_wins["landlord" if seat == "landlord" else "farmer"]))
    return win, hero


def work(job):
    seed, seat = job
    deck = list(server.DECK)
    rng = random.Random(seed)
    rng.shuffle(deck)
    order = list(VERSIONS)
    rng.shuffle(order)
    results = {version: play(deck, seat, version) for version in order}
    old, old_hero = results["old"]
    short = None
    if old_hero.short_history is not None:
        # 从基线首次遇到短手对家时重放相同历史，保留原发牌，双方比较同一局面。
        short_new, short_hero = play(deck, seat, "new", old_hero.short_history)
        short_off, _ = play(deck, seat, "no_rollout", old_hero.short_history)
        short = {"old": old, "new": short_new, "no_rollout": short_off,
                 "take_lead": short_hero.takes_lead}
    wins = {version: results[version][0] for version in VERSIONS}
    return {"seed": seed, "seat": seat, **wins, "take_lead": results["new"][1].takes_lead, "short": short}


def wilson(wins, n, z=1.96):
    rate = wins / n
    center = rate + z * z / (2 * n)
    radius = z * math.sqrt((rate * (1 - rate) + z * z / (4 * n)) / n)
    return tuple(value / (1 + z * z / n) for value in (center - radius, center + radius))


def summarize(rows):
    for label, group in (("overall", rows),
                         ("opponent <=2", [row["short"] for row in rows if row["short"] is not None])):
        n = len(group)
        if not n:
            print(f"{label} n=0")
            continue
        print(f"{label} n={n} pairs, take_lead={sum(row['take_lead'] for row in group)}")
        for version in VERSIONS:
            wins = sum(row[version] for row in group)
            low, high = wilson(wins, n)
            print(f"  {version} {wins}/{n} = {wins/n:.2%} (95% Wilson CI {low:.2%}, {high:.2%})")
        for candidate, reference in (("new", "old"), ("no_rollout", "old"), ("no_rollout", "new")):
            diffs = [row[candidate] - row[reference] for row in group]
            # 分别给改善和变差比例算 97.5% Wilson 区间，两者相减得保守的配对 95% 区间。
            improved = diffs.count(1)
            worsened = diffs.count(-1)
            low, high = paired_interval(improved, worsened, n)
            print(f"  {candidate} vs {reference} {statistics.mean(diffs):+.2%}"
                  f" (95% CI {low:+.2%}, {high:+.2%}), wins flipped +{improved}/-{worsened}")


def paired_interval(improved, worsened, n):
    better_low, better_high = wilson(improved, n, z=2.2414)
    worse_low, worse_high = wilson(worsened, n, z=2.2414)
    return better_low - worse_high, better_high - worse_low


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="d1c9b6d5")
    parser.add_argument("--seed", type=int, default=20261122)
    parser.add_argument("--deals", type=int, default=96)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--summarize", type=Path)
    args = parser.parse_args()
    if args.summarize:
        summarize([json.loads(line) for line in args.summarize.read_text().splitlines()])
        return
    if args.out is None:
        parser.error("--out is required")
    baseline = subprocess.check_output(["git", "show", f"{args.base}:tools/douzero/server.py"], text=True)
    jobs = [(args.seed + i, server.SEATS[i % len(server.SEATS)]) for i in range(args.deals)]
    rows = []
    with args.out.open("x") as out, mp.Pool(args.workers, initializer=init, initargs=(baseline,)) as pool:
        for row in pool.imap_unordered(work, jobs):
            rows.append(row)
            print(json.dumps(row), file=out, flush=True)
    summarize(rows)


if __name__ == "__main__":
    main()
