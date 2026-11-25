"""用 DouZero 自己的牌局随机发牌打到中途:只凭看得见的信息重建,出的牌要和拿完整牌局时一模一样。"""
import random
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from douzero.env import move_detector as md
from douzero.env.game import GameEnv

from server import (
    ACT_ENDGAME_BUDGET_S, ACT_ENDGAME_TIE_MARGIN, ACT_TOTAL_BUDGET_S, DECK, DOUBLE_WIN, FARMER_DOUBLE_WIN, NAMES,
    ROLLOUT_MIN_GAMES, SEATS, SUPER_DOUBLE_WIN, Advisor, Mismatch, WEIGHTS, Cards,
    bid_level, cards, handler, parse_auction, resolve, should_double,
)

ADVISOR = Advisor(WEIGHTS)


def names(cards):
    return [NAMES[c] for c in cards]


class Recorder:
    def __init__(self, advisor):
        self.advisor = advisor

    def act(self, infoset):
        return self.advisor.agents[infoset.player_position].act(infoset)


def new_game(seed):
    deck = list(DECK)
    random.Random(seed).shuffle(deck)
    env = GameEnv({s: Recorder(ADVISOR) for s in SEATS})
    env.card_play_init({"landlord": sorted(deck[:20]), "landlord_up": sorted(deck[20:37]),
                        "landlord_down": sorted(deck[37:54]), "three_landlord_cards": sorted(deck[17:20])})
    return env


def request_split(position):
    """把一条实机请求拆成 likelihood 要用的东西:
    (座位, 历史, 三家此刻剩的牌, 各家张数, 看不见的牌, 三家出过的牌)。"""
    import server as server_module
    seat = position["seat"]
    hand = cards(position["hand"])
    bottom = cards(position.get("bottom", []))
    history = resolve(hand, [[str(c) if str(c).startswith("?") else cards([c])[0] for c in m]
                             for m in position["history"]])
    start = server_module.deal(seat, hand, bottom, history)
    played = {s: [] for s in SEATS}
    for i, move in enumerate(history):
        played[SEATS[i % 3]] += move
    left = {s: len(start[s]) - len(played[s]) for s in SEATS}
    current = {s: server_module.minus(start[s], played[s]) for s in SEATS}
    unseen = sorted(server_module.minus(DECK, current[seat] + sum(played.values(), [])))
    return seat, history, current, left, unseen, played


class ServerTest(unittest.TestCase):
    def test_rebuilt_state_gives_the_same_move(self):
        checked = 0
        for seed in range(40):
            env = new_game(seed)
            for _ in range(random.Random(seed).randint(0, 25)):
                env.step()
                if env.game_over:
                    break
            if env.game_over:
                continue
            seat = env.acting_player_position
            info = env.game_infoset
            want = ADVISOR.agents[seat].act(info)
            got = ADVISOR.act(seat, names(info.player_hand_cards), names(info.three_landlord_cards),
                              [names(m) for m in info.card_play_action_seq])
            self.assertEqual(sorted(want), sorted(got), f"seed {seed}")
            checked += 1
        self.assertGreater(checked, 25)

    def test_a_misread_move_is_caught(self):
        env = new_game(7)
        for _ in range(6):
            env.step()
        info = env.game_infoset
        history = [names(m) for m in info.card_play_action_seq]
        first = next(i for i, m in enumerate(history) if m)
        history[first] = history[first] + ["BJ"] if "BJ" not in history[first] else history[first][:-1]
        with self.assertRaises(Exception) as caught:
            ADVISOR.act(env.acting_player_position, names(info.player_hand_cards), names(info.three_landlord_cards), history)
        self.assertIn("mismatch", str(caught.exception))

    def test_a_missed_pass_is_caught(self):
        env = new_game(3)
        for _ in range(9):
            env.step()
        info = env.game_infoset
        history = [names(m) for m in info.card_play_action_seq]
        if [] not in history:
            self.skipTest("这局前 9 手没有不出")
        history.remove([])
        with self.assertRaises(Exception):
            ADVISOR.act(env.acting_player_position, names(info.player_hand_cards), names(info.three_landlord_cards), history)


class BottomCardTest(unittest.TestCase):
    def test_a_misread_bottom_card_does_not_stop_the_game(self):
        env = new_game(11)
        env.step()
        info = env.game_infoset
        seat, hand = env.acting_player_position, names(info.player_hand_cards)
        history = [names(m) for m in info.card_play_action_seq]
        # 底牌认成了已经不可能有的牌:我手里的那几张再加两张大王
        wrong = [hand[0], "BJ", "BJ"]
        self.assertTrue(ADVISOR.act(seat, hand, wrong, history) is not None)


class RibbonCardTest(unittest.TestCase):
    """地主最后一张认不准记成 ?:只有一种补法时补上、出牌不变;补法不止一种就报错,不瞎猜。"""

    def test_a_pair_or_longer_play_is_completed(self):
        filled = ambiguous = 0
        for seed in range(60):
            env = new_game(seed)
            for _ in range(random.Random(seed).randint(3, 25)):
                env.step()
                if env.game_over:
                    break
            if env.game_over:
                continue
            info = env.game_infoset
            history = [names(m) for m in info.card_play_action_seq]
            landlord_moves = [i for i in range(0, len(history), 3) if len(history[i]) >= 2]
            if not landlord_moves:
                continue
            i = landlord_moves[-1]
            masked = [list(m) for m in history]
            masked[i] = masked[i][:-1] + ["?"]
            seat, hand, bottom = env.acting_player_position, names(info.player_hand_cards), names(info.three_landlord_cards)
            try:
                got = ADVISOR.act(seat, hand, bottom, masked)
            except Exception as e:
                self.assertIn("ambiguous_card", str(e))
                ambiguous += 1
                continue
            self.assertEqual(sorted(ADVISOR.act(seat, hand, bottom, history)), sorted(got), f"seed {seed}")
            filled += 1
        self.assertGreater(filled, 10)

    def test_a_single_card_is_too_open_to_guess(self):
        for seed in range(200):
            env = new_game(seed)
            env.step()
            info = env.game_infoset
            history = [names(m) for m in info.card_play_action_seq]
            if len(history[0]) == 1:
                break
        else:
            self.fail("找不到地主先出单张的一局")
        with self.assertRaises(Exception) as caught:
            ADVISOR.act(env.acting_player_position, names(info.player_hand_cards), names(info.three_landlord_cards), [["?"]] + history[1:])
        self.assertIn("ambiguous_card", str(caught.exception))


class JokerKickerTest(unittest.TestCase):
    """带牌里不能同时有大小王(王炸不能当飞机/四带二的带牌),只带一张王、王炸单独出照旧。"""

    def test_the_plane_does_not_take_both_jokers_as_kickers(self):
        # 2026-11-15 13:52 实机:地主 444555 + 大小王,advisor 出了 444555 SJ BJ,按下后手牌仍是 8 张、仍是领出
        history = [["10", "10", "10", "2"], ["K", "K", "K", "8"], [], ["7", "A", "A", "A"], [], [], ["Q", "Q", "Q", "Q"], [], []]
        hand = ["BJ", "SJ", "5", "5", "5", "4", "4", "4"]
        play = [NAMES[c] for c in ADVISOR.act("landlord", hand, ["4", "4", "10"], history)]
        self.assertNotEqual(sorted(["4", "4", "4", "5", "5", "5", "SJ", "BJ"]), sorted(play))
        # 大小王一起出现时只能是王炸本身,不能当任何带牌的翅膀
        if {"SJ", "BJ"} <= set(play):
            self.assertEqual(sorted(["SJ", "BJ"]), sorted(play))

    def test_the_search_never_generates_both_jokers_as_kickers(self):
        # 残局搜索模拟别家出牌用同一套合法性:别的玩家也出不了 444555 + 大小王
        import endgame
        hand = tuple(cards(["4", "4", "4", "5", "5", "5", "9", "SJ", "BJ"]))
        found = endgame.moves(hand, [])
        self.assertNotIn(tuple(sorted(cards(["4", "4", "4", "5", "5", "5", "SJ", "BJ"]))), found)
        # 不带王、只带一张王的飞机,以及王炸本身,照旧
        self.assertIn(tuple(sorted(cards(["4", "4", "4", "5", "5", "5", "SJ", "9"]))), found)
        self.assertIn(tuple(sorted(cards(["4", "4", "4", "5", "5", "5"]))), found)
        self.assertIn(tuple(sorted(cards(["SJ", "BJ"]))), found)


