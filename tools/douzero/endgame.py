"""残局搜索:剩的牌少时不全信 DouZero,按明牌把每一手算到底。

别家手里的牌合起来是知道的(整副牌减去自己手里的和出过的),只是不知道两家各拿哪几张。张数都对得上的分法一个个摆开,
每种分法都当成明牌,地主一边、两个农民一边,算双方都不失误时谁赢;挑赢的分法最多的那一手。
算不完(超时、分法太多)就不管,照用 DouZero 的。
"""
import random
import time
from collections import Counter
from itertools import combinations

from douzero.env import move_detector as md
from douzero.env import move_selector as ms
from douzero.env.move_generator import MovesGener

SEATS = ["landlord", "landlord_down", "landlord_up"]
NEXT = {"landlord": "landlord_down", "landlord_down": "landlord_up", "landlord_up": "landlord"}
# 三家手里合起来最多这么多张才算残局。自己手里牌多时另算:两家对手手里合起来不超过 MAX_UNKNOWN 张、
# 分法不超过 MAX_SPLITS 时也搜——看不见的牌少,分法就少,算得完。
# 2026-11-17 13:05:08 精英场,我是地主下家 17 张,地主 7 张 + 队友 2 张(共 9 张),23 种分法:
# 三家合计 26 张原来直接不跑,退回现场模拟按均匀发牌出了 pass(地主第 7 张正好凑成顺子走完);
# 放开后带 likelihood 实测 0.14 秒算完,搜出 5 5 5 5 必赢。
# 阈值:40 局随机牌局里,三家合计 > 20、自己 ≥ 15 张、至少两手能出的位置,两家 ≤ 12 张时 17 个里
# 10 个在 2.5 秒内算完,9 张以内 10 个里 8 个;再放宽多出来的大多是算不完的,所以取 12。
# 算不完仍由 Solver 的 deadline 抛 Timeout,best 返回 None 退回原来的流程,整手不超过 ACT_TOTAL_BUDGET_S。
MAX_CARDS = 20
MAX_UNKNOWN = 12
MAX_SPLITS = 400


class Timeout(Exception):
    pass


def double_joker_kickers(move):
    """这手牌是不是把大小王一起当了带牌:飞机带单的翅膀、四带二的两张单牌。王炸自己出不算。"""
    kind = md.get_move_type(list(move))["type"]
    return kind in (md.TYPE_11_SERIAL_3_1, md.TYPE_13_4_2) and 20 in move and 30 in move


def without_double_joker_kickers(actions):
    return [m for m in actions if not double_joker_kickers(m)]


def is_bomb(move):
    """这手是不是炸弹或王炸。"""
    return md.get_move_type(list(move))["type"] in (md.TYPE_4_BOMB, md.TYPE_5_KING_BOMB)


def bomb_cards(hand):
    """[hand] 里能当炸弹 / 王炸打出去的牌:每个点数的四张、大小王一对。

    真人常把炸弹 / 王炸留到最后;估他之前那手杂牌像不像时,先把这些牌放一边,按剩下的牌打分。
    """
    counts = Counter(hand)
    out = [rank for rank, n in counts.items() if rank < 20 and n >= 4 for _ in range(4)]
    if counts[20] and counts[30]:
        out += [20, 30]
    return tuple(sorted(out))


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
    out = {tuple(sorted(m)) for m in found if not double_joker_kickers(m)}
    if rival:
        out.add(())
    return out


def minus(hand, move):
    left = list(hand)
    for c in move:
        left.remove(c)
    return tuple(left)


# 出完一手以后还剩几手(自己每次都先出)是「这手干不干净」的尺子。拆分组合多,一次决策最多算 MIN_PLAYS_BUDGET_S 秒;
# 算过的牌型留着,同一手牌反复问不用重算;服务是长跑进程,攒到上限就整个清掉,免得越积越多
MIN_PLAYS_BUDGET_S = 0.2
MIN_PLAYS_MEMO_MAX = 50000
_MIN_PLAYS = {}


