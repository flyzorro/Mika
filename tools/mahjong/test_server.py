"""按公开入口测:handle(body) 返回 (HTTP 状态码, JSON);另有一条走真实 HTTP 锁状态码。"""
import base64
import glob
import io
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest import mock

import server

from PIL import Image, ImageDraw

from server import (_best, _feature, HAND_MATCH, HAND_TEMPLATES,
                    handle, handler, win_fan, win_multiplier, win_probability)


def post(hand, melds=None, que="s", draws=None):
    body = {"hand": hand, "que": que}
    if melds is not None:
        body["melds"] = melds
    if draws is not None:
        body["draws"] = draws
    return handle(body)


class ValidationTest(unittest.TestCase):
    def test_bad_tile_returns_bad_tile_error(self):
        for bad in ("0m", "1z", "east"):
            with self.subTest(bad=bad):
                hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p", "4p", "6p", bad]
                status, body = post(hand)
                self.assertEqual(status, 200)
                self.assertEqual(body["error"], "bad_tile")

    def test_bad_que_returns_bad_que_error(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p", "4p", "6p", "9p"]
        status, body = post(hand, que="z")
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "bad_que")

    def test_bad_meld_type_returns_bad_meld_error(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p"]
        status, body = post(hand, melds=[{"type": "chi", "tile": "5p"}])
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "bad_meld")

    def test_thirteen_tiles_is_not_drawn(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p", "4p", "6p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "not_drawn")

    def test_fifteen_tiles_is_wrong_count(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p", "1m"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "wrong_count")

    def test_five_copies_is_too_many(self):
        hand = ["1m", "1m", "1m", "1m", "1m",
                "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "too_many")

    def test_meld_plus_hand_over_four_is_too_many(self):
        hand = ["5p", "5p", "1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m"]
        status, body = post(hand, melds=[{"type": "pon", "tile": "5p"}])
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "too_many")

    def test_kan_counts_as_four_copies(self):
        hand = ["5p", "1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p"]
        status, body = post(hand, melds=[{"type": "kan", "tile": "5p"}])
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "too_many")

    def test_thirteen_tiles_with_a_meld_is_not_drawn(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p"]
        status, body = post(hand, melds=[{"type": "pon", "tile": "5p"}])
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "not_drawn")


class WinTest(unittest.TestCase):
    def test_regular_hand_is_a_win(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "2p", "3p", "1p", "1p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertEqual(body, {"win": True})

    def test_dragon_seven_pairs_is_a_win(self):
        hand = ["1m", "1m", "1m", "1m", "3m", "3m", "5m", "5m",
                "2p", "2p", "5p", "5p", "9p", "9p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertEqual(body, {"win": True})


class DiscardTest(unittest.TestCase):
    def test_que_tile_is_discarded_first(self):
        hand = ["1m", "1m", "1m", "1m", "3m", "3m", "5m", "5m",
                "2p", "2p", "5p", "5p", "9p", "9s"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertEqual(body["discard"], "9s")
        self.assertEqual(body["queLeft"], 1)

    def test_main_hand_discards_9p(self):
        # 这手牌的蒙特卡洛:9p 单钓 5p(4 张)约 0.30,6p 的一向听 32 进张约 0.19;
        # 所以第一版的 9p 断言在「对这手牌本身做模拟」下仍然成立。
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertEqual(body["discard"], "9p")
        self.assertEqual(body["target"], "fast")
        self.assertEqual(body["fan"], 0)
        self.assertEqual(body["shanten"], 0)
        self.assertEqual(body["ukeire"], 4)
        self.assertEqual(body["waits"], ["5p"])
        self.assertEqual(body["queLeft"], 0)
        first = body["options"][0]
        self.assertEqual({key: first[key] for key in ("discard", "shanten", "ukeire", "waits")},
                         {"discard": "9p", "shanten": 0, "ukeire": 4, "waits": ["5p"]})
        self.assertLessEqual(len(body["options"]), 3)

    def test_meld_disables_seven_pairs(self):
        hand = ["1m", "1m", "4m", "4m", "7m", "7m", "1p", "1p", "4p", "4p", "7p"]
        melds = [{"type": "pon", "tile": "5p"}]
        status, body = post(hand, melds=melds)
        self.assertEqual(status, 200)
        self.assertEqual(body["shanten"], 2)

    def test_empty_melds_is_accepted(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p"]
        status, body = post(hand, melds=[])
        self.assertEqual(status, 200)
        self.assertEqual(body["discard"], "9p")

    def test_ukeire_excludes_que_and_full_tiles(self):
        hand = ["1m", "1m", "1m", "1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "9s", "9s"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertEqual(body["discard"], "9s")
        self.assertEqual(body["queLeft"], 2)
        self.assertNotIn("9s", body["waits"])
        self.assertNotIn("1m", body["waits"])
        self.assertTrue(all(tile[1] != "s" for tile in body["waits"]))
        self.assertGreater(body["ukeire"], 0)


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler())
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_validation_error_is_http_200(self):
        hand = ["0m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p"]
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/discard",
            data=json.dumps({"hand": hand, "que": "s"}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.loads(response.read())["error"], "bad_tile")

    def test_wrong_path_is_404(self):
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/nope",
            data=b"{}",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request)
        self.assertEqual(caught.exception.code, 404)
        caught.exception.close()

    def test_invalid_json_is_400(self):
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/discard",
            data=b"not json",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request)
        self.assertEqual(caught.exception.code, 400)
        caught.exception.close()


class ScoringTest(unittest.TestCase):
    """番数 → 倍数按规则页:0→1、1→2、2→4、3→8,之后每番 +4。"""

    def test_fan_of_each_pattern_and_multiplier(self):
        cases = [
            ("平胡", ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                     "1p", "2p", "3p", "5p", "5p"], 0),
            ("断幺九平胡", ["2m", "3m", "4m", "4m", "5m", "6m", "6m", "7m", "8m",
                        "2p", "3p", "4p", "5p", "5p"], 1),
            ("碰碰胡", ["1m", "1m", "1m", "3m", "3m", "3m", "5m", "5m", "5m",
                     "7m", "7m", "7m", "9p", "9p"], 1),
            ("清一色", ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                     "2m", "3m", "4m", "5m", "5m"], 2),
            ("七对", ["1m", "1m", "3m", "3m", "5m", "5m", "7m", "7m",
                    "2p", "2p", "4p", "4p", "6p", "6p"], 2),
            ("清碰", ["1m", "1m", "1m", "3m", "3m", "3m", "5m", "5m", "5m",
                    "7m", "7m", "7m", "9m", "9m"], 3),
            ("清七对", ["1m", "1m", "3m", "3m", "5m", "5m", "7m", "7m",
                     "2m", "2m", "4m", "4m", "6m", "6m"], 4),
            ("龙七对", ["1m", "1m", "1m", "1m", "3m", "3m", "5m", "5m", "7m", "7m",
                     "2p", "2p", "4p", "4p"], 3),
            ("清龙七对", ["1m", "1m", "1m", "1m", "3m", "3m", "5m", "5m", "7m", "7m",
                      "2m", "2m", "4m", "4m"], 5),
        ]
        for name, hand, fan in cases:
            with self.subTest(name=name):
                self.assertEqual(win_fan(hand), fan)
                self.assertEqual(win_multiplier(fan), {0: 1, 1: 2, 2: 4, 3: 8, 4: 12, 5: 16}[fan])