class EndgameTest(unittest.TestCase):
    def test_jj_passes_on_teammates_trio_when_endgame_rates_are_close(self):
        # 2026-11-25 12:51:47 JJ：队友出 666 带 33，AAA 带 JJ 的加权胜率只比不出高约 0.1 个百分点。
        import endgame
        hand = ["BJ", "A", "A", "A", "K", "J", "J", "8", "4", "4"]
        history = [["5", "5", "4", "4", "3", "3"], ["7", "7", "8", "8", "9", "9"], [], [],
                   ["10"], ["Q"], ["SJ"], [], [], ["J", "10", "9", "8", "7", "6"], [],
                   ["A", "K", "Q", "J", "10", "9"], [], [], ["6", "6", "6", "3", "3"], []]
        best = endgame.best

        def check_search(*args, **kwargs):
            result = best(*args, **kwargs)
            self.assertIsNotNone(result)
            wins, total, _, _ = result
            gap = (wins[tuple(cards(["J", "J", "A", "A", "A"]))] - wins[()]) / total
            self.assertGreater(gap, 0)
            self.assertLess(gap, 0.01)
            return result

        with mock.patch.object(endgame, "best", side_effect=check_search) as search:
            self.assertEqual([], ADVISOR.act("landlord_down", hand, [], history))
        search.assert_called_once()

    def test_the_search_finds_the_forced_win_douzero_missed(self):
        # 地主只剩一张(最大到 J)、我小王是场上最大:小王接过来,再出对子、A、Q、8 一路走完
        import time
        history = [["6", "6", "6", "4"], ["2", "2", "2", "10"], [], [], ["J", "10", "9", "8", "7"], [], ["3", "3", "3", "3"], [], [],
                   ["9", "9", "9", "7"], [], ["4", "J", "J", "J"], [], [], ["5", "5"], ["8", "8"], ["Q", "Q"], [], ["K", "K"], [], [],
                   ["Q"], ["A"], [], ["2"], ["BJ"], [], [], ["5"], ["6"], ["A"], []]
        hand = ["SJ", "A", "K", "K", "Q", "10", "10", "8", "7", "7"]
        started = time.monotonic()
        play = ADVISOR.act("landlord_up", hand, ["A", "4", "7"], history)
        self.assertEqual(["SJ"], [NAMES[c] for c in play])
        self.assertLess(time.monotonic() - started, 2.0)

    def test_moves_follow_the_game_rules(self):
        import endgame
        hand = tuple(cards(["3", "3", "5", "BJ", "SJ"]))
        self.assertIn((), endgame.moves(hand, cards(["4"])))
        self.assertIn(tuple(cards(["5"])), endgame.moves(hand, cards(["4"])))
        self.assertNotIn(tuple(cards(["3"])), endgame.moves(hand, cards(["4"])))
        self.assertIn(tuple(sorted(cards(["SJ", "BJ"]))), endgame.moves(hand, cards(["4"])))
        self.assertNotIn((), endgame.moves(hand, []))

    def test_the_last_pair_is_not_treated_as_a_random_pair(self):
        # 2026-11-16 20:51:41 土豪场,我是地主:手里 BJ SJ 2 Q 9 9,下家四张时先出 7 7 只剩两张,
        # 上家剩六张,没露面的是 4 9 K K A A 2 2。搜索把任意两张当成一样可能,算出 9 9 赢的分法最多;
        # 可刚主动出过对子的人最后两张多半还是一对,只有王炸能赢 KK。
        history = [["3", "3"], ["4", "4"], ["Q", "Q"], ["K", "K"], ["A", "A"], [], [],
                   ["5", "5", "5", "3"], ["6", "6", "6", "5"], ["4", "J", "J", "J"], [], [],
                   ["6", "7", "8", "9", "10"], [], [], ["7"], ["J"], ["2"], [], [],
                   ["8", "8", "8", "3"], [], ["10", "10", "10", "Q"], [], [], ["7", "7"], []]
        play = ADVISOR.act("landlord", ["BJ", "SJ", "2", "Q", "9", "9"], ["9", "10", "Q"], history)
        self.assertEqual(["9", "9"], [NAMES[c] for c in play])

    def test_a_single_lead_does_not_talk_the_search_into_a_pair(self):
        # 同样的残局,但下家最后不是出对子,而是把对子拆开出了一张单 7:这时他手里不太可能还留着对子。
        # 加权不能变成「永远猜对子」:王炸在 9 张(4 9 K K A A 2 2 7)的分法里只赢 65%,
        # 单张/不出每一种都赢,所以仍按不加权时的选择出单张 2,而不是王炸。2026-11-16 20:51:41 土豪场同局的推断。
        history = [["3", "3"], ["4", "4"], ["Q", "Q"], ["K", "K"], ["A", "A"], [], [],
                   ["5", "5", "5", "3"], ["6", "6", "6", "5"], ["4", "J", "J", "J"], [], [],
                   ["6", "7", "8", "9", "10"], [], [], ["7"], ["J"], ["2"], [], [],
                   ["8", "8", "8", "3"], [], ["10", "10", "10", "Q"], [], [], ["7"], []]
        play = ADVISOR.act("landlord", ["BJ", "SJ", "2", "Q", "9", "9"], ["9", "10", "Q"], history)
        self.assertEqual(["2"], [NAMES[c] for c in play])

    def test_a_terrible_opponent_move_does_not_turn_off_the_search(self):
        # 2026-11-16 人工构造(用 plain DouZero 随机打到残局,再让地主在倒数第 2 手故意出一张
        # DouZero 分数比最优低 3.64 的单 K):这张 K 在温度 0.02 下 float32 概率下溢成 0,
        # 4 手概率相乘后所有分法权重都是 0。旧实现 total=0、wins 全 0,act 不换牌,退回 DouZero
        # 原本的 pass;可不加权的残局搜索这手会换成 8 8。加权必须仍按搜索换牌。
        history = [['9'], ['K'], [], ['2'], [], [], ['4', '4'], [], ['10', '10'], ['J', 'J'], ['Q', 'Q'], [], ['A', 'A'], [], [],
                   ['10'], [], ['Q'], ['2'], [], [], ['6', '6'], [], ['9', '9'], [], [], ['K'], [], [], ['K'], [], ['A'], [], [],
                   ['6'], ['2'], [], [], ['5', '5'], ['8', '8'], [], [], ['SJ', 'BJ'], [], [], ['K'], ['2'], [], [], ['5', '5']]
        play = ADVISOR.act("landlord_up", ["3", "6", "8", "8", "9", "J", "A"], ["9", "J", "2"], history)
        self.assertEqual(["8", "8"], [NAMES[c] for c in play])

    def test_a_one_card_opponent_who_passed_cannot_beat_the_last_play(self):
        # 2026-11-17 23:12:13 土豪场,我是地主,手里 2 K K J;下家 7 张、上家只剩 1 张。
        # 第 31 手我出单张 7,下家不出,上家只剩一张也不出:能一手走完的人不会不出,
        # 所以上家那张一定 <= 7。服务原来按看不见的 8 张随便分,也考虑了上家是 Q / A 的分法,
        # 出了 K K 被下家 A A 管上输掉;按明牌出单张 J 必赢。下面的请求照抄服务日志
        # (底牌顺序 6 J A;auction 只有加倍,没有叫分)。
        import server as server_module
        history = [["3", "3", "4", "4", "5", "5", "6", "6"], [], [],
                   ["5"], ["7"], ["J"],
                   ["Q"], [], ["K"],
                   ["A"], [], ["2"],
                   [], [], ["8", "8", "8", "6"],
                   [], ["10", "10", "10", "9"], [],
                   [], ["9", "8", "7", "6", "5"], ["A", "K", "Q", "J", "10"],
                   [], [], ["4", "4"],
                   ["9", "9"], [], ["2", "2"],
                   ["SJ", "BJ"], [], [],
                   ["7"], [], []]
        auction = server_module.parse_auction({"double": {"landlord_down": False, "landlord_up": True}})
        play = ADVISOR.act("landlord", ["2", "K", "K", "J"], ["6", "J", "A"], history, auction=auction)
        self.assertEqual(["J"], [NAMES[c] for c in play])

    def test_a_one_card_opponent_who_cannot_beat_the_last_play_may_pass(self):
        # 同样的局面,但地主最后出的是 2:上家那一张 3 / 7 / J / Q / A 都管不上,不出是正常的。
        # 「能一手走完却不出」这条约束不能误伤这种分法,出牌要和加约束前一样是单张 7。
        import server as server_module
        history = [["3", "3", "4", "4", "5", "5", "6", "6"], [], [],
                   ["5"], ["7"], ["J"],
                   ["Q"], [], ["K"],
                   ["A"], [], ["2"],
                   [], [], ["8", "8", "8", "6"],
                   [], ["10", "10", "10", "9"], [],
                   [], ["9", "8", "7", "6", "5"], ["A", "K", "Q", "J", "10"],
                   [], [], ["4", "4"],
                   ["9", "9"], [], ["2", "2"],
                   ["SJ", "BJ"], [], [],
                   ["2"], [], []]
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": False, "landlord_down": False, "landlord_up": True}})
        play = ADVISOR.act("landlord", ["K", "K", "J", "7"], ["A", "J", "6"], history, auction=auction)
        self.assertEqual(["7"], [NAMES[c] for c in play])

    def test_a_landlord_with_a_pair_left_who_leads_wins(self):
        import endgame, time
        solver = endgame.Solver(time.monotonic() + 1)
        hands = {"landlord": tuple(cards(["4", "4"])), "landlord_down": tuple(cards(["A"])), "landlord_up": tuple(cards(["2", "3"]))}
        self.assertTrue(solver.landlord_wins(hands, "landlord", (), "landlord"))
        # 轮到农民先出:下家出 A 走完
        self.assertFalse(solver.landlord_wins(hands, "landlord_down", (), "landlord_down"))