def min_plays(hand, deadline=None):
    """[hand] 自己每次先出时最少几手出完:把牌拆成最少的几手合规牌(牌型同 DouZero,大小王不当带牌)。

    2026-11-16 11:58:20 土豪场那手 BJ 2 J 9 8 7 6 5 5 5 5 4:出 4..9 剩 BJ 2 J 5 5 5 三手(555 带 J、2、BJ),
    出 5..9 剩 BJ 2 J 5 5 5 4 四手。算不完([deadline] 到了,默认 MIN_PLAYS_BUDGET_S 秒)返回 None,调用方就不比手数。
    """
    if deadline is None:
        deadline = time.monotonic() + MIN_PLAYS_BUDGET_S
    if len(_MIN_PLAYS) > MIN_PLAYS_MEMO_MAX:
        _MIN_PLAYS.clear()
    try:
        return _min_plays(tuple(sorted(hand)), deadline)
    except Timeout:
        return None


def _min_plays(hand, deadline):
    if not hand:
        return 0
    if hand in _MIN_PLAYS:
        return _MIN_PLAYS[hand]
    if time.monotonic() > deadline:
        raise Timeout()
    best = None
    for move in moves(hand, ()):
        if not move:
            continue
        plays = 1 + _min_plays(minus(hand, move), deadline)
        if best is None or plays < best:
            best = plays
            if best == 1:
                break
    _MIN_PLAYS[hand] = best
    return best


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


def splits(unknown, a, na, b, nb, kept=()):
    """把 [unknown] 分给 [a] [na] 张、[b] [nb] 张的各种分法,带上这种分法有几种摆法(同点数的牌不分花色)。

    [kept] 是一定在 [a] 手里的牌:我是农民时,地主没打出去的底牌一定还在地主手里(同 server.sample_hands)。
    """
    fixed = tuple(sorted((Counter(unknown) & Counter(kept)).elements()))
    pool = list((Counter(unknown) - Counter(fixed)).elements())
    need = na - len(fixed)
    if need < 0 or need + nb != len(pool):
        return None
    seen = Counter()
    for picked in combinations(range(len(pool)), need):
        chosen = set(picked)
        ha = tuple(sorted(fixed + tuple(pool[i] for i in picked)))
        hb = tuple(sorted(pool[i] for i in range(len(pool)) if i not in chosen))
        seen[(ha, hb)] += 1
        if len(seen) > MAX_SPLITS:
            return None
    return seen


def best(infoset, budget=1.5, likelihood=None):
    """残局时每一手能出的牌在多少种分法下能赢,两种口径一起给:
    (按 [likelihood] 加权的 {move: 赢的权重}, 总权重, 只按摆法数的 {move: 赢的摆法数}, 总摆法数)。
    不在残局、算不完为 None。

    [likelihood] 可选: `likelihood(splits, deadline)` 给每种分法一个权重(和摆法数相乘), splits 是
    [{座位: 这种分法下此刻剩的牌}], 返回同样长的浮点列表。默认 None 就是原来的摆法数权重。
    回调要在 [deadline] 前算完, 来不及就抛 Timeout, 这一次退回不加权的摆法数。
    两种口径在同一遍搜索里一起累加:不加权的摆法数是不信「猜牌」时炸弹敢不敢翻倍的依据(见 Agent.act)。
    """
    me = infoset.player_position
    left = infoset.num_cards_left_dict
    others = [s for s in SEATS if s != me]
    # 三家合计多、但两家对手手里合起来没几张时也值得算:看不见的牌少,分法就少(见 MAX_UNKNOWN)
    if sum(left.values()) > MAX_CARDS and sum(left[s] for s in others) > MAX_UNKNOWN:
        return None
    unknown = sorted(infoset.other_hand_cards)
    if len(unknown) != left[others[0]] + left[others[1]]:
        return None
    ways = splits(unknown, others[0], left[others[0]], others[1], left[others[1]])
    if ways is None:
        return None
    hand = tuple(sorted(infoset.player_hand_cards))
    rival = tuple(sorted(infoset.last_move)) if [] in infoset.legal_actions else ()
    owner = infoset.last_pid
    deadline = time.monotonic() + budget
    splits_hands = [{me: hand, others[0]: ha, others[1]: hb} for (ha, hb), _ in ways.items()]
    if likelihood is not None:
        try:
            likes = likelihood(splits_hands, deadline)
        except Timeout:
            likes = [1.0] * len(splits_hands)
    else:
        likes = [1.0] * len(splits_hands)
    solver = Solver(deadline)
    wins = Counter()
    plain = Counter()
    total = 0.0
    plain_total = 0
    try:
        for ((ha, hb), count), like in zip(ways.items(), likes):
            hands = {me: hand, others[0]: ha, others[1]: hb}
            weight = count * like
            plain_total += count
            total += weight
            for action in infoset.legal_actions:
                move = tuple(sorted(action))
                landlord = solver.after(hands, me, rival, owner, move)
                if landlord == (me == "landlord"):
                    wins[move] += weight
                    plain[move] += count
    except Timeout:
        return None
    return wins, total, plain, plain_total


