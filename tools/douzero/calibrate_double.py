"""加倍门槛校准:随机发牌,只留地主会叫的,用新的 Advisor.double 估自己这一方的赢面,再让三家 DouZero
把这副牌打完,按估值分桶看「估值 → 实际胜率、样本数」,给出 FARMER_DOUBLE_WIN / SUPER_DOUBLE_WIN 的建议值。

农民模式(默认)每个地主会叫的牌局算两个农民座位;地主模式只算地主这一方,用来定超级加倍门槛。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  .venv/bin/python calibrate_double.py --deals 2000 --workers 8
  .venv/bin/python calibrate_double.py --mode landlord --deals 1000 --workers 8
"""
import argparse
import json
import multiprocessing as mp
import os
import random
import statistics
import time
from collections import defaultdict

from douzero.env.game import GameEnv

from server import Advisor, BID_BATCH, DECK, DOUBLE_WIN, NAMES, WEIGHTS

_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS, rollout=False)
    return _ADVISOR


def sample_deals(count, seed):
    """随机发牌,只留地主那 20 张(17 + 底牌)会叫的。返回 (底牌, 地主, 上家, 下家)。"""
    a = advisor()
    rng = random.Random(seed)
    deals = []
    while len(deals) < count:
        # 每副牌有自己的底牌,逐副判;一副约 3 毫秒
        for _ in range(BID_BATCH):
            deck = list(DECK)
            rng.shuffle(deck)
            bottom, lord = sorted(deck[:3]), sorted(deck[3:20] + deck[:3])
            if a.landlord_would_call(lord, bottom):
                deals.append((bottom, lord, sorted(deck[20:37]), sorted(deck[37:54])))
                if len(deals) >= count:
                    break
    return deals


def play_out(bottom, landlord, up, down):
    env = GameEnv(advisor().plain)
    env.card_play_init({"landlord": list(landlord), "landlord_up": list(up), "landlord_down": list(down),
                        "three_landlord_cards": list(bottom)})
    while not env.game_over:
        env.step()
    return bool(env.num_wins["farmer"])


def estimate(seat, hand, bottom, games, budget, min_games):
    win, played = advisor().double(seat, [NAMES[c] for c in hand], [NAMES[c] for c in bottom],
                                   games=games, budget=budget, min_games=min_games)
    return win, played


def work(job):
    deal, games, budget, min_games = job
    bottom, landlord, up, down = deal
    actual = play_out(bottom, landlord, up, down)
    rows = []
    for seat, hand in (("landlord_up", up), ("landlord_down", down)):
        win, played = estimate(seat, hand, bottom, games, budget, min_games)
        if played >= min_games:
            rows.append((win, actual, seat, played))
    return rows


def work_landlord(job):
    """地主模式:估地主这一方的赢面,实际输赢是同一副牌地主有没有赢。"""
    deal, games, budget, min_games = job
    bottom, landlord, up, down = deal
    actual = not play_out(bottom, landlord, up, down)
    win, played = estimate("landlord", landlord, bottom, games, budget, min_games)
    return [(win, actual, "landlord", played)] if played >= min_games else []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("farmer", "landlord"), default="farmer",
                        help="farmer:两个农民座位;landlord:只算地主,用来定超级加倍门槛")
    parser.add_argument("--deals", type=int, default=2000, help="留多少副地主会叫的牌")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--seed", type=int, default=20261116)
    parser.add_argument("--games", type=int, default=12, help="每一方估值打几局")
    parser.add_argument("--budget", type=float, default=0.8, help="每一方估值最多几秒")
    parser.add_argument("--min-games", type=int, default=8, help="少于这么多局就跳过这一行")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    out = args.out or f"calibrate_double_{args.mode}.json"
    worker = work_landlord if args.mode == "landlord" else work

    started = time.time()
    # 用 #99 的条件抽样估值(landlords_would_call)代替逐副跑 bid:只留地主会叫的牌,和 /bid 的口径一致
    deals = sample_deals(args.deals, args.seed)
    print(f"mode={args.mode} deals={len(deals)} sampled in {time.time() - started:.1f}s", flush=True)

    jobs = [(deal, args.games, args.budget, args.min_games) for deal in deals]
    rows = []
    if args.workers > 1:
        with mp.Pool(args.workers) as pool:
            for i, part in enumerate(pool.imap_unordered(worker, jobs, chunksize=8), 1):
                rows.extend(part)
                if i % 100 == 0:
                    print(f"{i}/{len(deals)} deals, {time.time() - started:.0f}s", flush=True)
    else:
        for i, job in enumerate(jobs, 1):
            rows.extend(worker(job))
            if i % 100 == 0:
                print(f"{i}/{len(deals)} deals, {time.time() - started:.0f}s", flush=True)

    json.dump([{"win": w, "actual": a, "seat": s, "games": g} for w, a, s, g in rows], open(out, "w"))
    print(f"rows={len(rows)} in {time.time() - started:.0f}s -> {out}")

    buckets = defaultdict(list)
    for win, actual, _, _ in rows:
        buckets[round(win / 0.05) * 0.05].append(actual)
    print("\n估值    实际胜率  样本数")
    for key in sorted(buckets):
        values = buckets[key]
        print(f"{key:.2f}    {statistics.mean(values):.3f}     {len(values)}")

    keys = sorted(buckets)
    if args.mode == "landlord":
        # 实际胜率 >= 0.85 的最低估值档(本档样本 >= 30),且不低于普通加倍门槛
        best = None
        for key in keys:
            values = buckets[key]
            if len(values) >= 30 and statistics.mean(values) >= 0.85:
                best = key
                break
        if best is None:
            print("\n建议 SUPER_DOUBLE_WIN = 没有样本 >= 30 且实际胜率 >= 0.85 的档")
        else:
            print(f"\n建议 SUPER_DOUBLE_WIN = {max(best, DOUBLE_WIN):.2f} "
                  f"(实际胜率 >= 0.85 的最低估值档 {best:.2f},不低于 DOUBLE_WIN {DOUBLE_WIN})")
        return

    # 实际胜率稳定 >= 0.6 的最低估值:本档样本 >= 30 且下一档也 >= 0.6
    best = None
    for i, key in enumerate(keys):
        values = buckets[key]
        if len(values) < 30 or statistics.mean(values) < 0.6:
            continue
        if i + 1 < len(keys):
            nxt = buckets[keys[i + 1]]
            if len(nxt) < 30 or statistics.mean(nxt) < 0.6:
                continue
        best = key
        break
    print(f"\n建议 FARMER_DOUBLE_WIN = {best if best is not None else '没有稳定 >= 0.6 的档'}")


if __name__ == "__main__":
    main()
