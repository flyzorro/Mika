"""
斗地主出牌服务:给 DouZero 一局的已知信息,回下一手出什么。只在本机或局域网里给 Mika 用。

对手的手牌看不见,DouZero 也不需要逐张知道:它只看「其他两家合起来还剩哪些牌」和各家张数。
所以把看不见的牌随便分给两家凑够张数,再把识别到的出牌历史在 DouZero 的牌局里从头重放一遍:
重放时每一手都要合规,识别漏了一手、认错一张,这里就会对不上,直接报 history_mismatch,调用方退回「提示」。

POST /act  {"seat": "landlord" | "landlord_down" | "landlord_up",
            "hand": ["3", "3", ..., "BJ"],         # 自己此刻的手牌
            "bottom": ["9", "8", "7"],              # 三张底牌
            "history": [["3"], [], ["5", "?"], ...],# 从地主第一手起每一手,[] 是不出,? 是认不准的一张
            "auction": {"bid": {"landlord": 3},     # 可选:各座位最后叫的分(0..3),没有 / null = 不知道
                        "double": {"landlord": true, # 可选:各座位加没加倍,没有 / null = 不知道
                                   "landlord_down": false,
                                   "landlord_up": null}}}
# auction 是这一局的叫分 / 加倍结果,只用来给「看不见的牌怎么分」加权:叫了三分、加了倍的那家更可能拿到强牌,
# 残局搜索的分法按这份证据加权。v1 只看地主的叫分(叫三分),加倍按座位看;
# 我自己的叫分 / 加倍不参与。字段格式不对(座位名不认识、叫分不在 0..3、类型不对)就整块当没带,照常出牌,不报错。

? 是地主出的最后一张:右上角压着绶带,手机上常认不准。按牌规补:15 种点数挨个代进去,
凑得成合规牌型、压得过上家、牌还有剩的只有一种就补上;有几种都说得通时(单张、三带一的那一张),
手机可以在 ? 后面按字形从像到不像带上几个点数(如 "?5,6,9"),先试这几个,都说不通再试其余合规的点数,一个都不行才报 ambiguous_card。
猜错了,后面的出牌在重放里会不合规,照样对得出来。
-> {"play": ["5", "5"]} 或 {"error": "..."}

POST /bid  {"hand": [17 张]}  -> {"level": 3 | 0, "win": 0.62, "games": 300}   # 当地主的赢面,够了就叫三分;games 是 3 秒内实际打了几局

POST /double {"seat": "landlord" | "landlord_down" | "landlord_up" | "farmer",
              "hand": [...],                     # 地主 20 张(含底牌),农民 17 张
              "bottom": [三张]}                  # 三张明牌,归地主
-> {"double": true | false, "super": true | false, "win": 0.83, "games": 300}
   # 自己这一方的赢面,够了才建议加倍;只有地主、且赢面到 SUPER_DOUBLE_WIN 才回 super
   # games 是预算内实际打了几局:地主 / 单个农民座位 1.5 秒,farmer 两个座位各打一局(计两局)、共用 2 秒;
   # games 不到下限(其它座位 ROLLOUT_MIN_GAMES,farmer 2×)时 double / super 一律 false(赢面只是少数几局的比率)
# farmer 是认不出地主坐哪边时用的:两个农民座位各算一遍(共用时间预算),取较低的赢面
"""
import io
import json
import math
import os
import random
import sys
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import torch
from douzero.dmc.models import model_dict
from douzero.env import move_detector as md
from douzero.env.env import get_obs
from douzero.env.game import GameEnv

# 用 -I 起服务时脚本所在目录不在 sys.path 里:残局搜索和服务放在一起
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import endgame  # noqa: E402

SEATS = ["landlord", "landlord_down", "landlord_up"]
RANKS = {"3": 3, "4": 4, "5": 5, "6": 6, "7": 7, "8": 8, "9": 9, "10": 10, "J": 11, "Q": 12, "K": 13,
         "A": 14, "2": 17, "SJ": 20, "BJ": 30}