class ProbabilityTest(unittest.TestCase):
    """概率只由(向听、进张、还能摸几张)决定;三项都单调,且同一输入每次相同。"""

    def test_monotonic_in_shanten_ukeire_and_draws(self):
        for shanten in range(0, 10):
            for ukeire in (0, 1, 4, 9, 20, 40, 72):
                for draws in (1, 4, 8, 16, 30):
                    p = win_probability(shanten, ukeire, draws)
                    self.assertGreaterEqual(p, win_probability(shanten + 1, ukeire, draws))
                    self.assertGreaterEqual(win_probability(shanten, ukeire + 1, draws), p)
                    self.assertGreaterEqual(win_probability(shanten, ukeire, draws + 1), p)
                    self.assertEqual(p, win_probability(shanten, ukeire, draws))

    def test_bounds(self):
        self.assertEqual(win_probability(-1, 0, 8), 1.0)
        self.assertEqual(win_probability(3, 10, 0), 0.0)
        self.assertGreater(win_probability(0, 8, 8), 0.0)
        self.assertLess(win_probability(8, 0, 8), win_probability(0, 0, 8) + 0.001)


class ExpectedValueTest(unittest.TestCase):
    """同一手牌:剩得多该冲清一色,剩得少该快胡;value 来自这手牌的蒙特卡洛。"""

    HAND = ["1m", "2m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "9m",
            "5s", "6s", "7s"]

    def test_chases_qingyise_when_draws_are_plentiful(self):
        status, body = post(self.HAND, que="p", draws=14)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertIn(body["discard"], ("5s", "6s", "7s"))
        self.assertEqual(body["target"], "qingyise")
        self.assertEqual(body["fan"], 2)
        # 参考模拟:切 5s 冲清一色万 draws=14 约 0.25,4 倍后约 1.0
        self.assertGreaterEqual(body["value"], 0.8)
        self.assertLessEqual(body["value"], 1.3)
        for option in body["options"]:
            self.assertIn(option["target"], ("fast", "qingyise", "pengpeng", "qidui"))
            self.assertIsInstance(option["fan"], int)
            self.assertIsInstance(option["value"], float)
            self.assertIn("shanten", option)
            self.assertIn("ukeire", option)
            self.assertIn("waits", option)

    def test_prefers_fast_win_when_draws_are_short(self):
        status, body = post(self.HAND, que="p", draws=6)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertEqual(body["discard"], "2m")
        self.assertEqual(body["target"], "fast")
        self.assertEqual(body["fan"], 0)
        # 参考模拟:切 2m 快胡 draws=6 约 0.36;冲清一色约 0.15(4 倍后)
        self.assertGreaterEqual(body["value"], 0.25)
        self.assertLessEqual(body["value"], 0.40)


