"""叫分 / 加倍先验校准:随机发牌,用 DouZero 各座位的模型给开局整手牌打分(所有合法出牌里的最高分),
按座位类型统计牌力分位数,给出 AUCTION_BID_T / AUCTION_DOUBLE_T / AUCTION_TAU 的建议值。

牌力 s = 这家开局整手牌(17 或 20 张,地主含底牌)在这家自己的 DouZero 模型眼里,开局领出时所有合法
出牌的最高分。随机发牌下:
  T_bid 取地主牌力的 q65 —— 叫三分的手牌约占 35%;
  T_dbl["landlord"] 取地主牌力的 q80、T_dbl["farmer"] 取两个农民座位合起来的 q80 —— 加倍约占 20%;
  AUCTION_TAU 取地主牌力 35%~65% 分位间距的一半,是 logistic 的尺度:分位间距越大,证据越温和。

用法(在 tools/douzero 下,用带 douzero 的 Python):
  .venv/bin/python calibrate_auction.py --deals 4000 --workers 8
"""
import argparse
import json
import math
import multiprocessing as mp
import random
import statistics
import time

from server import Advisor, DECK, WEIGHTS

_ADVISOR = None


def advisor():
    global _ADVISOR
    if _ADVISOR is None:
        _ADVISOR = Advisor(WEIGHTS)
    return _ADVISOR


def sample_deals(count, seed):
    """随机发 [count] 副牌,返回 [(地主 20 张, 上家 17 张, 下家 17 张), ...]。"""
    rng = random.Random(seed)
    deck = list(DECK)
    deals = []
    for _ in range(count):
        rng.shuffle(deck)
        deals.append((sorted(deck[:20]), sorted(deck[20:37]), sorted(deck[37:54])))
    return deals


def work(batch):
    """一批牌:三个座位各自用自己座位的模型打分(每批一次前向)。"""
    a = advisor()
    out = []
    for seat, index in (("landlord", 0), ("landlord_up", 1), ("landlord_down", 2)):
        out.append((seat, a.agents[seat].opening_strengths([deal[index] for deal in batch])))
    return out


def quantile(values, p):
    """线性插值分位数,和 statistics.quantiles 的口径一致,但单点也好算。"""
    ordered = sorted(values)
    i = (len(ordered) - 1) * p
    lo = int(i)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (i - lo)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deals", type=int, default=4000, help="随机发多少副牌")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--batch", type=int, default=64, help="每个并行任务攒多少副牌一次前向")
    parser.add_argument("--seed", type=int, default=20261117)
    parser.add_argument("--out", default="calibrate_auction.json")
    args = parser.parse_args()

    started = time.time()
    deals = sample_deals(args.deals, args.seed)
    batches = [deals[i:i + args.batch] for i in range(0, len(deals), args.batch)]
    strengths = {"landlord": [], "landlord_up": [], "landlord_down": []}
    if args.workers > 1:
        with mp.Pool(args.workers) as pool:
            for parts in pool.imap_unordered(work, batches):
                for seat, values in parts:
                    strengths[seat].extend(values)
    else:
        for batch in batches:
            for seat, values in work(batch):
                strengths[seat].extend(values)
    print(f"deals={len(deals)} sampled+scored in {time.time() - started:.1f}s", flush=True)

    landlord = strengths["landlord"]
    farmers = strengths["landlord_up"] + strengths["landlord_down"]
    bid_t = quantile(landlord, 0.65)
    landlord_double_t = quantile(landlord, 0.80)
    farmer_double_t = quantile(farmers, 0.80)
    tau = (quantile(landlord, 0.65) - quantile(landlord, 0.35)) / 2

    def sigmoid(x):
        if x >= 0:
            return 1.0 / (1.0 + math.exp(-x))
        e = math.exp(x)
        return e / (1.0 + e)

    # 边际检查:按建议值,P(叫三分) 和 P(加倍) 的样本均值应该分别接近 35% / 20%
    p_bid = statistics.mean(sigmoid((s - bid_t) / tau) for s in landlord)
    p_lord_double = statistics.mean(sigmoid((s - landlord_double_t) / tau) for s in landlord)
    p_farmer_double = statistics.mean(sigmoid((s - farmer_double_t) / tau) for s in farmers)
    result = {
        "seed": args.seed,
        "deals": len(deals),
        "landlord_samples": len(landlord),
        "farmer_samples": len(farmers),
        "quantiles": {
            "landlord_q35": round(quantile(landlord, 0.35), 4),
            "landlord_q50": round(quantile(landlord, 0.50), 4),
            "landlord_q65": round(quantile(landlord, 0.65), 4),
            "landlord_q80": round(quantile(landlord, 0.80), 4),
            "farmer_q50": round(quantile(farmers, 0.50), 4),
            "farmer_q80": round(quantile(farmers, 0.80), 4),
        },
        "suggested": {
            "AUCTION_BID_T": round(bid_t, 4),
            "AUCTION_DOUBLE_T": {"landlord": round(landlord_double_t, 4), "farmer": round(farmer_double_t, 4)},
            "AUCTION_TAU": round(tau, 4),
        },
        "marginal_probability": {
            "bid_3": round(p_bid, 4),
            "landlord_double": round(p_lord_double, 4),
            "farmer_double": round(p_farmer_double, 4),
        },
    }
    json.dump(result, open(args.out, "w"), ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