NAMES = {v: k for k, v in RANKS.items()}
DECK = [r for r in RANKS.values() if r < 20 for _ in range(4)] + [20, 30]
# 叫分:当地主自己跟自己打这么多局,赢面到这个数就叫三分,不到就不叫。闯关里农民靠不住电脑队友,地主赢了分数还翻倍
BID_GAMES = 300
# 不计炸弹时地主输赢都按两份算,期望是 4×赢面−2,赢面 0.5 保本。
# 2026-11-19 用 200 副牌、三家 plain DouZero 校准:估值 0.50–0.60 档炸弹计分均值 −0.19,
# 估值 >= 0.65 的均值 +1.475(按手牌统计 SE 0.26),所以门槛取 0.65。
BID_WIN = 0.65
# 加倍:同一手牌自己跟自己打这么多局看这一方的赢面。80 局只能分到 ±0.06,0.8 和 0.74 分不开;
# 300 局约 ±0.03。加倍只有约 5 秒,80 局在本机实测 0.75~1.35 秒,300 局最慢要 5 秒:
# 叫分最多打 [ROLLOUT_BUDGET_S] 秒;加倍单独用 [DOUBLE_BUDGET_S],到点就按已经打完的局数算
# (至少 [ROLLOUT_MIN_GAMES] 局)。手机读超时 4 秒,1.5 秒的模拟还留 2.5 秒给网络和点按钮。
DOUBLE_GAMES = 300
ROLLOUT_BUDGET_S = 3.0
# 地主刚定下来那 2 秒左右是底牌插进手牌的动画,手机这时还看不到加倍按钮,5 秒的加倍窗口只剩约 3 秒。
# 2026-11-17 15:23 精英场:54.16 叫三分当上地主 → 57.85 手机第一次看到加倍画面 → 问 /double(3 秒,
# 回 win 0.97、super)→ 00.88 游戏服务器记「不加倍」(超时)→ 01.02 手机才点下「加倍」,晚了 0.13 秒,
# 少了一次 ×2。所以加倍只模拟 1.5 秒,叫分仍用 ROLLOUT_BUDGET_S。
# 2026-11-17 本机(Apple M3 Max / macOS 26.7.2 / Python 3.14 venv)实测 12 手真实牌(地主都会叫):
# 地主 1.5 秒打 65~157 局(中位 102),3 秒 130~300 局(中位 202);赢面逐手差(1.5−3.0)中位 -0.010、
# 平均 +0.002;加倍 / 超级加倍决定 11/12 一致(一手 0.816 vs 0.765 在 0.8 门槛上翻转),
# 另 16 手随机 20 张牌 16/16 一致。单个农民座位 1.5 秒 78~123 局(中位 94),3 秒 155~235 局,决定 12/12 一致。
DOUBLE_BUDGET_S = 1.5
# seat="farmer" 两个农民座位共用预算,每副牌要打两局:1.5 秒实测 12 手真实牌只有 35~57 局(中位 48),
# 有一手不到 ROLLOUT_MIN_GAMES;2.0 秒 45~79 局(中位 67),全部过线,决定 12/12 与 3 秒一致。
# 2 秒是上限,不再加。极端强农民手(双王 + 四个 2)剩下的牌太弱,条件抽样 2 秒只采纳 31 副,
# 会按不加倍处理;真实牌局里地主拿剩下的牌叫三分几乎不会出现。
FARMER_DOUBLE_BUDGET_S = 2.0
ROLLOUT_MIN_GAMES = 40
# 赢面到了这个门槛才加倍。只点黄色「加倍」,绝不点绿色的「超级加倍」,金豆省着用。
DOUBLE_WIN = 0.8
# 农民单独的门槛:农民加倍只翻自己这一行(底分 × 炸弹 × 地主加倍 × 自己加倍),真实赢面 > 0.5 就是正期望;
# 但估值有误差,留余量。2026-11-16 用 calibrate_double.py 跑了 2000 副地主会叫的牌、两个农民座位共 4000 行:
# 估值 >= 0.65 的 135 行实际赢 0.607(0.60 档只有 1 行,0.70 以上 62 行赢 0.581),所以取 0.65。
FARMER_DOUBLE_WIN = 0.65
# 超级加倍:只有地主、且赢面到 SUPER_DOUBLE_WIN 才建议点绿色「超级加倍」,农民永远不点。
# 2026-11-16 用 calibrate_double.py 的地主模式跑了 1000 副地主会叫的牌(条件抽样用 landlords_would_call 估值):
# 估值 0.85 档 155 副实际赢 0.858,0.90 档 178 副赢 0.871,1.00 档 165 副赢 0.921;
# 实际胜率 >= 0.85 的最低估值档就是 0.85(见 double-calibration-2026-11-16.md)。
SUPER_DOUBLE_WIN = 0.85
# 条件抽样:地主这 20 张(17 张随机 + 3 张底牌)会不会叫。用 DouZero 地主模型对开局第一手所有合规出牌的
# 最高估值:单副一次前向约 3 毫秒,按 BID_BATCH 副攒成一批一次前向约 0.5 毫秒一副。
# 估值是这副牌在 DouZero 眼里的期望得分;2026-11-16 用 260 副随机 20 张牌、每副 40 局自对弈标定:
# 估值 0 附近实际赢面 0.56,当时取 BID_VALUE 为 0。
# 此门槛用于农民条件抽样,应反映真实对手地主的叫分行为,不必与我们自己的 BID_WIN 一致。
BID_VALUE = 0.0
# 条件抽样一次判多少副:攒成一批前向,弱地主牌被丢掉时也不至于把 2 秒预算全花在判断上
BID_BATCH = 32
# /act 总预算包含模型前向与残局搜索。
ACT_TOTAL_BUDGET_S = 3.5
ACT_ENDGAME_BUDGET_S = 2.5
# 残局加权胜率距最高值 1 个百分点以内时，由 DouZero 打分决胜。
ACT_ENDGAME_TIE_MARGIN = 0.01
# 残局分法似然:每家对手取最近 [LIKELIHOOD_MOVES] 手不是不出的出牌,各手概率相乘。取 2 是因为
# 2026-11-16 20:51:41 土豪场那一手,下家倒数第二手 10 10 10 + Q:如果手里还留着 4、9 这种低单张,
# 正常会拿小牌当带牌而不是 Q。只看最近一手 7 7 看不出这点,两手里的三带一才把「剩零散小牌」的分法压下去。
# 温度见 LIKELIHOOD_TEMPERATURE;对每手都用动作 softmax(该手在当时所有合法出牌里的概率),不掺绝对分值。
LIKELIHOOD_MOVES = 2
# 残局分法似然的温度:对手当时那副牌上,对那一手所有合法出牌的 DouZero 分数做 softmax 时除以它。
# 2026-11-16 20:51:41 土豪场那一手(地主 BJ SJ 2 Q 9 9,下家刚出 7 7 只剩两张)离线扫过 0.005~1.0:
# 0.2(只看最近一手时的温度)这一手仍是 9 9;取最近两手后 0.03 以下才翻成王炸,0.05 又回到 9 9。
# 0.02 时王炸的加权权重是 9 9 的 6.6 倍、不出的 16 倍;0.01 更极端,0.03 只剩 2 倍。取 0.02 留余量。
LIKELIHOOD_TEMPERATURE = 0.02
# 残局分法似然的下限:软证据(出牌历史的 DouZero 动作概率、叫分 / 加倍)把一种分法相对最可能的分法压低时,
# 权重不少于这个比例,不再往 0 压。硬约束(能一手走完却不出)仍是 0。
# 2026-11-19 10:53:07 普通场,我是地主上家(农民),手 14 张,地主剩 4 张、队友 5 张,看不见的是
# 4 4 9 J A A 2 2 BJ;地主之前已经把 2 2 2 2 拆开打过两次单张 2。温度 0.02 下 DouZero 给
# 「地主还拿着另外两张 2」的 12 种分法压到 0~1.7e-5,残局里炸弹 5 5 5 5 的赢面 99% 全是假的,
# 按 ×2 一算就炸了队友,翻倍输掉。只有最近两手出牌、模型给的概率远没有这么锋利,不能让软证据把
# 一整类分法判死。2026-11-20 在 200 个配对残局上比较 0.02 / 0.1 / 0.2,每个残局打两种对手:
# 0.1 胜负不变,0.2 少赢 1 局(−0.25 个百分点,标准误 0.25)。取噪声内的最大值 0.2,软证据最多拉开 5 倍。
LIKELIHOOD_FLOOR = 0.2
# 叫分 / 加倍先验:每家牌力 s = 用自己座位的 DouZero 模型给开局整手牌(手里剩的 + 出过的,地主含底牌)
# 打分,取所有合法出牌里的最高分。每条已知证据乘一个 logistic 似然因子:P = sigmoid((s - T) / AUCTION_TAU)。
# 2026-11-17 用 calibrate_auction.py 随机发 4000 副牌(seed 20261117,地主 4000 手、两个农民座位共 8000 手)
# 标定:地主牌力的 q35 / q65 / q80 = -0.933 / -0.021 / 0.595,农民牌力的 q80 = 0.237。
# T_bid 取地主 q65(叫三分的手牌约占 35%),T_dbl 取 q80(加倍约占 20%),τ 取地主 q35~q65 分位间距的
# 一半 0.456。按这组值在随机牌上的边际概率:P(叫三分)=0.385、P(地主加倍)=0.242、P(农民加倍)=0.291
# (T 由分位数定,logistic 较宽时边际会偏离 35% / 20%,但方向与门槛不变)。
AUCTION_BID_T = -0.021
AUCTION_DOUBLE_T = {"landlord": 0.595, "farmer": 0.237}
AUCTION_TAU = 0.456
WEIGHTS = os.environ.get("DOUZERO_WEIGHTS", os.path.join(os.path.dirname(__file__), "baselines", "douzero_ADP"))


