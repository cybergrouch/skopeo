#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Generate a table of rating deltas for a set of scores, given two players' ratings.

An explainer/analysis companion to docs/product/RATING_CALCULATION_ALGORITHM.md: it reproduces the
v2 per-set calculation (dominance + gap/scale + K + sign) so you can see, for each score, how much a
result would move each player's rating. It's a teaching/what-if tool — the server's BigDecimal engine
is the source of truth; this uses floats and may differ in the last digit.

Two modes:
  independent (default) — each score is its own standalone single-set match, all starting from the
                          SAME given ratings. Best for "what would a 6-2 vs a 2-6 do?" comparisons.
  sequential            — treat the scores as consecutive sets of ONE match: ratings carry forward
                          set-to-set and the net (summed) change is reported.

Formula per set (see the doc): Δ = K × |dominance| × scale × sign, where
  dominance      = (subjectGames − oppGames) / (subjectGames + oppGames)
  normalizedGap  = |ratingSubject − ratingOpp| / range
  scale          = max(0, (threshold − normalizedGap)/threshold) × m     if the favorite won / equal
                 = (normalizedGap / threshold) × upsetMultiplier × m      if the underdog won
  sign           = +1 if the subject won the set, −1 if they lost (0 dominance on a tie → Δ 0)

Examples:
  # Independent single-set sims from a pair of ratings, open-play context, as a Markdown table:
  ./scripts/rating-delta-table.py --rating-a 3.243325 --rating-b 3.266000 \\
      --name-a Tin --name-b Razel --match-type OPEN_PLAY --markdown \\
      --scores 6-6,6-5,6-4,6-3,6-2,6-1,6-0,5-6,4-6,3-6,2-6,1-6,0-6

  # One multi-set match, net delta:
  ./scripts/rating-delta-table.py --rating-a 3.29 --rating-b 3.25 --mode sequential \\
      --scores 6-4,4-6,6-3
