"""用 DouZero 自己的牌局随机发牌打到中途:只凭看得见的信息重建,出的牌要和拿完整牌局时一模一样。"""
import random
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from douzero.env import move_detector as md
from douzero.env.game import GameEnv

from server import (
    ACT_BUDGET_SAFETY_S, ACT_ENDGAME_BUDGET_S, ACT_ROLLOUT_BUDGET_S, ACT_ROLLOUT_MIN_S, ACT_TOTAL_BUDGET_S, DECK,
    DOUBLE_WIN, FARMER_DOUBLE_WIN, NAMES, ROLLOUT_MIN_GAMES, SEATS, SUPER_DOUBLE_WIN, Advisor, Mismatch, WEIGHTS,
    Cards, bid_level, cards, fewer_plays, handler, is_bomb, move_label, parse_auction, resolve, should_double,
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


def has_bomb(hand):
    """[hand] 里有没有炸弹 / 王炸。"""
    counts = {card: list(hand).count(card) for card in set(hand)}
    return any(n >= 4 for card, n in counts.items() if card < 20) or (20 in hand and 30 in hand)


def short_bomb_mass(position, advisor=ADVISOR, auction=None, only_short=False, counted=True):
    """牌最少的那家对手,按 [advisor].likelihood 加权后手里有炸弹的概率。

    [only_short] 为真时只看他的出牌(同 Advisor.short_hand_plan):另一家很早以前的出牌
    会在两百多种分法里把权重整个带走。[counted] 为假时每种不同的剩牌算一个假设,
    不再按同点数的摆法数放大(同 short_hand_plan)。
    """
    import endgame
    seat, history, current, left, unseen, _ = request_split(position)
    others = [s for s in SEATS if s != seat]
    short = min(others, key=lambda s: left[s])
    far = others[0] if others[1] == short else others[1]
    ways = endgame.splits(unseen, short, left[short], far, left[far])
    if ways is None:
        raise AssertionError("分法太多,这条测试请求不该枚举不完")
    splits = [{seat: tuple(current[seat]), short: ha, far: hb} for ha, hb in ways]
    likes = advisor.likelihood(history, seat, auction, seats=[short] if only_short else None)(
        splits, time.monotonic() + 30)
    total = bomb = 0.0
    for ((ha, _), count), like in zip(ways.items(), likes):
        weight = (count if counted else 1.0) * like
        total += weight
        if has_bomb(ha):
            bomb += weight
    return bomb / total


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


class KickerChoiceTest(unittest.TestCase):
    """2026-11-19 实机五手三带一:带牌比手里最小的零散单张大,或者拆了对子 / 三条,就换成最小的零散单张,不只 A/2。

    DouZero 自己的出牌用打桩的 values 钉成当时回复的那手(实机是现场模拟选的,重跑不保证一样);
    act 末尾的换带牌规则照旧,按公开入口 Advisor.act 测。
    """

    def advise(self, seat, hand, bottom, history, chosen):
        import torch
        agent = ADVISOR.agents[seat]
        target = sorted(cards(chosen))

        def values(info):
            scores = torch.zeros(len(info.legal_actions))
            for i, action in enumerate(info.legal_actions):
                if sorted(action) == target:
                    scores[i] = 100.0
            return scores

        with mock.patch.object(agent, "values", values):
            return names(ADVISOR.act(seat, hand, bottom, history))

    def test_a_q_kicker_is_replaced_with_the_loose_5(self):
        # 12:06:46 地主:JJJ+Q,Q 是单张、比手里最小的散牌 5 大
        hand = ["BJ", "SJ", "2", "2", "A", "A", "A", "Q", "J", "J", "J",
                "10", "10", "10", "9", "9", "8", "7", "6", "5"]
        self.assertEqual(sorted(["J", "J", "J", "5"]),
                         sorted(self.advise("landlord", hand, [], [], ["J", "J", "J", "Q"])))

    def test_a_k_kicker_is_replaced_with_the_loose_8(self):
        # 13:35:08 我是地主下家:AAA+K,K 是单张、比手里最小的散牌 8 大
        hand = ["2", "A", "A", "A", "K", "Q", "Q", "10", "9", "9", "8", "7", "7", "7", "7", "4", "4"]
        history = [["Q", "Q", "J", "J", "10", "10", "9", "9", "8", "8"], [], [], ["6", "6", "6", "3"]]
        self.assertEqual(sorted(["A", "A", "A", "8"]),
                         sorted(self.advise("landlord_down", hand, [], history, ["K", "A", "A", "A"])))

    def test_a_10_kicker_from_a_pair_is_replaced_with_the_loose_4(self):
        # 13:14:37 地主:555+10,10 在手里是一对,换成最小的散牌 4
        hand = ["SJ", "2", "2", "A", "A", "K", "Q", "J", "10", "10",
                "9", "8", "8", "8", "6", "6", "5", "5", "5", "4"]
        self.assertEqual(sorted(["5", "5", "5", "4"]),
                         sorted(self.advise("landlord", hand, [], [], ["5", "5", "5", "10"])))

    def test_a_10_kicker_from_a_pair_is_replaced_with_the_loose_3(self):
        # 15:37:58 我是地主上家:444+10,10 在手里是一对,换成最小的散牌 3
        hand = ["BJ", "K", "K", "Q", "J", "10", "10", "8", "7", "6", "6",
                "5", "5", "4", "4", "4", "3"]
        bottom = ["6", "10", "A"]
        history = [["Q", "J", "10", "9", "8", "7", "6"], [], [], ["3", "3", "3", "7"], []]
        self.assertEqual(sorted(["4", "4", "4", "3"]),
                         sorted(self.advise("landlord_up", hand, bottom, history, ["4", "4", "4", "10"])))

    def test_a_paired_9_kicker_with_no_loose_card_is_kept(self):
        # 12:06:59 地主:101010+9。9 在手里是一对,但 5~9 是五连顺,拿掉哪张都拆不出顺子:
        # 没有可换的零散单张,保持原样(反例)
        hand = ["BJ", "SJ", "2", "2", "A", "A", "A", "10", "10", "10", "9", "9", "8", "7", "6", "5"]
        history = [["J", "J", "J", "Q"], [], []]
        self.assertEqual(sorted(["9", "10", "10", "10"]),
                         sorted(self.advise("landlord", hand, [], history, ["9", "10", "10", "10"])))

    def test_a_paired_3_kicker_is_replaced_by_a_bigger_loose_4(self):
        # 构造的局面:(b) 单独生效——带牌 3 是手里的一对、比最小的散牌 4 还小,仍换成 4,不拆对子
        hand = ["3", "3", "4", "5", "5", "5", "6", "7", "8", "9", "10", "J",
                "Q", "K", "A", "2", "K", "Q", "A", "2"]
        self.assertEqual(sorted(["5", "5", "5", "4"]),
                         sorted(self.advise("landlord", hand, [], [], ["5", "5", "5", "3"])))

    def test_a_plane_replaces_both_single_kickers_in_order(self):
        # 构造的局面:飞机带单 333444+QK,两张带牌都换成最小的零散单张,依次是 5、6
        hand = ["3", "3", "3", "4", "4", "4", "5", "6", "7", "8", "9", "10",
                "J", "Q", "K", "A", "2", "2", "2", "A"]
        self.assertEqual(sorted(["3", "3", "3", "4", "4", "4", "5", "6"]),
                         sorted(self.advise("landlord", hand, [], [], ["3", "3", "3", "4", "4", "4", "Q", "K"])))


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

    def test_the_last_pair_is_not_treated_as_a_random_pair(self):
        # 2026-11-16 20:51:41 土豪场,我是地主:手里 BJ SJ 2 Q 9 9,下家四张时先出 7 7 只剩两张,
        # 上家剩六张,没露面的是 4 9 K K A A 2 2。搜索把任意两张当成一样可能,算出 9 9 赢的分法最多;
        # 可刚主动出过对子的人最后两张多半还是一对,只有王炸能赢 KK。
        # 2026-11-18 残局改成按金额比后:likelihood 给王炸的赢面只有权重 ~0.0001,
        # 王炸值 = 2×(2×0.0001−1) ≈ −2,9 9 值 = −1,不炸少输一半,所以这里出 9 9。
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

    三家剩 7 / 6 / 5 张,462 种分法,带 likelihood 在本机实测 1.58~1.84 秒;原来给的 1.5 秒正好差一点,
    搜索超时返回 None,退回按均匀发牌的现场模拟,选了 J。明牌求解只有 6 6 能赢。按公开入口 ROLLOUT_ADVISOR.act 测。
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

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report)

    def test_the_search_finishes_in_time_and_picks_the_pair(self):
        # 搜索算完了就以它为准:出 6 6,不再退回按均匀发牌的现场模拟
        report = {}
        started = time.monotonic()
        move = self.act(self.LIVE, report)
        elapsed = time.monotonic() - started
        self.assertEqual(["6", "6"], names(move))
        self.assertNotIn("rollout", report, "残局搜索算完了不该退回现场模拟")
        self.assertLess(elapsed, ACT_TOTAL_BUDGET_S + 0.2)


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

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report)

    def test_the_search_runs_when_only_the_opponents_are_short(self):
        report = {}
        move = self.act(self.LIVE, report)
        self.assertEqual(["5", "5", "5", "5"], names(move))
        self.assertNotIn("rollout", report, "残局搜索算完了就以它为准,不再现场模拟")

    def test_an_existing_endgame_result_is_unchanged(self):
        # 三家合计 18(7/6/5)本来就走残局搜索:门槛只放宽,结果还是 6 6
        report = {}
        live = EndgameBudgetTest.LIVE
        move = ROLLOUT_ADVISOR.act(live["seat"], live["hand"], live["bottom"], live["history"], report)
        self.assertEqual(["6", "6"], names(move))
        self.assertNotIn("rollout", report)

    def test_the_turn_stays_inside_the_budget(self):
        started = time.monotonic()
        self.act(self.LIVE)
        self.assertLess(time.monotonic() - started, ACT_TOTAL_BUDGET_S)