def bid_level(win):
    """赢面到了门槛就叫三分,不到就不叫。"""
    return 3 if win >= BID_WIN else 0


def should_double(win, seat="landlord"):
    """赢面到了门槛才建议加倍:地主 0.8,农民用单独的门槛。"""
    return win >= (DOUBLE_WIN if seat == "landlord" else FARMER_DOUBLE_WIN)


def should_super(win, seat="landlord"):
    """只有地主、赢面到 SUPER_DOUBLE_WIN 才建议超级加倍;农民永远不点绿色。"""
    return seat == "landlord" and win >= SUPER_DOUBLE_WIN


class Mismatch(Exception):
    pass


class Auction:
    """一局的叫分 / 加倍结果:只留下确定知道的座位;没有任何证据时 parse_auction 返回 None。"""

    def __init__(self, bid, double):
        self.bid = dict(bid)
        self.double = dict(double)

    def __repr__(self):
        return f"Auction(bid={self.bid!r}, double={self.double!r})"

    def evidence(self, seat):
        """这个座位有哪些已知证据:("bid", 3) / ("double", True/False)。叫分 v1 只看地主叫三分。"""
        out = []
        if seat == "landlord" and self.bid.get("landlord") == 3:
            out.append(("bid", 3))
        if seat in self.double:
            out.append(("double", self.double[seat]))
        return out

    def seats(self, me):
        """要按证据加权的对手:有证据、又不是我自己(我自己的叫分 / 加倍不参与)。"""
        return [s for s in SEATS if s != me and self.evidence(s)]

    def log_weight(self, me, strength, seats=None):
        """这一局的证据在 [strength] 这种分法下的对数似然(<=0);[strength] 是 {座位: 开局整手牌的牌力}。

        [seats] 可选:只算这几家的证据(同 Advisor.likelihood 的 seats)。
        """
        total = 0.0
        for seat in (self.seats(me) if seats is None else [s for s in self.seats(me) if s in seats]):
            for kind, value in self.evidence(seat):
                p = auction_probability(seat, kind, value, strength[seat])
                total += math.log(min(max(p, 1e-9), 1 - 1e-9))
        return total


def _sigmoid(x):
    """数值稳定的 sigmoid:太负的 exp 会溢出。"""
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


def auction_probability(seat, kind, value, strength):
    """一家牌力 [strength] 时出现这条证据的概率:叫三分 / 加倍是 P,没加过倍是 1-P。"""
    if kind == "bid":
        return _sigmoid((strength - AUCTION_BID_T) / AUCTION_TAU)
    threshold = AUCTION_DOUBLE_T["landlord" if seat == "landlord" else "farmer"]
    p = _sigmoid((strength - threshold) / AUCTION_TAU)
    return p if value else 1.0 - p


def parse_auction(raw):
    """把 /act 的 auction 字段规范化成 Auction;没带、全是不知道、或者格式不对都返回 None。

    格式不对就整块忽略(照常出牌,不报错):bid / double 必须是对象,座位名必须是 SEATS 里的,
    bid 的值是 0..3 的整数(不是 bool),double 的值是 bool;单个座位的 null 表示那个座位不知道。
    """
    if not isinstance(raw, dict):
        return None
    bids, doubles = raw.get("bid"), raw.get("double")
    bids = {} if bids is None else bids
    doubles = {} if doubles is None else doubles
    if not isinstance(bids, dict) or not isinstance(doubles, dict):
        return None
    bid = {}
    for seat, value in bids.items():
        if seat not in SEATS:
            return None
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 3:
            return None
        bid[seat] = value
    double = {}
    for seat, value in doubles.items():
        if seat not in SEATS:
            return None
        if value is None:
            continue
        if not isinstance(value, bool):
            return None
        double[seat] = value
    if not bid and not double:
        return None
    return Auction(bid, double)


def cards(names):
    try:
        return sorted(RANKS[str(n).upper()] for n in names)
    except KeyError as e:
        raise Mismatch(f"unknown_card:{e.args[0]}")


def minus(a, b):
    left = Counter(a)
    left.subtract(Counter(b))
    if any(v < 0 for v in left.values()):
        raise Mismatch("more_cards_than_exist")
    return sorted(left.elements())


class Replay:
    """按顺序把历史里的每一手交给牌局;牌局自己判合规。"""

    def __init__(self, moves):
        self.moves = list(moves)

    def act(self, infoset):
        move = self.moves.pop(0)
        if move not in infoset.legal_actions:
            raise Mismatch("history_mismatch")
        return move


