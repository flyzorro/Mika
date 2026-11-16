"""用 DouZero 自己的牌局随机发牌打到中途:只凭看得见的信息重建,出的牌要和拿完整牌局时一模一样。"""
import random
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from douzero.env.game import GameEnv

from server import (
    ACT_BUDGET_SAFETY_S, ACT_TOTAL_BUDGET_S, DECK, DOUBLE_WIN, NAMES, ROLLOUT_MIN_GAMES, SEATS, Advisor, Mismatch,
    WEIGHTS, bid_level, cards, fewer_plays, resolve, should_double,
)

# 造局面时关掉「接近时现场模拟」:随机走一步有近一半的顶两手分差在 0.1 以内,开着会让原有测试慢到不可用。
# 模拟本身由下面 CloseCallRolloutTest 用开着模拟的 ROLLOUT_ADVISOR 按公开入口测。
ADVISOR = Advisor(WEIGHTS, rollout=False)
ROLLOUT_ADVISOR = Advisor(WEIGHTS)


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


class LastCardTest(unittest.TestCase):
    def test_the_last_farmer_never_lets_a_one_card_landlord_lead(self):
        # 实机上的一手:地主只剩一张、单出了 A,下家不要;我手里的小王是场上最大的牌,DouZero 却选了不出
        history = [["6", "6", "6", "4"], ["2", "2", "2", "10"], [], [], ["J", "10", "9", "8", "7"], [], ["3", "3", "3", "3"], [], [],
                   ["9", "9", "9", "7"], [], ["4", "J", "J", "J"], [], [], ["5", "5"], ["8", "8"], ["Q", "Q"], [], ["K", "K"], [], [],
                   ["Q"], ["A"], [], ["2"], ["BJ"], [], [], ["5"], ["6"], ["A"], []]
        hand = ["SJ", "A", "K", "K", "Q", "10", "10", "8", "7", "7"]
        self.assertEqual(["SJ"], [NAMES[c] for c in ADVISOR.act("landlord_up", hand, ["A", "4", "7"], history)])


class TeammateTest(unittest.TestCase):
    def test_a_teammate_about_to_finish_is_not_beaten(self):
        # 实机上的一手:队友只剩三张、出了 2,地主只剩一张不要;DouZero 拿小王压了队友,再出单 9 让地主走了
        history = [["7", "6", "5", "4", "3"], [], [], ["J", "J", "J", "10", "10", "10", "Q", "9"], [], ["A", "A", "A", "K", "K", "K", "7", "3"], [], [], ["4", "4"], ["8", "8", "8", "8"], [], [], ["Q"], ["2"], [], [], ["2"], [], [], ["7", "7"], ["9", "9"], [], [], ["5"], ["K"], [], ["2"], []]
        hand = ["BJ", "SJ", "2", "Q", "J", "9", "6", "6", "6", "5", "4", "3", "3"]
        self.assertEqual([], ADVISOR.act("landlord_down", hand, [], history))