class EndgameBudgetTest(unittest.TestCase):
    """残局搜索的时限要够把几百种分法(带 likelihood)算完:2026-11-17 12:14:32 精英场那一手。

    三家剩 7 / 6 / 5 张，共 462 种摆法。似然下限从 0.02 调到 0.2 后（#170），搜索选 J。
    现场模拟也可能选 J，所以单独检查搜索没有超时回退。
    """

    # 我当时 17 张:SJ 2 2 A J 9 9 8 8 7 6 6 5 5 5 3 3;地主叫三分,底牌 10 7 9。
    # 现在(我是地主上家,农民)手里 SJ A J 7 6 6 5,地主还剩 6 张、队友还剩 5 张。
    LIVE = dict(seat="landlord_up",
                hand=["SJ", "A", "J", "7", "6", "6", "5"],
                bottom=["10", "7", "9"],
                history=[["4", "4", "4", "5"], ["Q", "Q", "Q", "4"], [], ["A", "A", "A", "7"], [], [],
                         ["6", "6"], ["7", "7"], [], ["9", "9"], ["10", "10"], [], [], ["3", "3"], [], ["10", "10"],
                         ["J", "J"], ["2", "2"], [], [], ["3", "3"], [], [], ["8", "8"], [], [], ["5", "5"], [],
                         [], ["9", "9"], [], []])

    def test_the_search_finishes_inside_the_turn_budget(self):
        import endgame
        completed = []
        original = endgame.best

        def observe(*args, **kwargs):
            result = original(*args, **kwargs)
            completed.append(result is not None)
            return result

        started = time.monotonic()
        with mock.patch.object(endgame, "best", side_effect=observe):
            move = ADVISOR.act(**self.LIVE)
        self.assertEqual([True], completed)
        self.assertEqual(["J"], names(move))
        self.assertLess(time.monotonic() - started, ACT_TOTAL_BUDGET_S + 0.2)


class EndgameShortLandlordTest(unittest.TestCase):
    def test_jj_1900_searches_before_the_landlord_goes_out(self):
        import endgame

        hand = ["SJ", "2", "2", "2", "2", "A", "K", "Q", "J", "10", "10", "8", "8", "7", "7", "4", "4"]
        history = [["10", "9", "8", "7", "6", "5", "4"], [], [],
                   ["A", "A", "A", "K", "K", "K", "Q", "10"], []]
        for left, plays in [(5, history), (4, history + [[], ["BJ"], []])]:
            with self.subTest(landlord_cards=left):
                original = endgame.best
                searches = []

                def record(info, **kwargs):
                    started = time.monotonic()
                    result = original(info, **kwargs)
                    searches.append((info.num_cards_left_dict["landlord"], result, time.monotonic() - started))
                    return result

                with mock.patch.object(endgame, "best", side_effect=record):
                    move = ADVISOR.act("landlord_up", hand, [], plays)
                remaining, result, elapsed = searches[0]
                self.assertEqual(left, remaining)
                self.assertIsNotNone(result)
                wins, total, _, _ = result
                self.assertGreater(total, 0)
                bomb = tuple(cards(["2"] * 4))
                if left == 4:
                    self.assertGreater(wins[bomb], wins[()])
                    self.assertEqual(bomb, tuple(move))
                else:
                    # 地主还剩 5 张时，两手都能赢的分法按 DouZero 打分决胜。
                    self.assertEqual(max(wins[bomb], wins[()]), wins[tuple(move)])
                self.assertLess(elapsed, ACT_ENDGAME_BUDGET_S)

    def test_extending_the_search_keeps_the_landlords_unplayed_bottom_cards(self):
        import endgame

        unseen = [3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9]
        info = SimpleNamespace(
            player_position="landlord_up",
            num_cards_left_dict={"landlord": 4, "landlord_down": 9, "landlord_up": 10},
            other_hand_cards=unseen,
            three_landlord_cards=[3, 3, 4],
            player_hand_cards=list(range(5, 14)) + [30],
            legal_actions=[[30], []],
            last_move=[20],
            last_pid="landlord",
        )
        observed = []

        def likelihood(holdings, deadline):
            observed.extend(holdings)
            return [1.0] * len(holdings)

        result = endgame.best(info, budget=ACT_ENDGAME_BUDGET_S, likelihood=likelihood)
        self.assertIsNotNone(result)
        self.assertEqual(10, result[3])
        self.assertEqual({(3, 3, 4, rank) for rank in range(4, 10)},
                         {hands["landlord"] for hands in observed})
        for hands in observed:
            self.assertEqual(unseen, sorted(hands["landlord"] + hands["landlord_down"]))

    def test_landlord_searches_when_a_farmer_has_four_cards(self):
        import endgame

        info = SimpleNamespace(
            player_position="landlord",
            num_cards_left_dict={"landlord": 10, "landlord_down": 9, "landlord_up": 4},
            other_hand_cards=[3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 9],
            three_landlord_cards=[],
            player_hand_cards=list(range(5, 14)) + [30],
            legal_actions=[[30], []],
            last_move=[20],
            last_pid="landlord_up",
        )
        result = endgame.best(info, budget=ACT_ENDGAME_BUDGET_S)
        self.assertIsNotNone(result)
        _, _, plain, total = result
        self.assertEqual(total, plain[(30,)])
        self.assertLess(plain[()], total)


