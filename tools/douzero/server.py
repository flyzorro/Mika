"""
斗地主出牌服务:给 DouZero 一局的已知信息,回下一手出什么。只在本机或局域网里给 Mika 用。

对手的手牌看不见,DouZero 也不需要逐张知道:它只看「其他两家合起来还剩哪些牌」和各家张数。
所以把看不见的牌随便分给两家凑够张数,再把识别到的出牌历史在 DouZero 的牌局里从头重放一遍:
重放时每一手都要合规,识别漏了一手、认错一张,这里就会对不上,直接报 history_mismatch,调用方退回「提示」。

POST /act  {"seat": "landlord" | "landlord_down" | "landlord_up",
            "hand": ["3", "3", ..., "BJ"],         # 自己此刻的手牌
            "bottom": ["9", "8", "7"],              # 三张底牌
            "history": [["3"], [], ["5", "?"], ...]} # 从地主第一手起每一手,[] 是不出,? 是认不准的一张

? 是地主出的最后一张:右上角压着绶带,手机上常认不准。按牌规补:15 种点数挨个代进去,
凑得成合规牌型、压得过上家、牌还有剩的只有一种就补上;有几种都说得通时(单张、三带一的那一张),
手机可以在 ? 后面按字形从像到不像带上几个点数(如 "?5,6,9"),先试这几个,都说不通再试其余合规的点数,一个都不行才报 ambiguous_card。
猜错了,后面的出牌在重放里会不合规,照样对得出来。
-> {"play": ["5", "5"]} 或 {"error": "..."}

POST /bid  {"hand": [17 张]}  -> {"level": 3 | 0, "win": 0.62, "games": 300}   # 当地主的赢面,够了就叫三分;games 是 3 秒内实际打了几局

POST /double {"seat": "landlord" | "landlord_down" | "landlord_up",
              "hand": [...],                     # 地主 20 张(含底牌),农民 17 张
              "bottom": [三张]}                  # 三张明牌,归地主
-> {"double": true | false, "win": 0.83, "games": 300}     # 自己这一方的赢面,够了才建议加倍
"""
import json
import os
import random
import sys
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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
# 地主输赢都按两份算,叫地主的期望是 4×赢面−2:过 0.5 就不亏,三家都不叫重发的期望约为 0;留一点余量取 0.55
BID_WIN = 0.55
# 加倍:同一手牌自己跟自己打这么多局看这一方的赢面。80 局只能分到 ±0.06,0.8 和 0.74 分不开;
# 300 局约 ±0.03。加倍只有约 5 秒,80 局在本机实测 0.75~1.35 秒,300 局最慢要 5 秒:
# 叫分、加倍都最多打 [ROLLOUT_BUDGET_S] 秒,到点就按已经打完的局数算(至少 [ROLLOUT_MIN_GAMES] 局),手机等 4 秒。
DOUBLE_GAMES = 300
ROLLOUT_BUDGET_S = 3.0
ROLLOUT_MIN_GAMES = 40
# 赢面到了这个门槛才加倍。只点黄色「加倍」,绝不点绿色的「超级加倍」,金豆省着用。
DOUBLE_WIN = 0.8
# 几手分数几乎一样时 DouZero 基本是随手挑:差不到这个数就优先不拆三条/对子/炸弹。
# 实机那手拆 10 10 10 去顶队友的 6 只比单张 9 高 0.009;再大就会盖住真正该拆的一手,0.05 只收真正的平局。
KEEP_MARGIN = 0.05
# 分数接近时现场模拟:2026-11-16 10:16 精英场,我是地主(BJ 2 A A A K K Q J 10 10 8 6 5 5 5 5 3 3),
# 下家农民用单张 2 顶住。DouZero 给不出不出的 +0.121 对 BJ 的 +0.060,只差 0.06 就信了分数选了不出。
# 按看得见的牌随机发几百副、三家都用 DouZero 打分打到底:不出赢 0.79、BJ 赢 0.69,分数那点差距并不可靠。
# 所以顶两手的分数差不到 CLOSE_MARGIN 就现场模拟:取 CLOSE_MARGIN 以内最多 CLOSE_TOP 手,同一批发牌比出牌,
# 赢面高出 SWITCH_MARGIN 才换,否则仍听 DouZero。2.5 秒内每手至少打 ACT_ROLLOUT_MIN_GAMES 局,手机这一手等 4 秒够。
# 但开局每局都长:2026-11-16 10:58:14 精英场,我当地主的第一手(2 2 A K K Q Q J 9 8 8 8 7 7 6 5 5 5 5 4),
# 2.5 秒只跑了 43 局,不到 60 局就白模拟了;单张 9 赢面 0.4 比单张 6 的 0.27 高 13 个点也换不掉。
# 三家手里还有 OPENING_CARDS 张以上算开局,下限降到 ACT_ROLLOUT_MIN_GAMES_OPENING 局;
# 局数少更吵,不到 60 局时改用 OPENING_SWITCH_MARGIN 才换。
CLOSE_MARGIN = 0.1
CLOSE_TOP = 3
SWITCH_MARGIN = 0.05
OPENING_SWITCH_MARGIN = 0.1
ACT_ROLLOUT_BUDGET_S = 2.5
ACT_ROLLOUT_GAMES = 300
ACT_ROLLOUT_MIN_GAMES = 60
OPENING_CARDS = 45
ACT_ROLLOUT_MIN_GAMES_OPENING = 30
# 一手牌总共只给这么多秒:手机 /act 最多等 4 秒(连接 2 秒 + 读 4 秒,失败还重试一次),超了就当服务挂了。
# 2026-11-16 12:18:11 土豪场,我是地主下家,手里 A 5 4 4 4,三家还剩 19 张:残局搜索先花掉 1.5 秒没结论,
# 现场模拟又打满 2.5 秒,回复 4903 毫秒;手机 4 秒超时,标成 douzero_unreachable 重发同一手,三次各约 4020 毫秒,
# 最后按简单规则打完这一局。所以从 Agent.act 进来起总共只给 3.2 秒,残局搜索、两手出完、现场模拟都在这一个预算里分:
# 残局搜索最多 ACT_ENDGAME_BUDGET_S 秒(原来就是 1.5),现场模拟用 min(2.5, 剩下的 − 0.1) 秒;
# 剩下的不到 ACT_ROLLOUT_MIN_S 就整个不模拟,直接听 DouZero 的。
ACT_TOTAL_BUDGET_S = 3.2
ACT_BUDGET_SAFETY_S = 0.1
ACT_ROLLOUT_MIN_S = 0.8
ACT_ENDGAME_BUDGET_S = 1.5
# 后半局不管分差都现场模拟:三家手里合起来只剩 LATE_CARDS 张时,DouZero 分数最高的 LATE_TOP 手(不出也算一手)都模拟一遍。
# 2026-11-16 11:43:53 土豪场,我是地主下家,手里 2 2 K Q J 10 9 8 8 8 4 3 3,三家还剩 33 张。
# DouZero 给 8 9 10 J Q K +0.736、8 9 10 J Q +0.627,只差 0.109,按 CLOSE_MARGIN 不模拟,信分数出了 8..K,输了。
# 离线按看得见的牌打 400 局:3 3 8 8 8 赢 0.98、8..K 只赢 0.82;明牌搜索也确认 8..K 必输。
# 后半局每手都短、2.5 秒够每手打 60 局以上,就不再拿分差当理由;仍要高出 SWITCH_MARGIN 才换。
LATE_CARDS = 35
LATE_TOP = 8
WEIGHTS = os.environ.get("DOUZERO_WEIGHTS", os.path.join(os.path.dirname(__file__), "baselines", "douzero_ADP"))