"""

import argparse
import sys

# Algorithm constants (mirror service/calculator/impl/v2/PerformanceBasedRankingCalculatorImpl.kt).
DEFAULT_K = 0.16
DEFAULT_THRESHOLD = 0.083          # COMPETITIVE_THRESHOLD_PCT = 0.5 NTRP / 6.0 range
DEFAULT_RANGE = 6.0                # NTRP_MAX (7.0) − NTRP_MIN (1.0)
DEFAULT_UPSET_MULT = 2.0
NTRP_MIN, NTRP_MAX = 1.0, 7.0

# Per-match-type factors (mirror MatchType.kt).
MATCH_TYPE_FACTORS = {
    "OPEN_PLAY": 0.5,
    "LEAGUE_PLAY": 0.8,
    "LEAGUE_PLAYOFFS": 1.1,
    "TOURNAMENT": 1.2,
}


def clamp(v):
    return max(NTRP_MIN, min(NTRP_MAX, v))


def parse_scores(raw):
    """'6-4,4-6' -> [(6, 4), (4, 6)] where each tuple is (subject A games, opponent B games)."""
    out = []
    for tok in raw.split(","):
        tok = tok.strip()
        if not tok:
            continue
        try:
            a, b = tok.split("-")
            out.append((int(a), int(b)))
        except ValueError:
            sys.exit(f"Bad score '{tok}': expected e.g. 6-4 (subject games - opponent games)")
    return out


def scale_for(is_upset, ngap, cfg):
    if is_upset:
        return (ngap / cfg["threshold"]) * cfg["upset_mult"]
    return max(0.0, (cfg["threshold"] - ngap) / cfg["threshold"])


def step(subj_rating, opp_rating, subj_games, opp_games, cfg):
    """One set from the SUBJECT's perspective. Returns a dict of the factors + the subject's delta."""
    total = subj_games + opp_games
    dom = (subj_games - opp_games) / total if total else 0.0
    won = subj_games > opp_games
    tie = subj_games == opp_games
    adv = subj_rating - opp_rating
    ngap = abs(adv) / cfg["range"]
    is_upset = (won and adv < 0) or ((not won) and adv > 0)
    scale = scale_for(is_upset, ngap, cfg) * cfg["mtf"]
    sign = 1.0 if won else -1.0
    delta = cfg["k"] * abs(dom) * scale * sign
    branch = "tie" if tie else ("upset" if is_upset else "expected")
    return {"dom": dom, "ngap": ngap, "branch": branch, "scale": scale, "delta": delta}


def render(rows, headers, markdown):
    if markdown:
        print("| " + " | ".join(headers) + " |")
        print("| " + " | ".join("---" for _ in headers) + " |")
        for r in rows:
            print("| " + " | ".join(r) + " |")
    else:
        widths = [max(len(headers[i]), *(len(r[i]) for r in rows)) if rows else len(headers[i])
                  for i in range(len(headers))]
        line = "  ".join(h.rjust(widths[i]) for i, h in enumerate(headers))
        print(line)
        print("  ".join("-" * widths[i] for i in range(len(headers))))
        for r in rows:
            print("  ".join(r[i].rjust(widths[i]) for i in range(len(headers))))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--rating-a", type=float, required=True, help="Subject (player A) starting rating")
    p.add_argument("--rating-b", type=float, required=True, help="Opponent (player B) starting rating")
    p.add_argument("--name-a", default="A", help="Display name for player A (subject)")
    p.add_argument("--name-b", default="B", help="Display name for player B (opponent)")
    p.add_argument("--scores", default="6-6,6-5,6-4,6-3,6-2,6-1,6-0,5-6,4-6,3-6,2-6,1-6,0-6",
                   help="Comma-separated set scores as A-B (A's games first). Default spans 6-0..0-6.")
    p.add_argument("--mode", choices=["independent", "sequential"], default="independent",
                   help="independent: each score its own single-set match from the same start; "
                        "sequential: the scores are consecutive sets of one match (net delta).")
    p.add_argument("--match-type", choices=sorted(MATCH_TYPE_FACTORS), default=None,
                   help="Sets the match-type factor from MatchType.kt (e.g. OPEN_PLAY=0.5, TOURNAMENT=1.2).")
    p.add_argument("--mtf", type=float, default=None, help="Match-type factor override (default 1.0 if unset).")
    p.add_argument("--k", type=float, default=DEFAULT_K, help=f"K-factor (default {DEFAULT_K})")
    p.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                   help=f"Competitive threshold (default {DEFAULT_THRESHOLD})")
    p.add_argument("--range", type=float, default=DEFAULT_RANGE, dest="rng",
                   help=f"NTRP range for gap normalization (default {DEFAULT_RANGE})")
    p.add_argument("--upset-mult", type=float, default=DEFAULT_UPSET_MULT,
                   help=f"Upset multiplier (default {DEFAULT_UPSET_MULT})")
    p.add_argument("--markdown", action="store_true", help="Emit a GitHub Markdown table")
    args = p.parse_args()

    mtf = args.mtf if args.mtf is not None else (MATCH_TYPE_FACTORS[args.match_type] if args.match_type else 1.0)
    cfg = {"k": args.k, "threshold": args.threshold, "range": args.rng,
           "upset_mult": args.upset_mult, "mtf": mtf}
    scores = parse_scores(args.scores)
    a, b = args.name_a, args.name_b

    print(f"{a}={args.rating_a:.6f}  {b}={args.rating_b:.6f}  "
          f"K={args.k}  MTF={mtf}  threshold={args.threshold}  range={args.rng}  mode={args.mode}\n")

    headers = ["set", "dominance", "normGap", "branch", "scale",
               f"Δ {a}", f"Δ {b}", "|Δ|", f"{a}→", f"{b}→"]
    rows = []

    if args.mode == "independent":
        for ga, gb in scores:
            s = step(args.rating_a, args.rating_b, ga, gb, cfg)
            d = s["delta"]
            rows.append([
                f"{ga}-{gb}", f"{s['dom']:.4f}", f"{s['ngap']:.6f}", s["branch"],
                ("–" if s["branch"] == "tie" else f"{s['scale']:.6f}"),
                f"{d:+.6f}", f"{-d:+.6f}", f"{abs(d):.6f}",
                f"{clamp(args.rating_a + d):.6f}", f"{clamp(args.rating_b - d):.6f}",
            ])
        render(rows, headers, args.markdown)
    else:  # sequential
        ra, rb = args.rating_a, args.rating_b
        for ga, gb in scores:
            s = step(ra, rb, ga, gb, cfg)
            d = s["delta"]
            ra, rb = clamp(ra + d), clamp(rb - d)
            rows.append([
                f"{ga}-{gb}", f"{s['dom']:.4f}", f"{s['ngap']:.6f}", s["branch"],
                ("–" if s["branch"] == "tie" else f"{s['scale']:.6f}"),
                f"{d:+.6f}", f"{-d:+.6f}", f"{abs(d):.6f}", f"{ra:.6f}", f"{rb:.6f}",
            ])
        render(rows, headers, args.markdown)
        print(f"\nNET {a}: {args.rating_a:.6f} -> {ra:.6f}  (Δ = {ra-args.rating_a:+.6f})")
        print(f"NET {b}: {args.rating_b:.6f} -> {rb:.6f}  (Δ = {rb-args.rating_b:+.6f})")


if __name__ == "__main__":
    main()