class EndgameUnknownTest(unittest.TestCase):
    """自己手里牌多、两家对手手里没几张时也跑残局搜索:看不见的牌少,分法就少,照样算得完。"""

    # 2026-11-17 13:05:08 精英场,我是地主下家(农民):手里 17 张一张没出过,地主剩 7 张、队友剩 2 张。
    # 三家合计 26 > MAX_CARDS,原来直接不搜;现场模拟按均匀发牌给 pass 1.0、5 5 5 5 1.0,打平按 DouZero 出了 pass,
    # 地主 K Q J 10 9 8 7 一手顺子走完。看不见的只有 9 张、23 种分法,残局搜索 0.14 秒就算完:5 5 5 5 必赢还多 ×2。
    LIVE = dict(seat="landlord_down",
                hand=["2", "A", "K", "Q", "Q", "9", "9", "8", "8", "7", "5", "5", "5", "5", "4", "4", "4"],
                bottom=["K", "7", "7"],
                history=[["3", "3", "3", "7"], [], ["6", "6", "6", "3"], ["J", "J", "J", "10"], [], [], ["A", "A"], [],
                         ["2", "2"], [], [], ["10", "9", "8", "7", "6"], [], [], ["10"], ["K"], [], ["A"], ["SJ"], [],
                         ["BJ"], [], [], ["4"], ["2"]])

    def test_the_search_runs_when_only_the_opponents_are_short(self):
        move = ADVISOR.act(**self.LIVE)
        self.assertEqual(["5", "5", "5", "5"], names(move))

    def test_an_existing_endgame_result_is_unchanged(self):
        # 三家合计 18(7/6/5)本来就走残局搜索。放宽门槛后仍出 J。
        live = EndgameBudgetTest.LIVE
        move = ADVISOR.act(**live)
        self.assertEqual(["J"], names(move))

    def test_the_turn_stays_inside_the_budget(self):
        started = time.monotonic()
        ADVISOR.act(**self.LIVE)
        self.assertLess(time.monotonic() - started, ACT_TOTAL_BUDGET_S)


class BombRestraintTest(unittest.TestCase):
    """重放 2026-11-19 10:53 普通场及 2026-11-22 18:45 新手启航。"""

    # 10:53:07 的原始请求(照抄服务日志):炸弹这一手。
    BOMB = dict(seat="landlord_up",
                hand=["K", "8", "8", "7", "7", "7", "6", "5", "5", "5", "5", "4", "4", "3"],
                bottom=[],
                history=[["3", "3"], ["6", "6"], [], ["A", "A"], [], [], ["10", "10", "9", "9", "8", "8"],
                         ["Q", "Q", "J", "J", "10", "10"], [], [], ["3"], ["Q"], ["K"], [], [], ["6"], ["7"],
                         ["9"], ["J"], ["K"], [], ["2"], [], [], ["Q"], [], ["K"], ["2"], ["SJ"]],
                auction={"double": {"landlord": False, "landlord_down": False}})

    # 10:53:14 的原始请求。炸弹之后领出 8 8，地主用 2 2 接住走完。
    FOLLOW_UP = dict(seat="landlord_up",
                     hand=["K", "8", "8", "7", "7", "7", "6", "4", "4", "3"],
                     bottom=[],
                     history=[["3", "3"], ["6", "6"], [], ["A", "A"], [], [], ["10", "10", "9", "9", "8", "8"],
                              ["Q", "Q", "J", "J", "10", "10"], [], [], ["3"], ["Q"], ["K"], [], [], ["6"], ["7"],
                              ["9"], ["J"], ["K"], [], ["2"], [], [], ["Q"], [], ["K"], ["2"], ["SJ"],
                              ["5", "5", "5", "5"], [], []],
                     auction={"double": {"landlord": False, "landlord_down": False}})

    def act(self, position):
        return ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                           position["history"], auction=parse_auction(position.get("auction")))

    def test_pass_has_a_higher_win_chance_than_the_bomb(self):
        started = time.monotonic()
        self.assertEqual([], self.act(self.BOMB))
        self.assertLess(time.monotonic() - started, ACT_TOTAL_BUDGET_S)

    def test_the_next_lead_uses_model_preference_inside_the_solver_tie(self):
        # ADP 下 88 比最高加权胜率低 0.526 个百分点，也在 1 个百分点平手区内。
        import endgame
        original = endgame.best
        expected = []

        def observe(info, **kwargs):
            result = original(info, **kwargs)
            self.assertIsNotNone(result)
            wins, total, _, _ = result
            scores = ADVISOR.agents[info.player_position].values(info)
            best = max(wins.values()) / total
            eligible = [i for i, a in enumerate(info.legal_actions)
                        if best - wins.get(tuple(sorted(a)), 0) / total <= ACT_ENDGAME_TIE_MARGIN]
            top = max(float(scores[i]) for i in eligible)
            expected.extend(sorted(info.legal_actions[i]) for i in eligible if float(scores[i]) == top)
            return result

        with mock.patch.object(endgame, "best", side_effect=observe):
            self.assertIn(sorted(self.act(self.FOLLOW_UP)), expected)

    def test_1845_pass_has_a_higher_win_chance_than_the_bomb(self):
        # 2026-11-22 18:45:11 新手启航:不出赢 210/210，炸 QQQQ 赢 192/210。
        history = [["7", "6", "5", "4", "3"], ["9", "8", "7", "6", "5"], [], [], ["3"], ["K"],
                   ["SJ"], ["BJ"], [], [], ["8"], ["2"], [], [], ["4", "5", "6", "7", "8"], [], [],
                   ["5"], ["6"], ["J"], [], ["2"], [], [], ["J", "J", "10", "10", "9", "9"], [], [],
                   ["3", "3"], ["A", "A"]]
        move = ADVISOR.act("landlord_up", ["A", "A", "Q", "Q", "Q", "Q", "10", "4", "4"], [], history)
        self.assertEqual([], move)


class EndgameLastTurnLikelihoodTest(unittest.TestCase):
    # 2026-11-24 21:04:39 JJ：地主领出 2 2，实际还剩 7 7。
    LIVE = dict(seat="landlord_down", hand=["K", "K", "K", "K", "Q", "Q", "J", "10", "10", "9", "5"],
                bottom=["5", "3", "7"],
                history=[["10"], [], ["A"], ["SJ"], ["BJ"], [], [], ["3", "4", "5", "6", "7"], [],
                         ["Q", "J", "10", "9", "8"], [], [], ["7", "6", "5", "4", "3"], [], [],
                         ["A", "A", "A", "J"], [], [], ["2", "2"]])

    def test_jj_bombs_the_landlords_pair(self):
        import endgame
        result = []
        best = endgame.best

        def record(*args, **kwargs):
            result.append(best(*args, **kwargs))
            return result[-1]

        with mock.patch("endgame.best", side_effect=record):
            play = ADVISOR.act(**self.LIVE)
        self.assertEqual(["K"] * 4, names(play))
        self.assertIsNotNone(result[0])
        wins, _, _, _ = result[0]
        self.assertGreater(wins[tuple(play)], wins[()])

    def test_a_landlord_who_led_a_pair_could_not_have_gone_out(self):
        import endgame
        me, history, current, _, unseen, _ = request_split(self.LIVE)
        splits = [dict(landlord=hand, landlord_down=current[me], landlord_up=endgame.minus(unseen, hand))
                  for hand in (cards(["2", "7"]), cards(["7", "7"]))]
        weights = ADVISOR.likelihood(history, me)
        self.assertEqual([0.0, 1.0], weights(splits, time.monotonic() + 5))
        self.assertEqual([1.0], weights(splits[:1], time.monotonic() + 5))

    def test_a_teammate_who_followed_could_have_bombed_to_go_out(self):
        # 上家用 A 接我的 5。原有 4 张 A 时可直接炸完，有 AAA2 时整手三带一接不住单张。
        history = [cards(["3"]), cards(["5"]), cards(["A"])]
        splits = [dict(landlord=cards(["3", "4", left]), landlord_down=cards(["6"]),
                       landlord_up=cards(["A", "A", right]))
                  for left, right in (("2", "A"), ("A", "2"))]
        weights = ADVISOR.likelihood(history, "landlord_down")
        self.assertEqual([0.0, 1.0], weights(splits, time.monotonic() + 5))

    def test_two_jokers_cannot_both_be_used_as_kickers_to_go_out(self):
        history = [cards(["3"] * 4)]
        pairs = (["4", "4"], ["SJ", "BJ"])
        splits = [dict(landlord=cards(hand), landlord_down=cards(["6"]), landlord_up=cards(other))
                  for hand, other in (pairs, pairs[::-1])]
        weights = ADVISOR.likelihood(history, "landlord_down")
        self.assertEqual([0.0, 1.0], weights(splits, time.monotonic() + 5))