def bid_level(win):
    """赢面到了门槛就叫三分,不到就不叫。"""
    return 3 if win >= BID_WIN else 0


def should_double(win):
    """赢面到了门槛才建议加倍。"""
    return win >= DOUBLE_WIN


class Mismatch(Exception):
    pass


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


def breaks_combo(move, hand):
    """这手是不是从手里某个点数里只拿了一部分:三条拆单、对子拆单、炸弹拆开。不出不算。"""
    in_hand = Counter(hand)
    return any(0 < n < in_hand[rank] for rank, n in Counter(move).items())


def is_simple_combo(move):
    """单张、对子、三条,以及带牌的三带一/三带二。顺子、连对、飞机、炸弹、王炸都不算。"""
    counts = tuple(sorted(Counter(move).values()))
    return counts in ((1,), (2,), (3,), (1, 3), (2, 3))


def single_kickers(move):
    """三带一/飞机带单里当带牌的单张;三条本身(哪怕三条是 A/2)不算。飞机带对不在其中。"""
    kind = md.get_move_type(sorted(move))["type"]
    if kind not in (md.TYPE_6_3_1, md.TYPE_11_SERIAL_3_1):
        return []
    return [rank for rank, n in Counter(move).items() if n == 1]


def straight_runs(hand):
    """手里的点数连成的顺子段(3 到 A;2 和王不连):[[3, 4, 5, ...], ...]。"""
    runs = []
    for rank in sorted({r for r in hand if r < RANKS["2"]}):
        if runs and rank == runs[-1][-1] + 1:
            runs[-1].append(rank)
        else:
            runs.append([rank])
    return runs


