"""概率表校准:随机发牌 + 贪心打牌,估计 (向听, 进张, 还能摸几张) → 赢牌概率。

服务启动时只读本脚本写出的 `prob_table.json` 做**粗排**;表里的每个格子是
「这个目标在 draws 张之内胡牌」的概率(千分比整数),按向听、进张、draws 单调。
粗排排在前面的 4 个(切牌 × 目标)再由服务对这手牌本身做固定种子蒙特卡洛,
最终排序和 `value` 用模拟结果,不用表的概率。所以表只决定「先模拟哪些候选」。

模型假设:
  * 牌墙完全随机,自己摸到有用的就留下,每次切牌选「切完向听最小、其次最孤立」的那张;
  * 进张(u)只扣自己手牌,不扣场上已见的牌;
  * 同一个 (向听 s, 进张 u) 的赢面用一个二项生存概率近似:
      q = C[s] * u / 94,need = s + 1,
      P(win by d) = Σ_{k=need..d} C(d,k) q^k (1-q)^(d-k)
    C[s] 由模拟的「第几摸胡牌」直方图做最大似然拟合,并且强制不随向听增大而增大,
    这样「向听越少概率不减」。94 是没有副露时自己看不到的牌数(108 - 14)。

怎么跑(在 tools/mahjong 下,用现有 venv):
  .venv/bin/python calibrate_prob.py --hands 12000 --seed 20261117
  # 想更快就 --hands 4000;想更稳就 --hands 30000(约几分钟)

本次提交的表:
  seed = 20261117,样本量 = 12000 手(随机 1/3 + 从胡牌回退的听牌/一向听 2/3),
  每手最多看 30 摸;详细数字见脚本运行输出和 README。

表只用纯模拟拟合结果,没有人为抬高/压低的下限。模拟的贪心打法就是「摸到有用的就留下、
切完向听最小」;多向听目标的真实赢面确实低,这是模型的一部分,不再为了某手牌的推荐去改。

依赖只用标准库 + mahjong==2.2.0(和 server.py 一样)。
"""
import argparse
import json
import math
import random
import time
from math import comb

from server import fast_shanten, filtered_regular_shanten, qidui_need, INDEX_SUIT

MAX_SHANTEN = 12
MAX_UKEIRE = 72
MAX_DRAWS = 30
UNSEEN = 94

TILE_TYPES = [f"{number}{suit}" for suit in "mps" for number in range(1, 10)]
DECK = [tile for tile in TILE_TYPES for _ in range(4)]


def _counts(tiles):
    counter = {}
    for tile in tiles:
        counter[tile] = counter.get(tile, 0) + 1
    return counter


def is_win_hand(tiles):
    """14 张、无定缺、无副露:普通型或七对。"""
    if filtered_regular_shanten(tiles, 0) == -1:
        return True
    return qidui_need(tiles) == 0


def isolation(tile, counter):
    suit, number = tile[1], int(tile[0])
    near = 0
    for delta in (-2, -1, 1, 2):
        other = number + delta
        if 1 <= other <= 9:
            near += counter.get(f"{other}{suit}", 0)
    return near


def choose_discard(hand):
    """切完向听最小;一样时切最孤立的;再一样按牌序,保证可重复。"""
    counter = _counts(hand)
    best = None
    for tile in sorted(set(hand), key=lambda t: (t[1], int(t[0]))):
        rest = list(hand)
        rest.remove(tile)
        score = (fast_shanten(rest, 0), isolation(tile, counter), tile[1], int(tile[0]))
        if best is None or score < best[0]:
            best = (score, tile)
    return best[1]


def count_ukeire(hand, shanten):
    counter = _counts(hand)
    total = 0
    for tile in TILE_TYPES:
        left = 4 - counter.get(tile, 0)
        if left <= 0:
            continue
        if fast_shanten(hand + [tile], 0) < shanten:
            total += left
    return total


def random_winning_hand(rng):
    """随机拼一手 14 张的胡牌:4 组(顺子/刻子)+ 1 对,每种牌不超过 4 张。"""
    while True:
        counts = [0] * 27
        ok = True
        for _ in range(4):
            suit = rng.randrange(3)
            if rng.random() < 0.5:
                start = rng.randrange(7)
                tiles = [suit * 9 + start + step for step in range(3)]
            else:
                tile = suit * 9 + rng.randrange(9)
                tiles = [tile] * 3
            for tile in tiles:
                counts[tile] += 1
                if counts[tile] > 4:
                    ok = False
                    break
            if not ok:
                break
        if not ok:
            continue
        pair = rng.randrange(27)
        if counts[pair] + 2 > 4:
            continue
        counts[pair] += 2
        return [f"{index % 9 + 1}{INDEX_SUIT[index // 9]}" for index, count in enumerate(counts)
                for _ in range(count)]