class LikelihoodFloorTest(unittest.TestCase):
    """似然下限:软证据把一种分法压到最可能分法的 LIKELIHOOD_FLOOR 以下时按下限算,不再往 0 压。

    用 10:53:07 那一手:48 种分法(126 种摆法),温度 0.02 下「地主还拿着两张 2」被压到 0~1.7e-5;
    软证据夹在当前默认的 [0.2, 1] 内。硬约束(能一手走完却不出)仍记 0。
    """

    def splits(self):
        import endgame
        import server
        body = BombRestraintTest.BOMB
        hand = cards(body["hand"])
        played = [c for m in body["history"] for c in cards(m)]
        unknown = sorted(server.minus(DECK, hand + played))
        ways = endgame.splits(unknown, "landlord", 4, "landlord_down", 5)
        return [{"landlord_up": tuple(hand), "landlord": ha, "landlord_down": hb} for (ha, hb) in ways]

    def test_soft_evidence_does_not_push_a_split_to_near_zero(self):
        history = [cards(m) for m in BombRestraintTest.BOMB["history"]]
        callback = ADVISOR.likelihood(history, "landlord_up", None)
        likes = callback(self.splits(), time.monotonic() + 5)
        self.assertEqual(1.0, max(likes))
        self.assertEqual(0.2, min(likes))


class KeepBombLikelihoodTest(unittest.TestCase):
    def test_a_kept_bomb_lifts_the_observed_non_bomb_move(self):
        # 只看这一条的因果:下家在第 4 手领出单张 3,按整手牌 4 4 4 4 + 3 打分 log 约 -1694,
        # 把炸弹放一边只剩 3,log 是 0。所以「留着炸弹的 4 4 4 4」这种分法权重该是 1,
        # 而不是被压到 LIKELIHOOD_FLOOR。
        import server as server_module
        history = [cards(["BJ"]), [], [], [], cards(["3"])]
        splits = [{"landlord": (), "landlord_down": cards(hand), "landlord_up": ()}
                  for hand in (["4", "4", "4", "4"], ["4", "4", "6", "6"])]
        likes = ADVISOR.likelihood(history, "landlord")(splits, time.monotonic() + 30)
        self.assertEqual(1.0, likes[0], "留着 4 4 4 4 领单张 3 的分法该是最可能的")
        self.assertEqual(server_module.LIKELIHOOD_FLOOR, likes[1])

class RibbonMisreadTest(unittest.TestCase):
    def test_an_illegal_landlord_play_gets_its_ribboned_card_refilled(self):
        # 实机上的一手:地主出 44433,最后一张带绶带的 3 在落下时认成了 J;只有补成 3 才合规
        hand = ["2", "2", "K", "K", "K", "Q", "Q", "J", "10", "10", "10", "10", "9", "7", "5", "5", "3"]
        history = [["4", "4", "4", "3", "J"]]
        out = resolve(cards(hand), [cards(m) for m in history])
        self.assertEqual(sorted(cards(["4", "4", "4", "3", "3"])), out[0])
        self.assertTrue(ADVISOR.act("landlord_down", hand, ["4", "5", "A"], history) is not None)


class BidTest(unittest.TestCase):
    def test_bid_level_threshold(self):
        # 0.64 还不到门槛不叫,0.65 到了就叫三分(门槛 0.65)
        self.assertEqual(0, bid_level(0.64))
        self.assertEqual(3, bid_level(0.65))

    def test_a_strong_hand_is_worth_more_as_landlord_than_a_weak_one(self):
        strong = ["BJ", "SJ", "2", "2", "2", "A", "A", "K", "K", "Q", "Q", "J", "10", "9", "8", "7", "6"]
        weak = ["3", "3", "4", "5", "6", "6", "7", "8", "9", "9", "10", "J", "J", "Q", "4", "5", "8"]
        self.assertGreater(ADVISOR.bid(strong, games=20)[0], 0.7)
        self.assertLess(ADVISOR.bid(weak, games=20)[0], 0.3)


