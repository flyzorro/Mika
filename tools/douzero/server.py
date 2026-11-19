"""
斗地主出牌服务:给 DouZero 一局的已知信息,回下一手出什么。只在本机或局域网里给 Mika 用。

对手的手牌看不见,DouZero 也不需要逐张知道:它只看「其他两家合起来还剩哪些牌」和各家张数。
所以把看不见的牌随便分给两家凑够张数,再把识别到的出牌历史在 DouZero 的牌局里从头重放一遍:
重放时每一手都要合规,识别漏了一手、认错一张,这里就会对不上,直接报 history_mismatch,调用方退回「提示」。

POST /act  {"seat": "landlord" | "landlord_down" | "landlord_up",
            "hand": ["3", "3", ..., "BJ"],         # 自己此刻的手牌
            "bottom": ["9", "8", "7"],              # 三张底牌
            "history": [["3"], [], ["5", "?"], ...],# 从地主第一手起每一手,[] 是不出,? 是认不准的一张
            "auction": {"bid": {"landlord": 3},     # 可选:各座位最后叫的分(0..3),没有 / null = 不知道
                        "double": {"landlord": true, # 可选:各座位加没加倍,没有 / null = 不知道
                                   "landlord_down": false,
                                   "landlord_up": null}}}
# auction 是这一局的叫分 / 加倍结果,只用来给「看不见的牌怎么分」加权:叫了三分、加了倍的那家更可能拿到强牌,
# 现场模拟的发牌和残局搜索的分法都按这份证据偏一点。v1 只看地主的叫分(叫三分),加倍按座位看;
# 我自己的叫分 / 加倍不参与。字段格式不对(座位名不认识、叫分不在 0..3、类型不对)就整块当没带,照常出牌,不报错。

? 是地主出的最后一张:右上角压着绶带,手机上常认不准。按牌规补:15 种点数挨个代进去,
凑得成合规牌型、压得过上家、牌还有剩的只有一种就补上;有几种都说得通时(单张、三带一的那一张),
手机可以在 ? 后面按字形从像到不像带上几个点数(如 "?5,6,9"),先试这几个,都说不通再试其余合规的点数,一个都不行才报 ambiguous_card。
猜错了,后面的出牌在重放里会不合规,照样对得出来。
-> {"play": ["5", "5"]} 或 {"error": "..."}

POST /bid  {"hand": [17 张]}  -> {"level": 3 | 0, "win": 0.62, "games": 300}   # 当地主的赢面,够了就叫三分;games 是 3 秒内实际打了几局

POST /double {"seat": "landlord" | "landlord_down" | "landlord_up" | "farmer",
              "hand": [...],                     # 地主 20 张(含底牌),农民 17 张
              "bottom": [三张]}                  # 三张明牌,归地主
-> {"double": true | false, "super": true | false, "win": 0.83, "games": 300}
   # 自己这一方的赢面,够了才建议加倍;只有地主、且赢面到 SUPER_DOUBLE_WIN 才回 super
   # games 是预算内实际打了几局:地主 / 单个农民座位 1.5 秒,farmer 两个座位各打一局(计两局)、共用 2 秒;
   # games 不到下限(其它座位 ROLLOUT_MIN_GAMES,farmer 2×)时 double / super 一律 false(赢面只是少数几局的比率)
# farmer 是认不出地主坐哪边时用的:两个农民座位各算一遍(共用时间预算),取较低的赢面
"""
import json
import math
import os
import random
import sys
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import torch
from douzero.dmc.models import model_dict
from douzero.env import move_detector as md
from douzero.env.env import get_obs
from douzero.env.game import GameEnv

# 用 -I 起服务时脚本所在目录不在 sys.path 里:残局搜索和服务放在一起
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import endgame  # noqa: E402
from endgame import is_bomb  # noqa: E402

SEATS = ["landlord", "landlord_down", "landlord_up"]
RANKS = {"3": 3, "4": 4, "5": 5, "6": 6, "7": 7, "8": 8, "9": 9, "10": 10, "J": 11, "Q": 12, "K": 13,
         "A": 14, "2": 17, "SJ": 20, "BJ": 30}