class BombRestraintTest(unittest.TestCase):
    """残局不拿「猜牌猜出来的赢面」当翻倍理由:炸弹 / 王炸在加权金额上最高,可不信猜牌、
    只按摆法数算时还不如非炸弹,就不炸。按公开入口 ROLLOUT_ADVISOR.act 测两处实机请求。

    2026-11-19 10:53:07 普通场,我是地主上家(农民),手 14 张,地主剩 4 张、队友剩 5 张;看不见的 9 张是
    4 4 9 J A A 2 2 BJ,地主之前已经把 2 2 2 2 拆开打过两次单张 2。残局按加权赢面 99% 出炸弹 5 5 5 5:
    total 18.725、不出 wins 18.7249、5 5 5 5 wins 18.563,金额 2×(2×18.563−18.725)≈36.8 > 18.7,
    于是炸了队友(地主手里正是 2 2),翻倍输掉。温度 0.02 的 softmax 把「地主还拿着另外两张 2」的分法
    压到 0~1.7e-5,赢面 99% 是假的。改后:似然有下限,炸弹还要过「只按摆法数」这一关(这边 87/126 对
    不出 120/126),于是不出。
    """

    # 10:53:07 的原始请求(照抄服务日志):炸弹这一手。
    BOMB = dict(seat="landlord_up",
                hand=["K", "8", "8", "7", "7", "7", "6", "5", "5", "5", "5", "4", "4", "3"],
                bottom=[],
                history=[["3", "3"], ["6", "6"], [], ["A", "A"], [], [], ["10", "10", "9", "9", "8", "8"],
                         ["Q", "Q", "J", "J", "10", "10"], [], [], ["3"], ["Q"], ["K"], [], [], ["6"], ["7"],
                         ["9"], ["J"], ["K"], [], ["2"], [], [], ["Q"], [], ["K"], ["2"], ["SJ"]],
                auction={"double": {"landlord": False, "landlord_down": False}})

    # 10:53:14 的原始请求:炸弹之后我领出。改前残局按加权的 99% 领出 8 8,地主 2 2 接住走完;
    # 改后 6 7 7 7 / 3 7 7 7 的金额 19.018 高过 8 8 的 18.918。不把具体出法钉死,只要求别再把 8 8
    # 送进 2 2(那 0.1 的差就是猜牌猜出来的)。
    FOLLOW_UP = dict(seat="landlord_up",
                     hand=["K", "8", "8", "7", "7", "7", "6", "4", "4", "3"],
                     bottom=[],
                     history=[["3", "3"], ["6", "6"], [], ["A", "A"], [], [], ["10", "10", "9", "9", "8", "8"],
                              ["Q", "Q", "J", "J", "10", "10"], [], [], ["3"], ["Q"], ["K"], [], [], ["6"], ["7"],
                              ["9"], ["J"], ["K"], [], ["2"], [], [], ["Q"], [], ["K"], ["2"], ["SJ"],
                              ["5", "5", "5", "5"], [], []],
                     auction={"double": {"landlord": False, "landlord_down": False}})

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report,
                                   auction=parse_auction(position.get("auction")))

    def test_the_bomb_is_not_played_on_guessed_splits(self):
        report = {}
        started = time.monotonic()
        move = self.act(self.BOMB, report)
        self.assertEqual([], move)
        self.assertEqual("5 5 5 5", report.get("bomb_veto"))
        self.assertLess(time.monotonic() - started, ACT_TOTAL_BUDGET_S)

    def test_the_next_lead_does_not_walk_into_the_two_twos(self):
        report = {}
        move = self.act(self.FOLLOW_UP, report)
        self.assertNotEqual(["8", "8"], names(move))


class BombVetoRuleTest(unittest.TestCase):
    """加权金额最高的炸弹,若不加权金额不如备选,或赢的摆法数落后太多,就改出非炸弹。
    用打桩的 endgame.best 单独测这条规则。"""

    def play(self, bomb_wins, pass_wins, bomb_plain, pass_plain, total=100):
        import endgame
        import torch
        info = SimpleNamespace(
            player_position="landlord_down",
            player_hand_cards=[3, 3, 3, 3, 4],
            legal_actions=[[3, 3, 3, 3], []],
            num_cards_left_dict={s: 5 for s in SEATS},
            three_landlord_cards=[], card_play_action_seq=[], last_move=[], last_two_moves=[[], []],
            last_move_dict={s: [] for s in SEATS}, played_cards={s: [] for s in SEATS},
            bomb_num=0, last_pid=None, all_handcards=None)
        agent = ADVISOR.agents["landlord_down"]
        # DouZero 永远更想炸:炸弹 9 分、不出 1 分;选哪手只由残局搜索的金额决定
        values = torch.tensor([9.0, 1.0])
        wins = {(3, 3, 3, 3): bomb_wins * total, (): pass_wins * total}
        plain = {(3, 3, 3, 3): bomb_plain * total, (): pass_plain * total}
        report = {}
        with mock.patch.object(agent, "values", return_value=values), \
                mock.patch.object(endgame, "best", return_value=(wins, total, plain, total)):
            move = agent.act(info, report)
        return names(move), report

    def test_a_bomb_that_only_wins_on_guessed_splits_is_dropped(self):
        # 加权:炸弹 0.9 对不出 0.5,炸弹金额 2×(2×0.9−1) = 1.6 最高;只按摆法数炸弹 40% 对不出 60%,
        # 炸弹金额 2×(2×0.4−1) = −0.4 < 不出 0.2,不炸
        move, report = self.play(0.9, 0.5, 0.4, 0.6)
        self.assertEqual([], move)
        self.assertEqual("3 3 3 3", report.get("bomb_veto"))

    def test_a_bomb_that_also_wins_without_guessing_is_kept(self):
        # 只按摆法数炸弹 0.7 对不出 0.6,炸弹金额 0.8 > 不出 0.2:炸弹本身站得住,照旧炸
        move, report = self.play(0.9, 0.5, 0.7, 0.6)
        self.assertEqual(["3", "3", "3", "3"], move)
        self.assertNotIn("bomb_veto", report)

    def test_a_bomb_that_wins_fewer_splits_than_pass_is_dropped(self):
        # 2026-11-20 精英场:队友剩 2 张出 JJJ9、地主不出,不出 36/36 全赢,5555 只赢 28/36。
        # 炸弹金额 2×(2×28−36)/36 ≈ 1.1 > 不出 1.0,但赢面少了约 22 个百分点,不炸
        move, report = self.play(0.94, 1.0, 28 / 36, 1.0, total=36)
        self.assertEqual([], move)
        self.assertEqual("3 3 3 3", report.get("bomb_veto"))

    def test_a_bomb_with_a_small_win_deficit_and_higher_amount_is_kept(self):
        move, report = self.play(17 / 21, 19 / 21, 17 / 21, 19 / 21, total=21)
        self.assertEqual(["3", "3", "3", "3"], move)
        self.assertNotIn("bomb_veto", report)

    def test_a_bomb_that_wins_more_splits_but_less_plain_amount_is_dropped(self):
        # 只按摆法数炸弹 45% 对不出 44%:炸弹赢得多,摆法数这条不拦;
        # 但炸弹金额 2×(0.9−1) = −0.2 < 不出 −0.12,金额那条照样不炸
        move, report = self.play(0.9, 0.5, 0.45, 0.44)
        self.assertEqual([], move)
        self.assertEqual("3 3 3 3", report.get("bomb_veto"))

    def test_a_vetoed_bomb_tied_on_amount_is_not_played(self):
        # 炸弹 75%、不出 100%:金额都是 1.0 并列,DouZero 首选炸弹;否决后不能因为并列又把炸弹放回来
        move, report = self.play(0.75, 1.0, 0.75, 1.0)
        self.assertEqual([], move)
        self.assertEqual("3 3 3 3", report.get("bomb_veto"))


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
        callback = ROLLOUT_ADVISOR.likelihood(history, "landlord_up", None)
        likes = callback(self.splits(), time.monotonic() + 5)
        self.assertEqual(1.0, max(likes))
        self.assertEqual(0.2, min(likes))


class KeepBombLikelihoodTest(unittest.TestCase):
    """对手留着炸弹时,他出的杂牌按「炸弹先放一边」打分:真人把炸弹留到最后,先走杂牌。

    2026-11-19 15:06 精英场,我是地主、超级加倍,输光 860 万。下家连出 9 8 7 6 5、K Q J 10 9,
    剩 5 张时领单张 J,最后 4 张是 4 4 4 4。下面是 15:07:50 / 15:08:02 两条实机请求(照抄 /act)。
    改前按两家 DouZero 出牌似然加权,P(下家剩的 4 张是炸弹) 只有 0.0003(均匀 0.0004)。
    这两条请求里 DouZero 本来就觉得下家那两手很像(整手打分 log 0.0),所以「留炸弹」规则本身
    在这手不加分;真正把炸弹抬起来的是只看他自己的出牌、每种剩牌一个假设(见 report.md)。
    """

    HAND = ["BJ", "SJ", "2", "K", "K", "7", "7", "5", "5", "5"]
    BOTTOM = ["7", "K"]
    BEFORE_J = [["3", "8", "8", "8"], [], ["10", "10", "10", "7"], ["Q", "A", "A", "A"], [], [],
                ["9", "9"], ["2", "2"], [], [], ["9", "8", "7", "6", "5"], [], [],
                ["K", "Q", "J", "10", "9"], []]
    AFTER_J = BEFORE_J + [[], ["J"], []]
    AUCTION = {"double": {"landlord_down": False, "landlord_up": False}}

    def position(self, history):
        return dict(seat="landlord", hand=self.HAND, bottom=self.BOTTOM, history=history)

    def auction(self):
        import server as server_module
        return server_module.parse_auction(self.AUCTION)

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

    def test_the_short_hand_bomb_is_no_longer_nearly_impossible(self):
        # 15:08:02:下家只剩 4 张,实际就是 4 4 4 4。改前按两家似然加权只有 0.0003;
        # 207 种分法里它仍是唯一最像的;下限 0.2 时后验约 2.08%,高于均匀分法的约 0.48%。
        mass = short_bomb_mass(self.position(self.AFTER_J), advisor=ADVISOR, auction=None,
                               only_short=True, counted=False)
        self.assertGreaterEqual(mass, 0.02, f"P(下家剩的 4 张是炸弹) 只有 {mass:.4f}")