class KeepCombosTest(unittest.TestCase):
    """几手分数几乎一样时,优先不拆三条/对子/炸弹。按公开入口测。残局有结论时仍由残局搜索说了算(见 EndgameTest)。"""

    def test_a_near_tie_keeps_the_triple_and_beats_with_the_single_9(self):
        # 2026-11-14 13:44 实机:DouZero 拆了 10 10 10 里的 10 去顶队友的 6;单张 9 只差 0.009,不该拆
        history = [["3"], ["5", "5", "5", "5"], [], [], ["6"]]
        hand = ["BJ", "2", "K", "K", "Q", "10", "10", "10", "9", "8", "7", "6", "4", "4", "4", "3", "3"]
        self.assertEqual(["9"], [NAMES[c] for c in ADVISOR.act("landlord_up", hand, ["9", "2", "J"], history)])

    def test_a_clear_best_move_is_left_alone(self):
        # 最高分那手本来就不拆(一对 3),照旧
        hand = ["3", "3", "4", "4", "4", "6", "6", "8", "10", "J", "J", "Q", "K", "K", "K", "A", "A", "A", "2", "BJ"]
        self.assertEqual(["3", "3"], [NAMES[c] for c in ADVISOR.act("landlord", hand, ["A", "A", "A"], [])])

    def test_a_breaking_move_that_is_clearly_better_is_left_alone(self):
        # 最高分是拆一对 2 的单张 2;不拆的最好一手还差 0.15,超过 KEEP_MARGIN,照旧
        hand = ["3", "4", "4", "4", "5", "6", "7", "9", "10", "10", "10", "K", "K", "A", "2", "2"]
        history = [["3", "4", "5", "6", "7", "8"], [], [], ["3"], ["Q"], [], ["A"]]
        self.assertEqual(["2"], [NAMES[c] for c in ADVISOR.act("landlord_down", hand, ["K", "2"], history)])

    def test_a_breaking_play_is_not_replaced_by_a_pass(self):
        # 实机同一手:最高分是拆一对 2 的单张 2;不出虽在 margin 内,但规则只在出牌里挑,没有不拆的出牌就保持出牌
        hand = ["2", "2", "K", "K", "J", "J", "10", "10", "8", "7", "6", "5", "4", "4", "4", "3", "3"]
        history = [["6", "7", "8", "9", "10", "J", "Q", "K"], [], [], ["5"], [], ["J"], ["Q"]]
        self.assertEqual(["2"], [NAMES[c] for c in ADVISOR.act("landlord_down", hand, [], history)])

    def test_a_straight_that_breaks_a_pair_is_left_alone(self):
        # 最高分是拆了一对 5(和一对 6)的顺子;单张 3 只差 0.012,但规则只管单张/对子/三条,顺子不动
        hand = ["3", "4", "4", "4", "5", "5", "6", "6", "7", "8", "9", "9", "Q", "Q", "Q", "K", "A", "2", "2", "2"]
        self.assertEqual(["5", "6", "7", "8", "9"],
                         [NAMES[c] for c in ADVISOR.act("landlord", hand, ["Q", "K", "A"], [])])


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