NAMES = {v: k for k, v in RANKS.items()}
DECK = [r for r in RANKS.values() if r < 20 for _ in range(4)] + [20, 30]
# 叫分:当地主自己跟自己打这么多局,赢面到这个数就叫三分,不到就不叫。闯关里农民靠不住电脑队友,地主赢了分数还翻倍
BID_GAMES = 300
# 地主输赢都按两份算,叫地主的期望是 4×赢面−2:过 0.5 就不亏,三家都不叫重发的期望约为 0;暂时提高余量取 0.65,待配对评估和校准
BID_WIN = 0.65
# 加倍:同一手牌自己跟自己打这么多局看这一方的赢面。80 局只能分到 ±0.06,0.8 和 0.74 分不开;
# 300 局约 ±0.03。加倍只有约 5 秒,80 局在本机实测 0.75~1.35 秒,300 局最慢要 5 秒:
# 叫分最多打 [ROLLOUT_BUDGET_S] 秒;加倍单独用 [DOUBLE_BUDGET_S],到点就按已经打完的局数算
# (至少 [ROLLOUT_MIN_GAMES] 局)。手机读超时 4 秒,1.5 秒的模拟还留 2.5 秒给网络和点按钮。
DOUBLE_GAMES = 300
ROLLOUT_BUDGET_S = 3.0
# 地主刚定下来那 2 秒左右是底牌插进手牌的动画,手机这时还看不到加倍按钮,5 秒的加倍窗口只剩约 3 秒。
# 2026-11-17 15:23 精英场:54.16 叫三分当上地主 → 57.85 手机第一次看到加倍画面 → 问 /double(3 秒,
# 回 win 0.97、super)→ 00.88 游戏服务器记「不加倍」(超时)→ 01.02 手机才点下「加倍」,晚了 0.13 秒,
# 少了一次 ×2。所以加倍只模拟 1.5 秒,叫分仍用 ROLLOUT_BUDGET_S。
# 2026-11-17 本机(Apple M3 Max / macOS 26.7.2 / Python 3.14 venv)实测 12 手真实牌(地主都会叫):
# 地主 1.5 秒打 65~157 局(中位 102),3 秒 130~300 局(中位 202);赢面逐手差(1.5−3.0)中位 -0.010、
# 平均 +0.002;加倍 / 超级加倍决定 11/12 一致(一手 0.816 vs 0.765 在 0.8 门槛上翻转),
# 另 16 手随机 20 张牌 16/16 一致。单个农民座位 1.5 秒 78~123 局(中位 94),3 秒 155~235 局,决定 12/12 一致。
DOUBLE_BUDGET_S = 1.5
# seat="farmer" 两个农民座位共用预算,每副牌要打两局:1.5 秒实测 12 手真实牌只有 35~57 局(中位 48),
# 有一手不到 ROLLOUT_MIN_GAMES;2.0 秒 45~79 局(中位 67),全部过线,决定 12/12 与 3 秒一致。
# 2 秒是上限,不再加。极端强农民手(双王 + 四个 2)剩下的牌太弱,条件抽样 2 秒只采纳 31 副,
# 会按不加倍处理;真实牌局里地主拿剩下的牌叫三分几乎不会出现。
FARMER_DOUBLE_BUDGET_S = 2.0
ROLLOUT_MIN_GAMES = 40
# 赢面到了这个门槛才加倍。只点黄色「加倍」,绝不点绿色的「超级加倍」,金豆省着用。
DOUBLE_WIN = 0.8
# 农民单独的门槛:农民加倍只翻自己这一行(底分 × 炸弹 × 地主加倍 × 自己加倍),真实赢面 > 0.5 就是正期望;
# 但估值有误差,留余量。2026-11-16 用 calibrate_double.py 跑了 2000 副地主会叫的牌、两个农民座位共 4000 行:
# 估值 >= 0.65 的 135 行实际赢 0.607(0.60 档只有 1 行,0.70 以上 62 行赢 0.581),所以取 0.65。
FARMER_DOUBLE_WIN = 0.65
# 超级加倍:只有地主、且赢面到 SUPER_DOUBLE_WIN 才建议点绿色「超级加倍」,农民永远不点。
# 2026-11-16 用 calibrate_double.py 的地主模式跑了 1000 副地主会叫的牌(条件抽样用 landlords_would_call 估值):
# 估值 0.85 档 155 副实际赢 0.858,0.90 档 178 副赢 0.871,1.00 档 165 副赢 0.921;
# 实际胜率 >= 0.85 的最低估值档就是 0.85(见 double-calibration-2026-11-16.md)。
SUPER_DOUBLE_WIN = 0.85
# 条件抽样:地主这 20 张(17 张随机 + 3 张底牌)会不会叫。用 DouZero 地主模型对开局第一手所有合规出牌的
# 最高估值:单副一次前向约 3 毫秒,按 BID_BATCH 副攒成一批一次前向约 0.5 毫秒一副。
# 估值是这副牌在 DouZero 眼里的期望得分;2026-11-16 用 260 副随机 20 张牌、每副 40 局自对弈标定:
# 估值 0 附近实际赢面 0.56,当时和 BID_WIN 0.55 对齐,所以 BID_VALUE 取 0。
# BID_WIN 暂提至 0.65 后不再对齐;农民条件抽样保持原门槛,等待单独校准。
BID_VALUE = 0.0
# 条件抽样一次判多少副:攒成一批前向,弱地主牌被丢掉时也不至于把 2 秒预算全花在判断上
BID_BATCH = 32
# 几手分数几乎一样时 DouZero 基本是随手挑:差不到这个数就优先不拆三条/对子/炸弹。
# 实机那手拆 10 10 10 去顶队友的 6 只比单张 9 高 0.009;再大就会盖住真正该拆的一手,0.05 只收真正的平局。
KEEP_MARGIN = 0.05
# 分数接近时现场模拟:2026-11-16 10:16 精英场,我是地主(BJ 2 A A A K K Q J 10 10 8 6 5 5 5 5 3 3),
# 下家农民用单张 2 顶住。DouZero 给不出不出的 +0.121 对 BJ 的 +0.060,只差 0.06 就信了分数选了不出。
# 按看得见的牌随机发几百副、三家都用 DouZero 打分打到底:不出赢 0.79、BJ 赢 0.69,分数那点差距并不可靠。
# 所以顶两手的分数差不到 CLOSE_MARGIN 就现场模拟:取 CLOSE_MARGIN 以内最多 CLOSE_TOP 手,同一批发牌比出牌,
# 平均得分高出 2×SWITCH_MARGIN 才换(不炸时得分 = 2×胜率−1,门槛也要 ×2),否则仍听 DouZero。
# 2.5 秒内每手至少打 ACT_ROLLOUT_MIN_GAMES 局,手机这一手等 4 秒够。
# 但开局每局都长:2026-11-16 10:58:14 精英场,我当地主的第一手(2 2 A K K Q Q J 9 8 8 8 7 7 6 5 5 5 5 4),
# 2.5 秒只跑了 43 局,不到 60 局就白模拟了;单张 9 赢面 0.4 比单张 6 的 0.27 高 13 个点也换不掉。
# 当时只给开局(三家手里还有 45 张以上)把下限降到 30 局;
# 局数少更吵,不到 ACT_ROLLOUT_FULL_GAMES 局时改用 OPENING_SWITCH_MARGIN 才换。
# 中局下限原来是 60:2026-11-19 10:44:32 普通场,我是地主上家,手 2 10 9 9 9 8 7 6 4 3,模拟 59 局:
# 6 7 8 9 10 平均得分 −0.083、DouZero 那手单张 10 −1.085,差了 1 分,只因少 1 局整个作废,拆了顺子。
# 胜率 0.8 时 60 局和 50 局的标准误差是 0.052 对 0.057,差不多;先降到 50(以后改成按配对标准误差判断)。
# 后半局一次模拟最多 8 手,最后两手常常只有 35~45 局,50 也不够:2026-11-19 11:52 普通场,我是地主下家,
# 11:52:36 K K 2.889 对 DouZero 的 2 2 1.422(45 局)、11:52:45 7 7 3.5 对 7 7 7 1.571(35 局)都被整个作废。
# 所以开局 / 中局 / 后半局下限统一用 30 局,局数不到 ACT_ROLLOUT_FULL_GAMES 时换手门槛用更严的 OPENING_SWITCH_MARGIN。
# 2026-11-19 10:22:39 普通场,我是地主(BJ 2 2 2 A Q Q 10 10 8 8 8 7 7 6 5 5 5),地主上家农民出单张 2、只剩 4 张,
# 三家合计 37 张。DouZero:不出 -0.912、BJ -1.053,差 0.141,按 0.1 不模拟就信了分数,地主上家拿回出牌权,
# 这一局输了 60 万。重放这一手把门槛临时放到 0.5:每次 103 局,不出胜率 0.81 / 平均得分 0.62~0.64,
# BJ 0.85 / 0.728,平均得分差约 0.1,3 次里只有 1 次越过 2×SWITCH_MARGIN 换成 BJ——换不换都在噪声里,
# 但至少该模拟,所以取 0.15。
CLOSE_MARGIN = 0.15
CLOSE_TOP = 3
SWITCH_MARGIN = 0.05
OPENING_SWITCH_MARGIN = 0.1
ACT_ROLLOUT_BUDGET_S = 2.5
ACT_ROLLOUT_GAMES = 300
ACT_ROLLOUT_MIN_GAMES = 30
ACT_ROLLOUT_FULL_GAMES = 50
# 边打边淘汰时,淘汰轮每个候选至少打这么多局才淘汰:8 手按 1/4 预算在这台机器上只分到 7 局,
# 7 局里 0.76 和 0.62 的差距一半是运气(见 close_rollout 里 13:10:11 那一手,8 就是被 7 局淘汰掉的)。
HALVING_MIN_ROUND_GAMES = 12
# 农民接地主的牌:现场模拟最后选了不出、而某手不炸的压牌和不出差不到这么多倍「配对差的标准误差」时,
# 改出压牌。2026-11-18 22:32:33 普通场,我是地主上家,手牌 5 5 6 7 7 7 7 8 A A,地主出 8 8 队友不出。
# 模拟 71 局:不出 0.85 / A A 0.80、平均得分 1.239 / 1.099,差 0.14,配对标准误差 0.19,当时回了不出;
# 地主接着走 AKQJ109,我们只能炸 7777,炸弹让输的翻倍。按可见信息明牌算到底 A A 0.977、不出 0.955:
# 局数只够分出 ±4 个点时,压一手比把出牌权交回去更稳,所以差在 1 个标准误差内就压(见 beat_landlord)。
BEAT_MARGIN_SE = 1.0
# 一手牌总共只给这么多秒:手机 /act 最多等 4 秒(连接 2 秒 + 读 4 秒,失败还重试一次),超了就当服务挂了。
# 2026-11-16 12:18:11 土豪场,我是地主下家,手里 A 5 4 4 4,三家还剩 19 张:残局搜索先花掉 1.5 秒没结论,
# 现场模拟又打满 2.5 秒,回复 4903 毫秒;手机 4 秒超时,标成 douzero_unreachable 重发同一手,三次各约 4020 毫秒,
# 最后按简单规则打完这一局。所以从 Agent.act 进来起总共只给 3.5 秒(手机端读超时 4 秒,留 0.5 秒网络余量),
# 残局搜索、两手出完、现场模拟、比手数都在这一个预算里分:
# 残局搜索最多 ACT_ENDGAME_BUDGET_S 秒,现场模拟用 min(2.5, 剩下的 − 0.1 − 比手数的 0.2) 秒,
# 剩下的不到 ACT_ROLLOUT_MIN_S 就整个不模拟,直接听 DouZero 的。
ACT_TOTAL_BUDGET_S = 3.5
ACT_BUDGET_SAFETY_S = 0.1
ACT_ROLLOUT_MIN_S = 0.8
# 残局搜索的时限要够把几百种分法(带 likelihood)算完:2026-11-17 12:14:32 精英场,我是地主上家(农民),
# 手里 SJ A J 7 6 6 5,地主剩 6 张、队友剩 5 张,分法 462 种。本机(Apple M3 Max / macOS 26.7.2 / Python 3.14 venv)
# 带 likelihood 实测 1.58~1.84 秒(6 次);原来给 1.5 秒,1.51 秒超时返回 None,退回模拟按均匀发牌出了 J,
# 可明牌求解只有 6 6 能赢。给 2.5 秒,比实测最慢的 1.84 秒还多 0.66 秒;扣掉搜索,剩下的 1.0 秒还在
# ACT_ROLLOUT_MIN_S 之上,现场模拟不会被整个跳过。
ACT_ENDGAME_BUDGET_S = 2.5
# 两手出完捷径要盖掉 DouZero 时,DouZero 首选比捷径那手高出这么多分就不盖,走原来的流程。
# 2026-11-19 12:13:20 精英场那手(DouZero 7 9 9 9 +2.282、捷径 Q Q Q Q 只有 +1.335,差 0.95)就是这么被盖掉的。
# 取 0.5:比 KEEP_MARGIN / CLOSE_MARGIN(0.05 / 0.1)大得多,只拦「DouZero 明显更看好」的情况。
# 按 eval_finish_two.py 的配对自对弈 210 副(主角地主 / 农民,改前 vs 改后带这个门槛)定:
# 配对差总额 +27、每副 +0.129(95% CI −0.009~+0.266),没亏;捷径触发 88 -> 90 次,没有挡掉有用的捷径。
SHORTCUT_MARGIN = 0.5
# 对手牌少、又没进残局搜索时,现场模拟不再均匀发牌:把牌最少的那家所有可能的剩牌枚举出来,
# 按他最近两手出牌的 likelihood(含「留着炸弹先走杂牌」)加权,每种不同的剩牌算一个假设。
# 2026-11-19 15:08:02 那手他剩 4 张、分法 207 种,枚举 + 打分实测约 0.3 秒;剩 5 张分法 398 种
# (8568 种摆法)约 0.7 秒,都还在现场模拟 2.5 秒的预算里。再往上枚举就吃不满模拟局数,
# 所以从 5 起,超时 / 分法太多(MAX_SPLITS)就退回原来的随机发牌。
SHORT_HAND = 5
# 残局分法似然:每家对手取最近 [LIKELIHOOD_MOVES] 手不是不出的出牌,各手概率相乘。取 2 是因为
# 2026-11-16 20:51:41 土豪场那一手,下家倒数第二手 10 10 10 + Q:如果手里还留着 4、9 这种低单张,
# 正常会拿小牌当带牌而不是 Q。只看最近一手 7 7 看不出这点,两手里的三带一才把「剩零散小牌」的分法压下去。
# 温度见 LIKELIHOOD_TEMPERATURE;对每手都用动作 softmax(该手在当时所有合法出牌里的概率),不掺绝对分值。
LIKELIHOOD_MOVES = 2
# 残局分法似然的温度:对手当时那副牌上,对那一手所有合法出牌的 DouZero 分数做 softmax 时除以它。
# 2026-11-16 20:51:41 土豪场那一手(地主 BJ SJ 2 Q 9 9,下家刚出 7 7 只剩两张)离线扫过 0.005~1.0:
# 0.2(只看最近一手时的温度)这一手仍是 9 9;取最近两手后 0.03 以下才翻成王炸,0.05 又回到 9 9。
# 0.02 时王炸的加权权重是 9 9 的 6.6 倍、不出的 16 倍;0.01 更极端,0.03 只剩 2 倍。取 0.02 留余量。
LIKELIHOOD_TEMPERATURE = 0.02
# 残局分法似然的下限:软证据(出牌历史的 DouZero 动作概率、叫分 / 加倍)把一种分法相对最可能的分法压低时,
# 权重不少于这个比例,不再往 0 压。硬约束(能一手走完却不出)仍是 0。
# 2026-11-19 10:53:07 普通场,我是地主上家(农民),手 14 张,地主剩 4 张、队友 5 张,看不见的是
# 4 4 9 J A A 2 2 BJ;地主之前已经把 2 2 2 2 拆开打过两次单张 2。温度 0.02 下 DouZero 给
# 「地主还拿着另外两张 2」的 12 种分法压到 0~1.7e-5,残局里炸弹 5 5 5 5 的赢面 99% 全是假的,
# 按 ×2 一算就炸了队友,翻倍输掉。只有最近两手出牌、模型给的概率远没有这么锋利,不能让软证据把
# 一整类分法判死。取 0.02:2026-11-19 在 48 副配对自对弈上扫 0.02 / 0.05,
# 配对差每副平均 −0.042(95% CI −0.123~+0.040)对 +0.021(−0.020~+0.062),两个下限分不出好坏;
# 取更小的 0.02(最可能的分法最多 50 倍于最不像的分法),离原来不加下限最近,又已经能把 1e-5 那一档拉回来。
LIKELIHOOD_FLOOR = 0.02
# 叫分 / 加倍先验:每家牌力 s = 用自己座位的 DouZero 模型给开局整手牌(手里剩的 + 出过的,地主含底牌)
# 打分,取所有合法出牌里的最高分。每条已知证据乘一个 logistic 似然因子:P = sigmoid((s - T) / AUCTION_TAU)。
# 2026-11-17 用 calibrate_auction.py 随机发 4000 副牌(seed 20261117,地主 4000 手、两个农民座位共 8000 手)
# 标定:地主牌力的 q35 / q65 / q80 = -0.933 / -0.021 / 0.595,农民牌力的 q80 = 0.237。
# T_bid 取地主 q65(叫三分的手牌约占 35%),T_dbl 取 q80(加倍约占 20%),τ 取地主 q35~q65 分位间距的
# 一半 0.456。按这组值在随机牌上的边际概率:P(叫三分)=0.385、P(地主加倍)=0.242、P(农民加倍)=0.291
# (T 由分位数定,logistic 较宽时边际会偏离 35% / 20%,但方向与门槛不变)。
# 现场模拟有 auction 时一次发这么多手候选做 importance resampling:按证据座位把这 K 手攒成一批前向。
# 2026-11-17 实测(2.5 秒模拟预算):K=8 时中局 72~75 局、后半局 88~100 局;K=4 时中局 80~86、
# 后半局 123~127;K=2 时中局 82~99、后半局 150~158。K=8/4 要把 8/4 手都算一遍,比旧拒绝采样平均
# 只算的 2.48 手(中局)还贵,模拟局数反而比改前更少;K=2 才回到改前的中局水平(最终 3 次 92~94),
# 后半局还从 115 涨到 150 左右,所以取 2。
AUCTION_MAX_TRIES = 2
AUCTION_BID_T = -0.021
AUCTION_DOUBLE_T = {"landlord": 0.595, "farmer": 0.237}
AUCTION_TAU = 0.456
# 后半局不管分差都现场模拟:三家手里合起来只剩 LATE_CARDS 张时,DouZero 分数最高的 LATE_TOP 手(不出也算一手)都模拟一遍。
# 2026-11-16 11:43:53 土豪场,我是地主下家,手里 2 2 K Q J 10 9 8 8 8 4 3 3,三家还剩 33 张。
# DouZero 给 8 9 10 J Q K +0.736、8 9 10 J Q +0.627,只差 0.109,按 CLOSE_MARGIN 不模拟,信分数出了 8..K,输了。
# 离线按看得见的牌打 400 局:3 3 8 8 8 赢 0.98、8..K 只赢 0.82;明牌搜索也确认 8..K 必输。
# 后半局每手都短、2.5 秒够每手打 60 局以上,就不再拿分差当理由;仍要高出 SWITCH_MARGIN 才换。
LATE_CARDS = 35
LATE_TOP = 8
WEIGHTS = os.environ.get("DOUZERO_WEIGHTS", os.path.join(os.path.dirname(__file__), "baselines", "douzero_ADP"))