def loose_single(rank, hand, rest):
    """[rank] 能不能当换带牌的零散小单张:手里只有一张、不超过 K;顺子看 [rest](出完当前的牌以后剩下的手牌),
    在里面 5 张以上的顺子中,只有当拿走它顺子还剩 5 张(在顺子两头且顺子至少 6 张)时才拿得走。"""
    if Counter(hand)[rank] != 1 or rank > RANKS["K"]:
        return False
    for run in straight_runs(rest):
        if rank in run and len(run) >= 5:
            return len(run) >= 6 and rank in (run[0], run[-1])
    return True


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


def move_label(move):
    """一手牌在诊断里的名字:不出叫 pass,其余按点数从小到大写出来(如 "5 5 5 5")。"""
    return " ".join(NAMES[c] for c in move) if move else "pass"


def fewer_plays(hand, keys, rates, scores, own, deadline=None):
    """模拟赢面差不多时,在赢面不低于 DouZero 那手([own])的候选里挑出完剩下手数最少的一手。

    [keys] 是候选手牌(已排序的元组),[rates] 是模拟赢面,[scores] 是 DouZero 分数。
    赢面比 [own] 低的候选不用手数翻案(模拟都说更差);手数一样多时挑赢面高的,再挑分数高的。
    返回 (选中的一手, 比过的手数),没有更少手的候选返回 None。一手都算不完(到 [deadline])也返回 None。
    """
    if deadline is None:
        deadline = time.monotonic() + endgame.MIN_PLAYS_BUDGET_S
    plays = {own: endgame.min_plays(endgame.minus(hand, own), deadline)}
    if plays[own] is None:
        return None
    picked = None
    for k in keys:
        if k == own or rates[k] < rates[own]:
            continue
        plays[k] = endgame.min_plays(endgame.minus(hand, k), deadline)
        if plays[k] is None:
            return None
        if plays[k] < plays[own] and (picked is None or (
                plays[k], -rates[k], -scores[k]) < (plays[picked], -rates[picked], -scores[picked])):
            picked = k
    return None if picked is None else (picked, plays)


def sample_hands(infoset, rng):
    """只按看得见的信息随机发牌:别人两家合起来的手牌分给两家、张数对得上。
    我是农民时地主没打出去的底牌一定还在他手里,先把这几张留出来(sample 与 deal 是同一个意思)。"""
    me = infoset.player_position
    left = infoset.num_cards_left_dict
    unseen = list(infoset.other_hand_cards)
    rng.shuffle(unseen)
    hands = {me: list(infoset.player_hand_cards)}
    if me != "landlord":
        kept = list((Counter(infoset.three_landlord_cards) & Counter(unseen)).elements())
        hands["landlord"] = kept
        unseen = list((Counter(unseen) - Counter(kept)).elements())
    for s in [s for s in SEATS if s != me]:
        need = left[s] - len(hands.get(s, []))
        hands[s] = hands.get(s, []) + unseen[:need]
        unseen = unseen[need:]
    return {s: sorted(hands[s] + list(infoset.played_cards[s])) for s in SEATS}