class KickerTest(unittest.TestCase):
    """三带一/飞机带单的带牌是 A 或 2 时,换成手里最小的零散小单张。按公开入口测。"""

    # 2026-11-16 10:04 实机的一局:地主先出 6 6 6 + 3,下家 8 8 8 + 5,地主再出 10 10 10 + 7
    HISTORY = [["6", "6", "6", "3"], ["8", "8", "8", "5"], [], ["10", "10", "10", "7"], []]
    BOTTOM = ["6", "Q", "Q"]

    def test_the_single_a_kicker_is_replaced_with_the_smallest_loose_card(self):
        # 我 K K K + A:A 当带牌丢了控制牌,地主随后 2 2 2 + J 压住走完。3 是剩下的 3..Q 顺子的低端,
        # 拿走它顺子还有 4..Q,换成最小的 3
        hand = ["A", "K", "K", "K", "Q", "J", "J", "10", "9", "8", "7", "7", "6", "5", "4", "4", "3"]
        self.assertEqual(["K", "K", "K", "3"],
                         [NAMES[c] for c in ADVISOR.act("landlord_up", hand, self.BOTTOM, self.HISTORY)])

    def test_no_loose_single_keeps_the_a_kicker(self):
        # 5/6/8/9/10 都在 4..Q 顺子的中间,4 和 Q 成对:没有能换的零散小单张,照原样带 A
        hand = ["A", "K", "K", "K", "Q", "Q", "J", "J", "10", "9", "8", "7", "7", "6", "5", "4", "4"]
        self.assertEqual(["K", "K", "K", "A"],
                         [NAMES[c] for c in ADVISOR.act("landlord_up", hand, self.BOTTOM, self.HISTORY)])

    def test_a_triple_of_aces_is_not_a_kicker(self):
        # 三条本身是 A A A、带牌是 3:A 是三条用的,不是带牌,不动
        hand = ["A", "A", "A", "4", "4", "4", "5", "5", "7", "7", "9", "9", "J", "J", "Q", "Q", "3"]
        self.assertEqual(["3", "A", "A", "A"],
                         [NAMES[c] for c in ADVISOR.act("landlord_up", hand, self.BOTTOM, self.HISTORY)])

    def test_a_joker_kicker_is_left_alone(self):
        # 规则只管 A/2:单张王当带牌不换(大小王一起当带牌已在 without_double_joker_kickers 里剔掉)
        info = SimpleNamespace(player_hand_cards=cards(["K", "K", "K", "SJ", "3", "4"]),
                               legal_actions=[cards(["K", "K", "K", "SJ"]), cards(["K", "K", "K", "3"])])
        move = cards(["K", "K", "K", "SJ"])
        self.assertEqual(["K", "K", "K", "SJ"],
                         [NAMES[c] for c in ADVISOR.agents["landlord_up"].swap_high_kickers(info, move, lambda m: True)])

    def test_the_endgame_verdict_keeps_the_a_kicker(self):
        # 残局只剩 15 张,搜索算出来 K K K + A 在 4 种分法下都赢、K K K + 3 只赢 2 种:搜索说带 A 更好,
        # 不合规的那手不进 allowed,规则不换
        hand = ["K", "K", "K", "A", "3", "4", "5", "6", "7", "8", "8"]
        env = GameEnv({s: None for s in SEATS})
        env.card_play_init({"landlord": cards(["Q"]), "landlord_up": cards(hand),
                            "landlord_down": cards(["8", "SJ", "BJ"]), "three_landlord_cards": []})
        env.acting_player_position = "landlord_up"
        env.last_pid = "landlord"
        env.card_play_action_seq = [cards(["10", "10", "10", "7"]), []]
        info = env.get_infoset()
        self.assertEqual(["K", "K", "K", "A"], [NAMES[c] for c in ADVISOR.agents["landlord_up"].act(info)])


class EndgameTest(unittest.TestCase):
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

    def test_a_landlord_with_a_pair_left_who_leads_wins(self):
        import endgame, time
        solver = endgame.Solver(time.monotonic() + 1)
        hands = {"landlord": tuple(cards(["4", "4"])), "landlord_down": tuple(cards(["A"])), "landlord_up": tuple(cards(["2", "3"]))}
        self.assertTrue(solver.landlord_wins(hands, "landlord", (), "landlord"))
        # 轮到农民先出:下家出 A 走完
        self.assertFalse(solver.landlord_wins(hands, "landlord_down", (), "landlord_down"))


class MinPlaysTest(unittest.TestCase):
    """出完一手以后还剩几手:自己每次都先出,把牌拆成最少的几手合规牌(同 DouZero 的牌型,大小王不当带牌)。"""

    def test_min_plays_counts_the_cleanest_split(self):
        import endgame
        self.assertEqual(3, endgame.min_plays(cards(["BJ", "2", "J", "5", "5", "5"])))  # 555 带 J,再 2、BJ
        self.assertEqual(1, endgame.min_plays(cards(["3", "4", "5", "6", "7"])))  # 一手顺子
        self.assertEqual(1, endgame.min_plays(cards(["3", "3"])))  # 一对
        self.assertEqual(1, endgame.min_plays(cards(["BJ", "SJ"])))  # 王炸
        self.assertEqual(2, endgame.min_plays(cards(["3", "5", "5"])))  # 单张 + 一对

    def test_an_empty_hand_needs_no_plays(self):
        import endgame
        self.assertEqual(0, endgame.min_plays([]))

    def test_the_live_tie_hands_are_three_and_four_plays(self):
        # 2026-11-16 11:58:20:5..9 出完剩 BJ 2 J 5 5 5 4 四手,4..9 出完剩 BJ 2 J 5 5 5 三手
        import endgame
        hand = cards(["BJ", "2", "J", "9", "8", "7", "6", "5", "5", "5", "5", "4"])
        self.assertEqual(4, endgame.min_plays(endgame.minus(hand, cards(["5", "6", "7", "8", "9"]))))
        self.assertEqual(3, endgame.min_plays(endgame.minus(hand, cards(["4", "5", "6", "7", "8", "9"]))))


