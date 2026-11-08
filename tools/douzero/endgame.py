"""残局搜索:剩的牌少时不全信 DouZero,按明牌把每一手算到底。

别家手里的牌合起来是知道的(整副牌减去自己手里的和出过的),只是不知道两家各拿哪几张。张数都对得上的分法一个个摆开,
每种分法都当成明牌,地主一边、两个农民一边,算双方都不失误时谁赢;挑赢的分法最多的那一手。
算不完(超时、分法太多)就不管,照用 DouZero 的。
"""
import time
from collections import Counter
from itertools import combinations

from douzero.env import move_detector as md
from douzero.env import move_selector as ms
from douzero.env.move_generator import MovesGener

SEATS = ["landlord", "landlord_down", "landlord_up"]
NEXT = {"landlord": "landlord_down", "landlord_down": "landlord_up", "landlord_up": "landlord"}
# 三家手里合起来最多这么多张才算;分法最多这么多种
MAX_CARDS = 20
MAX_SPLITS = 400


class Timeout(Exception):
    pass


def moves(hand, rival):
    """[hand] 能出的牌:[rival] 是要压的那手([] 是自己先出);要压时含不出 []。同 DouZero 牌局的规则。"""
    mg = MovesGener(list(hand))
    kind = md.get_move_type(list(rival))
    t, n = kind["type"], kind.get("len", 1)
    if t == md.TYPE_0_PASS:
        found = mg.gen_moves()
    elif t == md.TYPE_5_KING_BOMB:
        found = []
    elif t == md.TYPE_4_BOMB:
        found = ms.filter_type_4_bomb(mg.gen_type_4_bomb() + mg.gen_type_5_king_bomb(), rival)
    else:
        gen, keep = {
            md.TYPE_1_SINGLE: (mg.gen_type_1_single, ms.filter_type_1_single),
            md.TYPE_2_PAIR: (mg.gen_type_2_pair, ms.filter_type_2_pair),
            md.TYPE_3_TRIPLE: (mg.gen_type_3_triple, ms.filter_type_3_triple),
            md.TYPE_6_3_1: (mg.gen_type_6_3_1, ms.filter_type_6_3_1),
            md.TYPE_7_3_2: (mg.gen_type_7_3_2, ms.filter_type_7_3_2),
            md.TYPE_8_SERIAL_SINGLE: (lambda: mg.gen_type_8_serial_single(repeat_num=n), ms.filter_type_8_serial_single),
            md.TYPE_9_SERIAL_PAIR: (lambda: mg.gen_type_9_serial_pair(repeat_num=n), ms.filter_type_9_serial_pair),
            md.TYPE_10_SERIAL_TRIPLE: (lambda: mg.gen_type_10_serial_triple(repeat_num=n), ms.filter_type_10_serial_triple),
            md.TYPE_11_SERIAL_3_1: (lambda: mg.gen_type_11_serial_3_1(repeat_num=n), ms.filter_type_11_serial_3_1),
            md.TYPE_12_SERIAL_3_2: (lambda: mg.gen_type_12_serial_3_2(repeat_num=n), ms.filter_type_12_serial_3_2),
            md.TYPE_13_4_2: (mg.gen_type_13_4_2, ms.filter_type_13_4_2),
            md.TYPE_14_4_22: (mg.gen_type_14_4_22, ms.filter_type_14_4_22),
        }[t]
        found = keep(gen(), rival) + mg.gen_type_4_bomb() + mg.gen_type_5_king_bomb()
    out = {tuple(sorted(m)) for m in found}
    if rival:
        out.add(())
    return out


def minus(hand, move):
    left = list(hand)
    for c in move:
        left.remove(c)
    return tuple(left)


class Solver:
    """明牌下地主一方赢不赢。[deadline] 到了就抛 Timeout。"""

    def __init__(self, deadline):
        self.deadline = deadline
        self.memo = {}
        self.steps = 0

    def landlord_wins(self, hands, turn, rival, owner):
        """[hands] 三家手牌;轮到 [turn];要压 [owner] 出的 [rival](() 是先出)。"""
        key = (hands["landlord"], hands["landlord_down"], hands["landlord_up"], turn, rival, owner)
        if key in self.memo:
            return self.memo[key]
        self.steps += 1
        if self.steps % 512 == 0 and time.monotonic() > self.deadline:
            raise Timeout()
        mine = hands[turn]
        want = turn == "landlord"
        options = moves(mine, list(rival))
        # 先试能一手出完的、再试出得多的:更早剪枝
        result = not want
        for move in sorted(options, key=lambda m: (len(m) != len(mine), -len(m))):
            if self.after(hands, turn, rival, owner, move) == want:
                result = want
                break
        self.memo[key] = result
        return result

    def after(self, hands, turn, rival, owner, move):
        """[turn] 出了 [move] 以后地主一方赢不赢。"""
        nxt = NEXT[turn]
        if not move:
            # 不出:转了一圈回到出牌的人,他重新先出
            return self.landlord_wins(hands, nxt, () if nxt == owner else rival, owner)
        left = minus(hands[turn], move)
        if not left:
            return turn == "landlord"
        return self.landlord_wins({**hands, turn: left}, nxt, move, turn)


def splits(unknown, a, na, b, nb):
    """把 [unknown] 分给 [a] [na] 张、[b] [nb] 张的各种分法,带上这种分法有几种摆法(同点数的牌不分花色)。"""
    seen = Counter()
    for picked in combinations(range(len(unknown)), na):
        chosen = set(picked)
        ha = tuple(sorted(unknown[i] for i in picked))
        hb = tuple(sorted(unknown[i] for i in range(len(unknown)) if i not in chosen))
        seen[(ha, hb)] += 1
        if len(seen) > MAX_SPLITS:
            return None
    return seen


def best(infoset, budget=1.5):
    """残局时每一手能出的牌在多少种分法下能赢:{move: 赢的摆法数}, 总摆法数。不在残局、算不完为 None。"""
    me = infoset.player_position
    left = infoset.num_cards_left_dict
    if sum(left.values()) > MAX_CARDS:
        return None
    others = [s for s in SEATS if s != me]
    unknown = sorted(infoset.other_hand_cards)
    if len(unknown) != left[others[0]] + left[others[1]]:
        return None
    ways = splits(unknown, others[0], left[others[0]], others[1], left[others[1]])
    if ways is None:
        return None
    hand = tuple(sorted(infoset.player_hand_cards))
    rival = tuple(sorted(infoset.last_move)) if [] in infoset.legal_actions else ()
    owner = infoset.last_pid
    solver = Solver(time.monotonic() + budget)
    wins = Counter()
    total = sum(ways.values())
    try:
        for (ha, hb), weight in ways.items():
            hands = {me: hand, others[0]: ha, others[1]: hb}
            for action in infoset.legal_actions:
                move = tuple(sorted(action))
                landlord = solver.after(hands, me, rival, owner, move)
                if landlord == (me == "landlord"):
                    wins[move] += weight
    except Timeout:
        return None
    return wins, total