class AcceptanceTest(unittest.TestCase):
    def test_does_not_chase_qingyise_when_it_does_not_pay(self):
        hand = ["2m", "2m", "2m", "3m", "4m", "5m", "6m", "7m",
                "3p", "4p", "5p", "6p", "7p", "9p"]
        status, body = post(hand, que="s")
        self.assertEqual(status, 200)
        self.assertEqual(body["discard"], "9p")
        self.assertEqual(body["target"], "fast")
        self.assertEqual(body["fan"], 1)  # 断幺九
        self.assertEqual(body["shanten"], 0)
        self.assertGreater(body["value"], 0.5)

    def test_chases_seven_pairs(self):
        hand = ["1m", "1m", "4m", "4m", "7m", "7m", "2p", "2p",
                "5p", "5p", "8p", "8p", "3m", "6p"]
        status, body = post(hand, que="s")
        self.assertEqual(status, 200)
        self.assertIn(body["discard"], ("3m", "6p"))
        self.assertEqual(body["target"], "qidui")
        self.assertEqual(body["fan"], 2)
        self.assertEqual(body["shanten"], 0)


class ShantenRegressionTest(unittest.TestCase):
    """拆对子退向听换来的进张,不能盖过少一向听。

    概率表把「高向听 × 大进张」这一格估高了,而 200 局蒙卡的标准误差(约 0.026)
    比这些打法之间的真实差距还大;排序不能把噪声当成结论。
    """

    CASES = [
        # 打 5p 拆对子退成 3 向听(59 进张),打孤张 9p 留 2 向听(20 进张)
        (["1p", "1p", "1p", "2p", "5p", "5p", "9p", "2s", "4s", "6s", "8s", "8s", "9s", "4p"], "9p"),
        # 打 1s 拆对子退成 2 向听(43 进张),打孤张 8s 留 1 向听(10 进张)
        (["1p", "2p", "2p", "4p", "5p", "6p", "7p", "8p", "9p", "1s", "1s", "5s", "5s", "8s"], "8s"),
        # 打 8s 拆对子退成 2 向听(59 进张),打孤张 7p 留 1 向听(13 进张)
        (["1p", "1p", "2p", "2p", "3p", "4p", "5p", "7p", "1s", "5s", "6s", "7s", "8s", "8s"], "7p"),
        # 打 4s 拆 444s 刻子退成 3 向听(59 进张),打 6p 留 2 向听(16 进张)
        (["2p", "3p", "5p", "6p", "8p", "9p", "2s", "3s", "4s", "4s", "4s", "5s", "8s", "9s"], "6p"),
    ]

    def test_recommendation_keeps_the_least_shanten_for_its_target(self):
        for hand, expected in self.CASES:
            with self.subTest(hand=" ".join(hand)):
                status, body = handle({"hand": hand, "melds": [], "que": "m", "draws": 14}, "/discard")
                self.assertEqual(status, 200)
                self.assertFalse(body["win"])
                first = body["options"][0]
                same_target = [option for option in body["options"] if option["target"] == first["target"]]
                self.assertEqual(first["shanten"], min(option["shanten"] for option in same_target))
                self.assertEqual(body["discard"], expected)