class FewerPlaysTest(unittest.TestCase):
    """模拟赢面不低于 DouZero 那手时才拿手数翻案:赢面更低的候选再干净也不换。"""

    # 2026-11-16 11:58:20 那手:5..9 出完剩 BJ 2 J 5 5 5 4 四手,4..9 出完剩 BJ 2 J 5 5 5 三手
    HAND = cards(["BJ", "2", "J", "9", "8", "7", "6", "5", "5", "5", "5", "4"])
    OWN = tuple(cards(["5", "6", "7", "8", "9"]))
    CLEANER = tuple(cards(["4", "5", "6", "7", "8", "9"]))

    def test_the_fewest_plays_wins_among_equal_rates(self):
        import endgame
        self.assertEqual(4, endgame.min_plays(endgame.minus(self.HAND, self.OWN)))
        self.assertEqual(3, endgame.min_plays(endgame.minus(self.HAND, self.CLEANER)))
        picked, plays = fewer_plays(self.HAND, [self.OWN, self.CLEANER],
                                    {self.OWN: 0.6, self.CLEANER: 0.6},
                                    {self.OWN: 1.0, self.CLEANER: 0.9}, self.OWN)
        self.assertEqual(self.CLEANER, picked)
        self.assertEqual({self.OWN: 4, self.CLEANER: 3}, plays)

    def test_a_cleaner_move_the_simulation_rates_worse_is_not_taken(self):
        self.assertIsNone(fewer_plays(self.HAND, [self.OWN, self.CLEANER],
                                      {self.OWN: 0.7, self.CLEANER: 0.6},
                                      {self.OWN: 1.0, self.CLEANER: 0.9}, self.OWN))


class FinishInTwoTest(unittest.TestCase):
    def test_a_safe_pair_then_the_last_card_beats_a_single_into_a_known_joker(self):
        # Live 2026-11-10 run 19: landlord holding 2 2 6 led a single 2 (the small joker is out there
        # and beats it) instead of 2 2 (only a K bomb can stop it) then 6. 29 cards left: too many for the search.
        history = [["4", "4"], ["8", "8"], [], ["9", "9"], [], [], ["Q", "Q"], [], [], ["5", "7", "10", "10", "10", "J", "J", "J"],
                   ["3", "3", "3", "3"], [], [], ["5"], ["2"], ["BJ"], [], [], ["A", "A"], [], []]
        play = ADVISOR.act("landlord", ["2", "2", "6"], ["5", "6", "7"], history)
        self.assertEqual(["2", "2"], [NAMES[c] for c in play])

    def test_an_easily_beaten_first_move_is_left_to_douzero(self):
        import endgame
        hand = cards(["3", "3", "6"])
        unknown = cards(["4", "4", "5", "5", "7", "7", "8", "8", "9", "9", "10", "10", "J", "J", "Q", "Q", "K", "K"])
        legal = [cards(m) for m in (["3"], ["6"], ["3", "3"])]
        # any pair beats 3 3: no safe first move
        self.assertIsNone(endgame.finish_in_two(hand, legal, (), unknown, 9, 9))


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
        # 0.54 还不到门槛不叫,0.55 到了就叫三分(门槛 0.55)
        self.assertEqual(0, bid_level(0.54))
        self.assertEqual(3, bid_level(0.55))

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
        self.assertGreaterEqual(ADVISOR.double("landlord_up", self.STRONG[:17], ["3", "4", "5"])[0], DOUBLE_WIN)

    def test_a_weak_farmer_hand_is_not_worth_doubling(self):
        self.assertLess(ADVISOR.double("landlord_down", self.WEAK[:17], ["2", "A", "K"])[0], DOUBLE_WIN)

    def test_a_wrong_hand_count_is_an_error(self):
        with self.assertRaises(Mismatch) as caught:
            ADVISOR.double("landlord", self.STRONG[:19], ["2", "A", "K"])
        self.assertIn("hand_count_mismatch", str(caught.exception))


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