class DoubleTest(unittest.TestCase):
    """加倍:明显的强牌值,烂牌不值;张数不对报错。20 张是地主(含底牌),17 张是农民加三张明牌。"""

    STRONG = ["BJ", "SJ", "2", "2", "2", "2", "A", "A", "A", "K", "K", "Q", "Q", "J", "10", "9", "8", "7", "6", "5"]
    WEAK = ["3", "3", "3", "4", "4", "5", "5", "6", "6", "7", "7", "8", "8", "9", "9", "10", "10", "J", "J", "Q"]

    def test_double_threshold(self):
        # 0.79 还不到门槛不加倍,0.80 到了就加倍(门槛 0.8)
        self.assertFalse(should_double(0.79))
        self.assertTrue(should_double(0.80))

    def double_reply(self, seat, win, hand=None, bottom=None, games=100):
        """按 /double 的公开入口走一遍:假服务只回固定的赢面,拿到 handler 实际写出的回复。"""
        import io
        import json
        import server as server_module
        hand = self.STRONG if hand is None else hand
        bottom = ["2", "A", "K"] if bottom is None else bottom
        captured = {}

        class FakeAdvisor:
            def double(self, seat, hand, bottom):
                return win, games

        class Handler(server_module.handler(FakeAdvisor())):
            def reply(self, code, data, request=None):
                captured["code"] = code
                captured["data"] = data

        request = Handler.__new__(Handler)
        request.path = "/double"
        body = json.dumps({"seat": seat, "hand": hand, "bottom": bottom}).encode()
        request.rfile = io.BytesIO(body)
        request.headers = {"Content-Length": str(len(body))}
        request.do_POST()
        return captured["data"]

    def test_a_landlord_at_the_super_threshold_gets_super(self):
        reply = self.double_reply("landlord", SUPER_DOUBLE_WIN)
        self.assertTrue(reply["double"])
        self.assertTrue(reply["super"])

    def test_a_landlord_between_the_double_and_super_thresholds_does_not_super(self):
        self.assertGreater(SUPER_DOUBLE_WIN, DOUBLE_WIN, "超级加倍门槛不能低于普通加倍")
        reply = self.double_reply("landlord", (DOUBLE_WIN + SUPER_DOUBLE_WIN) / 2)
        self.assertTrue(reply["double"])
        self.assertFalse(reply["super"])

    def test_a_farmer_never_gets_super(self):
        # 农民赢面再高也只回 double,super 永远 false
        reply = self.double_reply("landlord_up", 0.99, hand=self.STRONG[:17], bottom=["3", "4", "5"])
        self.assertTrue(reply["double"])
        self.assertFalse(reply["super"])

    def test_a_weak_landlord_gets_neither_double_nor_super(self):
        reply = self.double_reply("landlord", DOUBLE_WIN - 0.01)
        self.assertFalse(reply["double"])
        self.assertFalse(reply["super"])

    def test_rollouts_stop_at_the_time_budget_after_the_minimum_games(self):
        # 时限一到就停,但至少打满 ROLLOUT_MIN_GAMES 局;手机只等 4 秒
        self.assertEqual(ADVISOR.double("landlord", self.STRONG, ["2", "A", "K"], budget=0)[1], ROLLOUT_MIN_GAMES)
        self.assertEqual(ADVISOR.bid(self.STRONG[:17], budget=0)[1], ROLLOUT_MIN_GAMES)
        self.assertEqual(ADVISOR.bid(self.STRONG[:17], games=5)[1], 5)

    def test_a_strong_landlord_hand_is_worth_doubling(self):
        self.assertGreaterEqual(ADVISOR.double("landlord", self.STRONG, ["2", "A", "K"])[0], DOUBLE_WIN)

    def test_a_weak_landlord_hand_is_not_worth_doubling(self):
        self.assertLess(ADVISOR.double("landlord", self.WEAK, ["3", "4", "5"])[0], DOUBLE_WIN)

    def test_a_strong_farmer_hand_is_worth_doubling(self):
        # 农民有自己的门槛:条件抽样之后赢面比无条件低,但强牌仍然过门槛。
        # 这手合成牌(双王 + 四个 2)把剩下的牌压得太弱,条件抽样 1.5 秒只采纳 28 副、2 秒 38 副,
        # 都不到 ROLLOUT_MIN_GAMES;这里显式给 3 秒,只测门槛(默认预算见 DoubleBudgetTest)。
        self.assertGreaterEqual(ADVISOR.double("landlord_up", self.STRONG[:17], ["3", "4", "5"], budget=3.0)[0],
                                FARMER_DOUBLE_WIN)

    def test_a_weak_farmer_hand_is_not_worth_doubling(self):
        self.assertLess(ADVISOR.double("landlord_down", self.WEAK[:17], ["2", "A", "K"])[0], FARMER_DOUBLE_WIN)

    def test_farmer_threshold_is_separate_from_the_landlord_one(self):
        # 地主仍用 0.8;农民用 FARMER_DOUBLE_WIN
        self.assertFalse(should_double(FARMER_DOUBLE_WIN - 0.01, "landlord_up"))
        self.assertTrue(should_double(FARMER_DOUBLE_WIN, "landlord_up"))
        self.assertFalse(should_double(DOUBLE_WIN - 0.01, "landlord"))
        self.assertTrue(should_double(DOUBLE_WIN, "landlord"))

    def test_conditioning_on_a_landlord_who_would_call_lowers_the_farmer_estimate(self):
        # 同一手农民牌、同一个种子:只留地主会叫的牌,农民赢面只会更低
        hand, bottom = self.STRONG[:17], ["3", "4", "5"]
        unconditional, _ = ADVISOR.double("landlord_up", hand, bottom, games=40, budget=30, condition=False)
        conditional, played = ADVISOR.double("landlord_up", hand, bottom, games=40, budget=30, condition=True)
        self.assertEqual(40, played)
        self.assertLessEqual(conditional, unconditional)

    def test_only_deals_whose_landlord_would_call_are_played_out(self):
        hand, bottom = self.STRONG[:17], ["3", "4", "5"]
        accepted = []
        win, played = ADVISOR.double("landlord_up", hand, bottom, games=20, budget=30, min_games=5,
                                     on_accept=lambda hands: accepted.append(hands["landlord"]))
        self.assertEqual(played, len(accepted))
        self.assertTrue(accepted)
        for lord in accepted:
            self.assertTrue(ADVISOR.landlord_would_call(lord, cards(bottom)), lord)
        self.assertGreaterEqual(win, 0.0)

    def test_the_farmer_seat_returns_the_lower_of_the_two_farmers(self):
        hand, bottom = self.STRONG[:17], ["3", "4", "5"]
        up, _ = ADVISOR.double("landlord_up", hand, bottom, games=20, budget=30, min_games=5)
        down, _ = ADVISOR.double("landlord_down", hand, bottom, games=20, budget=30, min_games=5)
        # farmer 座位的 games 是局数:20 副两个座位各打一局,正好 40 局(单个座位 40 局)
        both, played = ADVISOR.double("farmer", hand, bottom, games=40, budget=30, min_games=5)
        self.assertEqual(40, played)
        self.assertEqual(min(up, down), both)

    def test_an_under_sampled_combined_farmer_still_reports_its_win_rate(self):
        # 2026-11-19 10:36:58 那手:双王 + 四个 J + 四个 3,剩下的牌太弱,条件抽样只采纳二三十副。
        # 采纳不到下限时原来直接回 0.0,端点看起来像 0% 赢面;至少要把打过的局数的真实赢面报出来。
        hand = ["BJ", "SJ", "2", "K", "J", "J", "J", "J", "8", "8", "7", "7", "5", "3", "3", "3", "3"]
        bottom = ["9", "2", "K"]
        win, played = ADVISOR.double("farmer", hand, bottom, games=20, budget=30, min_games=40)
        self.assertEqual(20, played)  # 10 副 × 两个座位
        self.assertGreater(win, 0.0)

    def test_the_endpoint_does_not_act_on_an_under_sampled_rate(self):
        # 局数不到下限时,不管表面赢面多高都不加倍 / 不超级加倍
        short = self.double_reply("landlord", 0.99, games=ROLLOUT_MIN_GAMES - 1)
        self.assertFalse(short["double"])
        self.assertFalse(short["super"])
        enough = self.double_reply("landlord", 0.99, games=ROLLOUT_MIN_GAMES)
        self.assertTrue(enough["double"])
        self.assertTrue(enough["super"])
        # farmer 每副牌两个座位各打一局:40 副才是每个座位 40 局,门槛 2×
        farmer_short = self.double_reply("farmer", 0.99, games=2 * ROLLOUT_MIN_GAMES - 1)
        self.assertFalse(farmer_short["double"])
        farmer_enough = self.double_reply("farmer", 0.99, games=2 * ROLLOUT_MIN_GAMES)
        self.assertTrue(farmer_enough["double"])

    def test_a_farmer_deal_that_cannot_be_accepted_is_not_doubled(self):
        # 预算为 0 时一局都采纳不到:返回 0 局、赢面 0,端点据此点「不加倍」
        win, played = ADVISOR.double("landlord_up", self.STRONG[:17], ["3", "4", "5"], budget=0)
        self.assertEqual(0, played)
        self.assertEqual(0.0, win)

    def test_a_wrong_hand_count_is_an_error(self):
        with self.assertRaises(Mismatch) as caught:
            ADVISOR.double("landlord", self.STRONG[:19], ["2", "A", "K"])
        self.assertIn("hand_count_mismatch", str(caught.exception))


class DoubleBudgetTest(unittest.TestCase):
    """加倍单独的时间预算:地主加倍窗口约 5 秒,手机还要留出点按钮的时间,现场模拟只给约 1.5 秒;
    两个农民座位共用预算时每副牌要打两局,局数不够下限,单独给到 2 秒以内。叫分仍用 [ROLLOUT_BUDGET_S]。"""

    # 1.5 秒预算 + 0.3 秒余量(网络 + 点按钮);农民最多 2 秒,同样留 0.3 秒
    LANDLORD_WINDOW = 1.5 + 0.3
    FARMER_WINDOW = 2.0 + 0.3

    def double_request(self, seat, hand, bottom):
        """按 /double 的公开入口走一遍真服务:返回 (HTTP 状态码, 回复, 整次请求耗时秒数)。"""
        import io
        import json
        import server as server_module
        captured = {}

        class Handler(server_module.handler(ADVISOR)):
            def reply(self, code, data, request=None):
                captured["code"] = code
                captured["data"] = data

        request = Handler.__new__(Handler)
        request.path = "/double"
        body = json.dumps({"seat": seat, "hand": hand, "bottom": bottom}).encode()
        request.rfile = io.BytesIO(body)
        request.headers = {"Content-Length": str(len(body))}
        started = time.perf_counter()
        request.do_POST()
        return captured["code"], captured["data"], time.perf_counter() - started

    def test_a_landlord_double_fits_the_double_window(self):
        code, reply, elapsed = self.double_request("landlord", DoubleTest.STRONG, ["2", "A", "K"])
        self.assertEqual(200, code)
        self.assertLessEqual(elapsed, self.LANDLORD_WINDOW,
                             f"/double 用了 {elapsed:.2f} 秒,超过 {self.LANDLORD_WINDOW:.1f} 秒的加倍窗口")
        self.assertGreaterEqual(reply["games"], ROLLOUT_MIN_GAMES)

    def test_a_farmer_double_fits_the_double_window(self):
        # 用弱农民手:剩下的牌强,条件抽样几乎每批都收,局数够;强农民手剩下的牌太弱,2 秒也凑不够 40 局
        code, reply, elapsed = self.double_request("farmer", DoubleTest.WEAK[:17], ["2", "A", "K"])
        self.assertEqual(200, code)
        self.assertLessEqual(elapsed, self.FARMER_WINDOW,
                             f"/double 用了 {elapsed:.2f} 秒,超过 {self.FARMER_WINDOW:.1f} 秒的加倍窗口")
        self.assertGreaterEqual(reply["games"], ROLLOUT_MIN_GAMES)

    def test_the_double_budget_is_separate_and_the_bid_budget_is_left_alone(self):
        import inspect
        import server as server_module
        self.assertEqual(server_module.ROLLOUT_BUDGET_S,
                         inspect.signature(Advisor.bid).parameters["budget"].default)
        # 地主 / 单个农民座位用 DOUBLE_BUDGET_S;seat="farmer" 两个座位共用,单独给到 2 秒以内
        self.assertIsNone(inspect.signature(Advisor.double).parameters["budget"].default)
        self.assertLess(server_module.DOUBLE_BUDGET_S, server_module.ROLLOUT_BUDGET_S)
        self.assertGreaterEqual(server_module.FARMER_DOUBLE_BUDGET_S, server_module.DOUBLE_BUDGET_S)
        self.assertLessEqual(server_module.FARMER_DOUBLE_BUDGET_S, 2.0)


