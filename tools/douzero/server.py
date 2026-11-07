"""
斗地主出牌服务:给 DouZero 一局的已知信息,回下一手出什么。只在本机或局域网里给 Mika 用。

对手的手牌看不见,DouZero 也不需要逐张知道:它只看「其他两家合起来还剩哪些牌」和各家张数。
所以把看不见的牌随便分给两家凑够张数,再把识别到的出牌历史在 DouZero 的牌局里从头重放一遍:
重放时每一手都要合规,识别漏了一手、认错一张,这里就会对不上,直接报 history_mismatch,调用方退回「提示」。

POST /act  {"seat": "landlord" | "landlord_down" | "landlord_up",
            "hand": ["3", "3", ..., "BJ"],         # 自己此刻的手牌
            "bottom": ["9", "8", "7"],              # 三张底牌
            "history": [["3"], [], ["5"], ...]}     # 从地主第一手起每一手,[] 是不出
-> {"play": ["5", "5"]} 或 {"error": "..."}
"""
import json
import os
import sys
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import torch
from douzero.dmc.models import model_dict
from douzero.env.env import get_obs
from douzero.env.game import GameEnv

SEATS = ["landlord", "landlord_down", "landlord_up"]
RANKS = {"3": 3, "4": 4, "5": 5, "6": 6, "7": 7, "8": 8, "9": 9, "10": 10, "J": 11, "Q": 12, "K": 13,
         "A": 14, "2": 17, "SJ": 20, "BJ": 30}
NAMES = {v: k for k, v in RANKS.items()}
DECK = [r for r in RANKS.values() if r < 20 for _ in range(4)] + [20, 30]
WEIGHTS = os.environ.get("DOUZERO_WEIGHTS", os.path.join(os.path.dirname(__file__), "baselines", "douzero_ADP"))


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
        # 底牌是地主的,没打出去的那几张一定还在地主手里
        kept = list((Counter(bottom) - Counter(played["landlord"])).elements())
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
        obs = get_obs(infoset)
        with torch.no_grad():
            values = self.model.forward(torch.from_numpy(obs["z_batch"]).float(),
                                        torch.from_numpy(obs["x_batch"]).float(), return_value=True)["values"]
        return infoset.legal_actions[int(torch.argmax(values))]


class Advisor:
    def __init__(self, weights):
        self.agents = {s: Agent(s, os.path.join(weights, f"{s}.ckpt")) for s in SEATS}

    def act(self, seat, hand, bottom, history):
        hand, bottom = cards(hand), cards(bottom)
        history = [cards(m) for m in history]
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
            if self.path != "/act":
                return self.reply(404, {"error": "not_found"})
            started = time.perf_counter()
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                move = advisor.act(body["seat"], body["hand"], body.get("bottom", []), body.get("history", []))
                self.reply(200, {"play": [NAMES[c] for c in move], "ms": round((time.perf_counter() - started) * 1000)})
            except Mismatch as e:
                self.reply(200, {"error": str(e)})
            except (KeyError, ValueError, TypeError) as e:
                self.reply(400, {"error": f"bad_request:{e}"})

        def reply(self, code, data):
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