class ShortHandSamplingTest(unittest.TestCase):
    """对手牌少、又没进残局搜索时,现场模拟按枚举加权发牌,不再均匀随机。

    用 15:08:02 那条请求:下家只剩 4 张,实际是 4 4 4 4。按公开入口 Advisor.sample_hands 抽 1000 手,
    他这 4 张是炸弹的次数要接近似然权重,而不是均匀发牌时的千分之一。
    """

    POSITION = KeepBombLikelihoodTest().position(KeepBombLikelihoodTest.AFTER_J)

    def infoset(self):
        seat, history, current, left, unseen, played = request_split(self.POSITION)
        return SimpleNamespace(
            player_position=seat, player_hand_cards=current[seat], other_hand_cards=unseen,
            num_cards_left_dict=left, three_landlord_cards=cards(self.POSITION["bottom"]),
            played_cards=played, card_play_action_seq=[list(m) for m in history],
            last_move=[], last_two_moves=[[], []], last_move_dict={s: [] for s in SEATS},
            last_pid=None, bomb_num=0, all_handcards=None)

    def test_the_shortest_opponent_is_dealt_his_likely_bomb(self):
        import server as server_module
        info = self.infoset()
        played = info.played_cards["landlord_down"]
        rng = random.Random(20261119)
        draws = [ADVISOR.sample_hands(info, rng) for _ in range(1000)]
        bombs = sum(1 for hand in draws if has_bomb(server_module.minus(hand["landlord_down"], played)))
        # 改前均匀发牌 1000 手大约 0.4 手(0.0004),改后约 9 手;后验质量不到 0.2 的原因见 report.md
        self.assertGreaterEqual(bombs / len(draws), 0.003, f"1000 手只发出 {bombs} 手炸弹")


class ShortHandRolloutTest(unittest.TestCase):
    """15:08:02 那条:下家剩 4 张 4 4 4 4,领单张 2 赢面不该再是 1.0(这一手怎么出都输,只验估计)。"""

    def test_the_single_two_no_longer_wins_every_rollout(self):
        pos = KeepBombLikelihoodTest()
        report = {}
        ROLLOUT_ADVISOR.act("landlord", pos.HAND, pos.BOTTOM, pos.AFTER_J, report,
                            auction=pos.auction())
        self.assertIn("rollout", report, "后半局应该现场模拟")
        self.assertLess(report["rollout"]["rates"]["2"], 1.0)


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

    def test_the_landlords_unplayed_bottom_cards_stay_in_his_hand(self):
        # 我是农民时,地主没出过的底牌一定在他手里:同样是 Q Q Q Q 一手,给地主留一张 A,
        # 他凑成 A A A A 炸回来的机会就该比纯随机发牌高(其余三张 A 更容易落在他手上)。
        import endgame
        hand = cards(["Q", "Q", "Q", "Q", "9", "9", "9", "7"])
        legal = [cards(["Q", "Q", "Q", "Q"])]
        unknown = cards(["A", "A", "A", "A", "3", "4", "5", "6"])
        plain = endgame.finish_in_two(hand, legal, (), unknown, 5, 3, samples=2000, limit=1.0)
        kept = endgame.finish_in_two(hand, legal, (), unknown, 5, 3, samples=2000, limit=1.0,
                                     kept=cards(["A"]))
        self.assertIsNotNone(plain)
        self.assertIsNotNone(kept)
        self.assertGreater(kept[1], plain[1])

    def test_the_bomb_is_kept_for_the_last_of_the_two_moves(self):
        # Q Q Q Q + 9 9 9 7 两种顺序都两手出完:先出 9 9 9 7,有人管就用 Q Q Q Q 炸回去,
        # 只有对手留着 A A A A 拦得住;先甩 Q Q Q Q 等于给它一个用炸弹的靶子。
        import endgame
        hand = cards(["Q", "Q", "Q", "Q", "9", "9", "9", "7"])
        # 对手有更大的三带一:9 9 9 7 会被管,QQQQ 却没人炸得动 —— 纯按被压机会会先甩炸弹
        unknown = cards(["K", "K", "K", "J", "J", "J", "10", "10"])
        legal = [cards(["Q", "Q", "Q", "Q"]), cards(["9", "9", "9", "7"])]
        quick = endgame.finish_in_two(hand, legal, (), unknown, 4, 4, samples=100, limit=1.0)
        self.assertEqual(cards(["9", "9", "9", "7"]), list(quick[0]))


class FinishInTwoLiveTest(unittest.TestCase):
    """2026-11-19 12:13:20 精英场那一手:捷径先甩 Q Q Q Q,地主 A A A A 炸回,炸弹 ×2 再 ×2。"""

    # 我是地主下家(农民),手里 Q Q Q Q 9 9 9 7,我领出;地主剩 16 张、队友 15 张,底牌 4 7 A(地主手里至少一张 A)。
    REQUEST = dict(seat="landlord_down",
                   hand=["Q", "Q", "Q", "Q", "9", "9", "9", "7"],
                   bottom=["4", "7", "A"],
                   history=[["3"], ["4"], ["5"], ["7"], ["10"], ["J"], ["K"], ["2"], [], ["SJ"], ["BJ"], [], [],
                            ["6", "6", "6", "J", "J"], [], []],
                   auction={"double": {"landlord": False, "landlord_up": False}})

    def act(self, advisor, report=None):
        request = self.REQUEST
        return advisor.act(request["seat"], request["hand"], request["bottom"], request["history"], report,
                           auction=parse_auction(request["auction"]))

    def test_the_shortcut_no_longer_throws_the_bomb_first(self):
        move = self.act(ROLLOUT_ADVISOR)
        self.assertNotEqual(["Q", "Q", "Q", "Q"], names(move), "捷径不该把炸弹先甩出去")
        self.assertFalse(is_bomb(move), "这一手不是炸弹,炸弹留到最后一手")

    def test_a_much_higher_douzero_choice_is_not_overridden(self):
        # DouZero 首选 7 9 9 9 +2.282、捷径那手只有 +1.335:差 0.95 就不该盖。把捷径结果钉成 Q Q Q Q
        # 验门槛本身(顺序那条规则另验);rollout 关掉,免得等模拟。
        import endgame
        bad = (tuple(cards(["Q", "Q", "Q", "Q"])), 0.01)
        with mock.patch.object(endgame, "finish_in_two", return_value=bad):
            move = self.act(ADVISOR)
        self.assertNotEqual(["Q", "Q", "Q", "Q"], names(move), "DouZero 首选高出一大截时,捷径不该盖掉它")


class TeammateFinishInTwoTest(unittest.TestCase):
    """桌上是队友的牌时,「两手出完」不直接压队友:捷径那手和「不出」一起现场模拟,按赢面出。"""

    # 2026-11-17 12:47:36 精英场:我是地主下家(农民),手里 2 2 2 J 7 7,队友(地主上家)刚出 A A A J,地主不要了。
    # 捷径发现 J 2 2 2 + 7 7 两手能出完,直接压了队友,没跟「不出、让队友接着出」比。
    MATE = dict(seat="landlord_down",
                hand=["2", "2", "2", "J", "7", "7"],
                bottom=["3", "3", "6"],
                history=[["3", "3", "3", "10"], ["9", "9", "9", "10"], [], [], ["3"], ["10"], ["K"], ["A"], [], ["2"],
                         ["SJ"], [], [], ["5", "5", "5", "8"], ["A", "A", "A", "J"], []])

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report)

    def test_a_teammates_cards_are_not_snapped_up_by_finishing_in_two(self):
        report = {}
        self.act(self.MATE, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "桌上是队友的牌时,两手出完那手要交给现场模拟")
        self.assertIn("J 2 2 2", rollout["rates"])
        self.assertIn("pass", rollout["rates"])

    def test_the_shortcut_move_is_forced_into_the_candidates(self):
        # 这一手 DouZero 给「不出」1.13、J 2 2 2 只有 0.99,差 0.13,不在后半局时按分差根本轮不到它;
        # 桌上是队友的牌,捷径那手必须强制进候选(见 close_rollout 的 required)
        import server
        report = {}
        with mock.patch.object(server, "LATE_CARDS", 0):
            self.act(self.MATE, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "不在后半局也要把捷径那手和不出一起模拟")
        self.assertIn("J 2 2 2", rollout["rates"])
        self.assertIn("pass", rollout["rates"])

    def test_no_time_to_simulate_falls_back_to_pass(self):
        # 模拟跑不起来(剩下的时间不够)时退回不出,不压队友,也不退回捷径
        import server
        report = {}
        with mock.patch.object(server, "ACT_ROLLOUT_MIN_S", 100.0):
            move = self.act(self.MATE, report)
        self.assertEqual([], move)
        self.assertEqual("no_time", report.get("rollout_skipped"))

    def test_landlords_own_cards_still_take_the_shortcut(self):
        # 桌上是农民出的、我是地主:没有队友,两手出完那手照旧直接出,不模拟
        report = {}
        play = ROLLOUT_ADVISOR.act(
            "landlord", ["2", "2", "6"], ["5", "6", "7"],
            [["4", "4"], ["8", "8"], [], ["9", "9"], [], [], ["Q", "Q"], [], [],
             ["5", "7", "10", "10", "10", "J", "J", "J"], ["3", "3", "3", "3"], [], [], ["5"], ["2"], ["BJ"], [], [],
             ["A", "A"], [], []], report)
        self.assertEqual(["2", "2"], [NAMES[c] for c in play])
        self.assertNotIn("rollout", report)


