# search-eval JVM E2E benchmark (KTL-4710)

Two-tier eval suite. Both tiers drive the production search path
(`GET /search/projects`) and score each labeled case in `queries.json` with graded nDCG@10.
Scoring method: [SCORING.md](SCORING.md).

Two tiers, two bounds:

| tier | bound | corpus | runs | expectation |
|------|-------|--------|------|-------------|
| **Regression** | lower — "search can't get worse than this" | **frozen prod snapshot** (Testcontainers) | every deploy | floor cases **all green** |
| **Eval** | upper — "we strive for 100%" | **live prod-copy** (external DB) | manual, by whoever changes search | some **red** = the signal |

Both are gated off in the regular `./kotlin test` run (system property `search.eval.tier`) — they need
Docker and a corpus. Each is enabled explicitly via the commands below.

## Regression tier — the deterministic floor

`SearchRegressionTest` restores a **frozen prod snapshot** into Testcontainers, then asserts every id in the committed **floor**
(`floor.json`) still passes. Deterministic, so a red here is a real regression. 

**Corpus** — the weekly prod backup, pinned once and reused (refresh rarely):

```bash
./scripts/search-eval-freeze.sh                                   # one-time/rare: pin the weekly backup
SEARCH_EVAL_SNAPSHOT_KEY=search-eval/frozen-<date>.pgdump.gz \
  ./scripts/search-eval-fetch.sh                                  # download + gunzip -> app/build/search-eval/frozen.pgdump
./kotlin test -m app --include-classes '*SearchRegressionTest' --jvm-args '-Dsearch.eval.tier=regression'
```

`freeze` needs prod cluster access (VPN + kubectl); `fetch` takes creds from the environment
(CI: `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `SEARCH_EVAL_BUCKET` / `GCS_ENDPOINT`) or the
`klibs-readme-user` secret (local). Override the snapshot path with `SEARCH_EVAL_SNAPSHOT`.

**The floor updates:**   
When search improves so a new case passes, or the snapshot is refreshed,
overwrite the floor and commit it:

```bash
./kotlin test -m app --include-classes '*SearchRegressionTest' \
  --jvm-args '-Dsearch.eval.tier=regression -Dsearch.floor.overwrite=true'   # rewrites app/src/test/resources/search-eval/floor.json
```
## Eval tier — the aspirational target

`SearchEvalE2ETest` runs every case against a **live prod-copy** corpus and asserts each passes.
We aim for 100%, so unmet cases (Class-E fuzzy/typo today) show up **red** — those reds are what the
next search change should fix. Manual/local, never a deploy gate.

```bash
./scripts/copy_prod_db_to_local.sh -K klibs-prod -C klibs-postgres -L klibs -D klibs   # seed prod-copy
./kotlin test -m app --include-classes '*SearchEvalE2ETest' --jvm-args '-Dsearch.eval.tier=eval'
```

Point at another DB with `SEARCH_EVAL_DB_URL` / `SEARCH_EVAL_DB_USER` / `SEARCH_EVAL_DB_PASSWORD`
(defaults: `jdbc:postgresql://localhost:5432/klibs`, `klibs`/`klibs`).

**Progress readout.** Each run diffs against the committed baseline (`baseline.json` = last headline
\+ passing ids) and prints the headline delta plus which cases gained/lost ground — the
"is my change better?" answer, on the corpus you're testing against.

**After an improvement, record it**:

```bash
./kotlin test -m app --include-classes '*SearchEvalE2ETest' \
  --jvm-args '-Dsearch.eval.tier=eval -Dsearch.baseline.overwrite=true'   # rewrites app/src/test/resources/search-eval/baseline.json
```
