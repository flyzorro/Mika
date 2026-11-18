"""四川麻将血战到底出牌建议服务:给 Mika 的安卓端用。只在本机或局域网里跑,没有鉴权。

按期望得分推荐:每种打法对「快胡 / 清一色 / 碰碰胡 / 七对」各估一次
「能胡的概率 × 胡了值几倍」,取期望最高的目标。默认端口 3428。

POST /discard
  {"hand": ["1m", "2m", ...],                 # 自己此刻的手牌,14 张(或还没摸牌时 13 张)
   "melds": [{"type": "pon", "tile": "5p"}],  # 可选,碰/杠;可省略或为空;没有吃
   "que": "s",                                # 定缺,m/p/s 之一
   "draws": 8}                                # 可选,自己还能摸几张,1–30,默认 8
# 碰算 3 张、杠算 4 张;手牌张数 + 3 × 副露组数 = 14

成功(14 张、合法):
  {"win": false, "discard": "5s", "target": "qingyise", "fan": 2, "value": 1.35,
   "shanten": 2, "ukeire": 12, "waits": ["1m", ...], "queLeft": 0,
   "options": [{"discard": "5s", "target": "qingyise", "fan": 2, "value": 1.35,
                "shanten": 2, "ukeire": 12, "waits": ["1m", ...]}, ...]}
  options 最多 3 个,第一个就是推荐;已胡时只返回 {"win": true}。
  target 是 fast(快胡,普通型或七对)、qingyise(清一色)、pengpeng(碰碰胡)或 qidui(七对)。
  fan 是这个目标胡了的番数;value 是期望倍数(赢牌概率 × 番数倍数,保留 2 位)。
  value 不是查表值:表只用来粗排,排在前面的 4 个(切牌 × 目标)会对这手牌本身做固定种子
  蒙特卡洛(牌墙 = 108 − 自己的牌,按目标贪心切牌;draws≤14 每候选 200 局,>14 100 局)。
  排序时同一 target 下两个候选的赢牌率落在 95% 置信区间内就算分不出高低,先比向听、
  再比进张(否则 200 局的噪声会把拆对子退向听的打法排到前面),所以 options 的 value 不保证单调。
  shanten/ukeire/waits 指的是这个目标的向听、理论进张和进张牌。
  waits/ukeire 只扣自己手牌和副露,不扣场上已经打出去的牌。
  queLeft 是切牌前手里还有几张定缺牌;有定缺牌时先切定缺牌,shanten/ukeire 只按非定缺门算。

POST /que
  {"hand": ["1m", ... 13 或 14 张 ...], "draws": 8}   # 开局定缺建议;没有碰杠
成功:
  {"que": "s", "options": [{"que": "s", "target": "qingyise", "value": 1.2},
                          {"que": "p", "target": "fast", "value": 0.9},
                          {"que": "m", "target": "fast", "value": 0.4}]}
  三门都列出,按期望从高到低;期望一样时选张数少的那门,再一样按 m<p<s。
  value 同样是每门做固定种子蒙特卡洛得到的(draws≤14 70 局,>14 50 局)。

POST /hint
  {"image": "<2412×1080 整屏 PNG 或 JPEG 的 base64>"}   # JJ麻将主屏截图,按文件头识别
成功 state 取 turn / win / que_select / wait / unsure:
  turn:       {"state":"turn","hand":[...],"melds":[...],"que":"p","left":31,"draws":8,
               "jian":"9s"或 null,"advice":{…/discard 的返回…}}
  win:        {"state":"win",同上的识别字段,没有 advice}
  que_select: {"state":"que_select","hand":[...],"advice":{…/que 的返回…}}
              # 屏上出现「请选择定缺的花色」时,建议定缺哪门
  wait:       {"state":"wait",...}  # 不是用户出牌;牌桌画面会带上认得出的
              # hand / melds / que / left,大厅、结算只有 state
  unsure:     {"state":"unsure","reason":"tile"|"meld"|"que"|"left"}
              # 认不出就不给建议;认不出的牌面存进 unsure/,方便以后补模板
  draws = max(1, ⌈left ÷ 4⌉),即自己还能摸几张。
  手牌张数 + 3 × 碰杠组数 = 14 且没胡才算 turn;之前(13 张)是 wait。
  手牌按固定格子切(右对齐 x=2004、每张 123.5px、牌面 y=885–1057,刚摸的
  x=2043–2166),哪格有牌看模板得分而不是桌布颜色;被抬起的牌在 0–60px 内
  上下搜索。每张和 templates/hand/ 的模板比 40×64 归一化点积(BILINEAR),
  ≥0.95 才算认出;自己的碰杠用 templates/meld/,牌墙剩余用 templates/digit/,
  定缺角标用 templates/que/。只支持这台手机的 2412×1080 横屏。

/hint 错误(HTTP 200,同第一期约定):
  {"error": "bad_image" | "unsupported_screen", "detail": "人话"}
  # 不是 PNG/JPEG(按文件头)或解不开 → bad_image;不是 2412×1080 → unsupported_screen

校验错误(HTTP 200,安卓端把非 2xx 当成连不上):
  {"error": "bad_tile" | "bad_que" | "bad_meld" | "not_drawn" | "wrong_count"
            | "too_many" | "bad_draws",
   "detail": "人话"}
只有请求体不是 JSON、路径不对这类才用 4xx/404。
"""
import base64
import glob
import io
import json
import os
import random
import re
import sys
import time
import traceback
from math import comb, sqrt
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from PIL import Image, ImageFilter
from mahjong.shanten import Shanten

TILE_RE = re.compile(r"^([1-9])([mps])$")
SUIT_OFFSET = {"m": 0, "p": 9, "s": 18}

DEFAULT_DRAWS = 8
MIN_DRAWS = 1
MAX_DRAWS = 30
PROB_TABLE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "prob_table.json")

BASE_WALL = [f"{number}{suit}" for suit in "mps" for number in range(1, 10) for _ in range(4)]
MC_SEED = 20261117
MC_GAMES = 200                 # 每个候选的模拟局数(draws ≤ 14)
MC_LONG_DRAWS = 14
MC_LONG_GAMES = 100            # draws 更大时降到 100 局,保证单次请求 < 1 秒
MC_QUE_GAMES = 70              # /que 只排三门,局数少一些,保证也在 1 秒左右
MC_QUE_LONG_GAMES = 50


def mc_games(draws):
    return MC_GAMES if draws <= MC_LONG_DRAWS else MC_LONG_GAMES


def que_games(draws):
    return MC_QUE_GAMES if draws <= MC_LONG_DRAWS else MC_QUE_LONG_GAMES


def _load_prob_table():
    try:
        with open(PROB_TABLE_PATH, encoding="utf-8") as file:
            return json.load(file)
    except (OSError, ValueError):
        return None