class QueTest(unittest.TestCase):
    HAND = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "2p", "3p", "4p", "1s", "5s", "9s"]

    def test_prefers_the_scattered_suit(self):
        status, body = handle({"hand": self.HAND}, "/que")
        self.assertEqual(status, 200)
        self.assertEqual(body["que"], "s")
        self.assertEqual([option["que"] for option in body["options"]], ["s", "p", "m"])
        for option in body["options"]:
            self.assertIn(option["target"], ("fast", "qingyise", "pengpeng", "qidui"))
            self.assertIsInstance(option["value"], float)

    def test_dealer_14_tiles_is_accepted(self):
        status, body = handle({"hand": self.HAND + ["5m"]}, "/que")
        self.assertEqual(status, 200)
        self.assertIn(body["que"], "mps")
        self.assertEqual(len(body["options"]), 3)

    def test_wrong_count(self):
        for hand in (self.HAND[:12], self.HAND + ["5m", "5p"]):
            status, body = handle({"hand": hand}, "/que")
            self.assertEqual(status, 200)
            self.assertEqual(body["error"], "wrong_count")

    def test_bad_tile(self):
        status, body = handle({"hand": self.HAND[:-1] + ["0s"]}, "/que")
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "bad_tile")

    def test_http_que_path(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler())
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_address[1]}/que",
                data=json.dumps({"hand": self.HAND}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(request) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(json.loads(response.read())["que"], "s")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


class DrawsTest(unittest.TestCase):
    HAND = ["2m", "2m", "2m", "3m", "4m", "5m", "6m", "7m",
            "3p", "4p", "5p", "6p", "7p", "9p"]

    def test_default_is_eight(self):
        _, default = post(self.HAND)
        _, explicit = handle({"hand": self.HAND, "que": "s", "draws": 8})
        self.assertEqual(default, explicit)

    def test_more_draws_never_lower_the_value(self):
        values = [post(self.HAND, draws=draws)[1]["value"] for draws in (1, 2, 4, 8, 16, 30)]
        for lower, higher in zip(values, values[1:]):
            self.assertLessEqual(lower, higher)

    def test_bad_draws(self):
        for bad in (0, 31, -1, 1.5, "8", True):
            with self.subTest(bad=bad):
                status, body = handle({"hand": self.HAND, "que": "s", "draws": bad})
                self.assertEqual(status, 200)
                self.assertEqual(body["error"], "bad_draws")
                status, body = handle({"hand": QueTest.HAND, "draws": bad}, "/que")
                self.assertEqual(status, 200)
                self.assertEqual(body["error"], "bad_draws")


class StabilityTest(unittest.TestCase):
    def test_same_request_twice_is_identical(self):
        hand = ["1m", "2m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "9m",
                "5s", "6s", "7s"]
        self.assertEqual(post(hand, que="p"), post(hand, que="p"))

    def test_discard_returns_within_one_second(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p"]
        started = time.perf_counter()
        for _ in range(5):
            post(hand)
        self.assertLess((time.perf_counter() - started) / 5, 1.0)


# ---- /hint:一张主屏截图 → 局面 + 建议 ----
FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
FRAME_DIR = os.path.join(FIXTURES, "frames")


def setUpModule():
    # 测试里的 /hint 不往仓库目录的 captures/ 写图
    global _CAPTURE_TMP, _CAPTURE_PATCH
    _CAPTURE_TMP = tempfile.TemporaryDirectory()
    _CAPTURE_PATCH = mock.patch.object(server, "CAPTURE_DIR", _CAPTURE_TMP.name, create=True)
    _CAPTURE_PATCH.start()


def tearDownModule():
    _CAPTURE_PATCH.stop()
    _CAPTURE_TMP.cleanup()


def png_base64(path):
    with open(path, "rb") as file:
        return base64.b64encode(file.read()).decode()


def hint_frame(name):
    return handle({"image": png_base64(os.path.join(FRAME_DIR, name + ".png"))}, "/hint")


def hint_bytes(raw):
    return handle({"image": base64.b64encode(raw).decode()}, "/hint")


class HintImageTest(unittest.TestCase):
    def test_not_a_png_is_bad_image(self):
        for raw in (b"", b"not a png", b"\x89PNG\r\n\x1a\n-but-truncated"):
            with self.subTest(raw=raw[:12]):
                status, body = hint_bytes(raw)
                self.assertEqual(status, 200)
                self.assertEqual(body["error"], "bad_image")

    def test_wrong_size_is_unsupported_screen(self):
        buffer = io.BytesIO()
        Image.new("RGB", (800, 600), (0, 0, 0)).save(buffer, format="PNG")
        status, body = hint_bytes(buffer.getvalue())
        self.assertEqual(status, 200)
        self.assertEqual(body["error"], "unsupported_screen")

    def test_other_formats_are_bad_image(self):
        for fmt in ("GIF", "BMP"):
            with self.subTest(fmt=fmt):
                buffer = io.BytesIO()
                Image.new("RGB", (2412, 1080), (0, 0, 0)).save(buffer, format=fmt)
                status, body = hint_bytes(buffer.getvalue())
                self.assertEqual(status, 200)
                self.assertEqual(body["error"], "bad_image")

    def test_portrait_and_split_screens_are_unsupported(self):
        for size in ((1080, 2412), (2412, 1200)):
            for fmt in ("PNG", "JPEG"):
                with self.subTest(size=size, fmt=fmt):
                    buffer = io.BytesIO()
                    Image.new("RGB", size, (0, 0, 0)).save(buffer, format=fmt)
                    status, body = hint_bytes(buffer.getvalue())
                    self.assertEqual(status, 200)
                    self.assertEqual(body["error"], "unsupported_screen")

    def test_lobby_and_settlement_are_wait(self):
        for name in ("shots_204245", "shots_205135"):
            with self.subTest(name=name):
                status, body = hint_frame(name)
                self.assertEqual(status, 200)
                self.assertEqual(body, {"state": "wait"})


def load_frame(name):
    return Image.open(os.path.join(FRAME_DIR, name + ".png")).convert("RGB")


class HintTurnTest(unittest.TestCase):
    def test_turn_frame_recognizes_the_whole_table(self):
        status, body = hint_frame("shots_204809")
        self.assertEqual(status, 200)
        self.assertEqual(body["state"], "turn")
        self.assertEqual(body["hand"], ["1m", "2m", "4m", "7m", "7m", "8m", "8m",
                                        "2s", "3s", "5s", "6s", "7s", "8s", "8m"])
        self.assertEqual(body["melds"], [])
        self.assertEqual(body["que"], "p")
        self.assertEqual(body["left"], 39)
        self.assertEqual(body["draws"], 10)  # ceil(39 / 4)
        self.assertIsNone(body["jian"])
        self.assertIn("discard", body["advice"])
        self.assertFalse(body["advice"]["win"])

    def test_que_badge_reads_all_three_suits(self):
        for name, que in (("shots_204809", "p"), ("shots2_210336", "m"), ("shots3_211829", "s")):
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["que"], que)

    def test_left_digits_are_read(self):
        cases = {
            "shots_204809": 39,
            "shots_204850": 20,
            "shots2_210336": 24,
            "shots3_211829": 28,
            "shots4_212932_0057": 27,
        }
        for name, left in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["left"], left)

    def test_a_tile_without_a_template_is_unsure(self):
        # 用测试图模拟:把 2m 的牌面水平翻转,模板库里没有这种花纹,不能被认成别的牌。
        unsure_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "unsure")
        before = set(os.listdir(unsure_dir)) if os.path.isdir(unsure_dir) else set()
        image = load_frame("shots_204809")
        x0, x1, y0, y1 = 522, 646, 885, 1057
        image.paste(image.crop((x0 + 4, y0, x1 - 4, y1)).transpose(Image.FLIP_LEFT_RIGHT), (x0 + 4, y0))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        status, body = hint_bytes(buffer.getvalue())
        self.assertEqual(status, 200)
        self.assertEqual(body, {"state": "unsure", "reason": "tile"})
        after = set(os.listdir(unsure_dir))
        self.assertEqual(len(after - before), 1)  # 认不出的牌面存下来,方便以后补模板
        for name in after - before:
            os.unlink(os.path.join(unsure_dir, name))