def bid_level(win):
    """赢面到了门槛就叫三分,不到就不叫。"""
    return 3 if win >= BID_WIN else 0


def should_double(win, seat="landlord"):
    """赢面到了门槛才建议加倍:地主 0.8,农民用单独的门槛。"""
    return win >= (DOUBLE_WIN if seat == "landlord" else FARMER_DOUBLE_WIN)


def should_super(win, seat="landlord"):
    """只有地主、赢面到 SUPER_DOUBLE_WIN 才建议超级加倍;农民永远不点绿色。"""
    return seat == "landlord" and win >= SUPER_DOUBLE_WIN


class Mismatch(Exception):
    pass


class Auction:
    """一局的叫分 / 加倍结果:只留下确定知道的座位;没有任何证据时 parse_auction 返回 None。"""

    def __init__(self, bid, double):
        self.bid = dict(bid)
        self.double = dict(double)

    def __repr__(self):
        return f"Auction(bid={self.bid!r}, double={self.double!r})"

    def evidence(self, seat):
        """这个座位有哪些已知证据:("bid", 3) / ("double", True/False)。叫分 v1 只看地主叫三分。"""
        out = []
        if seat == "landlord" and self.bid.get("landlord") == 3:
            out.append(("bid", 3))
        if seat in self.double:
            out.append(("double", self.double[seat]))
        return out

    def seats(self, me):
        """要按证据加权的对手:有证据、又不是我自己(我自己的叫分 / 加倍不参与)。"""
        return [s for s in SEATS if s != me and self.evidence(s)]

    def log_weight(self, me, strength, seats=None):
        """这一局的证据在 [strength] 这种分法下的对数似然(<=0);[strength] 是 {座位: 开局整手牌的牌力}。

        [seats] 可选:只算这几家的证据(同 Advisor.likelihood 的 seats)。
        """
        total = 0.0
        for seat in (self.seats(me) if seats is None else [s for s in self.seats(me) if s in seats]):
            for kind, value in self.evidence(seat):
                p = auction_probability(seat, kind, value, strength[seat])
                total += math.log(min(max(p, 1e-9), 1 - 1e-9))
        return total


def _sigmoid(x):
    """数值稳定的 sigmoid:太负的 exp 会溢出。"""
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


def auction_probability(seat, kind, value, strength):
    """一家牌力 [strength] 时出现这条证据的概率:叫三分 / 加倍是 P,没加过倍是 1-P。"""
    if kind == "bid":
        return _sigmoid((strength - AUCTION_BID_T) / AUCTION_TAU)
    threshold = AUCTION_DOUBLE_T["landlord" if seat == "landlord" else "farmer"]
    p = _sigmoid((strength - threshold) / AUCTION_TAU)
    return p if value else 1.0 - p


def parse_auction(raw):
    """把 /act 的 auction 字段规范化成 Auction;没带、全是不知道、或者格式不对都返回 None。

    格式不对就整块忽略(照常出牌,不报错):bid / double 必须是对象,座位名必须是 SEATS 里的,
    bid 的值是 0..3 的整数(不是 bool),double 的值是 bool;单个座位的 null 表示那个座位不知道。
    """
    if not isinstance(raw, dict):
        return None
    bids, doubles = raw.get("bid"), raw.get("double")
    bids = {} if bids is None else bids
    doubles = {} if doubles is None else doubles
    if not isinstance(bids, dict) or not isinstance(doubles, dict):
        return None
    bid = {}
    for seat, value in bids.items():
        if seat not in SEATS:
            return None
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 3:
            return None
        bid[seat] = value
    double = {}
    for seat, value in doubles.items():
        if seat not in SEATS:
            return None
        if value is None:
            continue
        if not isinstance(value, bool):
            return None
        double[seat] = value
    if not bid and not double:
        return None
    return Auction(bid, double)


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


def is_simple_combo(move):
    """单张、对子、三条,以及带牌的三带一/三带二。顺子、连对、飞机、炸弹、王炸都不算。"""
    counts = tuple(sorted(Counter(move).values()))
    return counts in ((1,), (2,), (3,), (1, 3), (2, 3))


def splits_bomb(move):
    """这手是不是把某个点数的四张拆开用了:四带二、四带两对、飞机带翅膀里塞了炸弹等,而不是单独当炸弹出。
    拆开就少一次 ×2;四张一起出、王炸、不出都不算。"""
    return md.get_move_type(sorted(move))["type"] != md.TYPE_4_BOMB \
        and any(n == 4 for n in Counter(move).values())


def single_kickers(move):
    """三带一/飞机带单里当带牌的单张;三条本身(哪怕三条是 A/2)不算。飞机带对不在其中。"""
    kind = md.get_move_type(sorted(move))["type"]
    if kind not in (md.TYPE_6_3_1, md.TYPE_11_SERIAL_3_1):
        return []
    return [rank for rank, n in Counter(move).items() if n == 1]


def straight_runs(hand):
    """手里的点数连成的顺子段(3 到 A;2 和王不连):[[3, 4, 5, ...], ...]。"""
    runs = []
    for rank in sorted({r for r in hand if r < RANKS["2"]}):
        if runs and rank == runs[-1][-1] + 1:
            runs[-1].append(rank)
        else:
            runs.append([rank])
    return runs


def loose_single(rank, hand, rest):
    """[rank] 能不能当换带牌的零散小单张:手里只有一张、不超过 K;顺子看 [rest](出完当前的牌以后剩下的手牌),
    在里面 5 张以上的顺子中,只有当拿走它顺子还剩 5 张(在顺子两头且顺子至少 6 张)时才拿得走。"""
    if Counter(hand)[rank] != 1 or rank > RANKS["K"]:
        return False
    for run in straight_runs(rest):
        if rank in run and len(run) >= 5:
            return len(run) >= 6 and rank in (run[0], run[-1])
    return True


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


def move_label(move):
    """一手牌在诊断里的名字:不出叫 pass,其余按点数从小到大写出来(如 "5 5 5 5")。"""
    return " ".join(NAMES[c] for c in move) if move else "pass"


def late_picks(order, actions, top=LATE_TOP):
    """后半局候选:每种牌型先取 DouZero 分数最高的一手,名额还有剩再按分数从高到低补满。

    [order] 是按 DouZero 分数从高到低排好的合法出牌下标。牌型按 DouZero 的 move_detector.get_move_type 区分,
    「不出」也算一种。牌型种类比 [top] 还多时,只留代表手分数最高的前 [top] 种。
    DouZero 自己那手就是 order[0],必然是它那种牌型的第一个代表,天然在候选里。
    """
    kinds = {i: md.get_move_type(sorted(actions[i]))["type"] for i in order}
    seen, reps = set(), []
    for i in order:
        if kinds[i] in seen:
            continue
        seen.add(kinds[i])
        reps.append(i)
    picks = reps[:top]
    if len(picks) < top:
        taken = set(picks)
        for i in order:
            if len(picks) >= top:
                break
            if i not in taken:
                picks.append(i)
    return picks


def opening_state(seat, hand):
    """[seat] 开局时拿着整手 [hand] 的 info set:自己领出,其他两家只按张数算。

    给牌力打分用。三家开局时互相看不见,other_hand_cards 就是「除了我这手牌以外的全部牌」,
    不掺这种分法里对手具体拿了什么,所以同一手牌的牌力只由它自己决定,换个分法也不变。
    """
    hand = sorted(hand)
    return SimpleNamespace(
        player_position=seat,
        player_hand_cards=hand,
        other_hand_cards=sorted(minus(DECK, hand)),
        num_cards_left_dict={s: (20 if s == "landlord" else 17) for s in SEATS},
        three_landlord_cards=[],
        card_play_action_seq=[],
        legal_actions=[list(m) for m in sorted(endgame.moves(tuple(hand), ()))],
        last_move=[],
        last_two_moves=[[], []],
        last_move_dict={s: [] for s in SEATS},
        played_cards={s: [] for s in SEATS},
        bomb_num=0,
        last_pid=None,
        all_handcards=None,
    )


def history_frame(history, index):
    """第 [index] 手之前那些不随分法变的东西:每家从那手起又出了哪些牌、之前出过哪些、要压的牌。

    返回 (after, before, last_move_dict, bomb_num, last):[after] 是每家在第 [index] 手及以后出的牌
    (倒推当时手牌用),[before] 是之前出过的(模型的 played_cards),[last] 是当时要压的那手。
    """
    after = {s: [] for s in SEATS}
    before = {s: [] for s in SEATS}
    last_move_dict = {s: [] for s in SEATS}
    bomb_num = 0
    for j, move in enumerate(history):
        seat = SEATS[j % 3]
        if j >= index:
            after[seat] += move
            continue
        before[seat] += move
        last_move_dict[seat] = list(move)
        if md.get_move_type(list(move))["type"] in (md.TYPE_4_BOMB, md.TYPE_5_KING_BOMB):
            bomb_num += 1
    if index == 0:
        last = []
    elif history[index - 1]:
        last = list(history[index - 1])
    elif index >= 2:
        last = list(history[index - 2])
    else:
        last = []
    return after, before, last_move_dict, bomb_num, last