class FinishInTwoBombTest(unittest.TestCase):
    """捷径把炸弹拆成四带二时会少一次 ×2:不直接走捷径,回到正常流程让炸弹能单独炸出去。"""

    # 2026-11-17 12:58:35 精英场,我是地主下家(农民),先出:手里 BJ A A A A 9 4,地主剩 5 张、队友 15 张。
    # DouZero 自己选 4(2.073;9 2.067、A A A A 2.055、BJ 1.920),捷径却出 BJ,再拿 A A A A 当四带二带走,
    # 炸弹没单独炸、少一次 ×2。先出 4 或 9,有人管就 BJ 收回,再 AAAA 单独炸,同样必赢还翻倍。
    LIVE = dict(seat="landlord_down",
                hand=["BJ", "A", "A", "A", "A", "9", "4"],
                bottom=["5", "6", "10"],
                history=[["K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3"], [], [], ["7", "7"], ["Q", "Q"], [],
                         [], ["5"], ["Q"], [], ["K"], [], [], ["3"], ["K"], [], ["2"], [], [], ["8", "8"], [], ["9", "9"],
                         ["J", "J"], [], []])

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report)

    def test_a_bomb_split_shortcut_is_not_taken(self):
        report = {}
        move = self.act(self.LIVE, report)
        self.assertNotEqual(["BJ"], [NAMES[c] for c in move], "捷径拆了炸弹,不该直接出 BJ")
        self.assertIn("rollout", report, "拆炸弹的捷径不 return,交给现场模拟")

    def test_a_bomb_split_does_not_win_the_fewer_plays_tiebreak(self):
        # 四带二 A A A A + 4 + 9 出完只剩一对 8(一手),单张 4 出完还剩两手,手数上确实更少;
        # 可它把炸弹拆了,不该靠「手数更少」胜出
        hand = cards(["A", "A", "A", "A", "9", "8", "8", "4"])
        own = tuple(cards(["4"]))
        split = tuple(cards(["4", "9", "A", "A", "A", "A"]))
        self.assertIsNone(fewer_plays(hand, [own, split], {own: 0.8, split: 0.8},
                                      {own: 2.0, split: 0.7}, own))

    def test_a_shortcut_without_a_bomb_is_still_taken(self):
        # 不拆炸弹时行为不变:照旧直接出捷径那一手,不模拟
        report = {}
        play = ROLLOUT_ADVISOR.act(
            "landlord", ["2", "2", "6"], ["5", "6", "7"],
            [["4", "4"], ["8", "8"], [], ["9", "9"], [], [], ["Q", "Q"], [], [],
             ["5", "7", "10", "10", "10", "J", "J", "J"], ["3", "3", "3", "3"], [], [], ["5"], ["2"], ["BJ"], [], [],
             ["A", "A"], [], []], report)
        self.assertEqual(["2", "2"], [NAMES[c] for c in play])
        self.assertNotIn("rollout", report)


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

    # 2026-11-19 10:22:39 普通场:我是地主,手里 18 张(单张 BJ、2 2 2),地主上家农民出单张 2、只剩 4 张,
    # 三家合计 37 张,不算后半局。DouZero 给出不出 -0.912、BJ -1.053,只差 0.141;按 0.1 不模拟就信了分数,
    # 地主上家拿回出牌权,这一局输了 60 万。差 0.1~0.15 的出牌也要现场模拟,候选里要有 pass 和 BJ。
    MARGIN_015 = dict(seat="landlord",
                      hand=["BJ", "2", "2", "2", "A", "Q", "Q", "10", "10", "8", "8", "8", "7", "7", "6", "5", "5", "5"],
                      bottom=["7", "J", "2"],
                      history=[["3"], ["5"], ["A"], [], [], ["A", "K", "Q", "J", "10"], [], [],
                               ["9", "9", "9", "7", "7"], [], [], ["6"], ["J"], ["A"], ["2"]],
                      auction={"double": {"landlord_down": False, "landlord_up": False}})

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report,
                                   auction=parse_auction(position.get("auction")))

    def test_the_live_close_call_keeps_pass(self):
        report = {}
        self.assertEqual([], self.act(self.LIVE, report))
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手只差 0.06,应该现场模拟")
        self.assertIn("pass", rollout["rates"])
        self.assertIn("BJ", rollout["rates"])
        self.assertEqual("pass", rollout["chosen"])

    def test_a_move_that_leaves_fewer_plays_also_wins_on_amount(self):
        # 4..9 剩三手,5..9 剩四手。按金额比时输掉的局会被炸弹翻倍放大,两手的平均得分差得很开
        # (实测 -0.13 对 -0.37),4..9 直接胜出,不用走比手数那条路;手数更少的兜底仍见 FewerPlaysTest。
        import server
        report = {}
        with mock.patch.object(server, "ACT_ROLLOUT_GAMES", 150), \
                mock.patch.object(server, "ACT_ROLLOUT_BUDGET_S", 60.0):
            move = self.act(self.TIEBREAK, report)
        self.assertEqual(["4", "5", "6", "7", "8", "9"], [NAMES[c] for c in move])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手差不到门槛,应该现场模拟")
        self.assertEqual("4 5 6 7 8 9", rollout["chosen"])
        self.assertGreater(rollout["avg_score"]["4 5 6 7 8 9"],
                           rollout["avg_score"]["5 6 7 8 9"])

    def test_a_close_call_the_rollout_prefers_the_second_move(self):
        # CLOSE_MARGIN 到 0.15 后第三手 2 也进了候选:2.5 秒里三个候选各只打 50 局上下、够不到 60 局下限,
        # 模拟就不算数(仍出 K)。这里给足总预算和局数,把「模拟换成比 DouZero 那手更好的牌」单独测出来:
        # 150 局时 7 赢 0.61、K 0.50。2 和 7 谁高只差在噪声里(300 局时 2 反超),不在这里断言。
        import server
        report = {}
        with mock.patch.object(server, "ACT_TOTAL_BUDGET_S", 60.0), \
                mock.patch.object(server, "ACT_ROLLOUT_BUDGET_S", 60.0), \
                mock.patch.object(server, "ACT_ROLLOUT_GAMES", 150):
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

    def test_a_mid_game_close_call_keeps_the_mid_game_floor(self):
        report = {}
        self.act(self.SECOND, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "顶两手只差 0.011,应该现场模拟")
        self.assertEqual(30, rollout["min_games"])

    def test_a_clear_best_move_does_not_roll_out(self):
        report = {}
        move = self.act(self.CLEAR, report)
        self.assertNotIn("rollout", report)
        self.assertEqual(["9", "10", "J", "Q", "K", "A"], [NAMES[c] for c in move])
        # 不模拟时与关掉模拟的 advisor 完全一样
        self.assertEqual(sorted(ADVISOR.act(self.CLEAR["seat"], self.CLEAR["hand"], self.CLEAR["bottom"],
                                            self.CLEAR["history"])), sorted(move))

    def test_a_gap_just_over_the_old_margin_still_rolls_out(self):
        # 差 0.141:旧的 CLOSE_MARGIN 0.1 不模拟;放到 0.15 后 pass 和 BJ 都进候选。
        # 模拟结果喂固定数据(每手 71 局,pass 平均得分 0.2、BJ -0.1),机器快慢不影响这一手。
        games, means = 71, {"pass": 0.2, "BJ": -0.1}

        def stub(keys, own, scores, deal, play, budget, cap=None, pairs=None, anchor=None, **kwargs):
            by_label = {move_label(k): k for k in keys}
            alive = [by_label["pass"], by_label["BJ"]]
            return ({k: 0 for k in keys}, {k: means[move_label(k)] * games for k in keys},
                    {k: games for k in keys}, [[list(alive), games]], alive)

        report = {}
        with mock.patch("server.successive_halving", stub):
            move = self.act(self.MARGIN_015, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "差 0.141 超过旧的 0.1,CLOSE_MARGIN 到 0.15 后应该现场模拟")
        self.assertNotIn("late", rollout, "三家还剩 37 张,不该走后半局那条路")
        self.assertIn("pass", rollout["rates"])
        self.assertIn("BJ", rollout["rates"])
        self.assertEqual("pass", rollout["chosen"])
        self.assertEqual([], move)

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

    # 2026-11-16 13:10:11 土豪场:我是地主下家,三家还剩 35 张。8 手平分 2.5 秒每手只打了 27 局,
    # 不到 60 局整个模拟作废,仍出 DouZero 的 6。改成边打边淘汰后最后两手都要打满 60 局以上。
    # 本机实测:8 赢 0.80、6 赢 0.76,差 0.04 没到 0.05 的门槛,所以仍保留 6;300 局时两者打平(0.75 对 0.75),
    # 13:10 那手 8 领先 7 个点只是 27 局的运气,60 局以上就分不开了。
    HALVING = dict(seat="landlord_down",
                   hand=["2", "A", "Q", "Q", "Q", "J", "J", "10", "10", "9", "9", "8", "6"],
                   bottom=["K", "9", "A"],
                   history=[["3", "3"], ["4", "4"], ["5", "5"], ["K", "K"], [], ["2", "2"], [], [], ["3", "3"], [], [],
                            ["6", "6"], [], ["7", "7"], [], ["A", "A"], [], [], ["5"]])

    def test_the_logged_eight_way_call_halves_the_field_and_uses_sixty_games(self):
        report = {}
        move = self.act(self.HALVING, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "后半局不管分差都要现场模拟")
        self.assertTrue(rollout.get("late"))
        self.assertGreaterEqual(rollout["games"], 60, "最后两手都要打满 60 局")
        self.assertGreaterEqual(len(rollout["rates"]), 8, "每个候选的赢面都要留在诊断里")
        # 8 -> 4 -> 2:第一轮淘汰前每手至少打 12 局,最后两手再打完剩下的时间
        self.assertEqual([4, 2, 2], [len(alive) for alive, _ in rollout["rounds"]])
        self.assertGreaterEqual(rollout["rounds"][0][1], 12, "7 局就淘汰太靠运气")
        finalists = rollout["rounds"][-1][0]
        self.assertEqual(2, len(finalists))
        # 8 和 6 的真实赢面差不多(都约 0.75),谁进决赛、决赛里谁赢面最高都是采样运气,
        # 所以这里只看结构:DouZero 自己那手不能被淘汰,最后也只能出决赛两手之一。
        self.assertIn("6", finalists, "DouZero 自己的 6 要活到最后两手")
        self.assertIn(rollout["chosen"], finalists)
        self.assertEqual([NAMES[c] for c in move], rollout["chosen"].split())

    # 2026-11-16 21:10:00 土豪场:我是地主上家(农民)首出,地主只剩 5 张 2 2 K Q SJ,专吃单张。
    # 按明牌求解首出单张全输,对子(3 3/4 4/6 6/7 7/10 10)、顺子(6-10、6-J、7-J)都能赢;
    # 可后半局只取 DouZero 分数最高的 8 手,这一手前 8 名全是单张,对子和顺子根本没进模拟。
    # 候选里每种牌型至少留一手(该牌型分数最高的),才可能挑出对子/顺子。
    TYPES = dict(seat="landlord_up",
                 hand=["BJ", "2", "A", "J", "10", "10", "9", "8", "7", "7", "6", "6", "4", "4", "3", "3"],
                 bottom=["K", "8", "2"],
                 history=[["9", "8", "7", "6", "5", "4", "3"], ["Q", "J", "10", "9", "8", "7", "6"], [],
                          ["A", "K", "Q", "J", "10", "9", "8"], [], [], ["4"], ["J"], ["A"], [], []])

    def test_late_candidates_cover_every_move_type(self):
        report = {}
        self.act(self.TYPES, report)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "后半局不管分差都要现场模拟")
        kinds = {md.get_move_type(cards(label.split()))["type"]
                 for label in rollout["rates"] if label != "pass"}
        self.assertIn(md.TYPE_2_PAIR, kinds, "候选里要有一手对子")
        self.assertIn(md.TYPE_8_SERIAL_SINGLE, kinds, "候选里要有一手顺子")

    def test_a_clear_move_over_the_late_window_does_not_roll_out(self):
        # CLEAR 三家还剩 44 张、顶两手差 0.306:不在后半局,不模拟
        report = {}
        self.act(self.CLEAR, report)
        self.assertNotIn("rollout", report)

    def test_the_late_call_answers_within_the_turn_clock(self):
        started = time.monotonic()
        self.act(self.LATE)
        self.assertLess(time.monotonic() - started, 3.0)


