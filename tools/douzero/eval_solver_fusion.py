"""Paired production vs a fixed-policy tie-break when the landlord has one card."""
import argparse
from collections import Counter
import copy
import json
import math
import multiprocessing as mp
from pathlib import Path
import random
import signal
import sys
import time
import unittest
from statistics import mean, stdev
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server
from server import endgame

REQUEST = dict(seat="landlord_down", hand=["2", "2", "8", "6", "6", "3"],
               bottom=["8", "8", "5"], history=[
                   ["6", "6", "5", "5", "4", "4", "3", "3"], [], [], ["8", "8"], ["A", "A"], [], [],
                   ["7"], ["Q"], ["K"], ["2"], [], ["SJ"], [], [], ["J", "J", "J", "10", "10"],
                   ["9", "9", "Q", "Q", "Q"], [], [], ["K"], [], ["2"], [], ["BJ"], [], [],
                   ["3"], ["5"], ["J"], ["A"], [], [], ["4", "4"], []])
advisor = None
resumes = 0


def resumed(signum, frame):
    global resumes
    resumes += 1


def init(weights):
    global advisor
    signal.signal(signal.SIGCONT, resumed)
    server.torch.set_num_threads(1)
    advisor = server.Advisor(weights)


def policy_wins(info, hands, move, deadline, choices):
    """Exact continuation given both farmers' observation-based policies and one landlord card."""
    seat = info.player_position
    history = list(info.card_play_action_seq)
    hands = hands.copy()
    while True:
        if time.monotonic() >= deadline:
            raise endgame.Timeout()
        hands[seat] = endgame.minus(hands[seat], move)
        if not hands[seat]:
            return seat != "landlord"
        history.append(list(move))
        seat = endgame.NEXT[seat]
        if seat == "landlord":
            # With one card, winning immediately dominates passing. Otherwise pass is forced.
            rival = history[-1] or history[-2]
            if not rival or len(rival) == 1 and hands[seat][0] > rival[0]:
                return False
            move = ()
            continue
        # The root history is common. Own cards + new public actions determine
        # the full observation, including unseen cards and public hand sizes.
        key = seat, hands[seat], tuple(map(tuple, history[len(info.card_play_action_seq):]))
        if key not in choices:
            index = len(history)
            frame = server.history_frame(history, index)
            state = server.history_state(history, hands, seat, index, frame)
            choices[key] = tuple(advisor.plain[seat].act(state))
        move = choices[key]


def decision(info, diagnostics=False):
    seat = info.player_position
    agent = advisor.agents[seat]
    likelihood = advisor.likelihood(info.card_play_action_seq, seat)
    if seat == "landlord" or info.num_cards_left_dict["landlord"] != 1 or len(info.legal_actions) == 1:
        baseline = agent.act(info, likelihood)
        return baseline, baseline, {}
    # Instrument the actual production call once. Reuse its likelihoods, moves
    # and original search deadline, including production's timeout fallback.
    deals = []
    solved = None
    deadline = None
    real_best = endgame.best

    def capture_likes(hands, end):
        nonlocal deadline
        deadline = end
        likes = likelihood(hands, end)
        deals.extend(zip(hands, likes))
        return likes

    def capture_best(*args, **kwargs):
        nonlocal solved
        solved = real_best(*args, **kwargs)
        return solved

    with patch.object(endgame, "best", capture_best):
        baseline = agent.act(info, capture_likes)
    details = {}
    if solved is None or not deals:
        return baseline, baseline, details
    wins, total, plain, plain_total = solved
    actions = [tuple(sorted(m)) for m in info.legal_actions]
    cutoff = max(wins[m] for m in actions) - server.ACT_ENDGAME_TIE_MARGIN * total
    tied = [m for m in actions if wins[m] >= cutoff]
    if len(tied) < 2:
        return baseline, baseline, details
    details["checked"] = 1
    if diagnostics:
        details.update(total=total, allocations=plain_total, per_rank=[],
                       perfect={str(m): wins[m] / total for m in actions},
                       unweighted={str(m): plain[m] / plain_total for m in actions})
    counts = Counter(info.other_hand_cards)
    scores = Counter()
    choices = {}
    try:
        for hands, like in deals:
            weight = counts[hands["landlord"][0]] * like
            if not weight and not diagnostics:
                continue
            if diagnostics:
                rank_result = dict(rank=hands["landlord"][0], weight=weight, farmer_wins=[])
                details["per_rank"].append(rank_result)
            for move in tied:
                if policy_wins(info, hands, move, deadline, choices):
                    scores[move] += weight
                    if diagnostics:
                        rank_result["farmer_wins"].append(move)
        if time.monotonic() >= deadline:
            raise endgame.Timeout()
    except endgame.Timeout:
        details["timeout"] = 1
        return baseline, baseline, details
    if diagnostics:
        details["fixed"] = {str(m): scores[m] / total for m in tied}
    cutoff = max(scores[m] for m in tied) - server.ACT_ENDGAME_TIE_MARGIN * total
    allowed = [m for m in tied if scores[m] >= cutoff]
    if tuple(sorted(baseline)) in allowed:
        return baseline, baseline, details
    # Production used the same values. Recompute only when the second filter
    # removes its pick, so the experimental harness can leave Agent untouched.
    if len(allowed) > 1:
        values = dict(zip(actions, agent.values(info).tolist()))
        allowed.sort(key=values.get, reverse=True)
    if time.monotonic() >= deadline:
        details["timeout"] = 1
        return baseline, baseline, details
    return baseline, list(allowed[0]), details