# 两手出完:第一手被压的机会不超过这么多才换;把未知牌随机分给两家这么多次来估
SAFE_FIRST = 0.3
SAMPLES = 300


def finish_in_two(hand, legal, rival, unknown, left_a, left_b, samples=SAMPLES, limit=SAFE_FIRST, seed=0,
                  deadline=None, kept=(), b_is_mate=False):
    """剩的牌多、搜不动时的一条捷径:自己手里能两手出完(先出一手、剩下的正好是一手)时,挑第一手最不容易被压的。

    把 [unknown](两家手里合起来的牌)随机分成 [left_a]、[left_b] 张,看有没有一家压得住第一手;第一手没人压就接着出完。
    [kept] 是一定在 [a] 手里的牌:我是农民时地主的底牌还没打出去的那几张(同 server.sample_hands 的守底牌)。
    先出 [a] 再出 [b]。两种顺序都两手出完、其中一手是炸弹 / 王炸时,优先把炸弹 / 王炸留到最后:
    炸弹留到最后能管任何牌,先甩出去只是给对手一个用炸弹的靶子。
    [legal] 是现在能出的牌,[rival] 是要压的那手(() 是自己先出)。返回 (第一手, 被压的机会),没有够安全的为 None。
    [deadline] 是这一手牌总预算的截止时刻(monotonic):到了就返回 None,照用 DouZero 的,别把现场模拟的时间也吃掉。
    [b_is_mate]:[b] 是我的队友(我是农民),他压得住也不会压我,只看地主 [a] 压不压得住。
    2026-11-22 12:32 JJ:我是地主下家剩 A A A 4 3 3,队友 17 张一张没出,把队友也算进去被压机会 0.73 超了门槛,
    捷径作废,现场模拟 14 局一手的噪声里挑了先出 4;只看地主是 0.14,该先出 A A A 3 3。
    """
    kept = tuple(sorted((Counter(unknown) & Counter(kept)).elements()))
    need_a = left_a - len(kept)
    rest_pool = list((Counter(unknown) - Counter(kept)).elements())
    if need_a < 0 or need_a + left_b > len(rest_pool):
        return None
    best = None
    best_key = None
    for move in legal:
        if not move:
            continue
        rest = minus(hand, move)
        if not rest:
            return tuple(sorted(move)), 0.0  # 一手出完
        if md.get_move_type(list(rest))["type"] == md.TYPE_15_WRONG:
            continue
        rng = random.Random(seed)
        beaten = 0
        pool = list(rest_pool)
        for _ in range(samples):
            if deadline is not None and time.monotonic() > deadline:
                return None
            rng.shuffle(pool)
            a = list(kept) + pool[:need_a]
            b = pool[need_a:need_a + left_b]
            if any(m for m in moves(a, list(move))) or (not b_is_mate and any(m for m in moves(b, list(move)))):
                beaten += 1
        chance = beaten / samples
        # 先比「炸弹是不是留到了最后」,再比被压机会:炸弹先出的一律排在非炸弹先出的后面
        key = (is_bomb(move) and not is_bomb(rest), chance)
        if best_key is None or key < best_key:
            best, best_key = (tuple(sorted(move)), chance), key
    return best if best is not None and best[1] <= limit else None