class CloseCallRolloutTest(unittest.TestCase):
    """顶两手分数差不到 CLOSE_MARGIN 时,按看得见的牌随机发几百副、三家都用 DouZero 打分打到底,赢面见真章再定。
    按公开入口 ROLLOUT_ADVISOR.act 测(模拟默认开着)。"""

    # 2026-11-16 10:16 精英场:我是地主,下家农民单张 2 顶住。DouZero 给不出 +0.121、BJ +0.060,
    # 只差 0.06;实机信了分数选不出。模拟几百局后不出仍更好,这一手必须继续不出。
    LIVE = dict(seat="landlord",
                hand=["BJ", "2", "A", "A", "A", "K", "K", "Q", "J", "10", "10", "8", "6", "5", "5", "5", "5", "3", "3"],
                bottom=["7", "K", "6"],
                history=[["7"], ["10"], ["2"]])

    # 离线从随机牌局里搜到的一手:DouZero 最高分是单张 K、第二是单张 7,只差 0.011;
    # 模拟几百局后 7 的赢面明显更高(0.61 对 0.52),要换成第二手。
    SECOND = dict(seat="landlord",
                  hand=["3", "3", "3", "6", "6", "7", "K", "A", "A", "2", "2"],
                  bottom=["6", "A", "2"],
                  history=[["10"], [], ["A"], ["2"], [], [], ["4"], [], ["7"], ["J"], [], ["2"], [], [],
                           ["9", "9", "9", "10", "10"], ["8", "8", "Q", "Q", "Q"], [], []])

    # 最高分和第二名差 0.306,远超 CLOSE_MARGIN:不模拟,照旧出那手六张顺子。
    CLEAR = dict(seat="landlord",
                 hand=["9", "10", "10", "J", "Q", "K", "K", "A", "SJ", "BJ"],
                 bottom=["8", "9", "A"],
                 history=[["7", "7", "7", "8", "8"], [], [], ["4", "4", "5", "5", "5"], [], []])

    # 2026-11-16 10:58:14 精英场:我是地主的第一手。开局每局都长,2.5 秒只跑了 43 局,
    # 不到 60 局就白模拟了;单张 9 赢面高 13 个点也没换成。开局下限降到 30 局后应该换成 9。
    OPENING = dict(seat="landlord",
                   hand=["2", "2", "A", "K", "K", "Q", "Q", "J", "9", "8", "8", "8", "7", "7", "6", "5", "5", "5", "5", "4"],
                   bottom=["9", "4", "7"],
                   history=[])

    # 2026-11-16 11:43:53 土豪场:我是地主下家,手里 2 2 K Q J 10 9 8 8 8 4 3 3,三家还剩 33 张。
    # DouZero 给 8 9 10 J Q K +0.736、8 9 10 J Q +0.627,只差 0.109,按 CLOSE_MARGIN 不模拟就输了;
    # 按看得见的牌打 400 局 3 3 8 8 8 赢 0.98、8..K 只赢 0.82。后半局要模拟分数最高的 8 手。
    LATE = dict(seat="landlord_down",
                hand=["2", "2", "K", "Q", "J", "10", "9", "8", "8", "8", "4", "3", "3"],
                bottom=["8", "2", "4"],
                history=[["5"], ["6"], ["9"], ["Q"], ["K"], [], ["SJ"], [], ["BJ"], [], [],
                         ["K", "Q", "J", "10", "9"], ["A", "K", "Q", "J", "10"], [], [], ["3", "3"], ["7", "7"], [], []])

    # 后半局但顶两手差得开(0.44)、最高分那手也确实最好:照样模拟一遍,仍出原来那手。
    # 三家正好还剩 35 张,是后半局的上边界。
    LATE_CLEAR = dict(seat="landlord",
                      hand=["4", "4", "4", "A", "A"],
                      bottom=[],
                      history=[["5", "6", "7", "8", "9", "10", "J", "Q", "K"], [], [], ["10"], ["J"], ["Q"], ["SJ"], [], ["BJ"], [], [],
                               ["A"], ["2"], [], [], ["Q", "Q"], [], [], ["2"], [], []])

    # 2026-11-16 11:58:20 土豪场:我是地主上家,领出,手里 BJ 2 J 9 8 7 6 5 5 5 5 4。
    # DouZero 出 5 6 7 8 9,模拟 152 局 5..9 赢 0.59、4..9 赢 0.64,差不到 0.1 的门槛仍出 5..9;
    # 可 4..9 出完剩 BJ 2 J 5 5 5 三手,5..9 剩 BJ 2 J 5 5 5 4 四手,该出 4..9。
    TIEBREAK = dict(seat="landlord_up",
                    hand=["BJ", "2", "J", "9", "8", "7", "6", "5", "5", "5", "5", "4"],
                    bottom=[],
                    history=[["9"], ["10"], ["Q"], ["2"], [], ["3", "3", "3", "3"], [], []])

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report)

    def test_the_live_close_call_keeps_pass(self):
        report = {}
        self.assertEqual([], self.act(self.LIVE, report))
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手只差 0.06,应该现场模拟")
        self.assertIn("pass", rollout["rates"])
        self.assertIn("BJ", rollout["rates"])
        self.assertEqual("pass", rollout["chosen"])

    def test_a_near_tie_prefers_the_move_that_leaves_fewer_plays(self):
        # 模拟赢面差不到门槛时,手数更少的一手胜出:4..9 剩三手,5..9 剩四手。
        # 2.5 秒里多打一两局就能把赢面差顶过 0.05(150 局差 0.047,152 局差 0.053),固定局数让这条每次都走比手数那条路。
        import server
        report = {}
        with mock.patch.object(server, "ACT_ROLLOUT_GAMES", 150), \
                mock.patch.object(server, "ACT_ROLLOUT_BUDGET_S", 60.0):
            move = self.act(self.TIEBREAK, report)
        self.assertEqual(["4", "5", "6", "7", "8", "9"], [NAMES[c] for c in move])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手差不到门槛,应该现场模拟")
        self.assertEqual("fewer_plays", rollout.get("tiebreak"))
        self.assertEqual(3, rollout["plays"]["4 5 6 7 8 9"])
        self.assertEqual(4, rollout["plays"]["5 6 7 8 9"])
        self.assertEqual("4 5 6 7 8 9", rollout["chosen"])

    def test_a_close_call_the_rollout_prefers_the_second_move(self):
        report = {}
        move = self.act(self.SECOND, report)
        self.assertEqual(["7"], [NAMES[c] for c in move])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手只差 0.011,应该现场模拟")
        self.assertIn("K", rollout["rates"])
        self.assertEqual("7", rollout["chosen"])
        self.assertGreater(rollout["rates"]["7"], rollout["rates"]["K"])

    def test_an_opening_close_call_uses_the_lower_floor(self):
        report = {}
        move = self.act(self.OPENING, report)
        self.assertEqual(["9"], [NAMES[c] for c in move])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "开局顶两手应该现场模拟")
        self.assertLess(rollout["games"], 60)
        self.assertEqual(30, rollout["min_games"])
        self.assertEqual("9", rollout["chosen"])

    def test_a_mid_game_close_call_keeps_the_sixty_floor(self):
        report = {}
        self.act(self.SECOND, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手只差 0.011,应该现场模拟")
        self.assertEqual(60, rollout["min_games"])

    def test_a_clear_best_move_does_not_roll_out(self):
        report = {}
        move = self.act(self.CLEAR, report)
        self.assertNotIn("rollout", report)
        self.assertEqual(["9", "10", "J", "Q", "K", "A"], [NAMES[c] for c in move])
        # 不模拟时与关掉模拟的 advisor 完全一样
        self.assertEqual(sorted(ADVISOR.act(self.CLEAR["seat"], self.CLEAR["hand"], self.CLEAR["bottom"],
                                            self.CLEAR["history"])), sorted(move))

    def test_a_close_call_answers_within_the_turn_clock(self):
        started = time.monotonic()
        self.act(self.LIVE)
        self.assertLess(time.monotonic() - started, 3.0)

    def test_the_same_request_gets_the_same_move(self):
        first, second = {}, {}
        a, b = self.act(self.SECOND, first), self.act(self.SECOND, second)
        self.assertEqual(sorted(a), sorted(b))
        self.assertEqual(first["rollout"]["chosen"], second["rollout"]["chosen"])

    def test_the_late_live_call_rolls_out_the_top_eight(self):
        # 顶两手只差 0.109,平时不模拟;后半局(33 张)模拟分数最高的 8 手,3 3 8 8 8 该赢
        report = {}
        self.assertEqual(["3", "3", "8", "8", "8"], [NAMES[c] for c in self.act(self.LATE, report)])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "后半局不管分差都要现场模拟")
        self.assertTrue(rollout.get("late"))
        self.assertGreaterEqual(rollout["games"], 60)
        self.assertEqual("3 3 8 8 8", rollout["chosen"])
        self.assertGreaterEqual(len(rollout["rates"]), 8, "后半局要模拟分数最高的 8 手")

    def test_a_late_clear_call_still_rolls_out_and_keeps_the_top_move(self):
        # 44 张的 CLEAR 分差 0.306 不模拟;这里正好 35 张、分差 0.44,照样模拟,但最高分那手本来就最好,不换
        report = {}
        move = self.act(self.LATE_CLEAR, report)
        self.assertEqual(["4", "4", "4", "A", "A"], [NAMES[c] for c in move])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "后半局分差再大也要现场模拟")
        self.assertTrue(rollout.get("late"))
        self.assertEqual("4 4 4 A A", rollout["chosen"])
        self.assertEqual(sorted(ADVISOR.act(self.LATE_CLEAR["seat"], self.LATE_CLEAR["hand"],
                                            self.LATE_CLEAR["bottom"], self.LATE_CLEAR["history"])), sorted(move))

    def test_a_clear_move_over_the_late_window_does_not_roll_out(self):
        # CLEAR 三家还剩 44 张、顶两手差 0.306:不在后半局,不模拟
        report = {}
        self.act(self.CLEAR, report)
        self.assertNotIn("rollout", report)

    def test_the_late_call_answers_within_the_turn_clock(self):
        started = time.monotonic()
        self.act(self.LATE)
        self.assertLess(time.monotonic() - started, 3.0)