def legal(move, before):
    """[move] 接在 [before] 这串出牌后面合不合规:让 DouZero 的牌局按只拿着这手牌来生成能出的牌。"""
    probe = GameEnv({s: None for s in SEATS})
    probe.acting_player_position = "landlord"
    probe.info_sets["landlord"].player_hand_cards = list(move)
    probe.card_play_action_seq = list(before)
    return sorted(move) in [sorted(m) for m in probe.get_legal_card_play_actions()]


def resolve(hand, history):
    """把历史里的 ? 按牌规补上。history 里除 ? 外已是点数。

    一个 ? 有几种补法时,按手机猜的顺序挨个试,要后面每一手都还合规才算(单张 ? 猜成 J,下家却出了 J,就不对)。
    """
    known = [c for m in history for c in m if not isinstance(c, str)]
    pool = Counter(minus(minus(DECK, hand), known))
    # 手机按桌上从左到右报(顺子是 8 7 6 5 4 3),DouZero 的合规出牌是从小到大排的
    moves = [m if any(isinstance(c, str) for c in m) else sorted(m) for m in history]

    def search(i, out):
        if i == len(moves):
            return out
        move = moves[i]
        unknown = [c for c in move if isinstance(c, str)]
        if not unknown:
            if legal(move, out):
                return search(i + 1, out + [move])
            # 地主出的最后一张压着绶带,最常认错(牌还在落下的那一帧,3 会认成 J):不合规就把它当成认不出,按牌规补
            if i % 3 != 0 or not history[i]:
                return None
            wrong = history[i][-1]
            rest = list(history[i][:-1])
            pool[wrong] += 1
            fits = [r for r in sorted(set(DECK)) if r != wrong and pool[r] > 0 and legal(rest + [r], out)]
            for r in fits:
                pool[r] -= 1
                found = search(i + 1, out + [sorted(rest + [r])])
                pool[r] += 1
                if found is not None:
                    pool[wrong] -= 1
                    return found
            pool[wrong] -= 1
            return None
        if len(unknown) > 1:
            raise Mismatch("ambiguous_card")
        guesses = [RANKS.get(g.upper()) for g in unknown[0][1:].split(",") if g]
        rest = [c for c in move if not isinstance(c, str)]
        fits = [r for r in sorted(set(DECK)) if pool[r] > 0 and legal(rest + [r], out)]
        # 只有一种补法就是它;手机猜了几个就先试这几个,都不合规(丝带盖住了字,猜偏了)再按点数从小到大试其余说得通的
        if len(fits) == 1 or not guesses:
            tries = fits if len(fits) == 1 else []
        else:
            tries = [g for g in guesses if g in fits]
            tries += [r for r in fits if r not in tries]
        for r in tries:
            pool[r] -= 1
            found = search(i + 1, out + [sorted(rest + [r])])
            pool[r] += 1
            if found is not None:
                return found
        return None

    found = search(0, [])
    if found is None:
        raise Mismatch("ambiguous_card" if any(isinstance(c, str) for m in moves for c in m) else "history_mismatch")
    return found


def deal(seat, hand, bottom, history):
    played = {s: [] for s in SEATS}
    for i, move in enumerate(history):
        played[SEATS[i % 3]] += move
    unseen = minus(minus(DECK, hand), sum(played.values(), []))
    left = {s: (20 if s == "landlord" else 17) - len(played[s]) for s in SEATS}
    if left[seat] != len(hand):
        raise Mismatch("hand_count_mismatch")
    hands = {seat: list(hand)}
    others = [s for s in SEATS if s != seat]
    if seat != "landlord":
        # 底牌是地主的,没打出去的那几张一定还在地主手里。底牌那么小的字可能认错:看不见的牌里根本没有的那张丢掉,
        # 它只是 DouZero 的一点参考,不为它停掉整局
        kept = list(((Counter(bottom) - Counter(played["landlord"])) & Counter(unseen)).elements())
        hands["landlord"] = kept
        unseen = minus(unseen, kept)
    for s in others:
        need = left[s] - len(hands.get(s, []))
        if need < 0 or need > len(unseen):
            raise Mismatch("card_count_mismatch")
        hands[s] = hands.get(s, []) + unseen[:need]
        unseen = unseen[need:]
    if unseen:
        raise Mismatch("card_count_mismatch")
    return {s: sorted(hands[s] + played[s]) for s in SEATS}


def opening_state(seat, hand):
    """[seat] 开局时拿着整手 [hand] 的 info set:自己领出,其他两家只按张数算。

    给牌力打分用。三家开局时互相看不见,other_hand_cards 就是「除了我这手牌以外的全部牌」,
    不掺这种分法里对手具体拿了什么,所以同一手牌的牌力只由它自己决定,换个分法也不变。
    """
    hand = sorted(hand)
    return SimpleNamespace(
        player_position=seat,
        player_hand_cards=hand,
        other_hand_cards=sorted(minus(DECK, hand)),
        num_cards_left_dict={s: (20 if s == "landlord" else 17) for s in SEATS},
        three_landlord_cards=[],
        card_play_action_seq=[],
        legal_actions=[list(m) for m in sorted(endgame.moves(tuple(hand), ()))],
        last_move=[],
        last_two_moves=[[], []],
        last_move_dict={s: [] for s in SEATS},
        played_cards={s: [] for s in SEATS},
        bomb_num=0,
        last_pid=None,
        all_handcards=None,
    )


def history_frame(history, index):
    """第 [index] 手之前那些不随分法变的东西:每家从那手起又出了哪些牌、之前出过哪些、要压的牌。

    返回 (after, before, last_move_dict, bomb_num, last):[after] 是每家在第 [index] 手及以后出的牌
    (倒推当时手牌用),[before] 是之前出过的(模型的 played_cards),[last] 是当时要压的那手。
    """
    after = {s: [] for s in SEATS}
    before = {s: [] for s in SEATS}
    last_move_dict = {s: [] for s in SEATS}
    bomb_num = 0
    for j, move in enumerate(history):
        seat = SEATS[j % 3]
        if j >= index:
            after[seat] += move
            continue
        before[seat] += move
        last_move_dict[seat] = list(move)
        if md.get_move_type(list(move))["type"] in (md.TYPE_4_BOMB, md.TYPE_5_KING_BOMB):
            bomb_num += 1
    if index == 0:
        last = []
    elif history[index - 1]:
        last = list(history[index - 1])
    elif index >= 2:
        last = list(history[index - 2])
    else:
        last = []
    return after, before, last_move_dict, bomb_num, last