def near_win_hand(rng):
    """从一手胡牌里拿掉 1 张 → 听牌;拿掉 2 张再随便补 1 张 → 大多一向听。"""
    hand = random_winning_hand(rng)
    remove = 1 if rng.random() < 0.5 else 2
    for _ in range(remove):
        hand.pop(rng.randrange(len(hand)))
    if remove == 2:
        wall = list(DECK)
        for tile in hand:
            wall.remove(tile)
        hand.append(rng.choice(wall))
    return hand


def random_hand(rng):
    wall = list(DECK)
    rng.shuffle(wall)
    return wall[:13]


def play_out(hand, rng):
    wall = list(DECK)
    for tile in hand:
        wall.remove(tile)
    rng.shuffle(wall)
    hand = list(hand)
    for turn in range(1, MAX_DRAWS + 1):
        hand.append(wall.pop())
        if is_win_hand(hand):
            return turn
        hand.remove(choose_discard(hand))
    return MAX_DRAWS + 1


def survival(draws, need, q):
    if need <= 0:
        return 1.0
    if draws < need:
        return 0.0
    if q <= 0:
        return 0.0
    if q >= 1:
        return 1.0
    return sum(comb(draws, k) * q ** k * (1 - q) ** (draws - k) for k in range(need, draws + 1))


