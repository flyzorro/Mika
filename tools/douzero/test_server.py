"""用 DouZero 自己的牌局随机发牌打到中途:只凭看得见的信息重建,出的牌要和拿完整牌局时一模一样。"""
import random
import unittest

from douzero.env.game import GameEnv

from server import DECK, NAMES, SEATS, Advisor, WEIGHTS

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


if __name__ == "__main__":
    unittest.main()
