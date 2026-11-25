"""同副三座比较当前残局策略与纯 DouZero。每行 JSONL 包含整副配对结果。

DOUZERO_WEIGHTS=... nice -n 15 python -I eval_control.py --deals 50 --out /tmp/solver.jsonl
python -I eval_control.py --summarize /tmp/solver.jsonl
"""
import argparse
import json
import multiprocessing as mp
from pathlib import Path
import random
from statistics import mean, stdev
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server


_advisor = None


def init():
    global _advisor
    server.torch.set_num_threads(1)
    _advisor = server.Advisor(server.WEIGHTS)


class Player:
    def __init__(self, hero):
        self.hero = hero

    def act(self, info):
        seat = info.player_position
        if seat == self.hero:
            return sorted(_advisor.agents[seat].act(
                info, likelihood=_advisor.likelihood(info.card_play_action_seq, seat)))
        info.legal_actions = server.endgame.without_double_joker_kickers(info.legal_actions)
        return sorted(_advisor.plain[seat].act(info))


def play(deck, hero):
    player = Player(hero)
    env = server.GameEnv({s: player for s in server.SEATS})
    env.card_play_init({'landlord': sorted(deck[:20]), 'landlord_up': sorted(deck[20:37]),
                        'landlord_down': sorted(deck[37:]), 'three_landlord_cards': sorted(deck[17:20])})
    while not env.game_over:
        env.step()
    return {s: {'win': env.num_wins[s], 'score': env.num_scores[s]} for s in ('landlord', 'farmer')}


def work(seed):
    deck = list(server.DECK)
    random.Random(seed).shuffle(deck)
    plain = play(deck, None)
    seats = {}
    for seat in server.SEATS:
        role = 'landlord' if seat == 'landlord' else 'farmer'
        seats[seat] = {'outcomes': {'plain': plain[role], 'solver_only': play(deck, seat)[role]}}
    return {'seed': seed, 'seats': seats}


def summarize(rows):
    if not rows or len({row['seed'] for row in rows}) != len(rows):
        raise ValueError('expected complete deals with distinct seeds')
    print(f'{len(rows)} paired decks. Differences are solver_only minus plain.')
    for label, seats in [('overall', server.SEATS), ('landlord', ['landlord']), ('farmer', server.SEATS[1:])]:
        for metric in ('win', 'score'):
            deltas = [mean(row['seats'][s]['outcomes']['solver_only'][metric] -
                           row['seats'][s]['outcomes']['plain'][metric] for s in seats) for row in rows]
            value = mean(deltas)
            print(f'{label} {metric} {value:+.4f}', end='')
            if len(rows) > 1 and stdev(deltas) > 0:
                half = 1.96 * stdev(deltas) / len(rows)**.5
                print(f' (paired deck-level 95% CI {value-half:+.4f}, {value+half:+.4f})')
            else:
                print(' (insufficient observed variation for a normal CI)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed', type=int, default=20261125)
    parser.add_argument('--deals', type=int, default=1000)
    parser.add_argument('--workers', type=int, default=4, choices=range(1, 5))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--out', type=Path)
    mode.add_argument('--summarize', type=Path)
    args = parser.parse_args()
    if args.summarize:
        with args.summarize.open() as stream:
            summarize([json.loads(line) for line in stream])
        return
    if args.deals < 1:
        parser.error('--deals must be positive')
    rows = []
    with args.out.open('x') as stream, mp.Pool(args.workers, initializer=init) as pool:
        for row in pool.imap_unordered(work, range(args.seed, args.seed + args.deals)):
            print(json.dumps(row), file=stream, flush=True)
            rows.append(row)
    summarize(rows)


if __name__ == '__main__':
    main()