def history_state(history, hands, seat, index, frame, without=()):
    """把 [history] 重放到第 [index] 手之前,用三家此刻剩的 [hands] 倒推出当时的手牌,造出 [seat] 那时的 info set。

    第 [index] 手是 [seat] 出的:他当时的手牌 = 此刻剩的牌 + 他在这手及以后出的牌。别的两家同理。
    legal_actions 用 endgame.moves 生成(同样剔掉大小王一起当带牌),模型在自己的席位上打分。
    [without] 是先从 [seat] 手里放一边的牌(他留着没出的炸弹 / 王炸):按剩下的牌打分时用,
    放一边的牌算回看不见的牌里。
    """
    after, before, last_move_dict, bomb_num, last = frame
    hand_before = {s: sorted(list(hands[s]) + after[s]) for s in SEATS}
    removed = tuple(sorted((Counter(without) & Counter(hand_before[seat])).elements()))
    if removed:
        left = Counter(hand_before[seat])
        left.subtract(Counter(removed))
        hand_before[seat] = sorted(left.elements())
    legal = endgame.moves(tuple(hand_before[seat]), list(last))
    return SimpleNamespace(
        player_position=seat,
        player_hand_cards=list(hand_before[seat]),
        other_hand_cards=sorted(sum((hand_before[s] for s in SEATS if s != seat), []) + list(removed)),
        num_cards_left_dict={s: len(hand_before[s]) for s in SEATS},
        three_landlord_cards=[],
        card_play_action_seq=[list(m) for m in history[:index]],
        legal_actions=[list(m) for m in sorted(legal)],
        last_move=list(last),
        last_two_moves=[list(m) for m in history[max(0, index - 2):index]],
        last_move_dict=last_move_dict,
        played_cards=before,
        bomb_num=bomb_num,
        last_pid=None,
        all_handcards=None,
    )


def pass_frames(history, me):
    """[me] 的对手在能一手走完时却选择了不出的那些手:[(座位, 那手之后他出的牌, 当时要压的牌)]。

    不出时如果是新一轮领出(上家就是他自己,或两家都不出回到他)不算,领出不能不出。
    """
    out = []
    for i, move in enumerate(history):
        seat = SEATS[i % 3]
        if move or seat == me:
            continue
        after, _, _, _, last = history_frame(history, i)
        if last:
            out.append((seat, after[seat], last))
    return out


def fewer_plays(hand, keys, rates, scores, own, deadline=None):
    """模拟赢面差不多时,在赢面不低于 DouZero 那手([own])的候选里挑出完剩下手数最少的一手。

    [keys] 是候选手牌(已排序的元组),[rates] 是模拟赢面,[scores] 是 DouZero 分数。
    赢面比 [own] 低的候选不用手数翻案(模拟都说更差);拆了炸弹的候选也不翻案(少一次 ×2);手数一样多时挑赢面高的,
    再挑分数高的。返回 (选中的一手, 比过的手数),没有更少手的候选返回 None。一手都算不完(到 [deadline])也返回 None。
    """
    if deadline is None:
        deadline = time.monotonic() + endgame.MIN_PLAYS_BUDGET_S
    plays = {own: endgame.min_plays(endgame.minus(hand, own), deadline)}
    if plays[own] is None:
        return None
    picked = None
    for k in keys:
        if k == own or rates[k] < rates[own] or splits_bomb(k):
            continue
        plays[k] = endgame.min_plays(endgame.minus(hand, k), deadline)
        if plays[k] is None:
            return None
        if plays[k] < plays[own] and (picked is None or (
                plays[k], -rates[k], -scores[k]) < (plays[picked], -rates[picked], -scores[picked])):
            picked = k
    return None if picked is None else (picked, plays)


def paired_error(stat):
    """配对差均值的标准误差:[stat] = [Σ差, Σ差², 局数],差是同一批发牌下两手得分之差。
    局数不到 2 时返回 None。"""
    total, total_sq, n = stat
    if n < 2:
        return None
    mean = total / n
    variance = max(0.0, (total_sq - n * mean * mean) / (n - 1))
    return math.sqrt(variance / n)


def beat_landlord(infoset, keys, means, scores, played, pairs, games):
    """农民接地主的牌:现场模拟最后选了不出时,某手不炸的压牌和不出差不到一个标准误差,就改出这手压牌。

    只在「要压的是地主出的牌」时生效([infoset.last_pid] == "landlord"):桌上是队友的牌不动,
    我当地主也不动。炸弹 / 王炸不算压牌(翻倍,已有专门逻辑)。
    压牌要和决赛打一样多局([games])才认:淘汰得早、局数少的手不能因为标准误差大就翻案。
    平均得分差 = 不出 − 压牌;不超过 BEAT_MARGIN_SE 个配对标准误差([pairs] 见 successive_halving)才算在噪声内。
    在够格的手里挑平均得分最高的,再按 DouZero 分数。返回压牌,没有就返回 None。
    """
    if infoset.player_position == "landlord" or infoset.last_pid != "landlord" or () not in keys:
        return None
    best = None
    for k in keys:
        if not k or is_bomb(k) or played.get(k, 0) < games:
            continue
        error = paired_error(pairs[k]) if k in pairs else None
        if error is None or means[()] - means[k] > BEAT_MARGIN_SE * error:
            continue
        if best is None or (means[k], scores[k]) > (means[best], scores[best]):
            best = k
    return best


def sample_hands(infoset, rng):
    """只按看得见的信息随机发牌:别人两家合起来的手牌分给两家、张数对得上。
    我是农民时地主没打出去的底牌一定还在他手里,先把这几张留出来(sample 与 deal 是同一个意思)。"""
    me = infoset.player_position
    left = infoset.num_cards_left_dict
    unseen = list(infoset.other_hand_cards)
    rng.shuffle(unseen)
    hands = {me: list(infoset.player_hand_cards)}
    if me != "landlord":
        kept = list((Counter(infoset.three_landlord_cards) & Counter(unseen)).elements())
        hands["landlord"] = kept
        unseen = list((Counter(unseen) - Counter(kept)).elements())
    for s in [s for s in SEATS if s != me]:
        need = left[s] - len(hands.get(s, []))
        hands[s] = hands.get(s, []) + unseen[:need]
        unseen = unseen[need:]
    return {s: sorted(hands[s] + list(infoset.played_cards[s])) for s in SEATS}


def halve(alive, own, rates, scores):
    """一轮打完:按累计平均得分去掉较差的一半(向上取整),DouZero 选的那手 [own] 永远留着。

    留几名向上取整:8 手留 4、5~7 手留 3~4、3 手留 2、2 手不再淘汰。
    [own] 排在淘汰区时把它换进来、顶掉落选名次最高的那个,留的人数不变。返回按平均得分从高到低排好的名单。
    """
    keep = (len(alive) + 1) // 2
    ranked = sorted(alive, key=lambda k: (-rates[k], -scores[k], k))
    if len(ranked) <= 2:
        return ranked
    survivors = ranked[:keep]
    if own in alive and own not in survivors:
        survivors = ranked[:keep - 1] + [own]
    return survivors


def round_shares(count):
    """每个候选时每一轮拿多少预算:淘汰轮各拿 1/(淘汰轮数+2),最后两手拿剩下的。

    8 手是 [1/4, 1/4, 1/2]:第一轮每手约 0.6 秒,按每局 4 毫秒够打 12~15 局,先把差的 4 手筛掉;
    4 手再筛到 2 手;最后两手用掉剩下的一半时间。4~7 手有两次淘汰,也是 [1/4, 1/4, 1/2];
    4 手只有一次淘汰,给 [1/3, 2/3]。2~3 手淘汰和不淘汰差不多,原有的顶两手/开局行为就是三手用全部时间
    轮着打完,为了不动这些结果直接一轮打完(最多一次淘汰,这里取零次)。
    """
    if count <= 3:
        return [1.0]
    cuts, n = 0, count
    while n > 2:
        n = (n + 1) // 2
        cuts += 1
    share = 1 / (cuts + 2)
    return [share] * cuts + [1 - share * cuts]


def successive_halving(keys, own, scores, deal, play, budget, cap=ACT_ROLLOUT_GAMES,
                      min_round_games=HALVING_MIN_ROUND_GAMES, clock=time.monotonic,
                      pairs=None, anchor=None):
    """把 [budget] 秒分成几轮边打边淘汰:每轮还活着的候选都打同一批新发的牌,轮末去掉较差的一半。

    [deal]() 发一批牌(这一轮里所有还活着的候选共用),[play](hands, key) 拿这批牌打一局、返回这局的结果:
    赢了是正的 2^k、输了是负的 2^k(k 是这一手起打出的炸弹 / 王炸个数);布尔值 True/False 也照收。
    淘汰轮按 round_shares 分到的时间收手,但每个候选至少打满 [min_round_games] 局才淘汰——
    8 手按 1/4 预算只分到七八局,七八局的赢面差一半是运气,不能拿来淘汰。每个候选最多打 [cap] 局;
    最后一轮(只剩两手)用掉剩下的全部时间。轮末按累计平均得分淘汰,见 halve。
    传 [pairs] 时顺便记下每个候选和 [anchor](默认 [own])在同一批发牌上的得分差,给 beat_landlord 算配对标准误差:
    pairs[k] = [Σ差, Σ差², 局数]。
    返回 (每个候选累计赢的局数, 每个候选累计得分, played, 每轮 [留下的候选, 这一轮每个候选至少打了几局],
    最后还活着的候选)。赢的局数只用来在报告里算胜率;淘汰和最后比较都用平均得分,炸弹让这一局翻倍。
    [clock] 只为测试换成假时钟,线上就是 time.monotonic。
    """
    started = clock()
    anchor = own if anchor is None else anchor
    rollout_deadline = started + budget
    wins = {k: 0 for k in keys}
    earned = {k: 0.0 for k in keys}
    played = {k: 0 for k in keys}
    alive = list(keys)
    shares = round_shares(len(keys))
    rounds = []
    for i, _ in enumerate(shares):
        # 淘汰轮按预算的固定比例收手(但先打满下限);最后一轮把剩下的时间全用掉
        last = i == len(shares) - 1
        round_deadline = rollout_deadline if last \
            else min(rollout_deadline, started + budget * sum(shares[:i + 1]))
        floor = 0 if last else min_round_games
        before = {k: played[k] for k in alive}

        def round_over():
            if clock() >= rollout_deadline:
                return True
            return clock() >= round_deadline and all(played[k] - before[k] >= floor for k in alive)

        while not round_over() and any(played[k] < cap for k in alive):
            hands = deal()  # 这一批发牌,这一轮里所有候选都用
            results = {}
            for k in alive:
                if played[k] >= cap:
                    continue
                if clock() >= rollout_deadline:
                    break
                played[k] += 1
                result = play(hands, k)
                results[k] = float(result)
                if result > 0:
                    wins[k] += 1
                earned[k] += float(result)
            if pairs is not None and anchor in results:
                base = results[anchor]
                for k, value in results.items():
                    if k == anchor:
                        continue
                    stat = pairs.setdefault(k, [0.0, 0.0, 0])
                    difference = value - base
                    stat[0] += difference
                    stat[1] += difference * difference
                    stat[2] += 1
        games = min(played[k] - before[k] for k in alive)
        if len(alive) <= 2:
            rounds.append([list(alive), games])
            break
        means = {k: (earned[k] / played[k] if played[k] else 0.0) for k in keys}
        alive = halve(alive, own, means, scores)
        rounds.append([list(alive), games])
    return wins, earned, played, rounds, alive