class BeatLandlordTest(unittest.TestCase):
    """农民接地主的牌:现场模拟最后选了不出、某手不炸的压牌又和不出差不到一个标准误差时,改出压牌。
    用 2026-11-18 22:32:33 那一手的请求按公开入口 ROLLOUT_ADVISOR.act 测;模拟结果喂固定数据,
    局数 / 平均得分 / 配对差都可控,机器快慢不会让测试偶尔变绿。"""

    # 2026-11-18 22:32:33 普通场:我是地主上家(农民),手牌 5 5 6 7 7 7 7 8 A A;地主出 8 8、队友不出。
    # 请求原样取自实机 douzero 服务打印的请求 / 回复日志(ddz-hint-lifted 那个会话的 advisor.log 第 541 行,
    # t=22:32:33),带 auction:地主、队友都没加倍。回复 71 局:不出 0.85 / A A 0.80、平均得分 1.239 / 1.099,
    # 差 0.14 在 1 个标准误差(约 0.19)内,当时回了不出。地主接着走 AKQJ109,我们只能炸 7777,
    # 炸弹让输的翻倍;按可见信息明牌算到底 A A 才赢。重放这条完整请求(真代码、不 stub)5 次都回 A A;
    # 同样 5 次去掉 auction 就都回不出,所以 auction 必须留在 fixture 里。
    LOGGED = dict(seat="landlord_up",
                  hand=["A", "A", "8", "7", "7", "7", "7", "6", "5", "5"],
                  bottom=["SJ", "4", "6"],
                  history=[["5"], ["9"], ["K"], [], [], ["9"], ["A"], [], ["2"], [], [], ["10", "10"], [],
                           ["J", "J"], ["Q", "Q"], ["K", "K"], ["2", "2"], [], [], ["3", "3"], [], ["4", "4"],
                           ["6", "6"], [], ["8", "8"], []],
                  auction={"double": {"landlord": False, "landlord_down": False}})

    # 同一手但把一张 7 换成 J:候选里没有炸弹(A A 照样能接 8 8),用来单独测「差太大」。
    NO_BOMB = dict(LOGGED, hand=["A", "A", "8", "7", "7", "7", "6", "5", "5", "J"])

    def act(self, position, report=None):
        return ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                   position["history"], report,
                                   auction=parse_auction(position.get("auction")))

    @staticmethod
    def stub_rollout(means, games=71, gap_sd=1.6, finalists=("pass", "7 7 7 7")):
        """喂固定的模拟结果:每手 [games] 局;配对差的标准差 [gap_sd]。

        [means] 是 {标签: 平均得分},[finalists] 是最后还活着的两手(和实机那一手一样,淘汰轮会把其余候选筛掉)。
        配对差的标准误差 = gap_sd / sqrt(games)。
        """
        def stub(keys, own, scores, deal, play, budget, cap=None, pairs=None, anchor=None, **kwargs):
            by_label = {move_label(k): k for k in keys}
            alive = [by_label[label] for label in finalists]
            wins = {k: int(round((means[move_label(k)] + 1) / 2 * games)) for k in keys}
            played = {k: games for k in keys}
            earned = {k: means[move_label(k)] * games for k in keys}
            if pairs is not None:
                base = anchor if anchor is not None else own
                for k in keys:
                    if k == base:
                        continue
                    gap = means[move_label(k)] - means[move_label(base)]
                    pairs[k] = [gap * games, (gap * gap + gap_sd * gap_sd) * games, games]
            return wins, earned, played, [[list(alive), games]], alive
        return stub

    def test_the_logged_farmer_beats_the_landlord_when_the_gap_is_noise(self):
        report = {}
        stub = self.stub_rollout({"pass": 1.239, "A A": 1.099, "7 7 7 7": 1.239})
        with mock.patch("server.successive_halving", stub):
            move = self.act(self.LOGGED, report)
        self.assertEqual(["A", "A"], [NAMES[c] for c in move])
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout)
        self.assertEqual("A A", rollout["chosen"])
        self.assertEqual("A A", rollout.get("beat"))

    def test_a_gap_bigger_than_the_standard_error_keeps_pass(self):
        # 候选里只有 pass 和 A A(没有炸弹):A A 差 0.239,超过 1 个标准误差 0.19,不换
        report = {}
        stub = self.stub_rollout({"pass": 1.239, "A": 1.000, "A A": 1.000}, finalists=("pass", "A A"))
        with mock.patch("server.successive_halving", stub):
            move = self.act(self.NO_BOMB, report)
        self.assertEqual([], move)
        self.assertNotIn("beat", report["rollout"])
        self.assertNotIn("7 7 7 7", report["rollout"]["rates"], "这一手不该有炸弹候选")

    def test_a_bomb_is_not_treated_as_a_beat(self):
        # A A 差得开、只剩炸弹和不出差不多:炸弹翻倍,不能拿来接地主,继续不出
        report = {}
        stub = self.stub_rollout({"pass": 1.239, "A A": 1.000, "7 7 7 7": 1.239})
        with mock.patch("server.successive_halving", stub):
            move = self.act(self.LOGGED, report)
        self.assertEqual([], move)
        self.assertNotIn("beat", report["rollout"])
        self.assertEqual(1.239, report["rollout"]["avg_score"]["7 7 7 7"], "炸弹和不出分一样也照样不接")

    def test_only_plays_against_the_landlord_are_forced(self):
        # 桌上要是队友的牌,或者我当地主,这条都不生效
        import server
        keys = [(), (14, 14)]
        means = {(): 1.239, (14, 14): 1.099}
        scores = {(): 1.2, (14, 14): 1.1}
        played = {(): 71, (14, 14): 71}
        pairs = {(14, 14): [-0.14 * 71, (1.6 ** 2 + 0.14 ** 2) * 71, 71]}
        mate = SimpleNamespace(player_position="landlord_up", last_pid="landlord_down")
        landlord = SimpleNamespace(player_position="landlord", last_pid="landlord_up")
        self.assertIsNone(server.beat_landlord(mate, keys, means, scores, played, pairs, 71))
        self.assertIsNone(server.beat_landlord(landlord, keys, means, scores, played, pairs, 71))
        against = SimpleNamespace(player_position="landlord_up", last_pid="landlord")
        self.assertEqual((14, 14), server.beat_landlord(against, keys, means, scores, played, pairs, 71))

    def test_a_beat_simulated_less_than_the_finalists_is_not_taken(self):
        # 淘汰得早、局数比决赛少的手,模拟不够,不认
        import server
        keys = [(), (14, 14)]
        means = {(): 1.239, (14, 14): 1.099}
        scores = {(): 1.2, (14, 14): 1.1}
        pairs = {(14, 14): [-0.14 * 30, (1.6 ** 2 + 0.14 ** 2) * 30, 30]}
        infoset = SimpleNamespace(player_position="landlord_up", last_pid="landlord")
        played = {(): 71, (14, 14): 30}
        self.assertIsNone(server.beat_landlord(infoset, keys, means, scores, played, pairs, 71))