class HintMeldTest(unittest.TestCase):
    def test_pon_groups(self):
        cases = {
            "shots_204850": [{"type": "pon", "tile": "7m"}],
            "shots2_210336": [{"type": "pon", "tile": "1p"}],
            "shots3_211829": [{"type": "pon", "tile": "6m"}],
        }
        for name, melds in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], "turn")
                self.assertEqual(body["melds"], melds)

    def test_exposed_and_concealed_kans(self):
        cases = {
            "shots4_212841_0033": [{"type": "kan", "tile": "7m"}],
            "shots4_212820_0023": [{"type": "kan", "tile": "7m"}],
            "shots_205128": [{"type": "kan", "tile": "1m"}, {"type": "kan", "tile": "4m"}],
            # 500 场紫灰桌布:两个暗杠(七萬、五筒)都要切出和绿桌布一样的牌面。
            "shots5_145239_0051": [{"type": "kan", "tile": "7m"}, {"type": "kan", "tile": "5p"}],
        }
        for name, melds in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["melds"], melds)

    def test_wait_frames_still_recognize_the_table(self):
        cases = {
            "shots_204857": {
                "hand": ["8m", "8m", "8m", "6s", "6s", "7s", "7s", "8s", "8s", "9s"],
                "melds": [{"type": "pon", "tile": "7m"}], "que": "p", "left": 20,
            },
            "shots_205128": {
                "hand": ["5m", "7m", "7m", "8m", "9m", "2s", "2s"],
                "melds": [{"type": "kan", "tile": "1m"}, {"type": "kan", "tile": "4m"}],
                "que": "p", "left": 21,
            },
            "shots4_212820_0023": {
                "hand": ["1m", "2m", "3m", "4m", "5m", "6m", "1s", "3s", "5s", "1p"],
                "melds": [{"type": "kan", "tile": "7m"}], "que": "p", "left": 47,
            },
            "shots_204945": {
                "hand": ["6s", "6s", "7s", "7s"],
                "melds": [{"type": "pon", "tile": "7m"}, {"type": "kan", "tile": "8m"},
                          {"type": "pon", "tile": "8s"}],
                "que": "p", "left": 0,
            },
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], "wait")
                for key, value in expected.items():
                    self.assertEqual(body[key], value)
                self.assertNotIn("advice", body)


class HintMeldHandTemplateTest(unittest.TestCase):
    """碰/明杠的牌面不靠 templates/meld/,要能由 templates/hand/ 的 27 张生成。"""

    def test_melds_are_recognized_without_meld_templates(self):
        # 每组都有 27 种牌面,全部从 templates/hand/ 生成,仓库里没有 templates/meld/。
        self.assertEqual(sorted(server.MELD_FLAT_TEMPLATES[0]), sorted(HAND_TEMPLATES))
        self.assertEqual(glob.glob(os.path.join(server.TEMPLATE_DIR, "meld", "*")), [])
        cases = {
            "shots8_172536": [{"type": "pon", "tile": "8s"}],
            "shots_204945": [{"type": "pon", "tile": "7m"}, {"type": "kan", "tile": "8m"},
                             {"type": "pon", "tile": "8s"}],
            "shots7_165550": [{"type": "pon", "tile": "2s"}],
            "shots7_170205": [{"type": "pon", "tile": "3m"}],
        }
        for name, melds in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["melds"], melds)


