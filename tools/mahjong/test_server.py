"""按公开入口测:handle(body) 返回 (HTTP 状态码, JSON);另有一条走真实 HTTP 锁状态码。"""
import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from server import Advisor, handle, handler


def post(hand, melds=None, que="s"):
    body = {"hand": hand, "que": que}
    if melds is not None:
        body["melds"] = melds
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
        hand = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m",
                "1p", "1p", "4p", "6p", "9p"]
        status, body = post(hand)
        self.assertEqual(status, 200)
        self.assertFalse(body["win"])
        self.assertEqual(body["discard"], "9p")
        self.assertEqual(body["shanten"], 0)
        self.assertEqual(body["ukeire"], 4)
        self.assertEqual(body["waits"], ["5p"])
        self.assertEqual(body["queLeft"], 0)
        self.assertEqual(body["options"][0],
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
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler(Advisor()))
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
