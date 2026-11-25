"""Whole-deal, three-seat paired rule ablation for eval_control.py."""
from collections import Counter, defaultdict
from copy import deepcopy
import inspect
from math import comb, sqrt
import random
from statistics import mean, stdev
import time
from types import SimpleNamespace
from textwrap import dedent

from douzero.env.game import GameEnv

import server


RULES = ('endgame', 'take_lead', 'finish_two', 'keep_combos', 'must_beat', 'pass_mate', 'kickers')
ARMS = ('all', *('no_' + rule for rule in RULES), 'margin_0', 'margin_003', 'plain', 'solver_only')
_ADVISOR = None
_ACT = None


def compile_policy():
    # The two late guards are inline in production. Patch just their predicates
    # in a private function, and fail loudly if their source changes.
    source = dedent(inspect.getsource(server.Agent.act))
    for guard, rule in [('if not move and infoset.player_position == "landlord_up"', 'must_beat'),
                        ('if move and mate and infoset.last_pid == mate', 'pass_mate')]:
        if source.count(guard) != 1:
            raise RuntimeError(f'Agent.act changed; check the {rule} ablation')
        source = source.replace(guard, f"if '{rule}' in self.rules and " + guard[3:])
    namespace = vars(server).copy()
    exec(compile(source, '<ablation Agent.act>', 'exec'), namespace)
    return namespace['act']


def init():
    global _ACT, _ADVISOR
    server.torch.set_num_threads(1)
    _ADVISOR = server.Advisor(server.WEIGHTS, rollout=False)
    _ACT = compile_policy()


class Decision:
    """Share model/search draws only among arms at the exact same history."""
    rollout = False

    def __init__(self, info):
        self.agent = _ADVISOR.agents[info.player_position]
        self.likelihood = _ADVISOR.likelihood(info.card_play_action_seq, info.player_position)
        self.cache = {}
        self.credit = 0.
        self.rules = set()
        self.endgame = SimpleNamespace(SEATS=server.endgame.SEATS,
                                       without_double_joker_kickers=server.endgame.without_double_joker_kickers,
                                       best=self.best, finish_in_two=self.finish_two)

    def shared(self, key, fn, *args, **kwargs):
        if key in self.cache:
            value, elapsed = self.cache[key]
            self.credit += elapsed
            return value
        started = time.monotonic()
        value = fn(*args, **kwargs)
        self.cache[key] = (value, time.monotonic() - started)
        return value

    def monotonic(self):
        # A cache hit costs the original wall time against the caller's deadline.
        return time.monotonic() + self.credit

    def values(self, info):
        return self.shared('values', self.agent.values, info)

    def best(self, info, **kwargs):
        if 'endgame' in self.rules:
            return self.shared('best', server.endgame.best, info, **kwargs)

    def take_lead(self, info, values):
        if 'take_lead' in self.rules:
            return self.shared('lead', server.take_short_opponent_lead, info, values)

    def finish_two(self, *args, deadline, **kwargs):
        if 'finish_two' in self.rules:
            # A skipped upstream search can leave more time for this shortcut.
            key = ('finish', 'endgame' in self.rules, 'take_lead' in self.rules)
            return self.shared(key, server.endgame.finish_in_two, *args,
                               deadline=deadline - self.credit, **kwargs)

    def keep_combos(self, info, values, move):
        return self.agent.keep_combos(info, values, move) if 'keep_combos' in self.rules else move

    def swap_high_kickers(self, info, move, ok):
        return self.agent.swap_high_kickers(info, move, ok) if 'kickers' in self.rules else move

    def move(self, arm, info):
        self.credit = 0.
        self.rules = set(RULES)
        if arm.startswith('no_'):
            self.rules.remove(arm[3:])
        elif arm == 'solver_only':
            self.rules = {'endgame'}
        elif arm == 'plain':
            self.rules.clear()
        margin = {'margin_0': 0., 'margin_003': .03}.get(arm, server.ACT_ENDGAME_TIE_MARGIN)
        _ACT.__globals__.update(endgame=self.endgame, time=self, take_short_opponent_lead=self.take_lead,
                                ACT_ENDGAME_TIE_MARGIN=margin)
        return tuple(sorted(_ACT(self, info, likelihood=self.likelihood)))


class ChosenMove:
    def act(self, info):
        return list(self.move)