class HintAnkongTest(unittest.TestCase):
    """暗杠抬起的那张牌,27 种牌都要认得出,模板由 templates/hand/ 生成。"""

    def test_concealed_kan_of_every_tile_is_recognized(self):
        # 2026-11-18 23:0x 真机帧,同一条命、定缺 m:第 1 组暗杠 2p,
        # 手牌 10 张 + 暗杠 = 13 张,所以是 wait;之前这组认不出,整局后半段没有建议。
        cases = {
            "shots9_230144": ["3p", "4p", "8p", "8p", "2s", "3s", "4s", "4s", "7s", "8s"],
            "shots9_230348": ["4p", "4p", "8p", "8p", "8p", "2s", "3s", "4s", "7s", "8s"],
        }
        for name, hand in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], "wait")
                self.assertEqual(body["hand"], hand)
                self.assertEqual(body["melds"], [{"type": "kan", "tile": "2p"}])
                self.assertEqual(body["que"], "m")

    def test_dark_kans_are_recognized_without_ankong_templates(self):
        # templates/meld/ 里不再有逐张截的 ankong_*.png;1m、5p、7m 的暗杠由手牌模板生成。
        self.assertEqual(glob.glob(os.path.join(server.TEMPLATE_DIR, "meld", "ankong_*.png")), [])
        cases = {
            "shots_205128": [{"type": "kan", "tile": "1m"}, {"type": "kan", "tile": "4m"}],
            "shots5_145239_0051": [{"type": "kan", "tile": "7m"}, {"type": "kan", "tile": "5p"}],
        }
        for name, melds in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["melds"], melds)


class HintAnkongMarginTest(unittest.TestCase):
    def test_every_concealed_kan_has_a_clear_lead(self):
        # 暗杠认出的那张和「第二名」至少差 0.15:换一张牌不会被当成这一张。
        with open(os.path.join(FIXTURES, "labels.json"), encoding="utf-8") as file:
            labels = json.load(file)
        for name, label in labels.items():
            if name.startswith("_") or not label.get("melds"):
                continue
            image = load_frame(name)
            for group, meld in enumerate(label["melds"]):
                scores = server.ankong_scores(image, group)
                if scores is None:  # 碰/明杠,走 MELD_FLAT_TEMPLATES
                    continue
                with self.subTest(name=name, group=group):
                    self.assertEqual(scores[0][0], meld["tile"])
                    self.assertGreater(scores[0][1] - scores[1][1], 0.15)


class HintMeldMarginTest(unittest.TestCase):
    def test_every_pon_or_exposed_kan_has_a_clear_lead(self):
        # 认出的那张和「第二名」至少差 0.15:换一张牌不会被当成这一张。
        with open(os.path.join(FIXTURES, "labels.json"), encoding="utf-8") as file:
            labels = json.load(file)
        for name, label in labels.items():
            if name.startswith("_") or not label.get("melds"):
                continue
            image = load_frame(name)
            for group, meld in enumerate(label["melds"]):
                scores = server.meld_scores(image, group)
                if scores is None:  # 暗杠走 templates/meld/ankong_*.png,不在这次比较里
                    continue
                with self.subTest(name=name, group=group):
                    self.assertEqual(scores[0][0], meld["tile"])
                    self.assertGreater(scores[0][1] - scores[1][1], 0.15)


class HintUnsureMeldTest(unittest.TestCase):
    def test_unreadable_meld_saves_face_in_unsure_and_frame_in_captures(self):
        # 拿掉第 1 组的 7m 碰牌模板:这一组认不出(第二名 8m 只有 0.58,低于 0.65)。
        templates = [dict(table) for table in server.MELD_FLAT_TEMPLATES]
        del templates[0]["7m"]
        with tempfile.TemporaryDirectory() as unsure_dir, tempfile.TemporaryDirectory() as capture_dir, \
                mock.patch.object(server, "MELD_FLAT_TEMPLATES", templates), \
                mock.patch.object(server, "UNSURE_DIR", unsure_dir), \
                mock.patch.object(server, "CAPTURE_DIR", capture_dir), \
                mock.patch.object(server, "_last_capture", None, create=True):
            status, body = hint_frame("shots_204850")
            self.assertEqual(status, 200)
            self.assertEqual(body, {"state": "unsure", "reason": "meld"})
            names = os.listdir(unsure_dir)
            faces = [name for name in names if name.endswith("_meld.png")]
            self.assertEqual(names, faces)  # unsure/ 只放牌面
            self.assertEqual(len(faces), 1)  # 每组认不出的碰杠各存一张牌面
            with Image.open(os.path.join(unsure_dir, faces[0])) as saved:
                self.assertEqual(saved.size, server.MELD_FACE_SIZE)
            frames = [name for name in os.listdir(capture_dir) if name.endswith(".png")]
            self.assertEqual(len(frames), 1)  # 整屏在 captures/,方便查为什么没读出来


class HintQueSelectTest(unittest.TestCase):
    def test_que_select_returns_hand_and_que_advice(self):
        cases = {
            "shots_204728": ["1m", "2m", "4m", "7m", "7m", "8m", "2s", "3s", "5s", "6s", "7s", "8s", "3p"],
            "shots2_210150": ["1p", "1p", "2p", "4p", "5p", "5p", "5p", "1s", "3s", "6s", "6s", "8s", "9s"],
        }
        for name, hand in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], "que_select")
                self.assertEqual(body["hand"], hand)
                self.assertIn(body["advice"]["que"], "mps")
                self.assertEqual(len(body["advice"]["options"]), 3)
                self.assertNotIn("left", body)

    def test_que_select_with_partial_hand_waits(self):
        records = [
            {"tile": "1m", "slot": (100, 900), "crop": None},
            {"tile": "2m", "slot": (160, 900), "crop": None},
        ]
        with mock.patch.object(server, "_scan_hand", return_value=records):
            status, body = hint_frame("shots_204728")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"state": "wait"})