class HalvingScheduleTest(unittest.TestCase):
    """边打边淘汰的排期:假时钟 + 假出牌函数,不跑 DouZero,所以每次结果都一样。"""

    KEYS = ["pass", "8", "6", "J", "2", "A", "Q", "10"]
    PROBS = {"8": 0.9, "J": 0.75, "6": 0.6, "A": 0.5, "Q": 0.4, "10": 0.3, "2": 0.2, "pass": 0.1}

    def run_halving(self, own, probs=None, budget=2.5, per_game=0.01, seed=7):
        import server
        probs = probs or self.PROBS
        now = [0.0]
        rng = random.Random(seed)

        def clock():
            return now[0]

        def deal():
            return None

        def play(_, key):
            now[0] += per_game  # 每局 10 毫秒,和这台机器上后半局的实测差不多
            return rng.random() < probs[key]

        scores = {k: len(self.KEYS) - i for i, k in enumerate(self.KEYS)}
        result = server.successive_halving(self.KEYS, own, scores, deal, play, budget,
                                          cap=10 ** 9, clock=clock)
        return result, now

    def test_leader_survives_both_protected_moves(self):
        import server
        keys = ["9", "J", "8", "pass"]
        rates = dict(zip(keys, [0.9, 0.8, 0.7, 0.6]))
        survivors = server.halve(keys, "pass", rates, rates, protected="8")
        self.assertEqual({"9", "8", "pass"}, set(survivors))

    def test_final_round_keeps_all_three_candidates(self):
        import server
        keys = ["8", "9", "pass"]
        scores = {"8": 3, "9": 2, "pass": 1}
        _, _, played, rounds, finalists = server.successive_halving(
            keys, "pass", scores, lambda: None, lambda _, key: scores[key],
            1, cap=2, clock=lambda: 0, protected="8")
        self.assertEqual(set(keys), set(finalists))
        self.assertEqual(set(keys), set(rounds[-1][0]))
        self.assertEqual({key: 2 for key in keys}, played)

    def test_the_worse_half_is_dropped_and_the_final_two_get_most_games(self):
        ((wins, earned, played, rounds, finalists), _) = (self.run_halving("pass"))
        # 8 -> 4 -> 2,最后一轮只剩两手不再淘汰
        self.assertEqual([4, 2, 2], [len(alive) for alive, _ in rounds])
        self.assertGreaterEqual(rounds[0][1], 12, "第一轮淘汰前每手至少打满 12 局")
        self.assertGreaterEqual(rounds[1][1], 12, "第二轮淘汰前也要打满 12 局")
        rates = {k: wins[k] / played[k] for k in self.KEYS}
        best = max(self.KEYS, key=lambda k: rates[k])
        self.assertEqual("8", best)
        self.assertIn("8", finalists)
        # 最后两手打了最多局,淘汰掉的候选在淘汰那一轮就停手了
        self.assertGreater(min(played[k] for k in finalists),
                           max(played[k] for k in self.KEYS if k not in finalists))

    def test_the_douzero_move_is_never_dropped(self):
        # own 的假赢面最低,按赢面会被淘汰;它必须每一轮都在,而且换进来顶掉的只是最后一名
        ((wins, earned, played, rounds, finalists), _) = self.run_halving("pass")
        for alive, _ in rounds:
            self.assertIn("pass", alive)
        self.assertIn("pass", finalists)
        self.assertEqual(2, len(rounds[-1][0]))


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
        # 实机那一手回复 4903 毫秒,手机 4 秒就超时;总预算封顶后要在手机读超时(4000 毫秒)内答完
        started = time.monotonic()
        self.act(self.LOGGED)
        self.assertLess(time.monotonic() - started, 4.0)

    def test_the_turn_budget_stays_under_the_phone_timeout(self):
        # 手机端 core/.../DdzAdvisor.kt 的 ACT_READ_TIMEOUT_MS = 4000:总预算不能超过 3.6 秒,留出网络余量
        self.assertLessEqual(ACT_TOTAL_BUDGET_S, 3.6)

    def test_a_non_endgame_close_call_still_gets_the_whole_rollout_budget(self):
        # 不是残局(搜索立刻返回 None、不耗时)时,现场模拟能用的时间不能变少:还是拿满 ACT_ROLLOUT_BUDGET_S
        import server
        real = server.successive_halving
        seen = []

        def spy(keys, own, scores, deal, play, budget, **kwargs):
            seen.append(budget)
            return ({k: 0 for k in keys}, {k: 0.0 for k in keys}, {k: 1 for k in keys},
                    [[list(keys), 1]], list(keys))

        server.successive_halving = spy
        try:
            report = {}
            self.act(CloseCallRolloutTest.LIVE, report)
        finally:
            server.successive_halving = real
        self.assertEqual([ACT_ROLLOUT_BUDGET_S], seen)
        self.assertEqual(1, report["rollout"]["games"])

    def test_a_search_that_burns_the_clock_leaves_no_time_for_the_rollout(self):
        # 残局搜索把总预算烧到只剩 ACT_ROLLOUT_MIN_S 以下、又没有结论:现场模拟整个跳过,保留 DouZero 那一手,
        # 整手牌仍不超过 ACT_TOTAL_BUDGET_S
        import endgame
        real = endgame.best

        def slow_best(*args, **kwargs):
            time.sleep(ACT_TOTAL_BUDGET_S - ACT_ROLLOUT_MIN_S + 0.05)
            return None

        endgame.best = slow_best
        try:
            report = {}
            started = time.monotonic()
            self.act(CloseCallRolloutTest.LATE, report)
            elapsed = time.monotonic() - started
        finally:
            endgame.best = real
        self.assertLess(elapsed, ACT_TOTAL_BUDGET_S + 0.15)
        self.assertEqual("no_time", report.get("rollout_skipped"))
        self.assertNotIn("rollout", report)

    def test_a_search_that_uses_its_own_budget_still_leaves_room_for_the_rollout(self):
        # 残局搜索睡满自己的 ACT_ENDGAME_BUDGET_S 秒:总预算还剩 1.0 秒左右(仍在 ACT_ROLLOUT_MIN_S 之上),
        # 现场模拟照常跑,只是模拟得少些
        import endgame
        real = endgame.best

        def slow_best(*args, **kwargs):
            time.sleep(ACT_ENDGAME_BUDGET_S)
            return None

        endgame.best = slow_best
        try:
            report = {}
            started = time.monotonic()
            self.act(CloseCallRolloutTest.LIVE, report)
            elapsed = time.monotonic() - started
        finally:
            endgame.best = real
        self.assertLess(elapsed, ACT_TOTAL_BUDGET_S + 0.15)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout, "残局搜索用掉 2.5 秒后还剩 1.0 秒,应该照常现场模拟")
        self.assertGreater(rollout["budget_left"], ACT_ROLLOUT_MIN_S - 0.15)
        self.assertLess(rollout["budget_left"], ACT_TOTAL_BUDGET_S - ACT_ENDGAME_BUDGET_S + 0.2)

    def test_a_full_search_still_leaves_room_for_the_tiebreak(self):
        # 残局搜索睡满自己的 ACT_ENDGAME_BUDGET_S 秒、现场模拟再吃掉剩下的:比手数(最多 0.2 秒)也要在
        # ACT_TOTAL_BUDGET_S 内收尾。模拟截止时间要先给比手数留出 0.2 秒,比手数的 deadline 也不能超过总预算剩下的。
        import endgame
        import server
        real_best, real_min_plays = endgame.best, endgame.min_plays
        seen = []

        def slow_best(*args, **kwargs):
            time.sleep(ACT_ENDGAME_BUDGET_S)
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
        self.assertLess(elapsed, ACT_TOTAL_BUDGET_S + 0.15)
        self.assertTrue(seen, "模拟没换手时应该比手数")
        called_at, deadline = seen[0]
        self.assertGreater(deadline - called_at, 0.1, "模拟截止时间要给比手数留出余量")
        self.assertLessEqual(deadline, started + ACT_TOTAL_BUDGET_S - ACT_BUDGET_SAFETY_S + 0.01)


class AuctionProtocolTest(unittest.TestCase):
    """/act 的可选 auction 字段:合法时透传给 Advisor,格式不对就整块当没带,不报错;不带时出牌和原来一样。"""

    def act_through_handler(self, body):
        """按 /act 的公开入口走一遍:假服务只记下 handler 传进来的 auction,回复固定空出牌。"""
        import io
        import json
        import server as server_module
        captured = {}

        class FakeAdvisor:
            def act(self, seat, hand, bottom, history, report=None, auction=None):
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
        live = CloseCallRolloutTest.LATE
        play = ADVISOR.act(live["seat"], live["hand"], live["bottom"], live["history"], auction=auction)
        self.assertIsInstance(play, list)
        self.assertTrue(all(c in cards(live["hand"]) for c in play))