class Forced:
    def act(self, info):
        return self.move


def step(env, move):
    env.players[env.acting_player_position].move = sorted(move)
    env.step()


def paired(deck, hero):
    player = Forced()
    env = server.GameEnv(dict.fromkeys(server.SEATS, player))
    env.card_play_init(dict(landlord=sorted(deck[:20]), landlord_up=sorted(deck[20:37]),
                            landlord_down=sorted(deck[37:]), three_landlord_cards=sorted(deck[17:20])))
    role = "landlord" if hero == "landlord" else "farmer"
    counts = Counter(decisions=0, changes=0, checked=0, timeout=0)
    baseline_env = None
    first_change = None
    while not env.game_over:
        info = env.game_infoset
        seat = info.player_position
        if seat == hero:
            base, move, details = decision(info)
            counts["decisions"] += 1
            counts.update(details)
            if move != base:
                counts["changes"] += 1
                if baseline_env is None:
                    first_change = dict(history_length=len(info.card_play_action_seq),
                                        production=base, candidate=move)
                    baseline_env = copy.deepcopy(env)
                    step(baseline_env, base)
        else:
            info.legal_actions = endgame.without_double_joker_kickers(info.legal_actions)
            move = advisor.plain[seat].act(info)
        step(env, move)
    candidate = [env.num_wins[role], env.num_scores[role]]
    baseline = candidate
    if baseline_env is not None:
        while not baseline_env.game_over:
            info = baseline_env.game_infoset
            seat = info.player_position
            if seat == hero:
                move = advisor.agents[seat].act(info, advisor.likelihood(info.card_play_action_seq, seat))
            else:
                info.legal_actions = endgame.without_double_joker_kickers(info.legal_actions)
                move = advisor.plain[seat].act(info)
            step(baseline_env, move)
        baseline = [baseline_env.num_wins[role], baseline_env.num_scores[role]]
    return dict(**counts, production=baseline, candidate=candidate, first_change=first_change)


def work(seed):
    start = time.monotonic()
    deck = list(server.DECK)
    random.Random(seed).shuffle(deck)
    # A stop mid-decision would consume a live wall-clock deadline. Replay
    # the whole paired deal after CONT, preserving all ordinary timeouts.
    attempts = 0
    while True:
        epoch = resumes
        attempts += 1
        seats = {s: paired(deck, s) for s in server.SEATS}
        if epoch == resumes:
            return dict(seed=seed, seats=seats, attempts=attempts, elapsed=time.monotonic() - start)


def reproduce():
    original = advisor.agents[REQUEST["seat"]].act
    result = {}

    def capture(info, likelihood=None):
        with patch.object(advisor.agents[info.player_position], "act", original):
            b, c, details = decision(info, diagnostics=True)
        values = advisor.agents[info.player_position].values(info)
        result.update(details, baseline=b, candidate=c,
                      model={str(tuple(action)): float(value) for action, value in zip(info.legal_actions, values)})
        return b

    with patch.object(advisor.agents[REQUEST["seat"]], "act", capture):
        advisor.act(**REQUEST)
    return result


class ReproductionTest(unittest.TestCase):
    def test_jj_1523_wp_and_adp(self):
        root = Path(server.WEIGHTS).parent
        for model, expected in (("WP", [17, 17]), ("ADP", [6, 6])):
            with self.subTest(model=model):
                init(root / ("douzero_" + model))
                self.assertEqual(advisor.act(**REQUEST), expected)
                result = reproduce()
                self.assertEqual(result["baseline"], expected)
                self.assertEqual(result["perfect"], {"()": 1., "(6, 6)": 1., "(17, 17)": 1.})
                self.assertEqual(result["candidate"], expected)
                self.assertAlmostEqual(result["fixed"]["(17, 17)"], 1. if model == "WP" else .88048, places=4)
                self.assertAlmostEqual(sum(row["weight"] for row in result["per_rank"]), result["total"])


