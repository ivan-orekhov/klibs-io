# klibs.io Search Benchmark — Scoring

How the search-eval harness turns the labeled queries (`queries.json`) set into a score.  
The runners are `SearchRegressionTest` (lower bound) and `SearchEvalE2ETest` (upper bound).

## What "pass" means (both tiers)

Every case declares one pass criterion (`PassSpec` in `EvalModels.kt`). A result counts as relevant
if it is in the case's `expected` set **or** its optional `also` set (the union) — so adding `also`
libs can make a previously-unreachable `precision_at` case pass.

| criterion | passes when |
|-----------|-------------|
| `rank_le(n)` | a relevant lib appears at rank ≤ n |
| `any_in_top(k)` | at least one relevant lib is in the top k |
| `precision_at(k, p)` | ≥ p fraction of the top k are relevant |
| `non_empty` | any result comes back (coverage-gap probes, e.g. `pdf`) |
| `all_support_platform(k)` | every one of the top k supports the queried platform filter |

Both tiers consume `Scorer.scoreCase(case, results).pass`. That is **all** the regression tier uses.

---

# Eval verdict

Everything below is computed and reported by `SearchEvalE2ETest` only.

## Per-case score: nDCG@10

Each case scores in `[0,1]` by **nDCG@10** (normalized discounted cumulative gain, top 10).
It rewards putting relevant libraries *high*, not just *present*.

- **Relevance is graded**: gain = **2** if a result is in the case's `expected` (primary/canonical)
  set, **1** if in the optional `also` (secondary/also-relevant) set, else **0**. A case with no
  `also` reduces to binary — the ×2 cancels in DCG/IDCG, so its nDCG is unchanged.
- **DCG** = Σ over the top-10 results of `gain / log2(rank + 1)` — earlier ranks count more.
- **IDCG** = the same sum for the ideal ordering (all gains sorted high→low, packed at the top).
  - Note: equal-grade spots are interchangeable; a primary (2) always outranks a secondary (1)
- **nDCG = DCG / IDCG** → 1.0 = perfect ordering; 0.0 = no relevant lib in top-10.
- `non_empty` and `all_support_platform` cases (no `expected` set, e.g. coverage gaps like
  `pdf` or pure platform-filter checks) fall back to 1/0 on their pass criterion.

*Example:* expected `{koin}`. If koin is rank 1 → nDCG 1.0. Rank 2 → 1/log2(3) ≈ 0.63.
Not in top-10 → 0.0.

## Weighting: two independent levels

Demand and strategy are different axes, kept separate so neither double-counts.

### Level 1 — case weight (within a class) = *revealed demand*
`weight` is **pre-baked in `queries.json`**: `grade_factor × traffic_factor ∈ [1, 4]`
(grade 2× if human-graded, traffic `1 + visitors/max`). A high-traffic graded query (`ktor`)
weighs `4×`; a rare unlabeled one `1×`. Sets each case's share of its class mean.

### Level 2 — class weight (across classes) = *strategy*
```
headline = Σ(class_weight × class_mean) / Σ class_weight
  class_mean = case-weighted mean nDCG within the class
```
Class weight multiplies the **normalized class mean**.

| class | what | weight | why |
|-------|------|:------:|-----|
| **B** category | keyword → answer-key libs | **0.40** | bulk of real intent / highest-traffic ranking pain |
| **M** multi-term | compositional queries | **0.40** | bulk of real intent / common-term dilution |
| **E** query-mechanics | §2.1: dotted/coordinate name, apostrophe, stop-words, fuzzy/typo | **0.40** | **dealbreaker — absolute gate** (below) |
| **C** related-lib | "Hilt", "Room alternative" → KMP equivalent | **0.30** | semantic unlock; low-traffic, would otherwise be buried |
| **A** exact-name | project name → that project at rank 1 | 0.20 | **also a gate** (below) |
| **D** platform | query + platform filter | 0.15 | orthogonal to text relevance |


## Gates

Two classes are hard expectations on top of the headline, surfaced as `report.gateFailures`:

> **A must stay green.** Exact-name is a low weight but a hard expectation: a semantic/hybrid change
> is only acceptable if A stays ~1.0 *while* C improves. Once A cases are in the floor, an A dropping
> is a regression (red in the regression tier), independent of the headline.

> **E is the capability dealbreaker.** Class E tests the §2.1 query-mechanics the engine *must*
> support (`gate = true` on `EvalClass.E`). Today lexical PG-FTS fails E (no fuzzy: `serializaton`
> → 0 results), so E sits **red in the eval tier** — the marker for the query work. When the new
> engine satisfies §2.1, E goes green and graduates into the regression floor, where it must stay.