class AuctionSamplingTest(unittest.TestCase):
    """叫分 / 加倍给看不见的牌加权:地主叫 3 分 + 加倍时,发出来的地主整手牌平均更强,我自己的证据不参与。"""

    def farmer_infoset(self):
        env = new_game(7)
        env.step()  # 地主出了第一手,现在轮到地主下家(农民)
        info = env.game_infoset
        self.assertEqual("landlord_down", info.player_position)
        return info

    def landlord_strength(self, hands):
        return ADVISOR.opening_strength("landlord", hands["landlord"])

    def test_landlord_bid_and_double_push_the_deal_towards_a_stronger_landlord(self):
        import server as server_module
        info = self.farmer_infoset()
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": True}})
        plain_rng, weighted_rng = random.Random(20261117), random.Random(20261117)
        plain = [self.landlord_strength(server_module.sample_hands(info, plain_rng))
                 for _ in range(200)]
        weighted = [self.landlord_strength(ADVISOR.sample_hands(info, weighted_rng, auction))
                    for _ in range(200)]
        self.assertGreater(sum(weighted) / len(weighted) - sum(plain) / len(plain), 0.05)

    def forwards_per_deal(self, auction, info):
        """发一手牌做了几次前向、每批几张:包住 Agent.opening_strengths 数一数(它是公开的批量入口)。"""
        import server as server_module
        calls = []
        real = server_module.Agent.opening_strengths

        def counting(agent, hands, deadline=None):
            calls.append(len(hands))
            return real(agent, hands, deadline)

        with mock.patch.object(server_module.Agent, "opening_strengths", counting):
            ADVISOR.sample_hands(info, random.Random(1), auction)
        return calls

    def test_a_weighted_deal_scores_all_candidates_in_one_forward_per_seat(self):
        # 一次发 K 手候选,按证据座位各攒一批前向:证据只有地主时 1 次,地主 + 另一个对手时 2 次;
        # 不能像拒绝采样那样每试一手就单独前向一次(当时最多 AUCTION_MAX_TRIES 次串行)
        import server as server_module
        info = self.farmer_infoset()
        one = server_module.parse_auction({"bid": {"landlord": 3},
                                           "double": {"landlord": True}})
        self.assertEqual([server_module.AUCTION_MAX_TRIES], self.forwards_per_deal(one, info))
        two = server_module.parse_auction({"bid": {"landlord": 3},
                                           "double": {"landlord": True, "landlord_up": True}})
        self.assertEqual([server_module.AUCTION_MAX_TRIES] * 2, self.forwards_per_deal(two, info))

    def test_a_scoring_failure_falls_back_to_the_first_candidate(self):
        # 牌力算不出来(例如现场模拟的截止时刻到了)不能让发牌卡死:收下第一手候选
        import server as server_module
        info = self.farmer_infoset()
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": True}})
        with mock.patch.object(server_module.Agent, "opening_strengths",
                               side_effect=server_module.endgame.Timeout):
            got = ADVISOR.sample_hands(info, random.Random(5), auction)
        self.assertEqual(server_module.sample_hands(info, random.Random(5)), got)

    def test_without_auction_the_same_seed_deals_the_same_hands(self):
        # 没有 auction 时完全走原来的 sample_hands:同一个种子发出来的牌一手不差
        import server as server_module
        info = self.farmer_infoset()
        auction = server_module.parse_auction({})
        for seed in (0, 1, 2):
            self.assertEqual(server_module.sample_hands(info, random.Random(seed)),
                             ADVISOR.sample_hands(info, random.Random(seed), auction))

    def test_my_own_bid_does_not_weight_the_deal(self):
        # 我是地主:叫 3 分的是我自己,看不到的对手没有证据,发牌与不加权完全一样
        import server as server_module
        info = new_game(7).game_infoset
        self.assertEqual("landlord", info.player_position)
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": True}})
        for seed in (0, 1, 2):
            self.assertEqual(server_module.sample_hands(info, random.Random(seed)),
                             ADVISOR.sample_hands(info, random.Random(seed), auction))


class AuctionRolloutTest(unittest.TestCase):
    """现场模拟也吃 auction:地主叫 3 分 + 加倍时,把看不见的牌往地主那边偏,我(农民)的模拟赢面应该更低。
    用固定局数(不信时钟)让两边都可重放,只断言方向,不断言具体数值。"""

    def farmer_position(self):
        # seed 1 打到第 10 手:我是地主下家,顶两手分差在 CLOSE_MARGIN 以内,会现场模拟
        env = new_game(1)
        for _ in range(10):
            env.step()
        info = env.game_infoset
        self.assertEqual("landlord_down", info.player_position)
        return dict(seat=info.player_position, hand=names(info.player_hand_cards),
                    bottom=names(info.three_landlord_cards),
                    history=[names(m) for m in info.card_play_action_seq])

    def rates(self, position, auction):
        import server as server_module
        report = {}
        with mock.patch.object(server_module, "ACT_TOTAL_BUDGET_S", 60.0), \
                mock.patch.object(server_module, "ACT_ROLLOUT_BUDGET_S", 60.0), \
                mock.patch.object(server_module, "CLOSE_TOP", 2):
            ROLLOUT_ADVISOR.act(position["seat"], position["hand"], position["bottom"],
                                position["history"], report, auction=auction)
        self.assertIn("rollout", report, "这一手顶两手分差很小,应该现场模拟")
        return report["rollout"]["rates"]

    def test_a_landlord_who_bid_three_and_doubled_lowers_my_rollout_win_rate(self):
        import server as server_module
        position = self.farmer_position()
        plain = self.rates(position, None)
        auction = server_module.parse_auction({"bid": {"landlord": 3},
                                               "double": {"landlord": True}})
        weighted = self.rates(position, auction)
        # 同一个候选(两种发牌方式下 DouZero 选出的候选不变):加权后地主牌更强,我的赢面更低
        self.assertIn("3 4 5 6 7", plain)
        self.assertIn("3 4 5 6 7", weighted)
        self.assertLess(weighted["3 4 5 6 7"], plain["3 4 5 6 7"] - 0.05)


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


class LostEndgameRuleTest(unittest.TestCase):
    """残局搜索按金额比:每手值 = Σ 分法权重 ×(赢 +m / 输 −m),炸弹 / 王炸 m=2,其它 m=1。"""

    def play(self, bomb_wins, pass_wins):
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
        # DouZero 永远更想炸:炸弹 9 分、不出 1 分;选哪手只由残局搜索的金额决定
        values = torch.tensor([9.0, 1.0])
        wins = {(3, 3, 3, 3): bomb_wins * total, (): pass_wins * total}
        with mock.patch.object(agent, "values", return_value=values), \
                mock.patch.object(endgame, "best", return_value=(wins, total, wins, total)):
            return names(agent.act(info))

    def test_a_zero_win_endgame_drops_the_bomb(self):
        import endgame
        import torch
        info = SimpleNamespace(
            player_position="landlord",
            player_hand_cards=[3, 3, 3, 3, 4, 5, 6],
            legal_actions=[[3, 3, 3, 3], [4], [5], [6]],
            num_cards_left_dict={s: 7 for s in SEATS},
            three_landlord_cards=[], card_play_action_seq=[], last_move=[], last_two_moves=[[], []],
            last_move_dict={s: [] for s in SEATS}, played_cards={s: [] for s in SEATS},
            bomb_num=0, last_pid=None, all_handcards=None)
        agent = ADVISOR.agents["landlord"]
        # DouZero 给炸弹 9 分、其余 1/2/3 分;搜索说全军覆没时炸弹值 −2×权重、非炸弹 −1×权重,该出 6
        values = torch.tensor([9.0, 1.0, 2.0, 3.0])
        zero = {(3, 3, 3, 3): 0, (4,): 0, (5,): 0, (6,): 0}
        with mock.patch.object(agent, "values", return_value=values), \
                mock.patch.object(endgame, "best", return_value=(zero, 1, zero, 1)):
            move = agent.act(info)
        self.assertEqual([6], move)

    def test_a_bomb_that_wins_less_weight_than_pass_is_dropped(self):
        # 炸弹赢 40% 权重、不出 35%:炸弹 2×(0.4−0.6) = −0.4,不出 0.35−0.65 = −0.3,选不出
        self.assertEqual([], self.play(0.40, 0.35))

    def test_a_bomb_that_wins_more_amount_is_still_kept(self):
        # 炸弹赢 60% 权重、不出 55%:炸弹 2×(0.6−0.4) = +0.4,不出 0.1,仍然炸
        self.assertEqual(["3", "3", "3", "3"], self.play(0.60, 0.55))


