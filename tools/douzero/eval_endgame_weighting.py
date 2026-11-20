"""残局加权自对弈评测:同一个残局分别用「加权 / 不加权」两种方式打到底,配对比较主角胜率。

随机发牌,三家都用 plain DouZero 打到「三家合计 <= endgame.MAX_CARDS 且分法 <= endgame.MAX_SPLITS」
的第一个决策点;记下这个残局(初始三家手牌、底牌、到这一手为止的 history、轮到谁)。
之后每个残局做两次配对:主角(轮到的那个座位)在后续每一手都只用加权 / 只用不加权,
另外两家是对手。两种对手各跑一遍:
  plain —— 对手每手都取 DouZero 分数最高的一手(剔掉本玩法不允许的大小王带牌);
  noisy —— 对手对 DouZero 分数按温度 0.2 做 softmax 采样出牌(不是 30% 第二高分)。
主角在地主/农民两边都要有,分别统计。同一残局两种方式共用同一个对手随机种子。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  DOUZERO_WEIGHTS=... .venv/bin/python eval_endgame_weighting.py --deals 250 --workers 10
  # --deals 是每个主角阵营的残局数(默认 250),所以每种对手共 2*deals 个;
  # 冒烟:--deals 20 --workers 4
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

from server import Advisor, DECK, NAMES, SEATS, WEIGHTS, Replay
import endgame
import server

# 对手噪声:DouZero 分数按这个温度 softmax 采样。取 0.2 与残局似然的温度同量级,
# 分数差 0.2 时首选/次选约 73% / 27%,足够让对手偶尔不按最优出牌,又不会乱出。
NOISY_TEMPERATURE = 0.2
OPPONENTS = ("plain", "noisy")
SIDES = ("landlord", "farmer")

_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS, rollout=False)
    return _ADVISOR


def names(cards):
    return [NAMES[c] for c in cards]


def legal_indices(infoset):
    """本玩法里真正能出的牌(剔掉大小王一起当带牌的那几手)。"""
    return [i for i, action in enumerate(infoset.legal_actions)
            if not endgame.double_joker_kickers(action)]


class PlainOpponent:
    """对手:取 DouZero 分数最高的一手。"""

    def __init__(self, adv):
        self.adv = adv

    def act(self, infoset):
        kept = legal_indices(infoset)
        if len(kept) <= 1:
            return list(infoset.legal_actions[kept[0]]) if kept else []
        values = self.adv.agents[infoset.player_position].values(infoset)
        pick = max(kept, key=lambda i: float(values[i]))
        return list(infoset.legal_actions[pick])


class NoisyOpponent:
    """对手:对 DouZero 分数按 NOISY_TEMPERATURE 做 softmax 采样。随机流由 [seed] 固定。"""

    def __init__(self, adv, seed):
        self.adv = adv
        self.rng = random.Random(seed)

    def act(self, infoset):
        kept = legal_indices(infoset)
        if len(kept) <= 1:
            return list(infoset.legal_actions[kept[0]]) if kept else []
        values = self.adv.agents[infoset.player_position].values(infoset)
        logits = [float(values[i]) / NOISY_TEMPERATURE for i in kept]
        top = max(logits)
        weights = [math.exp(x - top) for x in logits]
        total = sum(weights)
        roll = self.rng.random() * total
        acc = 0.0
        for i, weight in zip(kept, weights):
            acc += weight
            if roll <= acc:
                return list(infoset.legal_actions[i])
        return list(infoset.legal_actions[kept[-1]])


class EndgameAgent:
    """主角:之后每一手都走同一种方式(加权或不加权),记下每手耗时和第一手。"""

    def __init__(self, adv, weighted, seat):
        self.adv = adv
        self.weighted = weighted
        self.seat = seat
        self.ms = 0.0
        self.moves = 0
        self.first = None

    def act(self, infoset):
        if infoset.player_position != self.seat:
            raise RuntimeError(f"主角色不对: {infoset.player_position} != {self.seat}")
        started = time.perf_counter()
        if self.weighted:
            history = [list(m) for m in infoset.card_play_action_seq]
            move = self.adv.agents[self.seat].act(
                infoset, likelihood=self.adv.likelihood(history, self.seat))
        else:
            move = self.adv.agents[self.seat].act(infoset)
        self.ms += (time.perf_counter() - started) * 1000
        self.moves += 1
        if self.first is None:
            self.first = tuple(sorted(move))
        return move


def generate_endgame(rng, record_id):
    """随机发一副牌,三家 plain DouZero 打到第一个可算的残局决策点。"""
    deck = list(DECK)
    rng.shuffle(deck)
    initial = {"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
               "landlord_down": sorted(deck[37:54])}
    bottom = sorted(deck[17:20])
    env = GameEnv({s: advisor().plain[s] for s in SEATS})
    env.card_play_init({**{s: list(initial[s]) for s in SEATS}, "three_landlord_cards": list(bottom)})
    for _ in range(200):
        if env.game_over:
            return None
        info = env.game_infoset
        left = info.num_cards_left_dict
        if sum(left.values()) <= endgame.MAX_CARDS:
            me = info.player_position
            others = [s for s in SEATS if s != me]
            unknown = sorted(info.other_hand_cards)
            if len(unknown) == left[others[0]] + left[others[1]]:
                ways = endgame.splits(unknown, others[0], left[others[0]],
                                      others[1], left[others[1]])
                if ways is not None:
                    return {
                        "id": record_id,
                        "initial": {s: list(initial[s]) for s in SEATS},
                        "bottom": list(bottom),
                        "history": [list(m) for m in env.card_play_action_seq],
                        "seat": me,
                        "side": "landlord" if me == "landlord" else "farmer",
                        "splits": len(ways),
                    }
        env.step()
    return None


def play(record, opponent, weighted, seed):
    """从一个残局打到底:主角用 [weighted] 方式,另外两家用 [opponent] 方式。"""
    adv = advisor()
    seat = record["seat"]
    replay = Replay(record["history"])
    env = GameEnv({s: replay for s in SEATS})
    env.card_play_init({**{s: list(record["initial"][s]) for s in SEATS},
                        "three_landlord_cards": list(record["bottom"])})
    while replay.moves:
        env.step()
    if env.game_over:
        return None
    if env.acting_player_position != seat:
        raise RuntimeError(f"重放后不是主角: {env.acting_player_position} != {seat}")
    hero = EndgameAgent(adv, weighted, seat)
    players = {}
    for s in SEATS:
        if s == seat:
            players[s] = hero
        elif opponent == "noisy":
            players[s] = NoisyOpponent(adv, seed)
        else:
            players[s] = PlainOpponent(adv)
    env.players = players
    while not env.game_over:
        env.step()
    winner = "landlord" if env.num_wins["landlord"] else "farmer"
    win = winner == ("landlord" if seat == "landlord" else "farmer")
    return {
        "win": int(win),
        "first": list(hero.first) if hero.first is not None else [],
        "ms": hero.ms,
        "moves": hero.moves,
    }


def evaluate(record):
    """两种对手各跑一遍,同一残局里加权/不加权配对。"""
    row = {"id": record["id"], "side": record["side"], "seat": record["seat"],
           "splits": record["splits"], "opponents": {}}
    for opponent in OPPONENTS:
        # 交替先后,抵消同一进程里第一次前向的冷启动对耗时的影响
        order = ["unweighted", "weighted"] if (record["id"] + (opponent == "noisy")) % 2 == 0 \
            else ["weighted", "unweighted"]
        seed = record["id"] * 1000003 + (1 if opponent == "noisy" else 0)
        results = {mode: play(record, opponent, mode == "weighted", seed) for mode in order}
        if results["weighted"] is None or results["unweighted"] is None:
            row["opponents"][opponent] = None
            continue
        row["opponents"][opponent] = {mode: results[mode] for mode in ("weighted", "unweighted")}
    return row


def work(record):
    return evaluate(record)


def set_likelihood_floor(floor):
    if floor is not None:
        server.LIKELIHOOD_FLOOR = floor


def ci95(diffs):
    """配对差值的均值和 95% 置信区间(百分比点)。"""
    n = len(diffs)
    if n == 0:
        return 0.0, 0.0, 0.0
    mean = statistics.mean(diffs)
    sd = statistics.stdev(diffs) if n > 1 else 0.0
    half = 1.96 * sd / math.sqrt(n)
    return mean * 100, (mean - half) * 100, (mean + half) * 100


def summarize(rows):
    summary = {}
    for opponent in OPPONENTS:
        summary[opponent] = {}
        for side in SIDES + ("both",):
            subset = [r for r in rows
                      if r["opponents"].get(opponent) is not None
                      and (side == "both" or r["side"] == side)]
            diffs = [r["opponents"][opponent]["weighted"]["win"]
                     - r["opponents"][opponent]["unweighted"]["win"] for r in subset]
            w_ms = sum(r["opponents"][opponent]["weighted"]["ms"] for r in subset)
            w_moves = sum(r["opponents"][opponent]["weighted"]["moves"] for r in subset)
            u_ms = sum(r["opponents"][opponent]["unweighted"]["ms"] for r in subset)
            u_moves = sum(r["opponents"][opponent]["unweighted"]["moves"] for r in subset)
            first_diff = [r for r in subset
                          if r["opponents"][opponent]["weighted"]["first"]
                          != r["opponents"][opponent]["unweighted"]["first"]]
            summary[opponent][side] = {
                "n": len(subset),
                "weighted_wins": sum(r["opponents"][opponent]["weighted"]["win"] for r in subset),
                "unweighted_wins": sum(r["opponents"][opponent]["unweighted"]["win"] for r in subset),
                "diff_pp": ci95(diffs),
                "first_diff_n": len(first_diff),
                "first_diff_weighted_wins": sum(
                    r["opponents"][opponent]["weighted"]["win"] for r in first_diff),
                "first_diff_unweighted_wins": sum(
                    r["opponents"][opponent]["unweighted"]["win"] for r in first_diff),
                "weighted_ms_per_move": w_ms / w_moves if w_moves else 0.0,
                "unweighted_ms_per_move": u_ms / u_moves if u_moves else 0.0,
                "moves": (w_moves, u_moves),
            }
    return summary


def print_summary(rows):
    summary = summarize(rows)
    print("\n对手    主角    样本  加权胜率  不加权胜率  配对差(pp)   95% CI(pp)     第一手不同")
    for opponent in OPPONENTS:
        for side in SIDES + ("both",):
            s = summary[opponent][side]
            n = s["n"]
            wr = s["weighted_wins"] / n if n else 0.0
            ur = s["unweighted_wins"] / n if n else 0.0
            mean, low, high = s["diff_pp"]
            fd = s["first_diff_n"]
            fd_w = s["first_diff_weighted_wins"] / fd if fd else 0.0
            fd_u = s["first_diff_unweighted_wins"] / fd if fd else 0.0
            label = "两边" if side == "both" else ("地主" if side == "landlord" else "农民")
            print(f"{opponent:7s} {label:4s} {n:5d}  {wr:8.3f}  {ur:10.3f}  "
                  f"{mean:+9.2f}  [{low:+7.2f},{high:+7.2f}]  {fd:4d}  ({fd_w:.3f}/{fd_u:.3f})")
    print("\n每手耗时(毫秒):")
    for opponent in OPPONENTS:
        for side in SIDES + ("both",):
            s = summary[opponent][side]
            extra = s["weighted_ms_per_move"] - s["unweighted_ms_per_move"]
            label = "两边" if side == "both" else ("地主" if side == "landlord" else "农民")
            print(f"{opponent:7s} {label:4s} 加权 {s['weighted_ms_per_move']:8.1f}  "
                  f"不加权 {s['unweighted_ms_per_move']:8.1f}  多花 {extra:+8.1f}  "
                  f"(手数 {s['moves'][0]}/{s['moves'][1]})")
    verdict = []
    for opponent in OPPONENTS:
        low = summary[opponent]["both"]["diff_pp"][1]
        ok = low >= -1.0
        verdict.append((opponent, low, ok))
    print("\n判定(配对差值 95% CI 下界 >= -1 个百分点才算可以合):")
    for opponent, low, ok in verdict:
        print(f"  {opponent}: 下界 {low:+.2f}pp -> {'可以合' if ok else '不建议合'}")
    print(f"结论: {'可以合' if all(ok for _, _, ok in verdict) else '不建议合'}")
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=250,
                        help="每个主角阵营采多少残局(每种对手共 2*deals 个)")
    parser.add_argument("--workers", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument("--seed", type=int, default=20261116)
    parser.add_argument("--out", default="eval_endgame_weighting.json")
    parser.add_argument("--likelihood-floor", type=float, help="覆盖残局似然下限(0 到 1)")
    args = parser.parse_args()
    if args.likelihood_floor is not None and not 0 <= args.likelihood_floor <= 1:
        parser.error("--likelihood-floor 必须在 0 到 1 之间")
    set_likelihood_floor(args.likelihood_floor)

    started = time.time()
    rng = random.Random(args.seed)
    by_side = {side: [] for side in SIDES}
    attempts = 0
    max_attempts = max(2000, 60 * args.deals)
    while any(len(by_side[side]) < args.deals for side in SIDES) and attempts < max_attempts:
        attempts += 1
        record = generate_endgame(rng, attempts - 1)
        if record is not None and len(by_side[record["side"]]) < args.deals:
            by_side[record["side"]].append(record)
            if len(by_side["landlord"]) + len(by_side["farmer"]) in (1, 10, 20) \
                    or (len(by_side["landlord"]) + len(by_side["farmer"])) % 50 == 0:
                print(f"残局 {len(by_side['landlord']) + len(by_side['farmer'])}"
                      f"(地主 {len(by_side['landlord'])} / 农民 {len(by_side['farmer'])}) "
                      f"attempts={attempts} {time.time() - started:.0f}s", flush=True)
    records = by_side["landlord"] + by_side["farmer"]
    print(f"\n生成 {len(records)} 个残局(地主 {len(by_side['landlord'])} / 农民 {len(by_side['farmer'])}),"
          f" attempts={attempts}, {time.time() - started:.0f}s", flush=True)
    if len(records) < 2 * args.deals:
        print(f"警告:只采到 {len(records)} / {2 * args.deals} 个残局", flush=True)

    rows = []
    if args.workers > 1:
        chunksize = max(1, len(records) // (args.workers * 4))
        with mp.Pool(args.workers, initializer=set_likelihood_floor, initargs=(args.likelihood_floor,)) as pool:
            for i, row in enumerate(pool.imap_unordered(work, records, chunksize=chunksize), 1):
                rows.append(row)
                if i % 20 == 0:
                    print(f"{i}/{len(records)} 残局, {time.time() - started:.0f}s", flush=True)
    else:
        for i, record in enumerate(records, 1):
            rows.append(work(record))
            if i % 5 == 0:
                print(f"{i}/{len(records)} 残局, {time.time() - started:.0f}s", flush=True)

    summary = print_summary(rows)
    json.dump({"args": vars(args), "rows": rows, "summary": summary},
              open(args.out, "w"), ensure_ascii=False, indent=1)
    print(f"\n{len(rows)} 行 -> {args.out}, {time.time() - started:.0f}s")


if __name__ == "__main__":
    mp.freeze_support()
    main()