PROB_TABLE = _load_prob_table()


def win_probability(shanten, ukeire, draws):
    """粗排用的赢牌概率:向听、进张、还能摸几张,三项都不减则概率不减。

    表来自 calibrate_prob.py 的纯模拟拟合;表不在时退回未校准的二项估计。
    最终 value 不查表,由 MonteCarlo 对这手牌的前几个候选单独模拟。
    """
    if shanten < 0:
        return 1.0
    if draws <= 0:
        return 0.0
    if PROB_TABLE is not None:
        s = min(shanten, PROB_TABLE["max_shanten"])
        u = min(max(ukeire, 0), PROB_TABLE["max_ukeire"])
        d = min(draws, PROB_TABLE["max_draws"])
        return PROB_TABLE["milli"][s][u][d] / 1000.0
    coefficient = 1.0
    need = shanten + 1
    if need > draws:
        return 0.0
    q = min(1.0, coefficient * ukeire / 94.0)
    return sum(comb(draws, k) * q ** k * (1 - q) ** (draws - k) for k in range(need, draws + 1))


class RequestError(Exception):
    def __init__(self, code, detail):
        super().__init__(detail)
        self.code = code
        self.detail = detail


def parse_tile(value):
    if not isinstance(value, str) or not TILE_RE.match(value):
        raise RequestError("bad_tile", f"不认识的牌：{value!r}（只认 1m–9m、1p–9p、1s–9s）")
    return value


def to_counts(tiles):
    counts = [0] * 34
    for tile in tiles:
        counts[SUIT_OFFSET[tile[1]] + int(tile[0]) - 1] += 1
    return counts


def regular_shanten(tiles):
    return Shanten.calculate_shanten_for_regular_hand(to_counts(tiles))