def fit_coefficients(hist):
    """每个向听一个系数 C[s];按「第几摸胡」的似然网格搜索,再强制非增。"""
    coefficients = []
    for shanten in range(MAX_SHANTEN + 1):
        rows = hist[shanten]
        total = sum(sum(row) for row in rows)
        if total < 30:
            coefficients.append(None)
            continue
        need = shanten + 1
        # 按进张分桶(每 4 张一桶)降噪
        buckets = {}
        for ukeire, row in enumerate(rows):
            bucket = min(ukeire // 4, MAX_UKEIRE // 4)
            target = buckets.setdefault(bucket, [0] * (MAX_DRAWS + 2))
            for turn, count in enumerate(row):
                target[turn] += count
        best = None
        for step in range(1, 201):  # C = 0.05 .. 2.00
            coefficient = step / 100
            log_likelihood = 0.0
            for bucket, row in buckets.items():
                u = min(bucket * 4 + 2, MAX_UKEIRE)
                q = min(1.0, coefficient * u / UNSEEN)
                previous = 0.0
                for turn in range(1, MAX_DRAWS + 1):
                    current = survival(turn, need, q)
                    exact = current - previous
                    if row[turn] and exact <= 0:
                        log_likelihood = -math.inf
                        break
                    if row[turn]:
                        log_likelihood += row[turn] * math.log(exact)
                    previous = current
                if log_likelihood == -math.inf:
                    break
                censored = 1.0 - previous
                if row[MAX_DRAWS + 1] and censored <= 0:
                    log_likelihood = -math.inf
                    break
                if row[MAX_DRAWS + 1]:
                    log_likelihood += row[MAX_DRAWS + 1] * math.log(censored)
            if best is None or log_likelihood > best[0]:
                best = (log_likelihood, coefficient)
        coefficients.append(best[1])
    # 没样本的向听用上一档;再强制 C[s] 不随 s 增大
    for shanten in range(MAX_SHANTEN + 1):
        if coefficients[shanten] is None:
            coefficients[shanten] = coefficients[shanten - 1] if shanten else 1.0
        if shanten:
            coefficients[shanten] = min(coefficients[shanten], coefficients[shanten - 1])
    return coefficients


def build_table(coefficients):
    milli = [[[0] * (MAX_DRAWS + 1) for _ in range(MAX_UKEIRE + 1)]
             for _ in range(MAX_SHANTEN + 1)]
    for shanten in range(MAX_SHANTEN + 1):
        need = shanten + 1
        for ukeire in range(MAX_UKEIRE + 1):
            q = min(1.0, coefficients[shanten] * ukeire / UNSEEN)
            for draws in range(need, MAX_DRAWS + 1):
                milli[shanten][ukeire][draws] = int(round(survival(draws, need, q) * 1000))
    # 把四舍五入可能造成的极小反序压平,保证三项单调
    for shanten in range(1, MAX_SHANTEN + 1):
        for ukeire in range(MAX_UKEIRE + 1):
            for draws in range(MAX_DRAWS + 1):
                milli[shanten][ukeire][draws] = min(milli[shanten][ukeire][draws],
                                                    milli[shanten - 1][ukeire][draws])
    for shanten in range(MAX_SHANTEN + 1):
        for ukeire in range(MAX_UKEIRE + 1):
            for draws in range(1, MAX_DRAWS + 1):
                milli[shanten][ukeire][draws] = max(milli[shanten][ukeire][draws],
                                                    milli[shanten][ukeire][draws - 1])
    for shanten in range(MAX_SHANTEN + 1):
        for ukeire in range(1, MAX_UKEIRE + 1):
            for draws in range(MAX_DRAWS + 1):
                milli[shanten][ukeire][draws] = max(milli[shanten][ukeire][draws],
                                                    milli[shanten][ukeire - 1][draws])
    return milli


def check_monotonic(milli):
    """服务依赖的三条单调性:向听少、进张多、draws 多,概率都不减。"""
    for shanten in range(1, MAX_SHANTEN + 1):
        for ukeire in range(MAX_UKEIRE + 1):
            for draws in range(MAX_DRAWS + 1):
                if milli[shanten][ukeire][draws] > milli[shanten - 1][ukeire][draws]:
                    raise SystemExit(f"向听单调破了: s={shanten} u={ukeire} d={draws}")
    for shanten in range(MAX_SHANTEN + 1):
        for ukeire in range(1, MAX_UKEIRE + 1):
            for draws in range(MAX_DRAWS + 1):
                if milli[shanten][ukeire][draws] < milli[shanten][ukeire - 1][draws]:
                    raise SystemExit(f"进张单调破了: s={shanten} u={ukeire} d={draws}")
        for ukeire in range(MAX_UKEIRE + 1):
            for draws in range(1, MAX_DRAWS + 1):
                if milli[shanten][ukeire][draws] < milli[shanten][ukeire][draws - 1]:
                    raise SystemExit(f"draws 单调破了: s={shanten} u={ukeire} d={draws}")
    print("table monotonic ok")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--hands", type=int, default=12000, help="模拟手数(默认 12000)")
    parser.add_argument("--seed", type=int, default=20261117)
    parser.add_argument("--out", default="prob_table.json")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    hist = [[[0] * (MAX_DRAWS + 2) for _ in range(MAX_UKEIRE + 1)]
            for _ in range(MAX_SHANTEN + 1)]
    started = time.time()
    used = 0
    for index in range(args.hands):
        if index % 3 == 0:
            hand = random_hand(rng)
        else:
            hand = near_win_hand(rng)
        shanten = fast_shanten(hand, 0)
        if shanten < 0:
            continue
        shanten = min(shanten, MAX_SHANTEN)
        ukeire_count = min(count_ukeire(hand, shanten), MAX_UKEIRE)
        turn = play_out(hand, rng)
        hist[shanten][ukeire_count][turn] += 1
        used += 1
        if used % 500 == 0:
            print(f"  {used}/{args.hands} 手,{time.time() - started:.0f}s", flush=True)

    coefficients = fit_coefficients(hist)
    milli = build_table(coefficients)
    check_monotonic(milli)
    data = {
        "seed": args.seed,
        "hands": used,
        "unseen": UNSEEN,
        "max_shanten": MAX_SHANTEN,
        "max_ukeire": MAX_UKEIRE,
        "max_draws": MAX_DRAWS,
        "coefficients": [round(coefficient, 4) for coefficient in coefficients],
        "milli": milli,
    }
    with open(args.out, "w", encoding="utf-8") as file:
        json.dump(data, file, separators=(",", ":"))
    print(f"wrote {args.out}: {used} 手,{time.time() - started:.0f}s")
    print("C[s] =", [round(coefficient, 3) for coefficient in coefficients])
    for shanten, ukeire in ((0, 4), (0, 8), (1, 12), (1, 32), (2, 12), (2, 25), (3, 40)):
        q = min(1.0, coefficients[shanten] * ukeire / UNSEEN)
        print(f"  P(s={shanten}, u={ukeire}, draws=8) = {survival(8, shanten + 1, q):.3f}")


if __name__ == "__main__":
    main()