class Agent:
    """同 DouZero 的 DeepAgent,只是权重按纯张量加载:checkpoint 是 pickle,不按张量读会执行文件里的代码。"""

    def __init__(self, seat, path):
        self.seat = seat
        self.model = model_dict[seat]()
        self.model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True))
        self.model.eval()
        # 分数接近时要不要现场模拟;测试里造局面时关掉,免得每走一步都花 2.5 秒
        self.rollout = True
        # 三家「只按 DouZero 打分」的对手,现场模拟时用;由 Advisor 建好后填进来
        self.plain = {}
        # 带 auction 时现场模拟要按对手牌力加权;由 Advisor 建好后填进来
        self.advisor = None

    def act(self, infoset, report=None, likelihood=None, auction=None):
        # 一手牌的总预算从这里开始算:下面 DouZero 打分、残局搜索、两手出完、现场模拟、比手数都只能用剩下的时间
        deadline = time.monotonic() + ACT_TOTAL_BUDGET_S
        # 斗地主经典版不允许大小王一起当带牌(飞机带单的翅膀、四带二的两张单牌):DouZero 的合法出牌里有,
        # 先剔掉,后面 DouZero 打分、残局搜索、两手出完都只看这份
        infoset.legal_actions = endgame.without_double_joker_kickers(infoset.legal_actions)
        if len(infoset.legal_actions) == 1:
            return infoset.legal_actions[0]
        values = self.values(infoset)
        move = infoset.legal_actions[int(torch.argmax(values))]
        # 剩的牌少时按明牌把各种分法算到底:有赢面更大的一手就换成它(赢面一样大的里挑 DouZero 打分最高的)。
        # 下面几条硬规则只在搜索也认为不吃亏时才换。搜索最多 ACT_ENDGAME_BUDGET_S 秒,总预算不够时只给剩下的
        left_s = deadline - time.monotonic()
        solved = endgame.best(infoset, budget=min(ACT_ENDGAME_BUDGET_S, left_s), likelihood=likelihood) \
            if left_s > 0 else None
        allowed = None
        if solved is not None:
            wins, total, plain_wins, plain_total = solved
            score = {tuple(sorted(a)): float(values[i]) for i, a in enumerate(infoset.legal_actions)}
            # 残局也按金额比:每手的值 = Σ 分法权重 ×(赢 +m / 输 −m),炸弹 / 王炸 m=2,其它 m=1。
            # wins 是赢的分法权重,输的是 total − wins;一手都赢不了时炸弹值 −2×总权重 < 非炸弹 −1×总权重,
            # 自然就不会炸,不用再单独判 top == 0。
            amount = {}
            for m in score:
                weight = wins.get(m, 0)
                amount[m] = (2 if is_bomb(m) else 1) * (2 * weight - total)
            top = max(amount.values())
            allowed = {m for m in score if amount[m] == top}
            picked = max(allowed, key=score.get)
            # 炸弹 / 王炸的 ×2 是拿「猜牌猜出来的赢面」翻倍:不信猜牌、只按摆法数算时它也得
            # 不比非炸弹里金额最高的一手差,不然就是不炸,改出那一手。×2 只会放大估值误差。
            if is_bomb(picked):
                plain = {m: (2 if is_bomb(m) else 1) * (2 * plain_wins.get(m, 0) - plain_total)
                         for m in score}
                alt = max((m for m in score if not is_bomb(m)), key=lambda m: amount[m], default=None)
                if alt is not None and plain[picked] < plain[alt]:
                    if report is not None:
                        report["bomb_veto"] = move_label(picked)
                    picked = alt
                    allowed = {m for m in score if amount[m] == amount[alt]}
            if tuple(sorted(move)) not in allowed:
                return self.swap_high_kickers(infoset, list(picked), ok=lambda m: tuple(sorted(m)) in allowed)
        mate = {"landlord_down": "landlord_up", "landlord_up": "landlord_down"}.get(infoset.player_position)
        if solved is None:
            # 剩的牌多、搜不动:自己能两手出完、第一手很难被压时,先出那手再出完(DouZero 偶尔先出一张会被王压的单张)
            others = [p for p in endgame.SEATS if p != infoset.player_position]
            left = infoset.num_cards_left_dict
            rival = tuple(sorted(infoset.last_move)) if [] in infoset.legal_actions else ()
            # 我是农民时,地主没出过的底牌一定还在他手里:先从看不见的牌里留出来,别让捷径按纯随机发牌估被压机会
            kept = tuple((Counter(infoset.three_landlord_cards) & Counter(infoset.other_hand_cards)).elements()) \
                if infoset.player_position != "landlord" else ()
            quick = endgame.finish_in_two(tuple(sorted(infoset.player_hand_cards)), infoset.legal_actions, rival,
                                          sorted(infoset.other_hand_cards), left[others[0]], left[others[1]],
                                          deadline=deadline - ACT_BUDGET_SAFETY_S, kept=kept)
            # 桌上那手是队友出的:捷径不能直接压他,把它和「不出」一起交给下面的现场模拟比;地主没有队友,照旧走捷径
            forced = None
            if quick is not None and tuple(sorted(move)) != quick[0]:
                scores = {tuple(sorted(a)): float(values[i]) for i, a in enumerate(infoset.legal_actions)}
                if mate and infoset.last_pid == mate and [] in infoset.legal_actions:
                    forced = [list(quick[0]), []]
                elif splits_bomb(quick[0]) or splits_bomb(minus(infoset.player_hand_cards, quick[0])):
                    # 捷径把炸弹拆成四带二(或塞进别的牌型)会少一次 ×2:回到正常流程,让炸弹能单独炸出去
                    pass
                elif max(scores.values()) - scores.get(quick[0], float("-inf")) > SHORTCUT_MARGIN:
                    # DouZero 首选比捷径那手高出一大截:分数差得开,捷径不该盖掉它,照原来的流程出
                    pass
                else:
                    return list(quick[0])
            # 分数只差一点点时,现场按看得见的牌模拟几百局再定;模拟了就以它为准,不再走下面那条不拆牌的规则
            rolled = self.close_rollout(infoset, values, move, deadline, report, auction, required=forced) \
                if self.rollout else None
            if rolled is not None:
                move, info = rolled
                if report is not None:
                    report["rollout"] = info
                # 局数不到下限,模拟不算数:不压队友
                if forced is not None and info["games"] < info["min_games"]:
                    move = []
            elif forced is not None:
                # 时间不够、模拟整个跳过:同样不压队友,也不退回捷径
                move = []
            else:
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
        if move and mate and infoset.last_pid == mate and infoset.num_cards_left_dict[mate] <= 3 \
                and len(move) < len(infoset.player_hand_cards) and [] in infoset.legal_actions and ok([]):
            move = []
        return self.swap_high_kickers(infoset, move, ok)

    def swap_high_kickers(self, infoset, move, ok):
        """三带一/飞机带单的带牌里有 A 或 2 时,换成手里最小的零散小单张:带牌只是凑数,留着 A/2 当控制牌。

        2026-11-16 10:04 实机:地主出 10 10 10 + 7,我 K K K + A;丢掉的 A 正是后来被 2 2 2 + J 压住的关键。
        没有能换的小牌、换完不合规、或者残局搜索认为原来那手更好时,保持 DouZero 的选择。
        """
        if not move or len(move) == len(infoset.player_hand_cards):
            return move
        high = [r for r in single_kickers(move) if r in (RANKS["A"], RANKS["2"])]
        if not high:
            return move
        hand = infoset.player_hand_cards
        rest = list(hand)
        for r in move:
            rest.remove(r)
        swapped = list(move)
        for rank in sorted(high):
            pick = next((c for c in sorted(set(rest)) if loose_single(c, hand, rest)), None)
            if pick is None:
                return move
            rest.remove(pick)
            swapped.remove(rank)
            swapped.append(pick)
        if sorted(swapped) not in [sorted(a) for a in infoset.legal_actions] or not ok(swapped):
            return move
        return swapped

    def keep_combos(self, infoset, values, move):
        """最高分那手是单张/对子/三条(含三带一/三带二)且拆牌时,在 KEEP_MARGIN 以内的不拆出牌里挑分数最高的。
        不出、顺子、连对、飞机、炸弹、王炸都不参与;都不拆或都拆就保持原选择。"""
        if not move or not is_simple_combo(move):
            return move
        top = float(values.max())
        hand = infoset.player_hand_cards
        best, best_score = None, None
        for i, action in enumerate(infoset.legal_actions):
            score = float(values[i])
            if not action or top - score > KEEP_MARGIN or breaks_combo(action, hand) \
                    or not is_simple_combo(action):
                continue
            if best_score is None or score > best_score:
                best, best_score = action, score
        return move if best is None else best

    def close_rollout(self, infoset, values, move, deadline=None, report=None, auction=None, required=None):
        """顶两手的分数差不到 CLOSE_MARGIN 时,按看得见的牌随机发一批牌,每一手都用 DouZero 打分打到完,比赢面。

        [required] 是必须放进候选的几手(桌上是队友的牌时,两手出完那手和「不出」):不管分差、牌型都塞进来,
        其余候选仍按原来的方式挑;不在合法出牌里的跳过。

        后半局(三家手里合起来不超过 LATE_CARDS 张)不管分差多大都模拟:每种牌型先留 DouZero 分数最高的一手,
        名额还有剩再按分数补满 LATE_TOP 手(见 late_picks)。
        模拟不把所有候选平分到最后:分几轮边打边淘汰,见 successive_halving。2026-11-16 13:10:11 土豪场,
        我是地主下家,三家还剩 35 张,回复 rollout games 27、min_games 60:8 手轮着模拟,2.5 秒下来每手只打了
        27 局,不到 60 局整个模拟作废,仍出 DouZero 的 6;可 8 的模拟赢面 0.75 比 6 的 0.68 高 7 个点。
        现在第一轮 8 手各打十来局、淘汰差的 4 手,第二轮 4 手再淘汰 2 手,最后两手用掉剩下的时间(一半预算),
        每手打满 60 局以上,8 才换得出来。同一批发牌拿来比所有还活着的候选(控制随机性)。
        最后的赢面按每个候选打过的全部局数累计算,一手最多打 ACT_ROLLOUT_GAMES 局,或者到时限为止。
        时限是 min(ACT_ROLLOUT_BUDGET_S, [deadline] 剩下的 − ACT_BUDGET_SAFETY_S − endgame.MIN_PLAYS_BUDGET_S):
        一手牌总共只有 ACT_TOTAL_BUDGET_S 秒,残局搜索花掉的要从这里扣,还要给比手数留出 0.2 秒。
        剩下的不到 ACT_ROLLOUT_MIN_S 就整个不模拟(回复里记 rollout_skipped: no_time),直接听 DouZero 的。
        每手不够 ACT_ROLLOUT_MIN_GAMES 局就当没模拟;局数不到 ACT_ROLLOUT_FULL_GAMES 时改用 OPENING_SWITCH_MARGIN 才换。
        差不到门槛时,赢面不低于 DouZero 那手的候选里出完剩下手数更少的胜出(见 fewer_plays);
        比手数最多 endgame.MIN_PLAYS_BUDGET_S 秒,且不超过总预算剩下的,没余量就不比。
        最后要是还选了不出、而我是农民、桌上正是地主的牌,某手不炸的压牌和不出差不到一个配对标准误差,
        就改出那手压牌(见 beat_landlord):把出牌权交回给地主往往就是让他一手走完。
        返回 (选定的一手, 写进回复的诊断);分数差得开、不用模拟时返回 None。
        """
        # 分数从高到低;同分时把 DouZero 挑的那手放最前,后面「仍听 DouZero」才有确定的参照
        own = tuple(sorted(move))
        order = sorted(range(len(infoset.legal_actions)),
                       key=lambda i: (-float(values[i]), tuple(sorted(infoset.legal_actions[i])) != own))
        top = float(values[order[0]])
        # 后半局不设分差门槛:每种牌型先留分数最高的一手,再按分数补满 LATE_TOP 手;不出也算一种牌型
        late = sum(infoset.num_cards_left_dict.values()) <= LATE_CARDS
        picks = late_picks(order, infoset.legal_actions) if late \
            else [i for i in order if top - float(values[i]) < CLOSE_MARGIN][:CLOSE_TOP]
        if required:
            index = {tuple(sorted(a)): i for i, a in enumerate(infoset.legal_actions)}
            forced = [index[k] for k in (tuple(sorted(m)) for m in required) if k in index]
            picks += [i for i in forced if i not in picks]
        if len(picks) < 2:
            return None
        # 总预算快用完了:模拟来不及打满下限,不如直接听 DouZero 的,别让手机等到超时
        if deadline is None:
            deadline = time.monotonic() + ACT_TOTAL_BUDGET_S
        left = deadline - time.monotonic()
        if left < ACT_ROLLOUT_MIN_S:
            if report is not None:
                report["rollout_skipped"] = "no_time"
            return None
        budget = min(ACT_ROLLOUT_BUDGET_S, left - ACT_BUDGET_SAFETY_S - endgame.MIN_PLAYS_BUDGET_S)
        candidates = [infoset.legal_actions[i] for i in picks]
        keys = [tuple(sorted(c)) for c in candidates]
        by_key = dict(zip(keys, candidates))
        labels = {k: move_label(c) for k, c in zip(keys, candidates)}
        scores = {k: float(values[i]) for k, i in zip(keys, picks)}
        # 同一手牌、同一段历史每次拿到同一个种子:手机重发同一手,答案也不变
        rng = random.Random(hash((tuple(sorted(infoset.player_hand_cards)),
                                  tuple(tuple(m) for m in infoset.card_play_action_seq))))
        history = [list(m) for m in infoset.card_play_action_seq]
        # 边打边淘汰:每个还活着的候选打同一批新发的牌,轮末去掉较差的一半,最后只剩两手用掉剩下的时间。
        # 带 auction 时发牌按证据加权(拒绝采样);对手牌少时按似然加权枚举他的剩牌。枚举 + 打分的时间
        # 也算在这份预算里,算不完就退回原来的发牌(见 Advisor.sample_hands / short_hand_plan)。
        sampler_deadline = time.monotonic() + budget
        sampler = lambda: self.advisor.sample_hands(  # noqa: E731
            infoset, rng, auction, deadline=sampler_deadline)
        # 局数上限显式传 ACT_ROLLOUT_GAMES:留作默认参数会被 def 时绑死,测试里改常量就不生效了
        # pairs 记每个候选和「不出」的逐局得分差:最后判断压牌是不是在噪声内要用配对标准误差
        pairs = {}
        anchor = () if () in keys else own
        wins, earned, played, rounds, finalists = successive_halving(
            keys, own, scores, sampler,
            lambda hands, k: self.play_out(hands, infoset.three_landlord_cards, history,
                                           infoset.player_position, by_key[k]),
            budget, cap=ACT_ROLLOUT_GAMES, pairs=pairs, anchor=anchor)
        games = min(played[k] for k in finalists)
        if games == 0:
            return None
        rates = {k: (wins[k] / played[k] if played[k] else 0.0) for k in keys}
        means = {k: (earned[k] / played[k] if played[k] else 0.0) for k in keys}
        chosen = move
        tie = None
        beat = None
        min_games = ACT_ROLLOUT_MIN_GAMES
        if games >= min_games:
            # 只在最后两手(淘汰掉的不再参选)里挑平均得分最高的;同分仍按 DouZero 分数顺序。
            # 炸弹让这一局翻倍,所以要按得分比,不能只看胜率。
            best = max((k for k in keys if k in finalists), key=lambda k: means[k])
            margin = SWITCH_MARGIN if games >= ACT_ROLLOUT_FULL_GAMES else OPENING_SWITCH_MARGIN
            # 不炸的局得分 ±1,平均得分 = 2×胜率−1:同样的胜率差,平均得分差是它的 2 倍。
            # 原来的 0.05 / 0.1 是按胜率定的门槛,按平均得分比要 ×2,不然等于把换手门槛减半。
            if means[best] - means[own] >= 2 * margin:
                chosen = by_key[best]
            else:
                # 差不到门槛时挑出完剩下手数更少的:2026-11-16 11:58:20 土豪场,我是地主上家领出,
                # 手里 BJ 2 J 9 8 7 6 5 5 5 5 4。DouZero 出 5 6 7 8 9,模拟 152 局 5..9 赢 0.59、4..9 赢 0.64,
                # 差不到 0.1 的门槛仍出 5..9;可 4..9 出完剩 BJ 2 J 5 5 5 三手,5..9 剩 BJ 2 J 5 5 5 4 四手。
                # 平均得分不低于 DouZero 那手的候选里手数更少就换;淘汰掉的候选也不再参选。
                # 手数比较也吃一手牌的总预算:截止时刻取 min(自己的 0.2 秒, 总预算剩下的 − 0.1),
                # 总预算没余量就不比了(模拟截止时间已经先给它留出 0.2 秒)。
                tie_left = deadline - time.monotonic() - ACT_BUDGET_SAFETY_S
                if tie_left > 0:
                    tie = fewer_plays(infoset.player_hand_cards, [k for k in keys if k in finalists],
                                      means, scores, own,
                                      time.monotonic() + min(endgame.MIN_PLAYS_BUDGET_S, tie_left))
                if tie is not None:
                    chosen = by_key[tie[0]]
            # 农民压地主的牌:模拟最后仍是「不出」、压牌又只在噪声内差一点时,压一手而不是把出牌权交回去。
            # 和 fewer_plays 叠加:上一步已经挑出非不出的手就不动;只有还停在「不出」才看这条。
            if not chosen:
                beat = beat_landlord(infoset, keys, means, scores, played, pairs, games)
                if beat is not None:
                    chosen = by_key[beat]
        info = {"games": games, "min_games": min_games, "budget_left": round(left, 2),
                "rates": {labels[k]: round(rates[k], 2) for k in keys},
                "avg_score": {labels[k]: round(means[k], 3) for k in keys},
                "rounds": [[[labels[k] for k in alive], n] for alive, n in rounds],
                "chosen": move_label(chosen)}
        if late:
            info["late"] = True
        if tie is not None:
            info["tiebreak"] = "fewer_plays"
            info["plays"] = {labels[k]: n for k, n in tie[1].items()}
        if beat is not None:
            info["beat"] = labels[beat]
            info["beat_margin"] = round(BEAT_MARGIN_SE * paired_error(pairs[beat]), 3)
        return chosen, info

    def play_out(self, hands, bottom, history, me, move):
        """按 [hands] 开局把 [history] 重放到现在,我先出 [move],之后三家都按 DouZero 打分打到完。

        返回这一局的得分:赢 +2^k、输 −2^k,k 是这一手起(含候选这一手)打出的炸弹 / 王炸个数。
        斗地主每个炸弹把整局输赢 ×2,所以模拟要按金额比,不能只比赢面;历史里已经出过的炸弹不算。
        """
        replay = Replay(history)
        players = {s: _RolloutAgent(replay, self.plain[s], move if s == me else None) for s in SEATS}
        env = GameEnv(players)
        env.card_play_init({**{s: list(hands[s]) for s in SEATS}, "three_landlord_cards": list(bottom)})
        while not env.game_over:
            env.step()
        k = env.bomb_num - sum(1 for m in history if is_bomb(m))
        score = 2 ** k
        return score if env.num_wins["landlord" if me == "landlord" else "farmer"] else -score

    def values(self, infoset):
        obs = get_obs(infoset)
        with torch.no_grad():
            return self.model.forward(torch.from_numpy(obs["z_batch"]).float(),
                                      torch.from_numpy(obs["x_batch"]).float(), return_value=True)["values"].flatten()

    def state_values(self, states, deadline=None):
        """一批 [seat] 的 info set 攒成一次前向,返回每个 info set 各手的 DouZero 分数。"""
        zs, xs, sizes = [], [], []
        for state in states:
            if deadline is not None and time.monotonic() > deadline:
                raise endgame.Timeout()
            obs = get_obs(state)
            zs.append(torch.from_numpy(obs["z_batch"]).float())
            xs.append(torch.from_numpy(obs["x_batch"]).float())
            sizes.append(len(state.legal_actions))
        with torch.no_grad():
            values = self.model.forward(
                torch.cat(zs), torch.cat(xs), return_value=True)["values"].flatten()
        if deadline is not None and time.monotonic() > deadline:
            raise endgame.Timeout()
        out, start = [], 0
        for size in sizes:
            out.append(values[start:start + size])
            start += size
        return out

    def opening_strengths(self, hands, deadline=None):
        """一批开局整手牌在这家模型眼里的牌力:所有合法出牌里的最高分。

        [hands] 是每家开局时的整手牌(手里剩的 + 已经出过的,地主含底牌)。只用一次前向,可以攒成一批。
        """
        return [float(v.max()) for v in self.state_values(
            [opening_state(self.seat, hand) for hand in hands], deadline)]