def tile_order(tile):
    return (SUIT_OFFSET[tile[1]] // 9, int(tile[0]))


SUIT_INDEX = {"m": 0, "p": 1, "s": 2}
INDEX_SUIT = "mps"
TARGET_PRIORITY = {"qingyise": 3, "qidui": 2, "pengpeng": 1, "fast": 0}


def filtered_regular_shanten(useful, melds_count):
    # 剔掉定缺牌后手牌可能不够 13-3m 张:补几个永远不会成组的字牌把张数凑回去,
    # 让库按正确的副露组数算;missing 是至少要摸的张数,向听不能比它小。
    counts = to_counts(useful)
    target = 13 - 3 * melds_count
    missing = target - sum(counts)
    if missing <= 0:
        return Shanten.calculate_shanten_for_regular_hand(counts)
    if missing > 7:
        # 手里剩 9 张以上定缺牌:向听已经没意义,给个最大值
        return 8
    for index in range(missing):
        counts[27 + index] += 1
    return max(Shanten.calculate_shanten_for_regular_hand(counts), missing)


def qidui_need(tiles):
    """七对还差几张:每 4 张相同算 2 对(龙七对),取 7 个「对子单位」里最值钱的。"""
    counts = to_counts(tiles)
    units = []
    for index in range(27):
        count = counts[index]
        if count >= 2:
            units.append(2)
        elif count == 1:
            units.append(1)
        if count >= 4:
            units.append(2)
        elif count == 3:
            units.append(1)
    units.sort(reverse=True)
    return 14 - sum(units[:7])


def qidui_shanten(tiles):
    return qidui_need(tiles) - 1


def fast_shanten(tiles, melds_count):
    regular = filtered_regular_shanten(tiles, melds_count)
    if melds_count:
        return regular
    return min(regular, qidui_shanten(tiles))


def pengpeng_need(tiles, melds_count, que):
    """碰碰胡还差几张:(4 - 碰杠组数) 个刻子 + 1 个对子,选最省的类型。"""
    counts = to_counts(tiles)
    sets_needed = 4 - melds_count
    allowed = [index for index in range(27) if INDEX_SUIT[index // 9] != que]
    triplet_cost = {index: 3 - min(counts[index], 3) for index in allowed}
    pair_cost = {index: 2 - min(counts[index], 2) for index in allowed}
    best = None
    for pair in allowed:
        total = pair_cost[pair] + sum(sorted(triplet_cost[index] for index in allowed if index != pair)[:sets_needed])
        if best is None or total < best:
            best = total
    return best


def pengpeng_shanten(tiles, melds_count, que):
    return pengpeng_need(tiles, melds_count, que) - 1


def meld_tiles(meld):
    return [meld["tile"]] * (4 if meld["type"] == "kan" else 3)


def _counts_with_melds(tiles, melds):
    counts = to_counts(tiles)
    for meld in melds:
        counts[SUIT_OFFSET[meld["tile"][1]] + int(meld["tile"][0]) - 1] += (4 if meld["type"] == "kan" else 3)
    return counts


def _gen_count(tiles, melds, suits=None):
    counts = _counts_with_melds(tiles, melds)
    return sum(
        1
        for index in range(27)
        if counts[index] == 4 and (suits is None or INDEX_SUIT[index // 9] in suits)
    )


def _all_tiles(tiles, melds):
    return list(tiles) + [tile for meld in melds for tile in meld_tiles(meld)]


def _duanyao(existing, waits):
    # 只有「已经有的牌 + 所有能进的牌」都是 2–8,断幺九才是必然的番。
    return bool(existing) and all(tile[0] not in "19" for tile in existing) and all(
        tile[0] not in "19" for tile in waits
    )


def _fast_fan(useful, melds, shape, waits):
    if shape == "qidui":
        qingyise = bool(useful) and len({tile[1] for tile in useful}) == 1
        duanyao = _duanyao(useful, waits)
        return 2 + (2 if qingyise else 0) + (1 if duanyao else 0) + _gen_count(useful, melds)
    existing = _all_tiles(useful, melds)
    return (1 if _duanyao(existing, waits) else 0) + _gen_count(useful, melds)


def _qingyise_fan(x_tiles, melds, suit, waits):
    existing = _all_tiles(x_tiles, melds)
    duanyao = bool(x_tiles) and _duanyao(existing, waits)
    return 2 + (1 if duanyao else 0) + _gen_count(x_tiles, melds, {suit})


def _pengpeng_fan(useful, melds, waits):
    existing = _all_tiles(useful, melds)
    qingyise = bool(useful) and len({tile[1] for tile in existing}) == 1
    return (
        (2 if len(melds) == 4 else 1)
        + (2 if qingyise else 0)
        + (1 if _duanyao(existing, waits) else 0)
        + _gen_count(useful, melds)
    )


def _qidui_fan(useful, waits):
    qingyise = bool(useful) and len({tile[1] for tile in useful}) == 1
    return 2 + (2 if qingyise else 0) + (1 if _duanyao(useful, waits) else 0) + _gen_count(useful, [])


def win_multiplier(fan):
    """番数 → 胡牌倍数:0→1、1→2、2→4、3→8,之后每番 +4(规则页 2026-11-17)。"""
    if fan <= 3:
        return 2 ** fan
    return 8 + 4 * (fan - 3)


def is_pengpeng(hand, melds_count):
    """手牌部分能否拆成 (4 - 碰杠组数) 个刻子 + 1 个对子。"""
    counts = to_counts(hand)
    sets_needed = 4 - melds_count
    if sets_needed == 0:
        return sum(counts) == 2 and any(count == 2 for count in counts)
    for index, count in enumerate(counts):
        if count < 2:
            continue
        rest = list(counts)
        rest[index] -= 2
        if sum(rest) == 3 * sets_needed and all(value % 3 == 0 for value in rest):
            return True
    return False


def win_fan(hand, melds=None):
    """一手胡牌的番数:牌型番 + 清一色 + 断幺九 + 根(规则页 2026-11-17)。"""
    melds = melds or []
    counts = to_counts(hand)
    total = _counts_with_melds(hand, melds)
    seven_pairs = not melds and len(hand) == 14 and all(count % 2 == 0 for count in counts)
    regular = sum(counts) in (2, 5, 8, 11, 14) and Shanten.calculate_shanten_for_regular_hand(counts) == -1
    if seven_pairs:
        pattern = 2
    elif regular:
        pattern = (2 if len(melds) == 4 else 1) if is_pengpeng(hand, len(melds)) else 0
    else:
        raise ValueError(f"不是胡牌：{hand!r}")
    tiles = _all_tiles(hand, melds)
    qingyise = len({tile[1] for tile in tiles}) == 1
    duanyao = all(tile[0] not in "19" for tile in tiles)
    gen = sum(1 for count in total if count == 4)
    return pattern + (2 if qingyise else 0) + (1 if duanyao else 0) + gen


def _finish(target, fan, shanten, ukeire, waits, draws, order=0):
    return {
        "target": target,
        "fan": fan,
        "shanten": shanten,
        "ukeire": ukeire,
        "waits": waits,
        "value": win_probability(shanten, ukeire, draws) * win_multiplier(fan),
        "order": order,
    }


def _copies_left(tile, hand_counts, meld_counts):
    return 4 - hand_counts.get(tile, 0) - meld_counts.get(tile, 0)


def _meld_counts(melds):
    counts = {}
    for meld in melds:
        counts[meld["tile"]] = counts.get(meld["tile"], 0) + (4 if meld["type"] == "kan" else 3)
    return counts


def _ukeire(useful, melds, que, base, shanten_of, suits=None):
    """按非定缺门的牌数进张;只扣自己手牌和副露,不扣场上已见。"""
    hand_counts = {}
    for tile in useful:
        hand_counts[tile] = hand_counts.get(tile, 0) + 1
    meld_counts = _meld_counts(melds)
    waits = []
    ukeire = 0
    for suit in (suits or ("m", "p", "s")):
        if suit == que:
            continue
        for number in range(1, 10):
            tile = f"{number}{suit}"
            left = _copies_left(tile, hand_counts, meld_counts)
            if left <= 0:
                continue
            if shanten_of(useful + [tile]) < base:
                waits.append(tile)
                ukeire += left
    return ukeire, waits


def fast_result(hand13, melds, que, draws):
    useful = [tile for tile in hand13 if tile[1] != que]
    regular = filtered_regular_shanten(useful, len(melds))
    shape = "regular"
    base = regular
    if not melds:
        qidui = qidui_shanten(useful)
        if qidui < regular:
            shape = "qidui"
            base = qidui
    ukeire, waits = _ukeire(useful, melds, que, base, lambda tiles: fast_shanten(tiles, len(melds)))
    return _finish("fast", _fast_fan(useful, melds, shape, waits), base, ukeire, waits, draws)


def qingyise_result(hand13, melds, que, suit, draws):
    if any(meld["tile"][1] != suit for meld in melds):
        return None
    useful = [tile for tile in hand13 if tile[1] != que]
    x_tiles = [tile for tile in useful if tile[1] == suit]
    base = filtered_regular_shanten(x_tiles, len(melds))
    ukeire, waits = _ukeire(x_tiles, melds, que, base,
                            lambda tiles: filtered_regular_shanten(tiles, len(melds)), (suit,))
    result = _finish("qingyise", _qingyise_fan(x_tiles, melds, suit, waits), base, ukeire, waits, draws,
                     SUIT_INDEX[suit])
    result["_suit"] = suit
    return result


def pengpeng_result(hand13, melds, que, draws):
    if any(meld["tile"][1] == que for meld in melds):
        return None
    useful = [tile for tile in hand13 if tile[1] != que]
    base = pengpeng_shanten(useful, len(melds), que)
    ukeire, waits = _ukeire(useful, melds, que, base,
                            lambda tiles: pengpeng_shanten(tiles, len(melds), que))
    return _finish("pengpeng", _pengpeng_fan(useful, melds, waits), base, ukeire, waits, draws)


def qidui_result(hand13, melds, que, draws):
    if melds:
        return None
    useful = [tile for tile in hand13 if tile[1] != que]
    base = qidui_shanten(useful)
    ukeire, waits = _ukeire(useful, melds, que, base, qidui_shanten)
    return _finish("qidui", _qidui_fan(useful, waits), base, ukeire, waits, draws)


def evaluate_hand(hand13, melds, que, draws):
    """13 张(切完一张)时每种目标的向听、进张、番数和期望倍数。"""
    if any(meld["tile"][1] == que for meld in melds):
        # 碰/杠了定缺门,永远胡不了;留给调用方一个零期望的结果
        return [_finish("fast", 0, 8, 0, [], draws)]
    results = [fast_result(hand13, melds, que, draws)]
    for suit in ("m", "p", "s"):
        if suit != que:
            result = qingyise_result(hand13, melds, que, suit, draws)
            if result is not None:
                results.append(result)
    result = pengpeng_result(hand13, melds, que, draws)
    if result is not None:
        results.append(result)
    result = qidui_result(hand13, melds, que, draws)
    if result is not None:
        results.append(result)
    return results


def best_target(results):
    return max(
        results,
        key=lambda result: (
            result["value"],
            -result["shanten"],
            result["ukeire"],
            TARGET_PRIORITY[result["target"]],
            -result["order"],
        ),
    )


def _option(tile, best):
    return {
        "discard": tile,
        "target": best["target"],
        "fan": best["fan"],
        "value": round(best["value"], 2),
        "shanten": best["shanten"],
        "ukeire": best["ukeire"],
        "waits": best["waits"],
    }


def _candidate_key(result):
    return (
        result["value"],
        -result["shanten"],
        result["ukeire"],
        TARGET_PRIORITY[result["target"]],
        -result["order"],
    )


def _coarse_key(discard, result):
    return (
        -result["value"],
        result["shanten"],
        -result["ukeire"],
        tile_order(discard),
        TARGET_PRIORITY[result["target"]],
        -result["order"],
    )


RANK_Z = 1.96  # 95% 置信区间


def _within_noise(first, second):
    """同一个 target 下,两个候选的蒙卡赢牌率是不是区分不开(95% 置信区间)。"""
    if first["target"] != second["target"]:
        return False
    first_p, second_p = first["_prob"], second["_prob"]
    variance = (first_p * (1 - first_p) + second_p * (1 - second_p)) / first["_games"]
    return abs(first_p - second_p) <= RANK_Z * sqrt(variance)


def _rank_key(result):
    return (-result["shanten"], result["ukeire"], result["value"],
            TARGET_PRIORITY[result["target"]], -result["order"])


def _rank_candidates(candidates):
    """按蒙卡赢牌率排序;同一个 target 下区分不开时,先比向听、再比进张。

    蒙卡 200 局的赢牌率标准误差约 0.026,常常比两种打法之间的真实差距还大;
    直接按点估计排序会把噪声当成结论,把拆对子退向听的打法排到前面。
    向听只跟同一个 target 比:清一色、七对的向听口径不同,跨目标只能比 value。
    """
    remaining = list(candidates)
    ordered = []
    while remaining:
        top = max(remaining, key=lambda item: _candidate_key(item[1]))
        tied = [item for item in remaining if _within_noise(item[1], top[1])]
        pick = max(tied, key=lambda item: _rank_key(item[1]))
        ordered.append(pick)
        remaining.remove(pick)
    return ordered


def _mc_target(result):
    if result["target"] == "qingyise":
        return f"qingyise:{result['_suit']}"
    return result["target"]


def _select_candidates(pairs, limit=4):
    """表只做粗排:取粗排最前面的 4 个切牌,每个用粗排最好的目标做蒙特卡洛。"""
    if not pairs:
        return []
    ordered = sorted(pairs, key=lambda item: _coarse_key(item[0], item[1]))
    best_by_discard = {}
    for discard, result in ordered:
        current = best_by_discard.get(discard)
        if current is None or _candidate_key(result) > _candidate_key(current):
            best_by_discard[discard] = result
    top_discards = sorted(best_by_discard, key=lambda discard: _coarse_key(discard, best_by_discard[discard]))[:limit]
    return [(discard, best_by_discard[discard]) for discard in top_discards]


class MonteCarlo:
    """对这手牌、这个目标、draws 张牌墙做模拟;固定种子,同一输入结果相同。"""

    def __init__(self, melds, que):
        self.melds = melds
        self.m = len(melds)
        self.que = que
        self.shanten_cache = {}
        self.won_cache = {}

    @staticmethod
    def _key(target, tiles):
        return (target, tuple(sorted(tiles)))

    def shanten(self, target, tiles):
        key = self._key(target, tiles)
        cached = self.shanten_cache.get(key)
        if cached is not None:
            return cached
        useful = [tile for tile in tiles if tile[1] != self.que]
        if target == "fast":
            value = fast_shanten(useful, self.m)
        elif target == "qidui":
            value = qidui_shanten(useful)
        elif target == "pengpeng":
            value = pengpeng_shanten(useful, self.m, self.que)
        else:
            suit = target.split(":", 1)[1]
            value = filtered_regular_shanten([tile for tile in useful if tile[1] == suit], self.m)
        self.shanten_cache[key] = value
        return value

    def won(self, target, tiles):
        if any(meld["tile"][1] == self.que for meld in self.melds):
            return False
        key = self._key(target, tiles)
        cached = self.won_cache.get(key)
        if cached is not None:
            return cached
        if any(tile[1] == self.que for tile in tiles):
            value = False
        elif target.startswith("qingyise:"):
            suit = target.split(":", 1)[1]
            value = all(tile[1] == suit for tile in tiles) and regular_shanten(tiles) == -1
        elif target == "qidui":
            value = self.m == 0 and len(tiles) == 14 and all(count % 2 == 0 for count in to_counts(tiles))
        elif target == "pengpeng":
            value = regular_shanten(tiles) == -1 and is_pengpeng(tiles, self.m)
        else:
            value = regular_shanten(tiles) == -1 or (
                self.m == 0 and len(tiles) == 14 and all(count % 2 == 0 for count in to_counts(tiles))
            )
        self.won_cache[key] = value
        return value

    def discard(self, target, tiles):
        ordered = sorted(tiles)
        for tile in ordered:
            if tile[1] == self.que:
                return tile
        if target.startswith("qingyise:"):
            suit = target.split(":", 1)[1]
            for tile in ordered:
                if tile[1] != suit:
                    return tile
        best = None
        for tile in sorted(set(tiles)):
            rest = list(tiles)
            rest.remove(tile)
            score = (self.shanten(target, rest), tile)
            if best is None or score < best[0]:
                best = (score, tile)
        return best[1]

    def run(self, hand, discard, target, draws, games):
        start = list(hand)
        if discard is not None:
            start.remove(discard)
        wall = list(BASE_WALL)
        for meld in self.melds:
            for tile in meld_tiles(meld):
                wall.remove(tile)
        for tile in hand:
            wall.remove(tile)
        rng = random.Random(MC_SEED)
        wins = 0
        for _ in range(games):
            shuffled = wall[:]
            rng.shuffle(shuffled)
            current = list(start)
            for turn in range(draws):
                current.append(shuffled[turn])
                if self.won(target, current):
                    wins += 1
                    break
                current.remove(self.discard(target, current))
        return wins / games


def _run_monte_carlo(hand, melds, que, draws, candidates):
    mc = MonteCarlo(melds, que)
    games = mc_games(draws)
    for discard, result in candidates:
        probability = mc.run(hand, discard, _mc_target(result), draws, games)
        result["value"] = probability * win_multiplier(result["fan"])
        result["_prob"] = probability
        result["_games"] = games
    return games


def parse_draws(value):
    if value is None:
        return DEFAULT_DRAWS
    if isinstance(value, bool) or not isinstance(value, int) or not (MIN_DRAWS <= value <= MAX_DRAWS):
        raise RequestError("bad_draws", f"draws 必须是 {MIN_DRAWS}–{MAX_DRAWS} 的整数，收到 {value!r}")
    return value


def _validate_hand(hand):
    if not isinstance(hand, list):
        raise TypeError("hand 必须是数组")
    for tile in hand:
        parse_tile(tile)


def _parse_melds(melds):
    if not isinstance(melds, list):
        raise TypeError("melds 必须是数组")
    parsed = []
    for meld in melds:
        if not isinstance(meld, dict):
            raise RequestError("bad_meld", f"副露必须是对象，收到 {meld!r}")
        kind = meld.get("type")
        if kind not in ("pon", "kan"):
            raise RequestError("bad_meld", f"副露只能是碰（pon）或杠（kan），收到 {kind!r}")
        try:
            tile = parse_tile(meld.get("tile"))
        except RequestError:
            raise RequestError("bad_meld", f"副露的牌不认识：{meld.get('tile')!r}") from None
        parsed.append({"type": kind, "tile": tile})
    return parsed


def _check_copies(hand, melds):
    used = {}
    for tile in hand:
        used[tile] = used.get(tile, 0) + 1
    for meld in melds:
        used[meld["tile"]] = used.get(meld["tile"], 0) + (3 if meld["type"] == "pon" else 4)
    for tile, count in used.items():
        if count > 4:
            raise RequestError("too_many", f"{tile} 一共 {count} 张，超过 4 张")


def is_win(hand, melds, que):
    if any(tile[1] == que for tile in hand):
        return False
    if any(meld["tile"][1] == que for meld in melds):
        return False
    if regular_shanten(hand) == -1:
        return True
    return not melds and len(hand) == 14 and all(count % 2 == 0 for count in to_counts(hand))


def advise(hand, melds, que, draws=None):
    _validate_hand(hand)
    if que not in ("m", "p", "s"):
        raise RequestError("bad_que", f"定缺只能是 m、p、s，收到 {que!r}")
    parsed_melds = _parse_melds(melds)
    total = len(hand) + 3 * len(parsed_melds)
    if total == 13:
        raise RequestError("not_drawn", f"手牌 {len(hand)} 张 + 副露 {len(parsed_melds)} 组 = 13 张，还没摸牌")
    if total != 14:
        raise RequestError("wrong_count", f"手牌 {len(hand)} 张 + 副露 {len(parsed_melds)} 组，不是 14 张")
    _check_copies(hand, parsed_melds)
    draws = parse_draws(draws)
    if is_win(hand, parsed_melds, que):
        return {"win": True}
    que_tiles = [tile for tile in hand if tile[1] == que]
    if que_tiles:
        que_counts = {}
        for tile in que_tiles:
            que_counts[tile] = que_counts.get(tile, 0) + 1
        candidates = sorted(que_counts, key=lambda tile: (que_counts[tile], tile_order(tile)))
        mc = MonteCarlo(parsed_melds, que)
        games = mc_games(draws)
        options = []
        for tile in candidates[:3]:
            remaining = list(hand)
            remaining.remove(tile)
            best = best_target(evaluate_hand(remaining, parsed_melds, que, draws))
            probability = mc.run(hand, tile, _mc_target(best), draws, games)
            best["value"] = probability * win_multiplier(best["fan"])
            options.append(_option(tile, best))
        return {"win": False, **options[0], "queLeft": len(que_tiles), "options": options}
    pairs = []
    for tile in sorted(set(hand), key=tile_order):
        remaining = list(hand)
        remaining.remove(tile)
        for result in evaluate_hand(remaining, parsed_melds, que, draws):
            pairs.append((tile, result))
    candidates = _select_candidates(pairs)
    _run_monte_carlo(hand, parsed_melds, que, draws, candidates)
    best_by_discard = {}
    for discard, result in candidates:
        current = best_by_discard.get(discard)
        if current is None or _candidate_key(result) > _candidate_key(current):
            best_by_discard[discard] = result
    scored = _rank_candidates(list(best_by_discard.items()))[:3]
    options = [_option(discard, result) for discard, result in scored]
    return {"win": False, **options[0], "queLeft": 0, "options": options}


def advise_que(hand, draws=None):
    _validate_hand(hand)
    if len(hand) not in (13, 14):
        raise RequestError("wrong_count", f"定缺需要 13 或 14 张手牌，收到 {len(hand)} 张")
    _check_copies(hand, [])
    draws = parse_draws(draws)
    games = que_games(draws)
    options = []
    for que in ("m", "p", "s"):
        best = None
        best_discard = None
        if len(hand) == 13:
            best = best_target(evaluate_hand(hand, [], que, draws))
        else:
            for tile in sorted(set(hand), key=tile_order):
                remaining = list(hand)
                remaining.remove(tile)
                candidate = best_target(evaluate_hand(remaining, [], que, draws))
                if best is None or _candidate_key(candidate) > _candidate_key(best):
                    best = candidate
                    best_discard = tile
        mc = MonteCarlo([], que)
        probability = mc.run(hand, best_discard, _mc_target(best), draws, games)
        best["value"] = probability * win_multiplier(best["fan"])
        count = sum(1 for tile in hand if tile[1] == que)
        options.append((best["value"], count, que, {"que": que, "target": best["target"],
                                                   "value": round(best["value"], 2)}))
    options.sort(key=lambda item: (-item[0], item[1], SUIT_INDEX[item[2]]))
    return {"que": options[0][3]["que"], "options": [item[3] for item in options]}


# ---- /hint:识别一张 2412×1080 的主屏截图 ----
# 手牌区右对齐到 x=2004,每张宽 123.5px;刚摸的那张单独在 x=2043–2166。
# 手牌和模板比 40×64 的归一化点积,>= 0.90 才算认出;自己的碰杠、牌墙剩余、
# 定缺角标和「荐」都用同一套模板/几何。认不出返回 unsure,并把牌面存进 unsure/。
SCREEN_SIZE = (2412, 1080)
HAND_RIGHT = 2004.0
HAND_PITCH = 123.5
DRAWN_X = (2043.0, 2166.0)
HAND_TOP, HAND_BOTTOM = 820, 1075
HAND_FACE_TOP, HAND_FACE_BOTTOM = 885, 1057
# 0.90 实测选的:发黄光/带黄点的牌最低 0.912(4m),真牌最低 0.903;
# 空槽/桌面最高 0.515,留 0.38 余量。0.95 会把这些 0.912–0.941 的牌漏成 unsure。
HAND_MATCH = 0.90
# 有没有牌不再看「牌面比桌布亮」,而是看固定格子里的模板得分:
# 正常牌 ≥0.90;空格子/桌面最高 0.52(新手场 0.21、500 场 0.26、结算 0.52)。
# 抬起的牌按 0–60px 上下搜索;玩家点选的那张实测抬 52px,碰/过提示抬 30px。
HAND_LIFT_MAX = 60
HAND_PRESENT = 0.60
HAND_WEAK = 0.45
HAND_FACE_WHITE = 0.55
MELD_MATCH = 0.9
QUE_MATCH = 0.9
DIGIT_MATCH = 0.9
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.join(BASE_DIR, "templates")
UNSURE_DIR = os.path.join(BASE_DIR, "unsure")
CAPTURE_DIR = os.path.join(BASE_DIR, "captures")
_last_capture = None  # 上一张存下的回复;用户想牌时手机每秒发同样的画面,只留第一张
MELD_GROUP_X = 456
MELD_GROUP_STEP = 320
MELD_GROUP_WIDTH = 276
MELD_COLUMN = 92
# 暗杠抬起那张牌的牌面顶边固定在 y≈878；淡紫灰桌布上方的高光也会过白色掩码，
# 所以行范围从牌面顶边开始找，不把桌布算进牌面（绿桌布、紫灰桌布切出同样的框）。
MELD_ANKONG_FACE_TOP = 870


def _feature(image, size=(40, 64)):
    # BILINEAR 比 LANCZOS 更抗 JPEG 的块效应(手牌最低分 0.9457 → 0.9570)。
    values = np.asarray(image.convert("RGB").resize(size, Image.BILINEAR), float).ravel()
    values -= values.mean()
    return values / (np.linalg.norm(values) + 1e-9)


def _load_features(pattern, size=(40, 64)):
    table = {}
    for path in sorted(glob.glob(os.path.join(TEMPLATE_DIR, pattern))):
        name = os.path.splitext(os.path.basename(path))[0]
        table[name] = _feature(Image.open(path), size)
    return table


def _digit_feature(image):
    if isinstance(image, np.ndarray):
        image = Image.fromarray(image.astype("uint8"))
    image = image.convert("L").filter(ImageFilter.GaussianBlur(0.6)).resize((16, 24), Image.BILINEAR)
    values = np.asarray(image, float).ravel()
    values -= values.mean()
    return values / (np.linalg.norm(values) + 1e-9)


def _load_digits():
    table = {}
    for pattern in (os.path.join("digit", "*.png"), os.path.join("digit_jpeg", "*.png")):
        for path in sorted(glob.glob(os.path.join(TEMPLATE_DIR, pattern))):
            name = os.path.splitext(os.path.basename(path))[0]
            table.setdefault(name[0], []).append(_digit_feature(Image.open(path)))
    return table


HAND_TEMPLATES = _load_features(os.path.join("hand", "*.png"))
MELD_TEMPLATES = _load_features(os.path.join("meld", "*.png"))
QUE_TEMPLATES = _load_features(os.path.join("que", "*.png"), (32, 32))
DIGIT_TEMPLATES = _load_digits()


def _best(feature, table):
    best = (0.0, None)
    for name, template in table.items():
        score = float(feature @ template)
        if score > best[0]:
            best = (score, name)
    return best


def _decode_screen(value):
    if not isinstance(value, str) or not value:
        raise RequestError("bad_image", "image 必须是整屏 PNG 的 base64 字符串")
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, TypeError):
        raise RequestError("bad_image", "image 不是合法的 base64") from None
    if raw.startswith(b"\x89PNG\r\n\x1a\n"):
        expected_format = "PNG"
    elif raw.startswith(b"\xff\xd8\xff"):
        expected_format = "JPEG"
    else:
        raise RequestError("bad_image", "只认 PNG 或 JPEG(按文件头判断)")
    try:
        image = Image.open(io.BytesIO(raw))
        image.load()
    except Exception:
        raise RequestError("bad_image", "图片解不开") from None
    if image.format != expected_format:
        raise RequestError("bad_image", f"文件头和实际格式不一致：{image.format}")
    if image.size != SCREEN_SIZE:
        raise RequestError(
            "unsupported_screen", f"只支持 {SCREEN_SIZE[0]}×{SCREEN_SIZE[1]}，收到 {image.width}×{image.height}"
        )
    return image.convert("RGB")


def _face_white(array, x0, x1, y0, y1):
    """牌面是低饱和的近白;紫桌布偏灰蓝、绿桌布偏暗,空格子达不到这个比例。"""
    region = array[int(y0) + 20:int(y1) - 20, int(x0) + 15:int(x1) - 15]
    white = (region.min(axis=2) > 150) & ((region.max(axis=2) - region.min(axis=2)) < 60)
    return float(white.mean())


def _match_slot(image, array, x0, x1):
    """在一个固定格子里找得分最高的牌面,顺带试抬起的牌。"""
    best = None
    for lift in range(HAND_LIFT_MAX + 1):
        y0 = HAND_FACE_TOP - lift
        y1 = HAND_FACE_BOTTOM - lift
        crop = image.crop((int(x0) + 4, int(y0), int(x1) - 4, int(y1)))
        score, tile = _best(_feature(crop), HAND_TEMPLATES)
        if best is None or score > best["score"]:
            best = {"score": score, "tile": tile, "lift": lift,
                    "slot": (x0, x1, y0, y1, False), "crop": crop}
        if lift == 0 and score >= HAND_MATCH:
            break
    best["present"] = _slot_present(array, best)
    return best


def _slot_present(array, record):
    score = record["score"]
    if score >= HAND_PRESENT:
        return True
    if score < HAND_WEAK:
        return False
    x0, x1, y0, y1, _ = record["slot"]
    return _face_white(array, x0, x1, y0, y1) >= HAND_FACE_WHITE


def _scan_hand(image, array):
    """从右往左按固定格子找手牌;遇到空格就停,避免把左边的碰杠当成手牌。"""
    records = []
    for index in range(13):
        x1 = HAND_RIGHT - HAND_PITCH * index
        x0 = x1 - HAND_PITCH
        record = _match_slot(image, array, x0, x1)
        if not record["present"]:
            break
        records.append(record)
    records.reverse()
    drawn = _match_slot(image, array, *DRAWN_X)
    if drawn["present"]:
        drawn["slot"] = (*DRAWN_X, drawn["slot"][2], drawn["slot"][3], True)
        records.append(drawn)
    for record in records:
        if record["score"] < HAND_MATCH:
            record["tile"] = None
    return records


def _white_fraction(array, x0, x1, y0, y1):
    region = array[y0:y1, x0:x1]
    white = (region.min(axis=2) > 150) & ((region.max(axis=2) - region.min(axis=2)) < 60)
    return float(white.mean())


def _recognize_melds(image, array, hand_start):
    """自己的碰杠:每组固定 276px 宽,按白面/绿背和抬起的那张判断碰、明杠、暗杠。"""
    melds = []
    if hand_start is None or hand_start <= 740:
        return melds
    for group in range(4):
        start = MELD_GROUP_X + MELD_GROUP_STEP * group
        if start + MELD_GROUP_WIDTH > hand_start - 20:
            break
        flat = []
        raised = []
        for column in range(3):
            x0 = start + MELD_COLUMN * column + 8
            x1 = start + MELD_COLUMN * column + 84
            flat.append(_white_fraction(array, x0, x1, 950, 1020))
            raised.append(_white_fraction(array, x0, x1, 870, 905))
        lifted = sum(value > 0.4 for value in raised)
        if lifted:
            if flat[0] > 0.5 and flat[2] > 0.5:
                kind, dark = "kan", False
            elif flat[0] < 0.2 and flat[2] < 0.2:
                kind, dark = "kan", True
            else:
                melds.append(None)
                continue
        elif all(value > 0.5 for value in flat):
            kind, dark = "pon", False
        else:
            melds.append(None)
            continue
        if dark:
            region = array[820:1080, start + 40:start + 230]
            white = (region.min(axis=2) > 150) & ((region.max(axis=2) - region.min(axis=2)) < 60)
            columns = white[80:130].mean(axis=0)
            xs = np.where(columns > 0.5)[0]
            if not len(xs):
                melds.append(None)
                continue
            left_x, right_x = start + 40 + int(xs.min()), start + 40 + int(xs.max()) + 1
            rows = white[:, xs.min():xs.max() + 1].mean(axis=1) > 0.5
            rows[:MELD_ANKONG_FACE_TOP - 820] = False
            ys = np.where(rows)[0]
            if not len(ys):
                melds.append(None)
                continue
            face = image.crop((left_x + 3, 820 + int(ys.min()) + 3, right_x - 3, 820 + int(ys.max()) - 2))
            prefix = "ankong_"
        else:
            face = image.crop((start + 6, 921, start + 86, 1026))
            prefix = "flat_"
        templates = {name: value for name, value in MELD_TEMPLATES.items() if name.startswith(prefix)}
        score, name = _best(_feature(face), templates)
        if score < MELD_MATCH or name is None:
            melds.append(None)
            continue
        # 同一张牌碰在不同组透视不一样,可以有多张模板:flat_8s.png、flat_8s_g0.png 都是 8s
        melds.append({"type": kind, "tile": name.split("_")[1]})
    return melds


def _recognize_que(image, array, records):
    """先读左下角头像旁的万/筒/条角标;读不到再看手里哪门有粉小猪头。"""
    score, name = _best(_feature(image.crop((203, 958, 257, 1017)), (32, 32)), QUE_TEMPLATES)
    if score >= QUE_MATCH:
        return name
    suits = {}
    for record in records:
        tile = record["tile"]
        if tile is None:
            continue
        x0, x1, y0, y1, _ = record["slot"]
        region = array[y0:y0 + 55, int(x1) - 60:int(x1) - 2]
        red, green, blue = region[..., 0], region[..., 1], region[..., 2]
        pink = ((red > 200) & (red - green > 30) & (red - blue > 30) & (green > 120)).sum()
        if pink > 100:
            suits[tile[1]] = suits.get(tile[1], 0) + 1
    if suits:
        return max(suits, key=lambda suit: (suits[suit], suit))
    return None


def _dilate(mask):
    out = mask.copy()
    out[1:, :] |= mask[:-1, :]
    out[:-1, :] |= mask[1:, :]
    out[:, 1:] |= mask[:, :-1]
    out[:, :-1] |= mask[:, 1:]
    return out


def _components(mask):
    """8 连通域;绿框顶边的白条和数字会各成一块。"""
    height, width = mask.shape
    seen = np.zeros_like(mask, bool)
    found = []
    for y in range(height):
        for x in range(width):
            if not mask[y, x] or seen[y, x]:
                continue
            stack = [(y, x)]
            seen[y, x] = True
            points = []
            while stack:
                cy, cx = stack.pop()
                points.append((cy, cx))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        ny, nx = cy + dy, cx + dx
                        if 0 <= ny < height and 0 <= nx < width and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            stack.append((ny, nx))
            found.append(points)
    return found


def _left_digits(array):
    """左上角绿框里的数字,按连通域切成 1–2 个灰度字形。

    绿框固定在这台手机的 x 375–408、y 34–80;用带边距的固定窗口,不跟 JPEG
    把绿框撑大的 1px 走。数字用连通域分,不靠列投影 —— 个位的 0/7 中间会断。
    """
    digits = array[36:76, 370:412]
    red, green, blue = digits[..., 0], digits[..., 1], digits[..., 2]
    if int(((green > 130) & (green - red > 50) & (green - blue > 50)).sum()) < 300:
        return None
    gray = digits.min(axis=2)
    white = gray > 150
    white[:, :5] = False  # 固定窗口左边 5px 是绿框的边
    boxes = []
    for points in _components(white):
        if len(points) < 8:
            continue
        xs = [point[1] for point in points]
        ys = [point[0] for point in points]
        if max(ys) <= 12:  # 绿框顶边的白条
            continue
        boxes.append([min(xs), max(xs) + 1, min(ys), max(ys) + 1])
    # 同一个数字裂成几块(例如 4 的竖和斜)时按 x 重叠合并;两个数字不会重叠。
    while True:
        merged = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                first, second = boxes[i], boxes[j]
                if first[0] <= second[1] + 1 and second[0] <= first[1] + 1:
                    boxes[i] = [min(first[0], second[0]), max(first[1], second[1]),
                                min(first[2], second[2]), max(first[3], second[3])]
                    del boxes[j]
                    merged = True
                    break
            if merged:
                break
        if not merged:
            break
    boxes.sort(key=lambda box: box[0])
    if not 1 <= len(boxes) <= 2:
        return None
    return [gray[y0:y1, x0:x1] for x0, x1, y0, y1 in boxes]


def _read_left(array):
    """牌墙剩余:逐位和 digit/、digit_jpeg/ 的 0–9 灰度模板比。"""
    glyphs = _left_digits(array)
    if glyphs is None:
        return None
    value = ""
    for glyph in glyphs:
        feature = _digit_feature(glyph)
        score, digit = 0.0, None
        for candidate, templates in DIGIT_TEMPLATES.items():
            for template in templates:
                current = float(feature @ template)
                if current > score:
                    score, digit = current, candidate
        if score < DIGIT_MATCH:
            return None
        value += digit
    return int(value)


def _is_result_screen(array):
    """结算弹窗盖住牌桌中间,是一大块近白面板;正常牌桌中央没有这么亮。"""
    region = array[180:620, 560:1400]
    return float((region.min(axis=2) > 200).mean()) > 0.20


def _is_que_select(array):
    """定缺阶段中间有「请选择定缺的花色」横幅和三个大花色圆盘。

    绿桌布横幅是深蓝底,紫桌布横幅是半透明浅蓝底(深蓝像素几乎没有),
    所以两种桌布都看红「万」圆盘:定缺时红色像素 >5000,正常打牌 <1000。
    """
    region = array[560:800, 650:1400]
    red, green, blue = region[..., 0], region[..., 1], region[..., 2]
    disk = (red > 180) & (red - green > 80) & (red - blue > 80)
    if int(disk.sum()) > 2000:
        return True
    banner = array[600:670, 600:1800]
    red, green, blue = banner[..., 0], banner[..., 1], banner[..., 2]
    dark = (blue > red + 10) & (blue > green + 5) & (blue < 110) & (red < 70)
    return int(dark.sum()) > 1000


def _recognize_jian(array, slots):
    """橙色「荐」在推荐那张牌正上方,按 x 找最近的牌。"""
    region = array[830:905, 380:2220]
    red, green, blue = region[..., 0], region[..., 1], region[..., 2]
    orange = (red > 200) & (green > 120) & (green < 210) & (blue < 120)
    ys, xs = np.where(orange)
    if len(xs) < 100:
        return None
    center = (int(xs.min()) + int(xs.max())) / 2 + 380
    nearest = None
    distance = 1e9
    for slot in slots:
        x0, x1 = slot[0], slot[1]
        current = abs((x0 + x1) / 2 - center)
        if current < distance:
            distance, nearest = current, slot
    if distance > 60:
        return None
    return nearest


def _save_unsure(crop, reason):
    try:
        os.makedirs(UNSURE_DIR, exist_ok=True)
        crop.save(os.path.join(UNSURE_DIR, f"{time.time_ns()}_{reason}.png"))
    except OSError:
        pass


def _worth_capturing(reply):
    # 「等待」只在认出的张数凑不成 13 时存(多半认错或漏了碰杠);其余回复都存
    if reply.get("state") != "wait":
        return True
    if "hand" not in reply:
        return False
    return len(reply["hand"]) + 3 * len(reply.get("melds", [])) != 13


def _capture(image_value, reply):
    # 原图字节原样落盘 + 同名 .json 存回复,事后逐帧核对认错的牌
    global _last_capture
    if reply == _last_capture:
        return
    _last_capture = reply
    try:
        raw = base64.b64decode(image_value, validate=True)
    except (ValueError, TypeError):
        return
    ext = "jpg" if raw.startswith(b"\xff\xd8\xff") else "png"
    stem = os.path.join(CAPTURE_DIR, f"{time.time_ns()}_{reply.get('state') or reply.get('error')}")
    try:
        os.makedirs(CAPTURE_DIR, exist_ok=True)
        with open(f"{stem}.{ext}", "wb") as file:
            file.write(raw)
        with open(f"{stem}.json", "w", encoding="utf-8") as file:
            json.dump(reply, file, ensure_ascii=False)
    except OSError:
        pass


def _tile_at(records, slot):
    if slot is None:
        return None
    for record in records:
        if abs(record["slot"][0] - slot[0]) < 1:
            return record["tile"]
    return None


def advise_hint(image_value):
    try:
        image = _decode_screen(image_value)
    except RequestError as error:
        return {"error": error.code, "detail": error.detail}
    array = np.asarray(image).astype(int)
    if _is_result_screen(array):
        return {"state": "wait"}
    records = _scan_hand(image, array)
    if not records:
        return {"state": "wait"}
    if _is_que_select(array):
        hand = [record["tile"] for record in records]
        if any(tile is None for tile in hand):
            for record in records:
                if record["tile"] is None:
                    _save_unsure(record["crop"], "tile")
            return {"state": "unsure", "reason": "tile"}
        if len(hand) not in (13, 14):
            return {"state": "wait"}
        return {"state": "que_select", "hand": hand, "advice": advise_que(hand)}
    hand = [record["tile"] for record in records]
    if any(tile is None for tile in hand):
        for record in records:
            if record["tile"] is None:
                _save_unsure(record["crop"], "tile")
        return {"state": "unsure", "reason": "tile"}
    slots = [record["slot"] for record in records]
    melds = _recognize_melds(image, array, slots[0][0])
    que = _recognize_que(image, array, records)
    left = _read_left(array)
    total = len(hand) + 3 * len(melds)
    fields = {}
    if all(tile is not None for tile in hand):
        fields["hand"] = hand
    if all(meld is not None for meld in melds):
        fields["melds"] = melds
    if que is not None:
        fields["que"] = que
    if left is not None:
        fields["left"] = left
    if total == 14:
        if any(meld is None for meld in melds):
            for index, meld in enumerate(melds):
                if meld is not None:
                    continue
                start = MELD_GROUP_X + MELD_GROUP_STEP * index
                _save_unsure(image.crop((start + 6, 921, start + 86, 1026)), "meld")
            return {"state": "unsure", "reason": "meld"}
        if que is None:
            return {"state": "unsure", "reason": "que"}
        if left is None:
            return {"state": "unsure", "reason": "left"}
        draws = max(1, (left + 3) // 4)
        if is_win(hand, melds, que):
            return {"state": "win", **fields, "draws": draws}
        jian = _tile_at(records, _recognize_jian(array, slots))
        return {
            "state": "turn",
            **fields,
            "draws": draws,
            "jian": jian,
            "advice": advise(hand, melds, que, draws),
        }
    return {"state": "wait", **fields}


def handle(body, path="/discard"):
    if not isinstance(body, dict):
        return 400, {"error": "bad_request", "detail": "请求体必须是 JSON 对象"}
    if path == "/hint":
        try:
            reply = advise_hint(body.get("image"))
        except Exception:
            trace = traceback.format_exc()
            print(trace, file=sys.stderr, flush=True)
            _capture(body.get("image"), {"error": "internal", "traceback": trace})
            return 500, {"error": "internal"}
        if _worth_capturing(reply):
            _capture(body.get("image"), reply)
        return 200, reply
    try:
        if path == "/que":
            return 200, advise_que(body["hand"], body.get("draws"))
        return 200, advise(body["hand"], body.get("melds") or [], body["que"], body.get("draws"))
    except RequestError as e:
        return 200, {"error": e.code, "detail": e.detail}
    except (KeyError, TypeError, ValueError) as e:
        return 400, {"error": f"bad_request:{e}"}


def handler():
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path not in ("/discard", "/que", "/hint"):
                return self.reply(404, {"error": "not_found"})
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            except ValueError as e:
                return self.reply(400, {"error": f"bad_request:{e}"})
            code, data = handle(body, self.path)
            self.reply(code, data, body)

        def reply(self, code, data, request=None):
            if isinstance(request, dict) and "image" in request:
                # /hint 的请求体是整屏 PNG 的 base64,别把几 MB 打进日志
                request = {**request, "image": f"<PNG base64 {len(str(request['image']))} chars>"}
            print(json.dumps({"request": request, "reply": data}, ensure_ascii=False), flush=True)
            raw = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, *args):
            pass

    return Handler


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 3428
    ThreadingHTTPServer(("0.0.0.0", port), handler()).serve_forever()