class ActBudgetTest(unittest.TestCase):
    """一手牌总共只给 ACT_TOTAL_BUDGET_S 秒:残局搜索、两手出完、现场模拟都在里面分,手机 4 秒不会超时。
    按公开入口 ROLLOUT_ADVISOR.act 测(模拟默认开着)。"""

    # 2026-11-16 12:18:11 土豪场实机:我是地主下家,手里 A 5 4 4 4,三家还剩 19 张。
    # 残局搜索 1.5 秒没结论、现场模拟又 2.5 秒,回复 4903 毫秒;手机 4 秒超时,标成 douzero_unreachable
    # 重发同一手,三次各约 4020 毫秒,最后按简单规则打完这一局。
    LOGGED = dict(seat="landlord_up",
                  hand=["A", "5", "4", "4", "4"],
                  bottom=["4", "7", "J"],
                  history=[["7", "7", "7", "4"], [], [], ["8", "8"], [], ["Q", "Q"], ["K", "K"], [], ["2", "2"], [], [],
                           ["8"], ["9"], ["J"], ["K"], [], [], ["6", "6", "6", "10", "10"], ["J", "J", "J", "10", "10"],
                           ["A", "A", "A", "5", "5"], [], [], ["6"], ["9"], ["Q"], ["K"]])

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report)

    def test_the_logged_request_answers_inside_the_phone_clock(self):
        # 实机那一手回复 4903 毫秒,手机 4 秒就超时;总预算封顶后要在 3.5 秒内答完
        started = time.monotonic()
        self.act(self.LOGGED)
        self.assertLess(time.monotonic() - started, 3.5)

    def test_a_search_that_burns_the_clock_leaves_no_time_for_the_rollout(self):
        # 残局搜索睡掉 2.6 秒、又没有结论:剩下的时间不到 ACT_ROLLOUT_MIN_S,现场模拟整个跳过,保留 DouZero 那一手
        import endgame
        real = endgame.best

        def slow_best(*args, **kwargs):
            time.sleep(2.6)
            return None

        endgame.best = slow_best
        try:
            report = {}
            started = time.monotonic()
            self.act(CloseCallRolloutTest.LATE, report)
            elapsed = time.monotonic() - started
        finally:
            endgame.best = real
        self.assertLess(elapsed, 3.5)
        self.assertEqual("no_time", report.get("rollout_skipped"))
        self.assertNotIn("rollout", report)

    def test_a_search_that_uses_its_own_budget_still_leaves_room_for_the_rollout(self):
        # 残局搜索睡满自己的 1.5 秒:总预算还剩 1.6 秒左右,现场模拟照常跑,只是模拟得少些
        import endgame
        real = endgame.best

        def slow_best(*args, **kwargs):
            time.sleep(1.5)
            return None

        endgame.best = slow_best
        try:
            report = {}
            started = time.monotonic()
            self.act(CloseCallRolloutTest.LIVE, report)
            elapsed = time.monotonic() - started
        finally:
            endgame.best = real
        self.assertLess(elapsed, 3.5)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "残局搜索用掉 1.5 秒后还剩 1.6 秒,应该照常现场模拟")
        self.assertGreater(rollout["budget_left"], 1.2)
        self.assertLess(rollout["budget_left"], 1.8)

    def test_a_full_search_still_leaves_room_for_the_tiebreak(self):
        # 残局搜索睡满 1.5 秒、现场模拟再吃掉剩下的:比手数(最多 0.2 秒)也要在 3.5 秒内收尾。
        # 模拟截止时间要先给比手数留出 0.2 秒,比手数的 deadline 也不能超过总预算剩下的。
        import endgame
        import server
        real_best, real_min_plays = endgame.best, endgame.min_plays
        seen = []

        def slow_best(*args, **kwargs):
            time.sleep(1.5)
            return None

        def recording_min_plays(hand, deadline=None):
            seen.append((time.monotonic(), deadline))
            return 3

        endgame.best = slow_best
        endgame.min_plays = recording_min_plays
        try:
            # 只有模拟赢面差不到门槛才比手数;把门槛调大,固定走比手数那条路
            with mock.patch.object(server, "SWITCH_MARGIN", 2.0), \
                    mock.patch.object(server, "OPENING_SWITCH_MARGIN", 2.0):
                report = {}
                started = time.monotonic()
                self.act(CloseCallRolloutTest.TIEBREAK, report)
                elapsed = time.monotonic() - started
        finally:
            endgame.best, endgame.min_plays = real_best, real_min_plays
        self.assertLess(elapsed, 3.5)
        self.assertTrue(seen, "模拟没换手时应该比手数")
        called_at, deadline = seen[0]
        self.assertGreater(deadline - called_at, 0.1, "模拟截止时间要给比手数留出余量")
        self.assertLessEqual(deadline, started + ACT_TOTAL_BUDGET_S - ACT_BUDGET_SAFETY_S + 0.01)


if __name__ == "__main__":
    unittest.main()
