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

POST /bid  {"hand": [17 张]}  -> {"level": 3 | 0, "win": 0.62}   # 当地主的赢面,够了就叫三分

POST /double {"seat": "landlord" | "landlord_down" | "landlord_up",
              "hand": [...],                     # 地主 20 张(含底牌),农民 17 张
              "bottom": [三张]}                  # 三张明牌,归地主
-> {"double": true | false, "win": 0.83}     # 自己这一方的赢面,够了才建议加倍
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
BID_GAMES = 60
BID_WIN = 0.7
# 加倍:同一手牌自己跟自己打这么多局看这一方的赢面。加倍只有约 5 秒,80 局在本机实测 0.75~1.35 秒
# (/bid 60 局约 0.74 秒);再多就赶不上手机的往返和超时重试了。
DOUBLE_GAMES = 80
# 赢面到了这个门槛才加倍。只点黄色「加倍」,绝不点绿色的「超级加倍」,金豆省着用。
DOUBLE_WIN = 0.8
# 几手分数几乎一样时 DouZero 基本是随手挑:差不到这个数就优先不拆三条/对子/炸弹。
# 实机那手拆 10 10 10 去顶队友的 6 只比单张 9 高 0.009;再大就会盖住真正该拆的一手,0.05 只收真正的平局。
KEEP_MARGIN = 0.05
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


class Agent:
    """同 DouZero 的 DeepAgent,只是权重按纯张量加载:checkpoint 是 pickle,不按张量读会执行文件里的代码。"""

    def __init__(self, seat, path):
        self.model = model_dict[seat]()
        self.model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True))
        self.model.eval()

    def act(self, infoset):
        if len(infoset.legal_actions) == 1:
            return infoset.legal_actions[0]
        values = self.values(infoset)
        move = infoset.legal_actions[int(torch.argmax(values))]
        # 剩的牌少时按明牌把各种分法算到底:有赢面更大的一手就换成它(赢面一样大的里挑 DouZero 打分最高的)。
        # 下面两条硬规则只在搜索也认为不吃亏时才换
        solved = endgame.best(infoset)
        allowed = None
        if solved is not None:
            wins, _ = solved
            score = {tuple(sorted(a)): float(values[i]) for i, a in enumerate(infoset.legal_actions)}
            top = max(wins.get(m, 0) for m in score)
            if wins.get(tuple(sorted(move)), 0) < top:
                return list(max((m for m in score if wins.get(m, 0) == top), key=score.get))
            allowed = {m for m in score if wins.get(m, 0) == top}
        if solved is None:
            # 剩的牌多、搜不动:自己能两手出完、第一手很难被压时,先出那手再出完(DouZero 偶尔先出一张会被王压的单张)
            others = [p for p in endgame.SEATS if p != infoset.player_position]
            left = infoset.num_cards_left_dict
            rival = tuple(sorted(infoset.last_move)) if [] in infoset.legal_actions else ()
            quick = endgame.finish_in_two(tuple(sorted(infoset.player_hand_cards)), infoset.legal_actions, rival,
                                          sorted(infoset.other_hand_cards), left[others[0]], left[others[1]])
            if quick is not None and tuple(sorted(move)) != quick[0]:
                return list(quick[0])
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
        return move

    def keep_combos(self, infoset, values, move):
        """最高分那手拆牌时,在 KEEP_MARGIN 以内的不拆候选里挑分数最高的;都不拆或都拆就保持原选择。"""
        top = float(values.max())
        hand = infoset.player_hand_cards
        best, best_score = None, None
        for i, action in enumerate(infoset.legal_actions):
            score = float(values[i])
            if top - score > KEEP_MARGIN or breaks_combo(action, hand):
                continue
            if best_score is None or score > best_score:
                best, best_score = action, score
        return move if best is None else best

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


class Advisor:
    def __init__(self, weights):
        self.agents = {s: Agent(s, os.path.join(weights, f"{s}.ckpt")) for s in SEATS}
        self.plain = {s: Plain(self.agents[s]) for s in SEATS}

    def bid(self, hand, games=BID_GAMES):
        """拿这 17 张当地主能赢几成:看不见的牌随机发成底牌和两家,DouZero 自己跟自己打 [games] 局。"""
        hand = cards(hand)
        if len(hand) != 17:
            raise Mismatch("hand_count_mismatch")
        rest = minus(DECK, hand)
        rng = random.Random(hash(tuple(hand)))
        wins = 0
        for _ in range(games):
            rng.shuffle(rest)
            bottom = rest[:3]
            env = GameEnv(self.plain)
            env.card_play_init({"landlord": sorted(hand + bottom), "landlord_down": sorted(rest[3:20]),
                                "landlord_up": sorted(rest[20:37]), "three_landlord_cards": sorted(bottom)})
            while not env.game_over:
                env.step()
            wins += bool(env.num_wins["landlord"])
        return wins / games

    def double(self, seat, hand, bottom, games=DOUBLE_GAMES):
        """这手牌加倍值不值:地主按 20 张(含底牌)、农民按 17 张加三张明牌,看不见的牌随机分给另外两家,
        DouZero 自己跟自己打 [games] 局,返回自己这一方的赢面(地主或农民)。"""
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
        wins = 0
        for _ in range(games):
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
        return wins / games

    def act(self, seat, hand, bottom, history):
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
        return self.agents[seat].act(env.game_infoset)


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
                    win = advisor.bid(body["hand"])
                    return self.reply(200, {"level": bid_level(win), "win": round(win, 2), "ms": ms()}, body)
                if self.path == "/double":
                    win = advisor.double(body["seat"], body["hand"], body["bottom"])
                    return self.reply(200, {"double": should_double(win), "win": round(win, 2), "ms": ms()}, body)
                move = advisor.act(body["seat"], body["hand"], body.get("bottom", []), body.get("history", []))
                self.reply(200, {"play": [NAMES[c] for c in move], "ms": ms()}, body)
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