class OrderTest(unittest.TestCase):
    def test_a_move_listed_high_to_low_still_replays(self):
        for seed in range(300):
            env = new_game(seed)
            while not env.game_over and not any(len(m) >= 5 for m in env.card_play_action_seq):
                env.step()
            if env.game_over:
                continue
            env.step()
            info = env.game_infoset
            history = [names(m) for m in info.card_play_action_seq]
            seat, hand, bottom = env.acting_player_position, names(info.player_hand_cards), names(info.three_landlord_cards)
            want = sorted(ADVISOR.act(seat, hand, bottom, history))
            # 桌上的顺子从左到右是从大到小
            got = sorted(ADVISOR.act(seat, hand, bottom, [list(reversed(m)) for m in history]))
            self.assertEqual(want, got)
            return
        self.fail("no game with a long move")


class LookaheadTest(unittest.TestCase):
    def test_a_guess_must_fit_the_moves_after_it(self):
        # 实机上的一手:地主单出一张压绶带的牌,手机猜 J、K、5;下家跟了 J,所以只能是 5
        history = [["K", "Q", "J", "10", "9", "8", "7", "?5,6,8"], [], [], ["?J,K,5"], [], ["J"], ["Q"]]
        hand = ["2", "2", "K", "K", "J", "J", "10", "10", "8", "7", "6", "5", "4", "4", "4", "3", "3"]
        out = resolve(cards(hand), [[str(c) if str(c).startswith("?") else cards([c])[0] for c in m] for m in history])
        self.assertEqual([6, 7, 8, 9, 10, 11, 12, 13], out[0])
        self.assertEqual([5], out[3])
        self.assertTrue(ADVISOR.act("landlord_down", hand, [], history))

    def test_guesses_that_all_fail_fall_back_to_any_legal_rank(self):
        # 实机上的一手:我出 A、上家不出,地主压了一张带绶带的 2,手机猜 J、5、3,都压不住 A
        history = [["10", "9", "8", "7", "6", "?J,5,7"], [], [], ["?J,K,5"], ["Q"], [], ["K"], ["A"], [], ["?J,5,3"]]
        hand = ["BJ", "2", "A", "K", "10", "9", "9", "8", "8", "7", "7", "6", "4", "3", "3"]
        out = resolve(cards(hand), [[str(c) if str(c).startswith("?") else cards([c])[0] for c in m] for m in history])
        self.assertEqual(cards(["2"]), out[9])
        self.assertTrue(ADVISOR.act("landlord_down", hand, [], history) is not None)


class GuessTest(unittest.TestCase):
    def test_the_first_guess_that_fits_is_used(self):
        for seed in range(200):
            env = new_game(seed)
            env.step()
            info = env.game_infoset
            history = [names(m) for m in info.card_play_action_seq]
            hand = names(info.player_hand_cards)
            jokers = [j for j in ["SJ", "BJ"] if j in hand]
            if len(history[0]) == 1 and jokers:
                break
        seat, bottom = env.acting_player_position, names(info.three_landlord_cards)
        truth = history[0][0]
        want = sorted(ADVISOR.act(seat, hand, bottom, history))
        # 头一个猜的是自己手里的王,别人不可能出,说不通;第二个才对
        got = sorted(ADVISOR.act(seat, hand, bottom, [["?" + jokers[0] + "," + truth]] + history[1:]))
        self.assertEqual(want, got)


class ShortOpponentEndgameTest(unittest.TestCase):
    def test_150450_searches_the_endgame_when_a_farmer_has_one_card(self):
        # 15:04 JJ:上家剩 1 张(7)、下家 12 张,看不见 13 张。原来不搜,结果先出单张 9 被下家 2 管住再喂上家走完;
        # 明牌搜出任意对子必赢
        position = dict(
            seat="landlord", hand=["A", "K", "K", "Q", "Q", "J", "J", "9", "9"], bottom=[],
            history=[["5", "6", "7", "8", "9", "10"], [], ["J", "10", "9", "8", "7", "6"], [], [],
                     ["5", "5", "5", "3"], [], [], ["6"], ["7"], ["J"], ["2"], [], [], ["K", "K"], [], [],
                     ["A", "A"], ["2", "2"], ["4", "4", "4", "4"], [], ["SJ", "BJ"], [], []])
        import endgame
        real = endgame.best
        results = []
        with mock.patch("endgame.best", lambda *a, **k: results.append(real(*a, **k)) or results[-1]):
            move = names(ADVISOR.act(**position))
        # 残局搜索真的跑了、算完了;出的对子在各种分法下赢得比单张 9 多
        self.assertTrue(results and results[-1] is not None)
        wins, total, _, _ = results[-1]
        self.assertEqual(2, len(move))
        self.assertEqual(move[0], move[1])
        self.assertGreater(wins.get(tuple(sorted(cards(move))), 0), wins.get(tuple(sorted(cards(["9"]))), 0))

class ActBudgetTest(unittest.TestCase):
    """出牌总预算不超过手机读超时。"""
    # 2026-11-16 12:18:11 土豪场实机:我是地主下家,手里 A 5 4 4 4,三家还剩 19 张。
    # 残局搜索 1.5 秒没结论、现场模拟又 2.5 秒,回复 4903 毫秒;手机 4 秒超时,标成 douzero_unreachable
    # 重发同一手,三次各约 4020 毫秒,最后按简单规则打完这一局。
    LOGGED = dict(seat="landlord_up",
                  hand=["A", "5", "4", "4", "4"],
                  bottom=["4", "7", "J"],
                  history=[["7", "7", "7", "4"], [], [], ["8", "8"], [], ["Q", "Q"], ["K", "K"], [], ["2", "2"], [], [],
                           ["8"], ["9"], ["J"], ["K"], [], [], ["6", "6", "6", "10", "10"], ["J", "J", "J", "10", "10"],
                           ["A", "A", "A", "5", "5"], [], [], ["6"], ["9"], ["Q"], ["K"]])

    def test_the_logged_request_answers_inside_the_phone_clock(self):
        # 实机那一手回复 4903 毫秒,手机 4 秒就超时;总预算封顶后要在手机读超时(4000 毫秒)内答完
        started = time.monotonic()
        ADVISOR.act(**self.LOGGED)
        self.assertLess(time.monotonic() - started, 4.0)

    def test_the_turn_budget_stays_under_the_phone_timeout(self):
        # 手机端 core/.../DdzAdvisor.kt 的 ACT_READ_TIMEOUT_MS = 4000:总预算不能超过 3.6 秒,留出网络余量
        self.assertLessEqual(ACT_TOTAL_BUDGET_S, 3.6)