class KeepComboRolloutTest(unittest.TestCase):
    """复现 2026-11-20 10:20:22 普通场的出牌。"""

    LIVE = dict(seat="landlord_down",
                hand=["2", "2", "K", "Q", "J", "9", "9", "9", "8", "4", "4", "3", "3"],
                bottom=[], history=[["5", "5"], ["A", "A"], [], [], ["7"], [], ["K"], ["SJ"], [],
                                    ["BJ"], [], [], ["8", "7", "6", "5", "4"], [],
                                    ["J", "10", "9", "8", "7"], [], [],
                                    ["8", "7", "6", "5", "4", "3"], [], [], ["3"], ["6"]],
                auction={"double": {"landlord": False, "landlord_up": False}})

    def test_keep_combo_reaches_final_despite_early_noise(self):
        import server as server_module
        seen = {}

        def play_out(hands, bottom, history, me, move):
            key = " ".join(NAMES[c] for c in move) or "pass"
            n = seen.get(key, 0)
            seen[key] = n + 1
            if key in ("8", "pass"):
                return -1 if n < 20 else 1
            if key == "9":
                return -1 if 20 <= n < 40 else 1
            return -1

        original = server_module.successive_halving
        def halving(*args, **kwargs):
            return original(*args, **kwargs, clock=lambda: min(seen.get("9", 0) // 13, 2))

        agent = ROLLOUT_ADVISOR.agents[self.LIVE["seat"]]
        report = {}
        with mock.patch.object(agent, "play_out", play_out), \
                mock.patch.object(ROLLOUT_ADVISOR, "sample_hands", return_value={}), \
                mock.patch.object(server_module, "successive_halving", halving), \
                mock.patch.object(server_module, "ACT_ROLLOUT_GAMES", 100):
            move = ROLLOUT_ADVISOR.act(self.LIVE["seat"], self.LIVE["hand"], self.LIVE["bottom"],
                                       self.LIVE["history"], report,
                                       auction=server_module.parse_auction(self.LIVE["auction"]))
        self.assertEqual(["8"], names(move))
        self.assertIn("8", report["rollout"]["rounds"][-1][0])
        self.assertIn("9", report["rollout"]["rounds"][-1][0])
        self.assertEqual("fewer_plays", report["rollout"]["tiebreak"])
        self.assertEqual(100, seen["9"])
        self.assertEqual(100, seen["8"])
        self.assertEqual(seen["pass"], seen["8"])


    def test_split_move_can_win_when_own_is_pass(self):
        import server as server_module
        seen = {}

        def play_out(hands, bottom, history, me, move):
            key = " ".join(NAMES[c] for c in move) or "pass"
            seen[key] = seen.get(key, 0) + 1
            return 1 if key == "9" else -1

        original = server_module.successive_halving
        def halving(*args, **kwargs):
            return original(*args, **kwargs, clock=lambda: min(seen.get("9", 0) // 13, 2))

        agent = ROLLOUT_ADVISOR.agents[self.LIVE["seat"]]
        report = {}
        with mock.patch.object(agent, "play_out", play_out), \
                mock.patch.object(ROLLOUT_ADVISOR, "sample_hands", return_value={}), \
                mock.patch.object(server_module, "successive_halving", halving), \
                mock.patch.object(server_module, "ACT_ROLLOUT_GAMES", 100):
            move = ROLLOUT_ADVISOR.act(self.LIVE["seat"], self.LIVE["hand"], self.LIVE["bottom"],
                                       self.LIVE["history"], report,
                                       auction=server_module.parse_auction(self.LIVE["auction"]))
        self.assertEqual(["9"], names(move))
        self.assertEqual({"9", "8", "pass"}, set(report["rollout"]["rounds"][-1][0]))
        self.assertEqual(100, seen["9"])
        self.assertEqual(seen["9"], seen["8"])
        self.assertEqual(seen["9"], seen["pass"])


    def act_with_rates(self, rate8, rate9):
        import server as server_module
        seen = {}

        def play_out(hands, bottom, history, me, move):
            key = " ".join(NAMES[c] for c in move) or "pass"
            n = seen.get(key, 0)
            seen[key] = n + 1
            return 1 if (n * 37) % 100 < {"8": rate8, "9": rate9, "pass": 50}.get(key, 0) else -1

        def values(infoset):
            scores = {"pass": 0.30, "9": 0.29, "8": 0.28}
            return server_module.torch.tensor([scores.get(
                " ".join(NAMES[c] for c in action) or "pass", 0.1) for action in infoset.legal_actions])

        original = server_module.successive_halving
        def halving(*args, **kwargs):
            return original(*args, **kwargs, clock=lambda: min(seen.get("9", 0) // 13, 2))

        agent = ROLLOUT_ADVISOR.agents[self.LIVE["seat"]]
        report = {}
        with mock.patch.object(agent, "values", values), \
                mock.patch.object(agent, "play_out", play_out), \
                mock.patch.object(ROLLOUT_ADVISOR, "sample_hands", return_value={}), \
                mock.patch.object(server_module, "successive_halving", halving), \
                mock.patch.object(server_module, "ACT_ROLLOUT_GAMES", 100):
            move = ROLLOUT_ADVISOR.act(self.LIVE["seat"], self.LIVE["hand"], self.LIVE["bottom"],
                                       self.LIVE["history"], report,
                                       auction=server_module.parse_auction(self.LIVE["auction"]))
        return names(move), report["rollout"]

    def test_similar_better_split_move_keeps_combo(self):
        move, rollout = self.act_with_rates(80, 80)
        self.assertEqual(["8"], move)
        self.assertEqual("8", rollout["keep_combo"])
        self.assertEqual(rollout["avg_score"]["8"], rollout["avg_score"]["9"])
        self.assertGreater(rollout["avg_score"]["9"] - rollout["avg_score"]["pass"], 0.1)

    def test_clearly_better_split_move_still_wins(self):
        move, rollout = self.act_with_rates(70, 90)
        self.assertEqual(["9"], move)
        self.assertNotIn("keep_combo", rollout)
        self.assertGreater(rollout["avg_score"]["9"] - rollout["avg_score"]["8"], 0.1)


class LostBombAmountTest(unittest.TestCase):
    """现场模拟按输赢金额比,不按胜率比:赢 +2^k、输 −2^k,k 是这一手起打出的炸弹 / 王炸个数。

    按公开入口 ROLLOUT_ADVISOR.act 测;mock 掉 play_out 让炸弹和不出各自有确定的赢面,
    其余候选一律输,这样最后两手一定是炸弹和不出。
    """

    # 2026-11-18 16:35:59 普通场,我是地主下家,地主出 2 2,现场模拟 rates={"pass": 0.48, "3 3 3 3": 0.55}。
    LIVE = dict(seat="landlord_down",
                hand=["2", "K", "J", "8", "8", "5", "4", "4", "4", "3", "3", "3", "3"],
                bottom=["7", "Q", "K"],
                history=[["5", "5"], ["10", "10"], ["A", "A"], [], [], ["8", "7", "6", "5", "4"],
                         ["K", "Q", "J", "10", "9"], [], [], ["7", "7"], [], ["J", "J"], ["Q", "Q"], ["A", "A"], [],
                         ["2", "2"]],
                auction={"double": {"landlord": True, "landlord_up": False}})

    def act(self, bomb_wins, pass_wins, report=None):
        import server as server_module
        seen = {}

        def fake_play_out(hands, bottom, history, me, move):
            key = "pass" if not move else " ".join(NAMES[c] for c in move)
            n = seen.get(key, 0)
            seen[key] = n + 1
            target = {"3 3 3 3": bomb_wins, "pass": pass_wins}.get(key, 0.0)
            won = (n % 100) < int(round(target * 100))
            # 炸弹那一手打出去,这一局 k=1:赢 +2、输 −2;不出的局 k=0:赢 +1、输 −1
            k = 1 if key == "3 3 3 3" else 0
            return (2 ** k) if won else -(2 ** k)

        agent = ROLLOUT_ADVISOR.agents[self.LIVE["seat"]]
        auction = server_module.parse_auction(self.LIVE.get("auction"))
        # 钉死局数,每手打满 100 局,赢面就是上面给的百分比,不受机器快慢影响
        with mock.patch.object(agent, "play_out", fake_play_out), \
                mock.patch.object(server_module, "ACT_ROLLOUT_GAMES", 100):
            return ROLLOUT_ADVISOR.act(self.LIVE["seat"], self.LIVE["hand"], self.LIVE["bottom"],
                                       self.LIVE["history"], report, auction=auction)

    def test_a_bomb_that_still_wins_more_often_is_still_the_better_bet(self):
        # 炸弹赢 0.55、不出赢 0.48:按金额炸弹 = 2×(0.55−0.45) = +0.20,不出 = 0.48−0.52 = −0.04,
        # 炸弹仍然对。规则 B 是按金额比,不是一刀切禁炸。
        report = {}
        move = self.act(0.55, 0.48, report)
        self.assertEqual(["3", "3", "3", "3"], names(move))
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout)
        self.assertEqual("3 3 3 3", rollout["chosen"])
        self.assertIn("avg_score", rollout, "报告里要另带平均得分")
        self.assertGreater(rollout["avg_score"]["3 3 3 3"], rollout["avg_score"]["pass"])

    def test_a_bomb_that_wins_less_amount_should_pass(self):
        # 炸弹赢 0.40、不出赢 0.35:按胜率选炸弹,按金额炸弹 −0.40 < 不出 −0.30,应该不出。
        report = {}
        move = self.act(0.40, 0.35, report)
        self.assertEqual([], move)
        rollout = report.get("rollout")
        self.assertIsNotNone(rollout)
        self.assertEqual("pass", rollout["chosen"])
        self.assertIn("avg_score", rollout, "报告里要另带平均得分")
        self.assertGreater(rollout["avg_score"]["pass"], rollout["avg_score"]["3 3 3 3"])
        self.assertGreater(rollout["rates"]["3 3 3 3"], rollout["rates"]["pass"], "rates 还是胜率")


class SwitchMarginScaleTest(unittest.TestCase):
    """平均得分 = 2×胜率−1(不炸的局 ±1),换手门槛也要 ×2;不然 0.04 的胜率差会被当成过了 0.05。"""

    LIVE = CloseCallRolloutTest.LIVE

    def act(self, pass_wins, bj_wins, report):
        import server as server_module
        seen = {}

        def fake_play_out(hands, bottom, history, me, move):
            key = "pass" if not move else " ".join(NAMES[c] for c in move)
            n = seen.get(key, 0)
            seen[key] = n + 1
            target = {"pass": pass_wins, "BJ": bj_wins}.get(key, 0.0)
            return 1 if (n % 100) < int(round(target * 100)) else -1

        agent = ROLLOUT_ADVISOR.agents[self.LIVE["seat"]]
        with mock.patch.object(agent, "play_out", fake_play_out), \
                mock.patch.object(server_module, "ACT_ROLLOUT_GAMES", 100):
            return names(ROLLOUT_ADVISOR.act(self.LIVE["seat"], self.LIVE["hand"], self.LIVE["bottom"],
                                             self.LIVE["history"], report))

    def test_a_four_point_win_rate_gap_does_not_switch(self):
        # 胜率 0.50 对 0.54,差 0.04 < 0.05;平均得分差 0.08,不能当过了 0.05
        report = {}
        self.assertEqual([], self.act(0.50, 0.54, report))
        self.assertEqual("pass", report["rollout"]["chosen"])
        self.assertLess(report["rollout"]["avg_score"]["BJ"] - report["rollout"]["avg_score"]["pass"], 0.1)

    def test_a_six_point_win_rate_gap_switches(self):
        # 胜率 0.50 对 0.56,差 0.06 > 0.05;平均得分差 0.12,要换
        report = {}
        self.assertEqual(["BJ"], self.act(0.50, 0.56, report))
        self.assertEqual("BJ", report["rollout"]["chosen"])


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