def history_state(history, hands, seat, index, frame, without=()):
    """把 [history] 重放到第 [index] 手之前,用三家此刻剩的 [hands] 倒推出当时的手牌,造出 [seat] 那时的 info set。

    第 [index] 手是 [seat] 出的:他当时的手牌 = 此刻剩的牌 + 他在这手及以后出的牌。别的两家同理。
    legal_actions 用 endgame.moves 生成(同样剔掉大小王一起当带牌),模型在自己的席位上打分。
    [without] 是先从 [seat] 手里放一边的牌(他留着没出的炸弹 / 王炸):按剩下的牌打分时用,
    放一边的牌算回看不见的牌里。
    """
    after, before, last_move_dict, bomb_num, last = frame
    hand_before = {s: sorted(list(hands[s]) + after[s]) for s in SEATS}
    removed = tuple(sorted((Counter(without) & Counter(hand_before[seat])).elements()))
    if removed:
        left = Counter(hand_before[seat])
        left.subtract(Counter(removed))
        hand_before[seat] = sorted(left.elements())
    legal = endgame.moves(tuple(hand_before[seat]), list(last))
    return SimpleNamespace(
        player_position=seat,
        player_hand_cards=list(hand_before[seat]),
        other_hand_cards=sorted(sum((hand_before[s] for s in SEATS if s != seat), []) + list(removed)),
        num_cards_left_dict={s: len(hand_before[s]) for s in SEATS},
        three_landlord_cards=[],
        card_play_action_seq=[list(m) for m in history[:index]],
        legal_actions=[list(m) for m in sorted(legal)],
        last_move=list(last),
        last_two_moves=[list(m) for m in history[max(0, index - 2):index]],
        last_move_dict=last_move_dict,
        played_cards=before,
        bomb_num=bomb_num,
        last_pid=None,
        all_handcards=None,
    )


class Agent:
    """同 DouZero 的 DeepAgent,只是权重按纯张量加载:checkpoint 是 pickle,不按张量读会执行文件里的代码。"""

    def __init__(self, seat, path):
        self.seat = seat
        self.model = model_dict[seat]()
        self.model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True))
        self.model.eval()

    def act(self, infoset, likelihood=None):
        deadline = time.monotonic() + ACT_TOTAL_BUDGET_S
        actions = endgame.without_double_joker_kickers(infoset.legal_actions)
        infoset.legal_actions = actions
        if len(actions) == 1:
            return actions[0]
        values = self.values(infoset)
        move = actions[int(torch.argmax(values))]
        budget = min(ACT_ENDGAME_BUDGET_S, deadline - time.monotonic())
        solved = endgame.best(infoset, budget=budget, likelihood=likelihood) if budget > 0 else None
        if solved is None:
            return move
        wins, total, _, _ = solved
        scores = {tuple(sorted(action)): float(value) for action, value in zip(actions, values)}
        cutoff = max(wins.get(key, 0) for key in scores) - ACT_ENDGAME_TIE_MARGIN * total
        allowed = {key for key in scores if wins.get(key, 0) >= cutoff}
        if tuple(sorted(move)) in allowed:
            return move
        return list(max(allowed, key=scores.get))

    def values(self, infoset):
        obs = get_obs(infoset)
        with torch.no_grad():
            return self.model.forward(torch.from_numpy(obs["z_batch"]).float(),
                                      torch.from_numpy(obs["x_batch"]).float(), return_value=True)["values"].flatten()

    def state_values(self, states, deadline=None):
        """一批 [seat] 的 info set 攒成一次前向,返回每个 info set 各手的 DouZero 分数。"""
        zs, xs, sizes = [], [], []
        for state in states:
            if deadline is not None and time.monotonic() > deadline:
                raise endgame.Timeout()
            obs = get_obs(state)
            zs.append(torch.from_numpy(obs["z_batch"]).float())
            xs.append(torch.from_numpy(obs["x_batch"]).float())
            sizes.append(len(state.legal_actions))
        with torch.no_grad():
            values = self.model.forward(
                torch.cat(zs), torch.cat(xs), return_value=True)["values"].flatten()
        if deadline is not None and time.monotonic() > deadline:
            raise endgame.Timeout()
        out, start = [], 0
        for size in sizes:
            out.append(values[start:start + size])
            start += size
        return out

    def opening_strengths(self, hands, deadline=None):
        """一批开局整手牌在这家模型眼里的牌力:所有合法出牌里的最高分。

        [hands] 是每家开局时的整手牌(手里剩的 + 已经出过的,地主含底牌)。只用一次前向,可以攒成一批。
        """
        return [float(v.max()) for v in self.state_values(
            [opening_state(self.seat, hand) for hand in hands], deadline)]


class Plain:
    """只按 DouZero 打分出牌,不做残局搜索:叫分时自己跟自己打几十局,要快。"""

    def __init__(self, agent):
        self.agent = agent

    def act(self, infoset):
        if len(infoset.legal_actions) == 1:
            return infoset.legal_actions[0]
        return infoset.legal_actions[int(torch.argmax(self.agent.values(infoset)))]


