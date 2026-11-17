"""按公开入口测:handle(body) 返回 (HTTP 状态码, JSON);另有一条走真实 HTTP 锁状态码。"""
import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from server import handle, handler, win_fan, win_multiplier, win_probability


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

    def test_main_hand_prefers_expected_value_over_tenpai(self):
        # 新规则(期望倍数)下,6p 的一向听 32 进张比 9p 的单钓 5p(4 张)期望高。
        # 第一版断言 top-level 必为 9p;按 spec「与新规则冲突时逐条说明并按新规则改」,
        # 这里改成 6p,并保留 9p 仍在 options 里的旧字段断言。
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertEqual(body["discard"], "6p")
        self.assertEqual(body["target"], "fast")
        self.assertEqual(body["fan"], 0)
        self.assertEqual(body["shanten"], 1)
        self.assertEqual(body["ukeire"], 32)
        self.assertEqual(body["queLeft"], 0)
        nine = next(option for option in body["options"] if option["discard"] == "9p")
        self.assertEqual(nine["shanten"], 0)
        self.assertEqual(nine["ukeire"], 4)
        self.assertEqual(nine["waits"], ["5p"])
        self.assertEqual(nine["fan"], 0)
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
        _, without_melds = post(hand)
        self.assertEqual(body, without_melds)

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
    def test_chases_qingyise_when_it_pays(self):
        hand = ["1m", "2m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "9m",
                "5s", "6s", "7s"]
        status, body = post(hand, que="p")
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertIn(body["discard"], ("5s", "6s", "7s"))
        self.assertEqual(body["target"], "qingyise")
        self.assertEqual(body["fan"], 2)
        self.assertGreater(body["value"], 0.0)
        for option in body["options"]:
            self.assertIn(option["target"], ("fast", "qingyise", "pengpeng", "qidui"))
            self.assertIsInstance(option["fan"], int)
            self.assertIsInstance(option["value"], float)
            self.assertIn("shanten", option)
            self.assertIn("ukeire", option)
            self.assertIn("waits", option)


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
        self.assertGreater(body["value"], 1.0)

    def test_chases_seven_pairs(self):
        hand = ["1m", "1m", "4m", "4m", "7m", "7m", "2p", "2p",
                "5p", "5p", "8p", "8p", "3m", "6p"]
        status, body = post(hand, que="s")
        self.assertEqual(status, 200)
        self.assertIn(body["discard"], ("3m", "6p"))
        self.assertEqual(body["target"], "qidui")
        self.assertEqual(body["fan"], 2)
        self.assertEqual(body["shanten"], 0)


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