class Agent:
    """同 DouZero 的 DeepAgent,只是权重按纯张量加载:checkpoint 是 pickle,不按张量读会执行文件里的代码。"""

    def __init__(self, seat, path):
        self.model = model_dict[seat]()
        self.model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True))
        self.model.eval()
        # 分数接近时要不要现场模拟;测试里造局面时关掉,免得每走一步都花 2.5 秒
        self.rollout = True
        # 三家「只按 DouZero 打分」的对手,现场模拟时用;由 Advisor 建好后填进来
        self.plain = {}

    def act(self, infoset, report=None):
        # 一手牌的总预算从这里开始算:下面 DouZero 打分、残局搜索、两手出完、现场模拟都只能用剩下的时间
        deadline = time.perf_counter() + ACT_TOTAL_BUDGET_S
        # 斗地主经典版不允许大小王一起当带牌(飞机带单的翅膀、四带二的两张单牌):DouZero 的合法出牌里有,
        # 先剔掉,后面 DouZero 打分、残局搜索、两手出完都只看这份
        infoset.legal_actions = endgame.without_double_joker_kickers(infoset.legal_actions)
        if len(infoset.legal_actions) == 1:
            return infoset.legal_actions[0]
        values = self.values(infoset)
        move = infoset.legal_actions[int(torch.argmax(values))]
        # 剩的牌少时按明牌把各种分法算到底:有赢面更大的一手就换成它(赢面一样大的里挑 DouZero 打分最高的)。
        # 下面几条硬规则只在搜索也认为不吃亏时才换。搜索最多 1.5 秒,总预算不够时只给剩下的
        left_s = deadline - time.perf_counter()
        solved = endgame.best(infoset, budget=min(ACT_ENDGAME_BUDGET_S, left_s)) if left_s > 0 else None
        allowed = None
        if solved is not None:
            wins, _ = solved
            score = {tuple(sorted(a)): float(values[i]) for i, a in enumerate(infoset.legal_actions)}
            top = max(wins.get(m, 0) for m in score)
            allowed = {m for m in score if wins.get(m, 0) == top}
            if wins.get(tuple(sorted(move)), 0) < top:
                move = list(max(allowed, key=score.get))
                return self.swap_high_kickers(infoset, move, ok=lambda m: tuple(sorted(m)) in allowed)
        if solved is None:
            # 剩的牌多、搜不动:自己能两手出完、第一手很难被压时,先出那手再出完(DouZero 偶尔先出一张会被王压的单张)
            others = [p for p in endgame.SEATS if p != infoset.player_position]
            left = infoset.num_cards_left_dict
            rival = tuple(sorted(infoset.last_move)) if [] in infoset.legal_actions else ()
            quick = endgame.finish_in_two(tuple(sorted(infoset.player_hand_cards)), infoset.legal_actions, rival,
                                          sorted(infoset.other_hand_cards), left[others[0]], left[others[1]],
                                          deadline=deadline - ACT_BUDGET_SAFETY_S)
            if quick is not None and tuple(sorted(move)) != quick[0]:
                return list(quick[0])
            # 分数只差一点点时,现场按看得见的牌模拟几百局再定;模拟了就以它为准,不再走下面那条不拆牌的规则
            rolled = self.close_rollout(infoset, values, move, deadline, report) if self.rollout else None
            if rolled is not None:
                move, info = rolled
                if report is not None:
                    report["rollout"] = info
            else:
                # 分数几乎一样的几手里优先不拆牌:DouZero 常常随手拆三条、对子去顶一张小牌
                move = self.keep_combos(infoset, values, move)
        ok = lambda m: allowed is None or tuple(sorted(m)) in allowed
        # 地主只剩一张、桌上是他的牌、我是他前面最后一个能管的农民:不管就是让他出完。DouZero 残局偶尔会选不出,
        # 能管就挑管得上的里面分最高的
        if not move and infoset.player_position == "landlord_up" and infoset.last_pid == "landlord" \
                and infoset.num_cards_left_dict["landlord"] == 1:
            beats = [i for i, a in enumerate(infoset.legal_actions) if a and ok(a)]
            if beats:
                move = infoset.legal_actions[max(beats, key=lambda i: float(values[i]))]
        # 桌上是队友的牌、队友只剩三张以内:不去压他(自己这手出完就赢的除外),让他接着出完。DouZero 残局偶尔会拿王压队友的 2,
        # 自己接过来再出单张,让只剩一张的地主走了
        mate = {"landlord_down": "landlord_up", "landlord_up": "landlord_down"}.get(infoset.player_position)
        if move and mate and infoset.last_pid == mate and infoset.num_cards_left_dict[mate] <= 3 \
                and len(move) < len(infoset.player_hand_cards) and [] in infoset.legal_actions and ok([]):
            move = []
        return self.swap_high_kickers(infoset, move, ok)

    def swap_high_kickers(self, infoset, move, ok):
        """三带一/飞机带单的带牌里有 A 或 2 时,换成手里最小的零散小单张:带牌只是凑数,留着 A/2 当控制牌。

        2026-11-16 10:04 实机:地主出 10 10 10 + 7,我 K K K + A;丢掉的 A 正是后来被 2 2 2 + J 压住的关键。
        没有能换的小牌、换完不合规、或者残局搜索认为原来那手更好时,保持 DouZero 的选择。
        """
        if not move or len(move) == len(infoset.player_hand_cards):
            return move
        high = [r for r in single_kickers(move) if r in (RANKS["A"], RANKS["2"])]
        if not high:
            return move
        hand = infoset.player_hand_cards
        rest = list(hand)
        for r in move:
            rest.remove(r)
        swapped = list(move)
        for rank in sorted(high):
            pick = next((c for c in sorted(set(rest)) if loose_single(c, hand, rest)), None)
            if pick is None:
                return move
            rest.remove(pick)
            swapped.remove(rank)
            swapped.append(pick)
        if sorted(swapped) not in [sorted(a) for a in infoset.legal_actions] or not ok(swapped):
            return move
        return swapped

    def keep_combos(self, infoset, values, move):
        """最高分那手是单张/对子/三条(含三带一/三带二)且拆牌时,在 KEEP_MARGIN 以内的不拆出牌里挑分数最高的。
        不出、顺子、连对、飞机、炸弹、王炸都不参与;都不拆或都拆就保持原选择。"""
        if not move or not is_simple_combo(move):
            return move
        top = float(values.max())
        hand = infoset.player_hand_cards
        best, best_score = None, None
        for i, action in enumerate(infoset.legal_actions):
            score = float(values[i])
            if not action or top - score > KEEP_MARGIN or breaks_combo(action, hand) \
                    or not is_simple_combo(action):
                continue
            if best_score is None or score > best_score:
                best, best_score = action, score
        return move if best is None else best

    def close_rollout(self, infoset, values, move, deadline=None, report=None):
        """顶两手的分数差不到 CLOSE_MARGIN 时,按看得见的牌随机发一批牌,每一手都用 DouZero 打分打到完,比赢面。

        后半局(三家手里合起来不超过 LATE_CARDS 张)不管分差多大都模拟,取 DouZero 分数最高的 LATE_TOP 手。
        同一批发牌拿来比所有候选(控制随机性),一手都打 ACT_ROLLOUT_GAMES 局或者到时限为止,轮着来。
        时限是 min(ACT_ROLLOUT_BUDGET_S, [deadline] 剩下的 − ACT_BUDGET_SAFETY_S − endgame.MIN_PLAYS_BUDGET_S):
        一手牌总共只有 ACT_TOTAL_BUDGET_S 秒,残局搜索花掉的要从这里扣,还要给比手数留出 0.2 秒。
        剩下的不到 ACT_ROLLOUT_MIN_S 就整个不模拟(回复里记 rollout_skipped: no_time),直接听 DouZero 的。
        每手不够 ACT_ROLLOUT_MIN_GAMES 局就当没模拟;开局(三家手里还够 OPENING_CARDS 张)只要求
        ACT_ROLLOUT_MIN_GAMES_OPENING 局,局数不到 60 时改用 OPENING_SWITCH_MARGIN 才换。
        差不到门槛时,赢面不低于 DouZero 那手的候选里出完剩下手数更少的胜出(见 fewer_plays);
        比手数最多 endgame.MIN_PLAYS_BUDGET_S 秒,且不超过总预算剩下的,没余量就不比。
        返回 (选定的一手, 写进回复的诊断);分数差得开、不用模拟时返回 None。
        """
        # 分数从高到低;同分时把 DouZero 挑的那手放最前,后面「仍听 DouZero」才有确定的参照
        own = tuple(sorted(move))
        order = sorted(range(len(infoset.legal_actions)),
                       key=lambda i: (-float(values[i]), tuple(sorted(infoset.legal_actions[i])) != own))
        top = float(values[order[0]])
        # 后半局不设分差门槛,直接取分数最高的 LATE_TOP 手;不出也在里面,只要它排进前 LATE_TOP
        late = sum(infoset.num_cards_left_dict.values()) <= LATE_CARDS
        picks = order[:LATE_TOP] if late \
            else [i for i in order if top - float(values[i]) < CLOSE_MARGIN][:CLOSE_TOP]
        if len(picks) < 2:
            return None
        # 总预算快用完了:模拟来不及打满下限,不如直接听 DouZero 的,别让手机等到超时
        if deadline is None:
            deadline = time.perf_counter() + ACT_TOTAL_BUDGET_S
        left = deadline - time.perf_counter()
        if left < ACT_ROLLOUT_MIN_S:
            if report is not None:
                report["rollout_skipped"] = "no_time"
            return None
        budget = min(ACT_ROLLOUT_BUDGET_S, left - ACT_BUDGET_SAFETY_S - endgame.MIN_PLAYS_BUDGET_S)
        candidates = [infoset.legal_actions[i] for i in picks]
        keys = [tuple(sorted(c)) for c in candidates]
        labels = {k: move_label(c) for k, c in zip(keys, candidates)}
        # 同一手牌、同一段历史每次拿到同一个种子:手机重发同一手,答案也不变
        rng = random.Random(hash((tuple(sorted(infoset.player_hand_cards)),
                                  tuple(tuple(m) for m in infoset.card_play_action_seq))))
        history = [list(m) for m in infoset.card_play_action_seq]
        wins = {k: 0 for k in keys}
        played = {k: 0 for k in keys}
        rollout_deadline = time.perf_counter() + budget
        while time.perf_counter() < rollout_deadline and any(played[k] < ACT_ROLLOUT_GAMES for k in keys):
            hands = sample_hands(infoset, rng)  # 这一批发牌所有候选都用
            for k, candidate in zip(keys, candidates):
                if played[k] >= ACT_ROLLOUT_GAMES:
                    continue
                if time.perf_counter() >= rollout_deadline:
                    break
                played[k] += 1
                if self.play_out(hands, infoset.three_landlord_cards, history, infoset.player_position, candidate):
                    wins[k] += 1
        games = min(played.values())
        if games == 0:
            return None
        rates = {k: wins[k] / played[k] for k in keys}
        chosen = move
        tie = None
        min_games = ACT_ROLLOUT_MIN_GAMES_OPENING if sum(infoset.num_cards_left_dict.values()) >= OPENING_CARDS \
            else ACT_ROLLOUT_MIN_GAMES
        if games >= min_games:
            best = keys[0]
            for k in keys[1:]:
                if rates[k] > rates[best]:
                    best = k
            margin = SWITCH_MARGIN if games >= ACT_ROLLOUT_MIN_GAMES else OPENING_SWITCH_MARGIN
            if rates[best] - rates[own] >= margin:
                chosen = next(c for k, c in zip(keys, candidates) if k == best)
            else:
                # 差不到门槛时挑出完剩下手数更少的:2026-11-16 11:58:20 土豪场,我是地主上家领出,
                # 手里 BJ 2 J 9 8 7 6 5 5 5 5 4。DouZero 出 5 6 7 8 9,模拟 152 局 5..9 赢 0.59、4..9 赢 0.64,
                # 差不到 0.1 的门槛仍出 5..9;可 4..9 出完剩 BJ 2 J 5 5 5 三手,5..9 剩 BJ 2 J 5 5 5 4 四手。
                # 赢面不低于 DouZero 那手的候选里手数更少就换;min_plays 算不完(超时)就照旧。
                # 手数比较也吃一手牌的总预算:截止时刻取 min(自己的 0.2 秒, 总预算剩下的 − 0.1),
                # 总预算没余量就不比了(模拟截止时间已经先给它留出 0.2 秒)。
                scores = {k: float(values[i]) for k, i in zip(keys, picks)}
                if deadline - time.perf_counter() > ACT_BUDGET_SAFETY_S:
                    tie = fewer_plays(infoset.player_hand_cards, keys, rates, scores, own,
                                      min(time.perf_counter() + endgame.MIN_PLAYS_BUDGET_S,
                                          deadline - ACT_BUDGET_SAFETY_S))
                if tie is not None:
                    chosen = next(c for k, c in zip(keys, candidates) if k == tie[0])
        info = {"games": games, "min_games": min_games, "budget_left": round(left, 2),
                "rates": {labels[k]: round(rates[k], 2) for k in keys},
                "chosen": move_label(chosen)}
        if late:
            info["late"] = True
        if tie is not None:
            info["tiebreak"] = "fewer_plays"
            info["plays"] = {labels[k]: n for k, n in tie[1].items()}
        return chosen, info

    def play_out(self, hands, bottom, history, me, move):
        """按 [hands] 开局把 [history] 重放到现在,我先出 [move],之后三家都按 DouZero 打分打到完。返回我这方赢没赢。"""
        replay = Replay(history)
        players = {s: _RolloutAgent(replay, self.plain[s], move if s == me else None) for s in SEATS}
        env = GameEnv(players)
        env.card_play_init({**{s: list(hands[s]) for s in SEATS}, "three_landlord_cards": list(bottom)})
        while not env.game_over:
            env.step()
        return bool(env.num_wins["landlord" if me == "landlord" else "farmer"])

    def values(self, infoset):
        obs = get_obs(infoset)
        with torch.no_grad():
            return self.model.forward(torch.from_numpy(obs["z_batch"]).float(),
                                      torch.from_numpy(obs["x_batch"]).float(), return_value=True)["values"].flatten()


