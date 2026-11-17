"""四川麻将血战到底出牌建议服务:给 Mika 的安卓端用。只在本机或局域网里跑,没有鉴权。

POST /discard
  {"hand": ["1m", "2m", ...],                 # 自己此刻的手牌,14 张(或还没摸牌时 13 张)
   "melds": [{"type": "pon", "tile": "5p"}],  # 可选,碰/杠;可省略或为空;没有吃
   "que": "s"}                                # 定缺,m/p/s 之一
# 碰算 3 张、杠算 4 张;手牌张数 + 3 × 副露组数 = 14

成功(14 张、合法):
  {"win": false, "discard": "9p", "shanten": 0, "ukeire": 4, "waits": ["5p"], "queLeft": 0,
   "options": [{"discard": "9p", "shanten": 0, "ukeire": 4, "waits": ["5p"]}, ...]}
  options 最多 3 个,第一个就是推荐;已胡时只返回 {"win": true}。
  waits/ukeire 是理论进张:只扣自己手牌和副露,不扣场上已经打出去的牌。
  queLeft 是切牌前手里还有几张定缺牌;有定缺牌时 shanten/ukeire 只按非定缺门的牌算。

校验错误(HTTP 200,安卓端把非 2xx 当成连不上):
  {"error": "bad_tile" | "bad_que" | "bad_meld" | "not_drawn" | "wrong_count" | "too_many",
   "detail": "人话"}
只有请求体不是 JSON、路径不对这类才用 4xx/404。
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mahjong.shanten import Shanten

TILE_RE = re.compile(r"^([1-9])([mps])$")
SUIT_OFFSET = {"m": 0, "p": 9, "s": 18}


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


def seven_pairs_shanten(tiles):
    # 4 张相同算 2 对;按 spec 不扣「种类不足」
    return 6 - sum(count // 2 for count in to_counts(tiles))


def tile_order(tile):
    return (SUIT_OFFSET[tile[1]] // 9, int(tile[0]))


def shanten_of(tiles, melds):
    regular = regular_shanten(tiles)
    if melds:
        return regular
    return min(regular, seven_pairs_shanten(tiles))


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


def filtered_shanten(useful, melds):
    regular = filtered_regular_shanten(useful, len(melds))
    if melds:
        return regular
    pairs = sum(count // 2 for count in to_counts(useful))
    return min(regular, 6 - pairs)


def analyze(hand13, melds, que, shanten):
    base = shanten(hand13, melds)
    counts = {}
    for tile in hand13:
        counts[tile] = counts.get(tile, 0) + 1
    meld_counts = {}
    for meld in melds:
        meld_counts[meld["tile"]] = meld_counts.get(meld["tile"], 0) + (3 if meld["type"] == "pon" else 4)
    waits = []
    ukeire = 0
    for suit in ("m", "p", "s"):
        if suit == que:
            continue
        for number in range(1, 10):
            tile = f"{number}{suit}"
            left = 4 - counts.get(tile, 0) - meld_counts.get(tile, 0)
            if left <= 0:
                continue
            if shanten(hand13 + [tile], melds) < base:
                waits.append(tile)
                ukeire += left
    return base, ukeire, waits


def is_win(hand, melds, que):
    if any(tile[1] == que for tile in hand):
        return False
    if regular_shanten(hand) == -1:
        return True
    return not melds and len(hand) == 14 and all(count % 2 == 0 for count in to_counts(hand))


def advise(hand, melds, que):
    if not isinstance(hand, list):
        raise TypeError("hand 必须是数组")
    for tile in hand:
        parse_tile(tile)
    if que not in ("m", "p", "s"):
        raise RequestError("bad_que", f"定缺只能是 m、p、s，收到 {que!r}")
    if not isinstance(melds, list):
        raise TypeError("melds 必须是数组")
    parsed_melds = []
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
        parsed_melds.append({"type": kind, "tile": tile})
    total = len(hand) + 3 * len(melds)
    if total == 13:
        raise RequestError("not_drawn", f"手牌 {len(hand)} 张 + 副露 {len(melds)} 组 = 13 张，还没摸牌")
    if total != 14:
        raise RequestError("wrong_count", f"手牌 {len(hand)} 张 + 副露 {len(melds)} 组，不是 14 张")
    used = {}
    for tile in hand:
        used[tile] = used.get(tile, 0) + 1
    for meld in parsed_melds:
        used[meld["tile"]] = used.get(meld["tile"], 0) + (3 if meld["type"] == "pon" else 4)
    for tile, count in used.items():
        if count > 4:
            raise RequestError("too_many", f"{tile} 一共 {count} 张，超过 4 张")
    if is_win(hand, parsed_melds, que):
        return {"win": True}
    que_tiles = [tile for tile in hand if tile[1] == que]
    if que_tiles:
        que_counts = {}
        for tile in que_tiles:
            que_counts[tile] = que_counts.get(tile, 0) + 1
        candidates = sorted(que_counts, key=lambda tile: (que_counts[tile], tile_order(tile)))
        options = []
        for tile in candidates[:3]:
            remaining = list(hand)
            remaining.remove(tile)
            useful = [item for item in remaining if item[1] != que]
            base, ukeire, waits = analyze(useful, parsed_melds, que, filtered_shanten)
            options.append({"discard": tile, "shanten": base, "ukeire": ukeire, "waits": waits})
        return {"win": False, **options[0], "queLeft": len(que_tiles), "options": options}
    options = []
    for tile in sorted(set(hand), key=tile_order):
        remaining = list(hand)
        remaining.remove(tile)
        base, ukeire, waits = analyze(remaining, parsed_melds, que, shanten_of)
        options.append({"discard": tile, "shanten": base, "ukeire": ukeire, "waits": waits})
    options.sort(key=lambda option: (option["shanten"], -option["ukeire"], tile_order(option["discard"])))
    options = options[:3]
    return {"win": False, **options[0], "queLeft": 0, "options": options}


def handle(body):
    if not isinstance(body, dict):
        return 400, {"error": "bad_request", "detail": "请求体必须是 JSON 对象"}
    try:
        return 200, advise(body["hand"], body.get("melds") or [], body["que"])
    except RequestError as e:
        return 200, {"error": e.code, "detail": e.detail}
    except (KeyError, TypeError, ValueError) as e:
        return 400, {"error": f"bad_request:{e}"}


def handler():
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/discard":
                return self.reply(404, {"error": "not_found"})
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            except ValueError as e:
                return self.reply(400, {"error": f"bad_request:{e}"})
            code, data = handle(body)
            self.reply(code, data, body)

        def reply(self, code, data, request=None):
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
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 3427
    ThreadingHTTPServer(("0.0.0.0", port), handler()).serve_forever()