class HintJianTest(unittest.TestCase):
    def test_jian_points_at_the_tile_above_it(self):
        cases = {"shots_204850": "9s", "shots4_212932_0057": "5m"}
        for name, jian in cases.items():
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], "turn")
                self.assertEqual(body["jian"], jian)

    def test_no_jian_on_other_frames(self):
        for name in ("shots_204809", "shots_204857", "shots4_212820_0023"):
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertIsNone(body.get("jian"))


class HintFixtureTest(unittest.TestCase):
    def test_every_fixture_state_matches_the_labels(self):
        with open(os.path.join(FIXTURES, "labels.json"), encoding="utf-8") as file:
            labels = json.load(file)
        for name, label in labels.items():
            if name.startswith("_"):
                continue
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], label["state"])


class HintFixtureFieldsTest(unittest.TestCase):
    def test_every_labelled_field_matches(self):
        with open(os.path.join(FIXTURES, "labels.json"), encoding="utf-8") as file:
            labels = json.load(file)
        for name, label in labels.items():
            if name.startswith("_"):
                continue
            with self.subTest(name=name):
                _, body = hint_frame(name)
                self.assertEqual(body["state"], label["state"])
                for key in ("hand", "melds", "que", "left"):
                    if key in label:
                        self.assertEqual(body[key], label[key], key)
                if "jian" in label:
                    self.assertEqual(body.get("jian"), label["jian"])
                if body["state"] == "turn" and "left" in body:
                    self.assertEqual(body["draws"], max(1, (body["left"] + 3) // 4))


class HintHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler())
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_hint_over_http(self):
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/hint",
            data=json.dumps({"image": png_base64(os.path.join(FRAME_DIR, "shots_204809.png"))}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request) as response:
            self.assertEqual(response.status, 200)
            body = json.loads(response.read())
        self.assertEqual(body["state"], "turn")
        self.assertEqual(body["hand"][0], "1m")


class HintPerformanceTest(unittest.TestCase):
    def test_one_hint_takes_less_than_one_and_a_half_seconds(self):
        started = time.perf_counter()
        status, body = hint_frame("shots_204809")
        elapsed = time.perf_counter() - started
        self.assertEqual(status, 200)
        self.assertEqual(body["state"], "turn")
        self.assertLess(elapsed, 1.5, f"{elapsed:.3f}s")


class HintUnsureReasonTest(unittest.TestCase):
    def test_unreadable_left_is_unsure_left(self):
        image = load_frame("shots_204809")
        ImageDraw.Draw(image).rectangle([340, 10, 450, 100], fill=(40, 130, 120))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        _, body = hint_bytes(buffer.getvalue())
        self.assertEqual(body, {"state": "unsure", "reason": "left"})

    def test_unreadable_que_is_unsure_que(self):
        image = load_frame("shots_204809")
        ImageDraw.Draw(image).rectangle([203, 958, 257, 1017], fill=(40, 130, 120))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        _, body = hint_bytes(buffer.getvalue())
        self.assertEqual(body, {"state": "unsure", "reason": "que"})

    def test_unreadable_meld_is_unsure_meld(self):
        image = load_frame("shots_204850")
        x0, x1 = 462, 542
        image.paste(image.crop((x0, 921, x1, 1026)).transpose(Image.FLIP_LEFT_RIGHT), (x0, 921))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        with tempfile.TemporaryDirectory() as unsure_dir, \
                mock.patch.object(server, "UNSURE_DIR", unsure_dir):
            _, body = hint_bytes(buffer.getvalue())
        self.assertEqual(body, {"state": "unsure", "reason": "meld"})


class HintWinTest(unittest.TestCase):
    def test_a_winning_hand_returns_win(self):
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "2p", "3p", "5p", "5p"]
        image = Image.new("RGB", (2412, 1080), (40, 130, 120))
        template_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates")
        for index, tile in enumerate(hand[:-1]):
            x0 = int(2004 - 123.5 * (13 - index))
            image.paste(Image.open(os.path.join(template_dir, "hand", tile + ".png")).convert("RGB"),
                        (x0 + 4, 885))
        image.paste(Image.open(os.path.join(template_dir, "hand", hand[-1] + ".png")).convert("RGB"),
                    (2043 + 4, 885))
        image.paste(load_frame("shots_204809").crop((360, 15, 440, 100)), (360, 15))
        image.paste(Image.open(os.path.join(template_dir, "que", "s.png")).convert("RGB"), (203, 958))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        _, body = hint_bytes(buffer.getvalue())
        self.assertEqual(body["state"], "win")
        self.assertEqual(body["hand"], hand)
        self.assertEqual(body["que"], "s")
        self.assertEqual(body["draws"], 10)
        self.assertNotIn("advice", body)