class Plain:
    """只按 DouZero 打分出牌,不做残局搜索:叫分时自己跟自己打几十局,要快。"""

    def __init__(self, agent):
        self.agent = agent

    def act(self, infoset):
        if len(infoset.legal_actions) == 1:
            return infoset.legal_actions[0]
        return infoset.legal_actions[int(torch.argmax(self.agent.values(infoset)))]


class _RolloutAgent:
    """现场模拟里的玩家:重放历史时照抄历史;重放完我先出指定的那一手(只一次),之后都按 DouZero 打分。"""

    def __init__(self, replay, plain, forced=None):
        self.replay = replay
        self.plain = plain
        self.forced = forced

    def act(self, infoset):
        if self.replay.moves:
            return self.replay.act(infoset)
        if self.forced is not None:
            move, self.forced = self.forced, None
            return list(move)
        return self.plain.act(infoset)


class Advisor:
    def __init__(self, weights, rollout=True):
        self.agents = {s: Agent(s, os.path.join(weights, f"{s}.ckpt")) for s in SEATS}
        self.plain = {s: Plain(self.agents[s]) for s in SEATS}
        for agent in self.agents.values():
            agent.rollout = rollout
            agent.plain = self.plain

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

    def double(self, seat, hand, bottom, games=DOUBLE_GAMES, budget=ROLLOUT_BUDGET_S):
        """这手牌加倍值不值:地主按 20 张(含底牌)、农民按 17 张加三张明牌,看不见的牌随机分给另外两家,
        DouZero 自己跟自己打 [games] 局,返回 (自己这一方的赢面(地主或农民), 实际打了几局):超过 [budget] 秒就停。"""
        if seat not in SEATS:
            raise Mismatch("bad_seat")
        hand, bottom = cards(hand), cards(bottom)
        if len(bottom) != 3:
            raise Mismatch("bottom_count_mismatch")
        if seat == "landlord":
            if len(hand) != 20:
                raise Mismatch("hand_count_mismatch")
            fixed = {"landlord": list(hand)}
            unseen = minus(DECK, hand)
        else:
            if len(hand) != 17:
                raise Mismatch("hand_count_mismatch")
            # 底牌归地主;看不见的 34 张随机分成地主另外的 17 张和队友的 17 张
            fixed = {seat: list(hand), "landlord": list(bottom)}
            unseen = minus(minus(DECK, hand), bottom)
        # 同一副牌每次问都一样:底牌也参与种子,地主和农民看到的是同一副
        rng = random.Random(hash(tuple(sorted(hand + bottom))))
        wins = played = 0
        deadline = time.perf_counter() + budget
        for _ in range(games):
            if played >= ROLLOUT_MIN_GAMES and time.perf_counter() > deadline:
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
            wins += bool(env.num_wins["landlord" if seat == "landlord" else "farmer"])
        return wins / played, played

    def act(self, seat, hand, bottom, history, report=None):
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
        return self.agents[seat].act(env.game_infoset, report)


def handler(advisor):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
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
                    win, games = advisor.double(body["seat"], body["hand"], body["bottom"])
                    return self.reply(200, {"double": should_double(win), "win": round(win, 2), "games": games, "ms": ms()}, body)
                report = {}
                move = advisor.act(body["seat"], body["hand"], body.get("bottom", []), body.get("history", []), report)
                self.reply(200, {"play": [NAMES[c] for c in move], "ms": ms(), **report}, body)
            except Mismatch as e:
                self.reply(200, {"error": str(e)}, body)
            except (KeyError, ValueError, TypeError) as e:
                self.reply(400, {"error": f"bad_request:{e}"})

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
    ThreadingHTTPServer(("0.0.0.0", port), handler(Advisor(WEIGHTS))).serve_forever()