class AuctionProtocolTest(unittest.TestCase):
    """/act 的可选 auction 字段:合法时透传给 Advisor,格式不对就整块当没带,不报错;不带时出牌和原来一样。"""

    def act_through_handler(self, body):
        """按 /act 的公开入口走一遍:假服务只记下 handler 传进来的 auction,回复固定空出牌。"""
        import io
        import json
        import server as server_module
        captured = {}

        class FakeAdvisor:
            def act(self, seat, hand, bottom, history, auction=None):
                captured["auction"] = auction
                return []

        class Handler(server_module.handler(FakeAdvisor())):
            def reply(self, code, data, request=None):
                captured["code"] = code
                captured["data"] = data

        request = Handler.__new__(Handler)
        request.path = "/act"
        raw = json.dumps(body).encode()
        request.rfile = io.BytesIO(raw)
        request.headers = {"Content-Length": str(len(raw))}
        request.do_POST()
        return captured

    def test_a_valid_auction_reaches_the_advisor(self):
        captured = self.act_through_handler({
            "seat": "landlord_down", "hand": ["3"], "bottom": [], "history": [],
            "auction": {"bid": {"landlord": 3},
                        "double": {"landlord": True, "landlord_down": False, "landlord_up": None}}})
        auction = captured["auction"]
        self.assertEqual({"landlord": 3}, auction.bid)
        self.assertEqual({"landlord": True, "landlord_down": False}, auction.double)

    def test_a_malformed_auction_is_ignored_without_an_error(self):
        # 座位名不对、分数不在 0..3、类型不对:整块忽略,照常出牌(200 + play),不报错
        for auction in (
                {"bid": {"landlord": 4}},
                {"bid": {"landlord": -1}},
                {"bid": {"farm": 3}},
                {"bid": {"landlord": "3"}},
                {"bid": {"landlord": 1.5}},
                {"bid": {"landlord": True}},
                {"double": {"landlord": "yes"}},
                {"double": {"landlord_down": 1}},
                {"bid": [3]},
                "landlord",
        ):
            captured = self.act_through_handler({
                "seat": "landlord_up", "hand": ["3"], "bottom": [], "history": [], "auction": auction})
            self.assertIsNone(captured["auction"], auction)
            self.assertEqual(200, captured["code"], auction)

    def test_no_or_empty_auction_is_the_same_as_none(self):
        for body in (
                {"seat": "landlord_up", "hand": ["3"], "bottom": [], "history": []},
                {"seat": "landlord_up", "hand": ["3"], "bottom": [], "history": [], "auction": {}},
                {"seat": "landlord_up", "hand": ["3"], "bottom": [], "history": [],
                 "auction": {"bid": {"landlord": None}, "double": {"landlord_down": None}}},
        ):
            captured = self.act_through_handler(body)
            self.assertIsNone(captured["auction"], body)
        self.assertEqual(200, captured["code"])

    def test_the_advisor_accepts_an_auction(self):
        # 公开入口 Advisor.act 收下合法 auction 后照常出牌(这一手牌多,不走加权,只验证协议接通)
        import server as server_module
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": True, "landlord_up": False}})
        live = EndgameUnknownTest.LIVE
        play = ADVISOR.act(live["seat"], live["hand"], live["bottom"], live["history"], auction=auction)
        self.assertIsInstance(play, list)
        self.assertTrue(all(c in cards(live["hand"]) for c in play))


class AuctionEndgameTest(unittest.TestCase):
    """残局似然也吃 auction:没有出牌历史、只有叫分 / 加倍时,地主牌强的分法权重更高;我自己的证据不算。"""

    MY = ["Q", "Q", "K", "K", "A", "J", "J", "J", "10", "10", "10", "9", "9", "9", "8", "8", "8"]
    STRONG = ["BJ", "SJ", "2", "2", "2", "2", "A", "A", "A", "K", "K",
              "Q", "Q", "J", "10", "9", "8", "7", "6", "5"]
    WEAK = ["3", "3", "3", "3", "4", "4", "4", "4", "5", "5", "5", "5",
            "6", "6", "6", "6", "7", "7", "7", "7"]
    # 我手牌之外的那 37 张;强 / 弱两种地主手牌都从这 37 张里分,另一种分法剩下的 17 张给队友
    REST_STRONG = ["3", "3", "3", "3", "4", "4", "4", "4", "5", "5", "5", "6", "6", "6", "7", "7", "7"]
    REST_WEAK = ["8", "9", "10", "J", "Q", "Q", "K", "K", "A", "A", "A", "2", "2", "2", "2", "SJ", "BJ"]

    def splits(self):
        return [
            {"landlord_up": cards(self.MY), "landlord": cards(self.STRONG), "landlord_down": cards(self.REST_STRONG)},
            {"landlord_up": cards(self.MY), "landlord": cards(self.WEAK), "landlord_down": cards(self.REST_WEAK)},
        ]

    def weights(self, auction, me):
        import time
        callback = ADVISOR.likelihood([], me, auction)
        return callback(self.splits(), time.monotonic() + 5)

    def test_a_strong_landlord_split_gets_more_weight_without_any_history(self):
        import server as server_module
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": True}})
        strong, weak = self.weights(auction, "landlord_up")
        self.assertGreater(strong, weak, "地主叫 3 分 + 加倍时,地主牌强的分法权重应该更高")
        self.assertEqual((1.0, server_module.LIKELIHOOD_FLOOR), (strong, weak))

    def test_my_own_evidence_is_not_used(self):
        import server as server_module
        # 我是地主:叫分 / 加倍都是我自己的,不算证据;我是农民上家时我自己的加倍也不算
        both = server_module.parse_auction({"bid": {"landlord": 3},
                                            "double": {"landlord": True}})
        self.assertEqual([1.0, 1.0], self.weights(both, "landlord"))
        own = server_module.parse_auction({"double": {"landlord_up": True}})
        self.assertEqual([1.0, 1.0], self.weights(own, "landlord_up"))

    def test_other_bid_levels_are_not_evidence(self):
        # v1 只看地主叫三分:叫 0/1/2 都不加权
        import server as server_module
        for level in (0, 1, 2):
            auction = server_module.parse_auction({"bid": {"landlord": level}})
            strong, weak = self.weights(auction, "landlord_up")
            self.assertEqual(strong, weak, level)


class EndgameRankingTest(unittest.TestCase):
    """用固定摆法权重测试残局出牌排序。"""

    def play(self, bomb_wins, pass_wins, model_scores, plain_wins):
        import endgame
        import torch
        total = 100
        info = SimpleNamespace(
            player_position="landlord_down",
            player_hand_cards=[3, 3, 3, 3, 4],
            legal_actions=[[3, 3, 3, 3], []],
            num_cards_left_dict={s: 5 for s in SEATS},
            three_landlord_cards=[], card_play_action_seq=[], last_move=[], last_two_moves=[[], []],
            last_move_dict={s: [] for s in SEATS}, played_cards={s: [] for s in SEATS},
            bomb_num=0, last_pid=None, all_handcards=None)
        agent = ADVISOR.agents["landlord_down"]
        values = torch.tensor(model_scores)
        wins = {(3, 3, 3, 3): bomb_wins, (): pass_wins}
        plain = {(3, 3, 3, 3): plain_wins[0], (): plain_wins[1]}
        with mock.patch.object(agent, "values", return_value=values), \
                mock.patch.object(endgame, "best", return_value=(wins, total, plain, total)):
            return names(agent.act(info))

    def test_a_bomb_with_the_highest_weighted_win_chance_is_played(self):
        # 炸弹只赢 45 种摆法，但它赢的摆法权重更大，且加权赢面高过不出。
        self.assertEqual(["3", "3", "3", "3"], self.play(60, 55, [1.0, 9.0], (45, 60)))

    def test_equal_win_chances_use_the_douzero_score(self):
        for scores, expected in [([1.0, 9.0], []), ([9.0, 1.0], ["3", "3", "3", "3"])]:
            with self.subTest(scores=scores):
                self.assertEqual(expected, self.play(60, 60, scores, (60, 60)))

    def test_one_percentage_point_is_included_in_the_tie(self):
        self.assertEqual([], self.play(60, 59, [1.0, 9.0], (60, 59)))
        self.assertEqual(["3"] * 4, self.play(60, 58.99, [1.0, 9.0], (60, 59)))


class CardsTest(unittest.TestCase):
    """没配认牌权重时 /cards 回 503,其余接口不受影响(不用装 torchvision)。"""

    def test_without_weights_cards_are_unavailable(self):
        import http.client
        import threading
        from http.server import ThreadingHTTPServer
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler(ADVISOR, Cards(None)))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=10)
            conn.request("POST", "/cards", body=b"not a jpeg", headers={"Content-Type": "image/jpeg"})
            res = conn.getresponse()
            self.assertEqual(503, res.status)
            self.assertIn(b"cards_unavailable", res.read())
        finally:
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