class HintJpegTest(unittest.TestCase):
    def test_every_fixture_as_jpeg_matches_the_png_result(self):
        with open(os.path.join(FIXTURES, "labels.json"), encoding="utf-8") as file:
            labels = json.load(file)
        for name, label in labels.items():
            if name.startswith("_"):
                continue
            with self.subTest(name=name):
                buffer = io.BytesIO()
                load_frame(name).save(buffer, format="JPEG", quality=85)
                status, body = hint_bytes(buffer.getvalue())
                self.assertEqual(status, 200)
                self.assertEqual(body["state"], label["state"])
                for key in ("hand", "melds", "que", "left"):
                    if key in label:
                        self.assertEqual(body[key], label[key], key)
                if "jian" in label:
                    self.assertEqual(body.get("jian"), label["jian"])

    def test_jpeg_is_accepted_at_the_right_size(self):
        buffer = io.BytesIO()
        load_frame("shots_204809").save(buffer, format="JPEG", quality=85)
        status, body = hint_bytes(buffer.getvalue())
        self.assertEqual(status, 200)
        self.assertEqual(body["state"], "turn")
        self.assertEqual(body["hand"][0], "1m")


class HandTemplateTest(unittest.TestCase):
    def test_committed_4s_crop_is_recognized(self):
        path = os.path.join(FIXTURES, "4s_live.png")
        score, tile = _best(_feature(Image.open(path)), HAND_TEMPLATES)
        self.assertEqual(tile, "4s")
        self.assertGreaterEqual(score, HAND_MATCH, f"{score:.4f}")


class HandNearMissTest(unittest.TestCase):
    def test_glow_and_dot_crops_pass_the_accept_rule(self):
        directory = os.path.join(FIXTURES, "near_miss")
        for name in sorted(os.listdir(directory)):
            expected = name.split("_")[0]
            with self.subTest(name=name):
                score, tile = _best(_feature(Image.open(os.path.join(directory, name))), HAND_TEMPLATES)
                self.assertEqual(tile, expected)
                self.assertGreaterEqual(score, HAND_MATCH, f"{score:.4f}")


class HintCaptureTest(unittest.TestCase):
    """非「等待」的帧、数不对的等待帧、出异常的帧,原图和回复都存进 captures/,事后核对认错的牌。"""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.patches = [mock.patch.object(server, "CAPTURE_DIR", self.dir.name),
                        mock.patch.object(server, "_last_capture", None, create=True)]
        for patch in self.patches:
            patch.start()

    def tearDown(self):
        for patch in self.patches:
            patch.stop()
        self.dir.cleanup()

    def captured(self):
        names = sorted(os.listdir(self.dir.name))
        replies = [n for n in names if n.endswith(".json")]
        images = [n for n in names if not n.endswith(".json")]
        return replies, images

    def test_turn_frame_is_saved_with_its_reply(self):
        path = os.path.join(FRAME_DIR, "shots_204809.png")
        _, body = hint_frame("shots_204809")
        replies, images = self.captured()
        self.assertEqual(len(replies), 1)
        self.assertEqual(len(images), 1)
        with open(os.path.join(self.dir.name, images[0]), "rb") as file, open(path, "rb") as original:
            self.assertEqual(file.read(), original.read())
        with open(os.path.join(self.dir.name, replies[0]), encoding="utf-8") as file:
            self.assertEqual(json.load(file), body)

    def test_same_reply_twice_in_a_row_is_saved_once(self):
        # 用户想牌时手机每秒发一帧同样的画面,只留第一张
        hint_frame("shots_204809")
        hint_frame("shots_204809")
        self.assertEqual(len(self.captured()[0]), 1)
        hint_frame("shots_204850")
        hint_frame("shots_204809")
        self.assertEqual(len(self.captured()[0]), 3)

    def test_plain_wait_is_not_saved(self):
        _, body = hint_frame("shots_204857")
        self.assertEqual(body["state"], "wait")
        self.assertEqual(self.captured(), ([], []))

    def test_wait_with_a_hand_that_does_not_add_up_is_saved(self):
        reply = {"state": "wait", "hand": ["1m"] * 10, "melds": []}
        with mock.patch.object(server, "advise_hint", return_value=reply):
            code, body = hint_frame("shots_204857")
        self.assertEqual((code, body), (200, reply))
        replies, images = self.captured()
        self.assertEqual((len(replies), len(images)), (1, 1))

    def test_crash_is_saved_and_answered_500(self):
        with mock.patch.object(server, "_scan_hand", side_effect=RuntimeError("boom")):
            code, body = hint_frame("shots_204809")
        self.assertEqual(code, 500)
        self.assertEqual(body["error"], "internal")
        replies, images = self.captured()
        self.assertEqual(len(images), 1)
        with open(os.path.join(self.dir.name, replies[0]), encoding="utf-8") as file:
            self.assertIn("RuntimeError: boom", json.load(file)["traceback"])

    def test_bad_image_saves_nothing(self):
        code, body = handle({"image": "not base64!"}, "/hint")
        self.assertEqual(body["error"], "bad_image")
        self.assertEqual(self.captured(), ([], []))