class Advisor:
    def __init__(self, weights):
        self.agents = {s: Agent(s, os.path.join(weights, f"{s}.ckpt")) for s in SEATS}
        self.plain = {s: Plain(self.agents[s]) for s in SEATS}

    def bid(self, hand, games=BID_GAMES, budget=ROLLOUT_BUDGET_S):
        """拿这 17 张当地主能赢几成:看不见的牌随机发成底牌和两家,DouZero 自己跟自己打 [games] 局。
        返回 (赢面, 实际打了几局):超过 [budget] 秒就停。"""
        hand = cards(hand)
        if len(hand) != 17:
            raise Mismatch("hand_count_mismatch")
        rest = minus(DECK, hand)
        rng = random.Random(hash(tuple(hand)))
        wins = played = 0
        deadline = time.perf_counter() + budget
        for _ in range(games):
            if played >= ROLLOUT_MIN_GAMES and time.perf_counter() > deadline:
                break
            played += 1
            rng.shuffle(rest)
            bottom = rest[:3]
            env = GameEnv(self.plain)
            env.card_play_init({"landlord": sorted(hand + bottom), "landlord_down": sorted(rest[3:20]),
                                "landlord_up": sorted(rest[20:37]), "three_landlord_cards": sorted(bottom)})
            while not env.game_over:
                env.step()
            wins += bool(env.num_wins["landlord"])
        return wins / played, played

    def landlord_would_call(self, hand, bottom):
        """地主这 20 张(含底牌)会不会叫地主:DouZero 地主模型对开局第一手所有合规出牌的最高估值到 BID_VALUE 就算会叫。
        只看地主的牌和三家张数,另外两家随便凑够 17 张(不影响地主观测)。"""
        return self.landlords_would_call([hand], bottom)[0]

    def landlords_would_call(self, hands, bottom):
        """一次判多副地主牌会不会叫:每副的观测攒成一批只前向一次。返回和 [hands] 一样长的布尔列表。"""
        zs, xs, sizes = [], [], []
        for hand in hands:
            rest = minus(DECK, hand)
            env = GameEnv(self.plain)
            env.card_play_init({"landlord": sorted(hand), "landlord_up": sorted(rest[:17]),
                                "landlord_down": sorted(rest[17:]), "three_landlord_cards": sorted(bottom)})
            obs = get_obs(env.game_infoset)
            zs.append(torch.from_numpy(obs["z_batch"]).float())
            xs.append(torch.from_numpy(obs["x_batch"]).float())
            sizes.append(len(env.game_infoset.legal_actions))
        if not zs:
            return []
        with torch.no_grad():
            values = self.agents["landlord"].model.forward(
                torch.cat(zs), torch.cat(xs), return_value=True)["values"].flatten()
        out, start = [], 0
        for size in sizes:
            out.append(float(values[start:start + size].max()) >= BID_VALUE)
            start += size
        return out

    def double(self, seat, hand, bottom, games=DOUBLE_GAMES, budget=None,
               condition=True, min_games=ROLLOUT_MIN_GAMES, on_accept=None):
        """这手牌加倍值不值:地主按 20 张(含底牌)、农民按 17 张加三张明牌,看不见的牌随机分给另外两家,
        DouZero 自己跟自己打 [games] 局,返回 (自己这一方的赢面(地主或农民), 实际打了几局):超过 [budget] 秒就停。
        [budget] 不传时地主 / 单个农民座位用 [DOUBLE_BUDGET_S],seat="farmer" 两个座位共用 [FARMER_DOUBLE_BUDGET_S]。

        农民默认做条件抽样:每副随机发牌先看地主那 20 张会不会叫(landlord_would_call),不会叫就丢掉重发;
        地主座位的算法不变。seat="farmer" 时两个农民座位各算一遍、共用预算,返回较低的赢面。
        min_games 是地主模拟的局数下限;农民座位不受它限制,返回实际打过的局数(两个座位每副各一局),
        调用方用 games 与门槛比较:端点不到 ROLLOUT_MIN_GAMES(seat="farmer" 为 2×)就按不加倍 / 不超级加倍。
        condition=False 只给校准/对照用。on_accept 每采纳一局回调三家手牌。"""
        if seat not in SEATS and seat != "farmer":
            raise Mismatch("bad_seat")
        if budget is None:
            budget = FARMER_DOUBLE_BUDGET_S if seat == "farmer" else DOUBLE_BUDGET_S
        hand, bottom = cards(hand), cards(bottom)
        if len(bottom) != 3:
            raise Mismatch("bottom_count_mismatch")
        if seat == "landlord":
            if len(hand) != 20:
                raise Mismatch("hand_count_mismatch")
            fixed = {"landlord": list(hand)}
            unseen = minus(DECK, hand)
            # 同一副牌每次问都一样:底牌也参与种子
            rng = random.Random(hash(tuple(sorted(hand + bottom))))
            wins = played = 0
            deadline = time.perf_counter() + budget
            for _ in range(games):
                if played >= min_games and time.perf_counter() > deadline:
                    break
                played += 1
                rng.shuffle(unseen)
                hands = {s: list(fixed.get(s, [])) for s in SEATS}
                pool = list(unseen)
                for s in SEATS:
                    need = (20 if s == "landlord" else 17) - len(hands[s])
                    hands[s] = sorted(hands[s] + pool[:need])
                    pool = pool[need:]
                if pool:
                    raise Mismatch("card_count_mismatch")
                env = GameEnv(self.plain)
                env.card_play_init({**hands, "three_landlord_cards": sorted(bottom)})
                while not env.game_over:
                    env.step()
                wins += bool(env.num_wins["landlord"])
            return wins / played, played
        if len(hand) != 17:
            raise Mismatch("hand_count_mismatch")
        seats = ["landlord_up", "landlord_down"] if seat == "farmer" else [seat]
        return self._farmers_double(seats, hand, bottom, games, budget, condition, on_accept)

    def _farmers_double(self, seats, hand, bottom, games, budget, condition, on_accept):
        """农民座位:底牌归地主;看不见的 34 张随机分成地主的 17 张和队友的 17 张。
        条件抽样只看地主那 20 张会不会叫。两个座位用同一批发牌(只差我坐地主的上家还是下家)。
        [games] 是模拟局数:一个座位一副牌一局,seat="farmer" 两个座位每副各一局(一副算两局)。"""
        unseen = minus(minus(DECK, hand), bottom)
        rng = random.Random(hash(tuple(sorted(hand + bottom))))
        wins = {s: 0 for s in seats}
        played = 0
        deadline = time.perf_counter() + budget
        while played * len(seats) < games:
            if time.perf_counter() > deadline:
                break
            # 一次抽一批再判:弱地主牌被丢掉的概率不低,一副一副判会把预算全花在判断上
            batch = []
            for _ in range(BID_BATCH):
                rng.shuffle(unseen)
                batch.append((sorted(bottom + unseen[:17]), sorted(unseen[17:])))
            keep = self.landlords_would_call([lord for lord, _ in batch], bottom) if condition else [True] * len(batch)
            for (landlord, teammate), accepted in zip(batch, keep):
                if not accepted:
                    continue
                if played * len(seats) >= games or time.perf_counter() > deadline:
                    break
                played += 1
                # 我坐地主上家:上家是我、下家是队友;我坐下家:下家是我、上家是队友。地主和队友的牌两边一样。
                # 牌局会就地删牌,每家每局都给一份新的列表
                deal = {"landlord": list(landlord), "landlord_up": list(hand), "landlord_down": list(teammate)}
                if on_accept is not None:
                    on_accept({k: list(v) for k, v in deal.items()})
                for s in seats:
                    hands = {k: list(v) for k, v in deal.items()}
                    if s == "landlord_down":
                        hands["landlord_up"], hands["landlord_down"] = list(teammate), list(hand)
                    env = GameEnv(self.plain)
                    env.card_play_init({**hands, "three_landlord_cards": sorted(bottom)})
                    while not env.game_over:
                        env.step()
                    wins[s] += bool(env.num_wins["farmer"])
        if played == 0:
            return 0.0, 0
        rates = {s: wins[s] / played for s in seats}
        # 每个采纳的牌局两个农民座位各打一局:games 报「局数」而不是「副数」。
        # 局数不到 ROLLOUT_MIN_GAMES 时也报真实赢面,由端点按 games 决定不加倍;不能把「不知道」写成 0 赢面。
        return min(rates.values()), played * len(seats)

    def likelihood(self, history, me, auction=None):
        """给 endgame.best 的似然回调:按两家对手最近 [LIKELIHOOD_MOVES] 手不是不出的出牌,判断一种分法像不像他们当时会这么出。

        用对手模型给历史局面的合法出牌打分,取这一手的 log softmax 概率 P(那手 | 当时手牌);同一对手各手 log 概率相加,
        两家对手再相加。只看最近一手不够:下家倒数第二手 10 10 10 + Q 才是「他手里没有 4/9
        这种零散小牌」的证据。
        用 float64 在对数空间算,再减去所有分法里最大的 log 似然后 exp:相对权重不变,但对手真出过一手
        模型觉得极差的牌时不会像 float32 概率相乘那样下溢成 0(那会让 total=0、残局搜索整个退回 DouZero)。
        软证据压出来的相对权重有下限 [LIKELIHOOD_FLOOR]:温度 0.02 下动作概率能差好几个数量级,
        可只有最近两手出牌,不能让「猜牌」把一整类分法判死(见 LIKELIHOOD_FLOOR 的注释)。
        某个观测出法在这种分法的合法出牌里找不到时(正常历史不该发生)这种分法权重记 0;
        如果所有分法都这样(全 -inf),这次退回不加权,不能把搜索关掉。
        一次把同一手的全部分法攒成一批前向;到 [deadline] 就抛 Timeout,best 退回不加权的摆法数。
        回调第一次被调用时才去翻 history:不是残局时 endgame.best 根本不叫它,这一手就没有额外开销。
        [auction] 是这一局的叫分 / 加倍:每家对手的开局整手牌(这种分法下剩的 + 已经出过的)用他自己座位的
        模型打分,把证据的 log 似然加进同一个对数空间;没有出牌历史、只有 auction 时也生效。
        我自己的叫分 / 加倍不算证据(auction.seats 已经把我排除)。
        """
        watched = set(SEATS) - {me}

        def callback(splits_hands, deadline):
            indices = {}
            seen_seats = set()
            finish_checks = []
            for i in range(len(history) - 1, -1, -1):
                seat = SEATS[i % 3]
                played = history[i]
                if seat != me:
                    latest = seat not in seen_seats
                    if latest or (not played and seat in watched):
                        after, _, _, _, last = history_frame(history, i)
                        if latest or last:
                            finish_checks.append((seat, after[seat], last, len(played)))
                    seen_seats.add(seat)
                if played and seat in watched:
                    moves = indices.setdefault(seat, [])
                    if len(moves) < LIKELIHOOD_MOVES:
                        moves.append(i)
            logs = [0.0] * len(splits_hands)
            # 能走完却没走完的分法权重记 0。检查两家最近一轮（出牌或过牌），也保留历史过牌约束。
            if finish_checks:
                for k, hands in enumerate(splits_hands):
                    if k % 32 == 0 and time.monotonic() > deadline:
                        raise endgame.Timeout()
                    for seat, after, last, played_count in finish_checks:
                        whole = tuple(sorted(list(hands[seat]) + after))
                        if len(whole) > played_count and whole in endgame.moves(whole, last):
                            logs[k] = float("-inf")
                            break
            for seat, moves in indices.items():
                for index in moves:
                    frame = history_frame(history, index)
                    states = []
                    for n, hands in enumerate(splits_hands):
                        if n % 32 == 0 and time.monotonic() > deadline:
                            raise endgame.Timeout()
                        states.append(history_state(history, hands, seat, index, frame))
                    scores = self.state_values(seat, states, deadline)
                    observed = tuple(sorted(history[index]))
                    # 真人会留着炸弹 / 王炸,先把杂牌走掉:这一手不是炸弹时,再按「炸弹先放一边」
                    # 剩下的牌打一次分,这一手的 log 概率取两个里大的那个。放一边后不合法的分法
                    # (这一手用到了炸弹里的牌)只用整手牌那一项。
                    reduced = {}
                    if not endgame.is_bomb(observed):
                        bombs = {k: endgame.bomb_cards(state.player_hand_cards)
                                 for k, state in enumerate(states)}
                        placed = {k: cards for k, cards in bombs.items() if cards}
                        if placed:
                            items = [(k, history_state(history, splits_hands[k], seat, index, frame,
                                                       without=cards))
                                     for k, cards in placed.items()]
                            rscores = self.state_values(seat, [state for _, state in items], deadline)
                            reduced = {k: (state, values) for (k, state), values in zip(items, rscores)}
                    for k, (state, values) in enumerate(zip(states, scores)):
                        if not splits_hands[k][seat]:
                            continue
                        actions = [tuple(sorted(m)) for m in state.legal_actions]
                        if observed not in actions:
                            logs[k] = float("-inf")
                            continue
                        j = actions.index(observed)
                        like = float(torch.log_softmax(
                            values.double() / LIKELIHOOD_TEMPERATURE, dim=0)[j])
                        if k in reduced:
                            rstate, rvalues = reduced[k]
                            ractions = [tuple(sorted(m)) for m in rstate.legal_actions]
                            if observed in ractions:
                                rj = ractions.index(observed)
                                like = max(like, float(torch.log_softmax(
                                    rvalues.double() / LIKELIHOOD_TEMPERATURE, dim=0)[rj]))
                        logs[k] += like
                    if time.monotonic() > deadline:
                        raise endgame.Timeout()
            evidence = [s for s in (auction.seats(me) if auction is not None else []) if s in watched]
            if evidence:
                # 每种分法的对手开局整手牌 = 这种分法下他还剩的 + 他已经出过的;按证据座位各攒一批前向
                played = {s: [] for s in SEATS}
                for i, move in enumerate(history):
                    played[SEATS[i % 3]] += move
                strength = [dict() for _ in splits_hands]
                for seat in evidence:
                    states = [opening_state(seat, sorted(list(hands[seat]) + played[seat]))
                              for hands in splits_hands]
                    values = self.state_values(seat, states, deadline)
                    for k, value in enumerate(values):
                        strength[k][seat] = float(value.max())
                for k in range(len(logs)):
                    logs[k] += auction.log_weight(me, strength[k], seats=evidence)
            finite = [x for x in logs if x != float("-inf")]
            if not finite:
                return [1.0] * len(splits_hands)
            top = max(finite)
            # 归一化后的软证据权重至少是 LIKELIHOOD_FLOOR;硬约束(-inf)仍是 0。
            return [max(math.exp(x - top), LIKELIHOOD_FLOOR) if x != float("-inf") else 0.0 for x in logs]

        return callback

    def state_values(self, seat, states, deadline):
        """一批 [seat] 的 info set 攒成一次前向,返回每个 info set 各手的 DouZero 分数。"""
        return self.agents[seat].state_values(states, deadline)

    def act(self, seat, hand, bottom, history, auction=None):
        hand, bottom = cards(hand), cards(bottom)
        history = resolve(hand, [[str(c) if str(c).startswith("?") else cards([c])[0] for c in m] for m in history])
        if len(history) % 3 != SEATS.index(seat):
            raise Mismatch("not_your_turn")
        start = deal(seat, hand, bottom, history)
        replay = Replay(history)
        env = GameEnv({s: replay for s in SEATS})
        env.card_play_init({**{s: start[s] for s in SEATS}, "three_landlord_cards": bottom})
        while replay.moves:
            env.step()
            if env.game_over:
                raise Mismatch("game_already_over")
        if env.acting_player_position != seat:
            raise Mismatch("not_your_turn")
        return self.agents[seat].act(env.game_infoset, likelihood=self.likelihood(history, seat, auction))