def play(deck, seat):
    selected = ChosenMove()
    env = GameEnv({s: selected for s in server.SEATS})
    env.card_play_init({'landlord': sorted(deck[:20]), 'landlord_up': sorted(deck[20:37]),
                        'landlord_down': sorted(deck[37:54]), 'three_landlord_cards': sorted(deck[17:20])})
    outcomes, changed, triggers = {}, Counter(), Counter()
    decisions = 0

    def run(env, arms):
        nonlocal decisions
        while not env.game_over:
            info = env.game_infoset
            pos = info.player_position
            if pos != seat:
                info.legal_actions = server.endgame.without_double_joker_kickers(info.legal_actions)
                selected.move = sorted(_ADVISOR.plain[pos].act(info))
                env.step()
                continue
            policy = Decision(info)
            # Shadow each counterfactual on the baseline path even after its
            # game has diverged. This fixes the conditioning set pre-outcome.
            compare = 'all' in arms
            choices = {arm: policy.move(arm, info) for arm in (ARMS if compare else arms)}
            if compare:
                decisions += 1
                for arm in ARMS:
                    differs = choices[arm] != choices['all']
                    changed[arm] += differs
                    triggers[arm] += differs and choices['all'] != choices['plain']
            groups = defaultdict(list)
            for arm in arms:
                groups[choices[arm]].append(arm)
            branches = list(groups.items())
            for move, subset in branches[1:]:
                branch = deepcopy(env, {id(env.players): env.players})
                selected.move = move
                branch.step()
                run(branch, subset)
            selected.move, arms = branches[0]
            env.step()

        role = 'landlord' if seat == 'landlord' else 'farmer'
        for arm in arms:
            outcomes[arm] = {'win': env.num_wins[role], 'score': env.num_scores[role],
                             'bombs': env.bomb_num}
    run(env, ARMS)
    return dict(decisions=decisions, triggers=triggers, changed=changed, outcomes=outcomes)


def work(seed):
    deck = list(server.DECK)
    random.Random(seed).shuffle(deck)
    return {'seed': seed, 'seats': {seat: play(deck, seat) for seat in server.SEATS}}


def validate(rows):
    if not rows or len({row['seed'] for row in rows}) != len(rows):
        raise ValueError('expected complete deals with distinct seeds')
    for row in rows:
        seats = row['seats']
        plain_scores = []
        for seat in server.SEATS:
            game = seats[seat]
            baseline = game['outcomes']['all']
            for arm in ARMS:
                outcome = game['outcomes'][arm]
                amount = (2 if seat == 'landlord' else 1) * 2**outcome['bombs']
                if outcome['score'] != (2*outcome['win'] - 1) * amount:
                    raise ValueError(f"score mismatch on {row['seed']} {seat} {arm}")
                if not 0 <= game['triggers'][arm] <= game['changed'][arm] <= game['decisions']:
                    raise ValueError('invalid trigger count')
                if not game['changed'][arm] and outcome != baseline:
                    raise ValueError('outcome diverged without a differing baseline action')
            plain_scores.append(game['outcomes']['plain']['score'])
        # With three plain players, changing the observer cannot change the game.
        if plain_scores[0] != -2 * plain_scores[1] or plain_scores[1] != plain_scores[2]:
            raise ValueError(f"plain controls disagree on {row['seed']}")


def invert_binomial(k, n, probability):
    """Solve P(X <= k) = probability for a sparse binomial count."""
    if k < 0:
        return 0.
    if k == n:
        return 1.
    if n - k - 1 < k:
        return 1 - invert_binomial(n-k-1, n, 1-probability)
    low, high = 0., 1.
    for _ in range(56):
        p = (low + high) / 2
        cdf = sum(comb(n, i) * p**i * (1-p)**(n-i) for i in range(k+1))
        if cdf > probability:
            low = p
        else:
            high = p
    return high


def sparse_interval(sums, max_count, fixed, limit):
    # E[net difference] = P(discordance) * E[difference | discordance].
    # Given discordance, an integer net difference has magnitude in [1, M].
    # Two exact 97.5% Clopper-Pearson intervals give joint coverage >=95%.
    n = len(sums)
    k = sum(x != 0 for x in sums)
    minimum_count = max_count if fixed else 1
    maximum_sum = max_count * limit
    if not k:
        upper = min(limit, maximum_sum * invert_binomial(0, n, .05) / minimum_count)
        return -upper, upper
    positive = sum(x > 0 for x in sums)
    event = (invert_binomial(k-1, n, .9875), invert_binomial(k, n, .0125))
    sign = (invert_binomial(positive-1, k, .9875), invert_binomial(positive, k, .0125))
    net = ((maximum_sum+1)*sign[0] - maximum_sum, (maximum_sum+1)*sign[1] - 1)
    endpoints = [p*x/d for p in event for x in net for d in (minimum_count, max_count)]
    return max(-limit, min(endpoints)), min(limit, max(endpoints))