class Plain:
    """只按 DouZero 打分出牌,不做残局搜索:叫分时自己跟自己打几十局,要快。"""

    def __init__(self, agent):
        self.agent = agent

    def act(self, infoset):
        if len(infoset.legal_actions) == 1:
            return infoset.legal_actions[0]
        return infoset.legal_actions[int(torch.argmax(self.agent.values(infoset)))]


class _RolloutAgent:
    """现场模拟里的玩家:重放历史时照抄历史;重放完我先出指定的那一手(只一次),之后都按 DouZero 打分。"""

    def __init__(self, replay, plain, forced=None):
        self.replay = replay
        self.plain = plain
        self.forced = forced

    def act(self, infoset):
        if self.replay.moves:
            return self.replay.act(infoset)
        if self.forced is not None:
            move, self.forced = self.forced, None
            return list(move)
        return self.plain.act(infoset)


class Advisor:
    def __init__(self, weights, rollout=True):
        self.agents = {s: Agent(s, os.path.join(weights, f"{s}.ckpt")) for s in SEATS}
        self.plain = {s: Plain(self.agents[s]) for s in SEATS}
        # 对手牌少时候选分法的枚举结果:一次现场模拟要发上百手牌,同一局面只算一次
        self.short_plans = {}
        for agent in self.agents.values():
            agent.rollout = rollout
            agent.plain = self.plain
            agent.advisor = self

    def short_hand_plan(self, infoset, auction=None, deadline=None):
        """牌最少的那家对手剩 ≤ [SHORT_HAND] 张时,把他所有可能的剩牌按 likelihood 加权。

        返回 ({座位: 这手牌}, 权重) 的列表,权重是 likelihood(只看他最近两手出牌,每种不同的剩牌
        算一个假设);牌不少 / 枚举不完 / 算不出来(例如到 [deadline])时返回 None,调用方退回原来的发牌。
        只枚举牌最少的那一家,另一家拿剩下的;
        我是农民、牌最少的是地主时,他没打出去的底牌先留在他手里(同 sample_hands)。
        枚举 + 打分要算进现场模拟的预算里,所以结果按局面缓存:一次模拟里只算一次。
        """
        me = infoset.player_position
        others = [s for s in SEATS if s != me]
        left = infoset.num_cards_left_dict
        short = min(others, key=lambda s: (left[s], SEATS.index(s)))
        if left[short] > SHORT_HAND:
            return None
        if deadline is not None and time.monotonic() > deadline:
            return None
        key = (me, tuple(sorted(infoset.player_hand_cards)), tuple(tuple(m) for m in infoset.card_play_action_seq),
               tuple(left[s] for s in SEATS), tuple(sorted(infoset.other_hand_cards)),
               tuple(sorted(infoset.three_landlord_cards)), repr(auction))
        if key in self.short_plans:
            return self.short_plans[key]
        if len(self.short_plans) > 8:
            self.short_plans.clear()
        unknown = sorted(infoset.other_hand_cards)
        if len(unknown) != left[others[0]] + left[others[1]]:
            return None
        far = others[0] if others[1] == short else others[1]
        kept = ()
        if me != "landlord" and short == "landlord":
            kept = tuple(sorted((Counter(infoset.three_landlord_cards) & Counter(unknown)).elements()))
        ways = endgame.splits(unknown, short, left[short], far, left[far], kept=kept)
        if ways is None:
            return None
        splits = [{me: tuple(sorted(infoset.player_hand_cards)), short: ha, far: hb} for ha, hb in ways]
        if deadline is None:
            deadline = time.monotonic() + ACT_ROLLOUT_BUDGET_S
        try:
            # 只看他最近两手出牌(第 1 条),不掺叫分 / 加倍:这里估的是「他打这些牌时手里留了什么」,
            # 叫分那一路已经在别的分支里用过。每种不同的剩牌算一个假设,不再按同点数的摆法数放大:
            # 4 4 4 4 这种少见的留法不该仅仅因为摆法少就被压掉(2026-11-19 15:08:02 那手)。
            likes = self.likelihood([list(m) for m in infoset.card_play_action_seq], me, None,
                                    seats=[short])(splits, deadline)
        except endgame.Timeout:
            return None
        weights = list(likes)
        if sum(weights) <= 0:
            return None
        plan = []
        for (ha, hb), weight in zip(ways, weights):
            current = {me: tuple(sorted(infoset.player_hand_cards)), short: ha, far: hb}
            plan.append(({s: sorted(list(current[s]) + list(infoset.played_cards[s])) for s in SEATS}, weight))
        self.short_plans[key] = plan
        return plan

    def sample_hands(self, infoset, rng, auction=None, deadline=None):
        """现场模拟发牌:牌最少的那家对手剩 ≤ [SHORT_HAND] 张时,按 likelihood 加权枚举他的剩牌;
        否则没有 auction 时完全走原来的 sample_hands(同一个种子一手不差)。

        有证据时一次发 K 手候选(K = [AUCTION_MAX_TRIES]),按证据座位把这 K 手的开局牌力攒成一批前向
        (每个座位一次,不再一手一手串行),再按权重从这 K 手里抽一手:权重是各条证据的似然相乘,在 0..1,
        即 importance resampling。权重全 0 / 牌力算不出来(例如到 [deadline])时收下第一手,不能让模拟卡死。
        只给我看不见的对手加权:我自己的叫分 / 加倍不算证据。
        """
        plan = self.short_hand_plan(infoset, auction, deadline)
        if plan is not None:
            pick = rng.random() * sum(weight for _, weight in plan)
            for hands, weight in plan:
                pick -= weight
                if pick <= 0:
                    return hands
            return plan[-1][0]
        me = infoset.player_position
        seats = auction.seats(me) if auction is not None else []
        if not seats:
            return sample_hands(infoset, rng)
        hands = [sample_hands(infoset, rng) for _ in range(AUCTION_MAX_TRIES)]
        try:
            strength = [dict() for _ in hands]
            for seat in seats:
                values = self.agents[seat].opening_strengths([h[seat] for h in hands], deadline)
                for k, value in enumerate(values):
                    strength[k][seat] = value
            logs = [auction.log_weight(me, s) for s in strength]
        except (endgame.Timeout, Mismatch):
            return hands[0]
        top = max(logs)
        if top == float("-inf"):
            return hands[0]
        # 减去最大值再 exp:权重再小也至少有一个是 1,不会整批下溢成 0;总权重为 0 只是防御
        weights = [math.exp(x - top) for x in logs]
        total = sum(weights)
        if total <= 0:
            return hands[0]
        pick = rng.random() * total
        for hand, weight in zip(hands, weights):
            pick -= weight
            if pick <= 0:
                return hand
        return hands[-1]

    def opening_strength(self, seat, hand):
        """[seat] 开局拿着 [hand] 的牌力:DouZero 这家模型给所有合法出牌打分的最高分。"""
        return self.agents[seat].opening_strengths([hand])[0]

    def bid(self, hand, games=BID_GAMES, budget=ROLLOUT_BUDGET_S):
        """拿这 17 张当地主能赢几成:看不见的牌随机发成底牌和两家,DouZero 自己跟自己打 [games] 局。
        返回 (赢面, 实际打了几局):超过 [budget] 秒就停。"""
        hand = cards(hand)
        if len(hand) != 17:
            raise Mismatch("hand_count_mismatch")
        rest = minus(DECK, hand)
        rng = random.Random(hash(tuple(hand)))
        wins = played = 0
        deadline = time.perf_counter() + budget
        for _ in range(games):
            if played >= ROLLOUT_MIN_GAMES and time.perf_counter() > deadline:
                break
            played += 1
            rng.shuffle(rest)
            bottom = rest[:3]
            env = GameEnv(self.plain)
            env.card_play_init({"landlord": sorted(hand + bottom), "landlord_down": sorted(rest[3:20]),
                                "landlord_up": sorted(rest[20:37]), "three_landlord_cards": sorted(bottom)})
            while not env.game_over:
                env.step()
            wins += bool(env.num_wins["landlord"])
        return wins / played, played

    def landlord_would_call(self, hand, bottom):
        """地主这 20 张(含底牌)会不会叫地主:DouZero 地主模型对开局第一手所有合规出牌的最高估值到 BID_VALUE 就算会叫。
        只看地主的牌和三家张数,另外两家随便凑够 17 张(不影响地主观测)。"""
        return self.landlords_would_call([hand], bottom)[0]

    def landlords_would_call(self, hands, bottom):
        """一次判多副地主牌会不会叫:每副的观测攒成一批只前向一次。返回和 [hands] 一样长的布尔列表。"""
        zs, xs, sizes = [], [], []
        for hand in hands:
            rest = minus(DECK, hand)
            env = GameEnv(self.plain)
            env.card_play_init({"landlord": sorted(hand), "landlord_up": sorted(rest[:17]),
                                "landlord_down": sorted(rest[17:]), "three_landlord_cards": sorted(bottom)})
            obs = get_obs(env.game_infoset)
            zs.append(torch.from_numpy(obs["z_batch"]).float())
            xs.append(torch.from_numpy(obs["x_batch"]).float())
            sizes.append(len(env.game_infoset.legal_actions))
        if not zs:
            return []
        with torch.no_grad():
            values = self.agents["landlord"].model.forward(
                torch.cat(zs), torch.cat(xs), return_value=True)["values"].flatten()
        out, start = [], 0
        for size in sizes:
            out.append(float(values[start:start + size].max()) >= BID_VALUE)
            start += size
        return out

    def double(self, seat, hand, bottom, games=DOUBLE_GAMES, budget=None,
               condition=True, min_games=ROLLOUT_MIN_GAMES, on_accept=None):
        """这手牌加倍值不值:地主按 20 张(含底牌)、农民按 17 张加三张明牌,看不见的牌随机分给另外两家,
        DouZero 自己跟自己打 [games] 局,返回 (自己这一方的赢面(地主或农民), 实际打了几局):超过 [budget] 秒就停。
        [budget] 不传时地主 / 单个农民座位用 [DOUBLE_BUDGET_S],seat="farmer" 两个座位共用 [FARMER_DOUBLE_BUDGET_S]。

        农民默认做条件抽样:每副随机发牌先看地主那 20 张会不会叫(landlord_would_call),不会叫就丢掉重发;
        地主座位的算法不变。seat="farmer" 时两个农民座位各算一遍、共用预算,返回较低的赢面。
        min_games 是地主模拟的局数下限;农民座位不受它限制,返回实际打过的局数(两个座位每副各一局),
        调用方用 games 与门槛比较:端点不到 ROLLOUT_MIN_GAMES(seat="farmer" 为 2×)就按不加倍 / 不超级加倍。
        condition=False 只给校准/对照用。on_accept 每采纳一局回调三家手牌。"""
        if seat not in SEATS and seat != "farmer":
            raise Mismatch("bad_seat")
        if budget is None:
            budget = FARMER_DOUBLE_BUDGET_S if seat == "farmer" else DOUBLE_BUDGET_S
        hand, bottom = cards(hand), cards(bottom)
        if len(bottom) != 3:
            raise Mismatch("bottom_count_mismatch")
        if seat == "landlord":
            if len(hand) != 20:
                raise Mismatch("hand_count_mismatch")
            fixed = {"landlord": list(hand)}
            unseen = minus(DECK, hand)
            # 同一副牌每次问都一样:底牌也参与种子
            rng = random.Random(hash(tuple(sorted(hand + bottom))))
            wins = played = 0
            deadline = time.perf_counter() + budget
            for _ in range(games):
                if played >= min_games and time.perf_counter() > deadline:
                    break
                played += 1
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
                wins += bool(env.num_wins["landlord"])
            return wins / played, played
        if len(hand) != 17:
            raise Mismatch("hand_count_mismatch")
        seats = ["landlord_up", "landlord_down"] if seat == "farmer" else [seat]
        return self._farmers_double(seats, hand, bottom, games, budget, condition, on_accept)

    def _farmers_double(self, seats, hand, bottom, games, budget, condition, on_accept):
        """农民座位:底牌归地主;看不见的 34 张随机分成地主的 17 张和队友的 17 张。
        条件抽样只看地主那 20 张会不会叫。两个座位用同一批发牌(只差我坐地主的上家还是下家)。
        [games] 是模拟局数:一个座位一副牌一局,seat="farmer" 两个座位每副各一局(一副算两局)。"""
        unseen = minus(minus(DECK, hand), bottom)
        rng = random.Random(hash(tuple(sorted(hand + bottom))))
        wins = {s: 0 for s in seats}
        played = 0
        deadline = time.perf_counter() + budget
        while played * len(seats) < games:
            if time.perf_counter() > deadline:
                break
            # 一次抽一批再判:弱地主牌被丢掉的概率不低,一副一副判会把预算全花在判断上
            batch = []
            for _ in range(BID_BATCH):
                rng.shuffle(unseen)
                batch.append((sorted(bottom + unseen[:17]), sorted(unseen[17:])))
            keep = self.landlords_would_call([lord for lord, _ in batch], bottom) if condition else [True] * len(batch)
            for (landlord, teammate), accepted in zip(batch, keep):
                if not accepted:
                    continue
                if played * len(seats) >= games or time.perf_counter() > deadline:
                    break
                played += 1
                # 我坐地主上家:上家是我、下家是队友;我坐下家:下家是我、上家是队友。地主和队友的牌两边一样。
                # 牌局会就地删牌,每家每局都给一份新的列表
                deal = {"landlord": list(landlord), "landlord_up": list(hand), "landlord_down": list(teammate)}
                if on_accept is not None:
                    on_accept({k: list(v) for k, v in deal.items()})
                for s in seats:
                    hands = {k: list(v) for k, v in deal.items()}
                    if s == "landlord_down":
                        hands["landlord_up"], hands["landlord_down"] = list(teammate), list(hand)
                    env = GameEnv(self.plain)
                    env.card_play_init({**hands, "three_landlord_cards": sorted(bottom)})
                    while not env.game_over:
                        env.step()
                    wins[s] += bool(env.num_wins["farmer"])
        if played == 0:
            return 0.0, 0
        rates = {s: wins[s] / played for s in seats}
        # 每个采纳的牌局两个农民座位各打一局:games 报「局数」而不是「副数」。
        # 局数不到 ROLLOUT_MIN_GAMES 时也报真实赢面,由端点按 games 决定不加倍;不能把「不知道」写成 0 赢面。
        return min(rates.values()), played * len(seats)

    def likelihood(self, history, me, auction=None, seats=None):
        """给 endgame.best 的似然回调:按两家对手最近 [LIKELIHOOD_MOVES] 手不是不出的出牌,判断一种分法像不像他们当时会这么出。

        [seats] 可选:只看这几家对手的出牌(默认两家都看)。现场模拟估计牌最少那一家的手牌时只传他,
        另一家很早以前的出牌和这一手关系不大,却会在 200 多种分法里把权重整个带走。

        每个对手的每一手都把他当时的手牌和局面用 history 倒推出来,用他自己座位的 DouZero 模型给当时所有
        合法出牌打分,取这一手的 log softmax 概率 P(那手 | 当时手牌);同一对手各手 log 概率相加,
        [seats] 里的对手再相加。只看最近一手不够:下家倒数第二手 10 10 10 + Q 才是「他手里没有 4/9
        这种零散小牌」的证据。
        用 float64 在对数空间算,再减去所有分法里最大的 log 似然后 exp:相对权重不变,但对手真出过一手
        模型觉得极差的牌时不会像 float32 概率相乘那样下溢成 0(那会让 total=0、残局搜索整个退回 DouZero)。
        软证据压出来的相对权重有下限 [LIKELIHOOD_FLOOR]:温度 0.02 下动作概率能差好几个数量级,
        可只有最近两手出牌,不能让「猜牌」把一整类分法判死(见 LIKELIHOOD_FLOOR 的注释)。
        某个观测出法在这种分法的合法出牌里找不到时(正常历史不该发生)这种分法权重记 0;
        如果所有分法都这样(全 -inf),这次退回不加权,不能把搜索关掉。
        一次把同一手的全部分法攒成一批前向;到 [deadline] 就抛 Timeout,best 退回不加权的摆法数。
        回调第一次被调用时才去翻 history:不是残局时 endgame.best 根本不叫它,这一手就没有额外开销。
        [auction] 是这一局的叫分 / 加倍:每家对手的开局整手牌(这种分法下剩的 + 已经出过的)用他自己座位的
        模型打分,把证据的 log 似然加进同一个对数空间;没有出牌历史、只有 auction 时也生效。
        我自己的叫分 / 加倍不算证据(auction.seats 已经把我排除)。
        """
        watched = set(SEATS) - {me} if seats is None else set(seats)

        def callback(splits_hands, deadline):
            indices = {}
            for i in range(len(history) - 1, -1, -1):
                seat = SEATS[i % 3]
                if history[i] and seat in watched:
                    moves = indices.setdefault(seat, [])
                    if len(moves) < LIKELIHOOD_MOVES:
                        moves.append(i)
            logs = [0.0] * len(splits_hands)
            # 硬约束:能一手走完却不出的人不会存在。某家在第 i 手不出,而按这种分法他那一刻的整手牌
            # (现在剩的 + 第 i 手之后出过的)本身就是一手能管上当时上家出牌的合法牌,这种分法权重记 0。
            passes = [frame for frame in pass_frames(history, me) if frame[0] in watched]
            if passes:
                for k, hands in enumerate(splits_hands):
                    if k % 32 == 0 and time.monotonic() > deadline:
                        raise endgame.Timeout()
                    for seat, after, last in passes:
                        whole = tuple(sorted(list(hands[seat]) + after))
                        if md.get_move_type(list(whole))["type"] == md.TYPE_0_PASS:
                            continue
                        if whole in endgame.moves(whole, last):
                            logs[k] = float("-inf")
                            break
            for seat, moves in indices.items():
                for index in moves:
                    frame = history_frame(history, index)
                    states = []
                    for n, hands in enumerate(splits_hands):
                        if n % 32 == 0 and time.monotonic() > deadline:
                            raise endgame.Timeout()
                        states.append(history_state(history, hands, seat, index, frame))
                    scores = self.state_values(seat, states, deadline)
                    observed = tuple(sorted(history[index]))
                    # 真人会留着炸弹 / 王炸,先把杂牌走掉:这一手不是炸弹时,再按「炸弹先放一边」
                    # 剩下的牌打一次分,这一手的 log 概率取两个里大的那个。放一边后不合法的分法
                    # (这一手用到了炸弹里的牌)只用整手牌那一项。
                    reduced = {}
                    if not endgame.is_bomb(observed):
                        bombs = {k: endgame.bomb_cards(state.player_hand_cards)
                                 for k, state in enumerate(states)}
                        placed = {k: cards for k, cards in bombs.items() if cards}
                        if placed:
                            items = [(k, history_state(history, splits_hands[k], seat, index, frame,
                                                       without=cards))
                                     for k, cards in placed.items()]
                            rscores = self.state_values(seat, [state for _, state in items], deadline)
                            reduced = {k: (state, values) for (k, state), values in zip(items, rscores)}
                    for k, (state, values) in enumerate(zip(states, scores)):
                        if not splits_hands[k][seat]:
                            continue
                        actions = [tuple(sorted(m)) for m in state.legal_actions]
                        if observed not in actions:
                            logs[k] = float("-inf")
                            continue
                        j = actions.index(observed)
                        like = float(torch.log_softmax(
                            values.double() / LIKELIHOOD_TEMPERATURE, dim=0)[j])
                        if k in reduced:
                            rstate, rvalues = reduced[k]
                            ractions = [tuple(sorted(m)) for m in rstate.legal_actions]
                            if observed in ractions:
                                rj = ractions.index(observed)
                                like = max(like, float(torch.log_softmax(
                                    rvalues.double() / LIKELIHOOD_TEMPERATURE, dim=0)[rj]))
                        logs[k] += like
                    if time.monotonic() > deadline:
                        raise endgame.Timeout()
            evidence = [s for s in (auction.seats(me) if auction is not None else []) if s in watched]
            if evidence:
                # 每种分法的对手开局整手牌 = 这种分法下他还剩的 + 他已经出过的;按证据座位各攒一批前向
                played = {s: [] for s in SEATS}
                for i, move in enumerate(history):
                    played[SEATS[i % 3]] += move
                strength = [dict() for _ in splits_hands]
                for seat in evidence:
                    states = [opening_state(seat, sorted(list(hands[seat]) + played[seat]))
                              for hands in splits_hands]
                    values = self.state_values(seat, states, deadline)
                    for k, value in enumerate(values):
                        strength[k][seat] = float(value.max())
                for k in range(len(logs)):
                    logs[k] += auction.log_weight(me, strength[k], seats=evidence)
            finite = [x for x in logs if x != float("-inf")]
            if not finite:
                return [1.0] * len(splits_hands)
            top = max(finite)
            # 软证据的权重下限:LIKELIHOOD_FLOOR 以下不再往下压。对数空间里就是
            # log 权重 >= log(LIKELIHOOD_FLOOR) - log 最大值;硬约束(-inf)仍是 0。
            return [max(math.exp(x - top), LIKELIHOOD_FLOOR) if x != float("-inf") else 0.0 for x in logs]

        return callback

    def state_values(self, seat, states, deadline):
        """一批 [seat] 的 info set 攒成一次前向,返回每个 info set 各手的 DouZero 分数。"""
        return self.agents[seat].state_values(states, deadline)

    def act(self, seat, hand, bottom, history, report=None, auction=None):
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
        return self.agents[seat].act(env.game_infoset, report,
                                     likelihood=self.likelihood(history, seat, auction), auction=auction)


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
                    win, games = advisor.bid(body["hand"])
                    return self.reply(200, {"level": bid_level(win), "win": round(win, 2), "games": games, "ms": ms()}, body)
                if self.path == "/double":
                    seat = body["seat"]
                    win, games = advisor.double(seat, body["hand"], body["bottom"])
                    # 局数不到下限时赢面只是打过的几局的比率,可能是运气:一律按不加倍 / 不超级加倍。
                    # 地主总能打满下限;农民的条件抽样在极端强手 / 机器忙时会不到 40 局。
                    # seat="farmer" 每副两个座位各打一局:要 40 副才是每个座位 40 局,所以门槛是 2 倍。
                    need = 2 * ROLLOUT_MIN_GAMES if seat == "farmer" else ROLLOUT_MIN_GAMES
                    enough = games >= need
                    return self.reply(200, {"double": enough and should_double(win, seat),
                                            "super": enough and should_super(win, seat),
                                            "win": round(win, 2), "games": games, "ms": ms()}, body)
                report = {}
                move = advisor.act(body["seat"], body["hand"], body.get("bottom", []), body.get("history", []),
                                   report, auction=parse_auction(body.get("auction")))
                self.reply(200, {"play": [NAMES[c] for c in move], "ms": ms(), **report}, body)
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