class Cards:
    """POST /cards 的认牌模型(tools/cardvision)。权重路径在 DDZ_CARDS_WEIGHTS;没配就不提供,其余接口照常。"""

    def __init__(self, path):
        self.path, self.model, self.lock = path, None, threading.Lock()

    def detect(self, jpeg):
        if not self.path or not os.path.exists(self.path):
            return None
        with self.lock:  # MPS 上一次只跑一张
            if self.model is None:
                sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "cardvision"))
                import model as cardvision  # noqa: E402  只有认牌要 torchvision
                self.model, self.predict = cardvision.load(self.path), cardvision.predict
            from PIL import Image
            return self.predict(self.model, Image.open(io.BytesIO(jpeg)).convert("RGB"))


def handler(advisor, card_reader=None):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path == "/cards":
                return self.post_cards()
            if self.path not in ("/act", "/bid", "/double"):
                return self.reply(404, {"error": "not_found"})
            started = time.perf_counter()
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                ms = lambda: round((time.perf_counter() - started) * 1000)
                if self.path == "/bid":
                    win, games = advisor.bid(body["hand"])
                    return self.reply(200, {"level": bid_level(win), "win": round(win, 2), "games": games, "ms": ms()}, body)
                if self.path == "/double":
                    seat = body["seat"]
                    win, games = advisor.double(seat, body["hand"], body["bottom"])
                    # 局数不到下限时赢面只是打过的几局的比率,可能是运气:一律按不加倍 / 不超级加倍。
                    # 地主总能打满下限;农民的条件抽样在极端强手 / 机器忙时会不到 40 局。
                    # seat="farmer" 每副两个座位各打一局:要 40 副才是每个座位 40 局,所以门槛是 2 倍。
                    need = 2 * ROLLOUT_MIN_GAMES if seat == "farmer" else ROLLOUT_MIN_GAMES
                    enough = games >= need
                    return self.reply(200, {"double": enough and should_double(win, seat),
                                            "super": enough and should_super(win, seat),
                                            "win": round(win, 2), "games": games, "ms": ms()}, body)
                move = advisor.act(body["seat"], body["hand"], body.get("bottom", []), body.get("history", []),
                                   auction=parse_auction(body.get("auction")))
                self.reply(200, {"play": [NAMES[c] for c in move], "ms": ms()}, body)
            except Mismatch as e:
                self.reply(200, {"error": str(e)}, body)
            except (KeyError, ValueError, TypeError) as e:
                self.reply(400, {"error": f"bad_request:{e}"})

        def post_cards(self):
            started = time.perf_counter()
            jpeg = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            try:
                found = card_reader.detect(jpeg) if card_reader else None
            except OSError as e:  # 图片坏了
                return self.reply(400, {"error": f"bad_image:{e}"})
            except Exception as e:  # 权重和代码的类别对不上之类:回个错,别让手机只看到断线
                return self.reply(500, {"error": f"cards_failed:{type(e).__name__}:{e}"})
            if found is None:
                return self.reply(503, {"error": "cards_unavailable"})
            self.reply(200, {"cards": found, "ms": round((time.perf_counter() - started) * 1000)},
                       {"jpeg_bytes": len(jpeg)})

        def reply(self, code, data, request=None):
            # 每次一行:问了什么、答了什么,对不上时拿来查是哪一手读错了
            print(json.dumps({"t": time.strftime("%H:%M:%S"), "request": request, "reply": data}, ensure_ascii=False), flush=True)
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
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 3426
    ThreadingHTTPServer(("0.0.0.0", port), handler(Advisor(WEIGHTS), Cards(os.environ.get("DDZ_CARDS_WEIGHTS")))).serve_forever()