def paired_estimate(groups, arm, metric, limit, max_count, fixed, structural):
    # Cluster ratio: sum of differences / number of eligible seat-games.
    # Triggered decks can contribute different numbers of eligible seats.
    counts = [len(g) for g in groups if g]
    sums = [sum(g['outcomes'][arm][metric] - g['outcomes']['all'][metric] for g in group)
            for group in groups if group]
    if not counts:
        return None, None, None
    delta = sum(sums) / sum(counts)
    if structural:
        return delta, (0., 0.), 0.
    n = len(sums)
    residuals = [x - delta*w for x, w in zip(sums, counts)]
    se = stdev(residuals) / (sqrt(n) * mean(counts)) if n > 1 else None
    k = sum(x != 0 for x in sums)
    if k < 10 or not se:
        ci = sparse_interval(sums, max_count, fixed, limit)
    else:
        ci = delta - 1.96*se, delta + 1.96*se
    return delta, ci, se


def statistics_for(rows, seats, arm):
    clusters = [[row['seats'][s] for s in seats] for row in rows]
    games = [game for cluster in clusters for game in cluster]
    cond = [[game for game in cluster if game['triggers'][arm]] for cluster in clusters]
    result = {'games': len(games), 'cond_n': sum(map(len, cond)),
              'turns': sum(g['decisions'] for g in games),
              'trigger_count': sum(g['triggers'][arm] for g in games),
              'changed_count': sum(g['changed'][arm] for g in games)}
    structural = arm == 'all' or (seats == ['landlord'] and arm in ('no_must_beat', 'no_pass_mate'))
    for metric in ('win', 'score'):
        result[metric] = mean(g['outcomes'][arm][metric] for g in games)
        for prefix, groups, fixed in [('d_', clusters, True), ('cond_', cond, False)]:
            # At most 13 four-of-a-kind bombs and one rocket in a deck.
            stakes = [2 if s == 'landlord' else 1 for s in seats]
            limit = 1 if metric == 'win' else 2 * 2**14 * (mean(stakes) if fixed else max(stakes))
            delta, ci, se = paired_estimate(groups, arm, metric, limit, len(seats), fixed, structural)
            result[prefix + metric] = delta
            result[prefix + 'ci_' + metric] = ci
            result[prefix + 'se_' + metric] = se
    return result


def summarize(rows):
    validate(rows)
    for role, seats in [('overall', server.SEATS), ('landlord', ['landlord']), ('farmer', server.SEATS[1:])]:
        print(f'\n### {role}\n')
        print('| arm | seat-games | win % | score | Δ win pp [95% CI] | Δ score [95% CI] '
              '| trigger % | triggered games | Δ score if triggered [95% CI] |')
        print('|---|---:|---:|---:|---:|---:|---:|---:|---:|')
        for arm in ARMS:
            r = statistics_for(rows, seats, arm)
            wl, wh = r['d_ci_win']
            sl, sh = r['d_ci_score']
            cond = '—' if r['cond_score'] is None else (
                f"{r['cond_score']:+.3f} [{r['cond_ci_score'][0]:+.3f}, {r['cond_ci_score'][1]:+.3f}]")
            print(f"| {arm} | {r['games']} | {100*r['win']:.2f} | {r['score']:+.3f} "
                  f"| {100*r['d_win']:+.2f} [{100*wl:+.2f}, {100*wh:+.2f}] "
                  f"| {r['d_score']:+.3f} [{sl:+.3f}, {sh:+.3f}] "
                  f"| {100*r['trigger_count']/r['turns']:.3f} | {r['cond_n']} | {cond} |")
    print('\n### Triggered games, all seats\n')
    print('| arm | seat-games | Δ win pp [95% CI] |')
    print('|---|---:|---:|')
    for arm in ARMS[1:]:
        r = statistics_for(rows, server.SEATS, arm)
        cell = '—'
        if r['cond_n']:
            low, high = r['cond_ci_win']
            cell = f"{100*r['cond_win']:+.2f} [{100*low:+.2f}, {100*high:+.2f}]"
        print(f"| {arm} | {r['cond_n']} | {cell} |")