def upper_binomial(k, n, tail):
    """Exact one-sided upper Clopper-Pearson bound for a sparse event."""
    if k == n:
        return 1.
    lo, hi = 0., 1.
    for _ in range(50):
        p = (lo + hi) / 2
        cdf = sum(math.comb(n, j) * math.exp(j * math.log(p) + (n-j) * math.log1p(-p))
                  for j in range(k + 1))
        if cdf > tail:
            lo = p
        else:
            hi = p
    return hi


def lower_binomial(k, n, tail):
    if not k:
        return 0.
    return upper_binomial(k-1, n, 1-tail)


def paired_effect(deltas, sizes, bound, varying_seats, conditional):
    # Each deck is one independent observation. A triggered deck can
    # contribute one or two farmers, hence use the ratio of sums.
    if not sizes:
        return None
    estimate = sum(deltas) / sum(sizes)
    if varying_seats == 0:
        return estimate, 0., 0.
    n = len(deltas)
    pos, neg = sum(x > 0 for x in deltas), sum(x < 0 for x in deltas)
    if pos + neg >= 10:
        residual = [x - estimate * size for x, size in zip(deltas, sizes)]
        half = 1.96 * stdev(residual) / (math.sqrt(n) * mean(sizes))
        return estimate, max(-bound, estimate-half), min(bound, estimate+half)
    # Sparse: simultaneously bound probabilities of positive and negative
    # net deck differences. Nonzero integer magnitudes lie in [1, cap].
    cap = bound * varying_seats
    tail = .05 / 4
    low = lower_binomial(pos, n, tail) - cap * upper_binomial(neg, n, tail)
    high = cap * upper_binomial(pos, n, tail) - lower_binomial(neg, n, tail)
    min_size, max_size = (1, varying_seats) if conditional else (sizes[0], sizes[0])
    return (estimate, max(-bound, low / (max_size if low > 0 else min_size)),
            min(bound, high / (max_size if high < 0 else min_size)))


def summarize(rows):
    seeds = [row["seed"] for row in rows]
    if not rows or len(set(seeds)) != len(seeds):
        raise ValueError("expected complete deals with distinct seeds")
    groups = {"overall": server.SEATS, "landlord": ["landlord"], "farmers": server.SEATS[1:],
              "down": ["landlord_down"], "up": ["landlord_up"]}
    result = {}
    for name, seats in groups.items():
        games = [[row["seats"][s] for s in seats] for row in rows]
        all_games = [game for deck in games for game in deck]
        r = result[name] = {field: sum(g[field] for g in all_games)
                            for field in ("decisions", "changes", "checked", "timeout")}
        r["games"] = len(all_games)
        r["triggered"] = sum(g["changes"] > 0 for g in all_games)
        r["trigger_decks"] = sum(any(g["changes"] for g in deck) for deck in games)
        for idx, metric in enumerate(("win", "score")):
            for arm in ("production", "candidate"):
                r[arm + "_" + metric] = mean(g[arm][idx] for g in all_games)
            for cond in (False, True):
                group = [[g for g in deck if not cond or g["changes"]] for deck in games]
                group = [deck for deck in group if deck]
                deltas = [sum(g["candidate"][idx] - g["production"][idx] for g in deck) for deck in group]
                varying = sum(s != "landlord" for s in seats)
                key = ("trigger_" if cond else "") + metric
                r[key] = paired_effect(deltas, [len(d) for d in group], (1, 32768)[idx], varying, cond)
                r[key + "_nonzero_decks"] = sum(x != 0 for x in deltas)
                if not cond:
                    r[metric + "_deck_sd"] = stdev(deltas) / len(seats) if len(deltas) > 1 else None
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--weights", type=Path, default=server.WEIGHTS)
    p.add_argument("--seed", type=int, default=20261125)
    p.add_argument("--deals", type=int, default=1000)
    p.add_argument("--workers", type=int, choices=range(1, 5), default=4)
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--out", type=Path)
    group.add_argument("--reproduce", action="store_true")
    group.add_argument("--summarize", type=Path)
    args = p.parse_args()
    if args.summarize:
        rows = list(map(json.loads, args.summarize.read_text().splitlines()))
        print(json.dumps(summarize(rows), indent=2))
        return
    if args.reproduce:
        init(args.weights)
        print(json.dumps(reproduce()))
        return
    if args.deals < 1:
        p.error("--deals must be positive")
    with args.out.open("x") as out, mp.Pool(args.workers, init, (args.weights,)) as pool:
        for row in pool.imap_unordered(work, range(args.seed, args.seed + args.deals)):
            print(json.dumps(row), file=out, flush=True)


if __name__ == "__main__":
    main()
