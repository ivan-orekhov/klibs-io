package io.klibs.core.search.eval

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** Answer-key models, mirroring the labeled query set in queries.json (KTL-4710). */

/** Relevance class of a case; `weight` feeds the class-weighted headline, `gate` marks dealbreakers. */
enum class EvalClass(val weight: Double, val gate: Boolean = false) {
    A(0.20),
    B(0.40),
    C(0.30),
    D(0.15),
    M(0.40),
    E(0.40, gate = true),
}

/** How a case is judged pass/fail. Each variant carries exactly the params it needs. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(PassSpec.RankLe::class, name = "rank_le"),
    JsonSubTypes.Type(PassSpec.AnyInTop::class, name = "any_in_top"),
    JsonSubTypes.Type(PassSpec.PrecisionAt::class, name = "precision_at"),
    JsonSubTypes.Type(PassSpec.AllSupportPlatform::class, name = "all_support_platform"),
    JsonSubTypes.Type(PassSpec.NonEmpty::class, name = "non_empty"),
)
sealed class PassSpec {
    /** A graded lib must appear at rank <= k. */
    data class RankLe(val k: Int) : PassSpec()

    /** Any graded lib must appear within the top k. */
    data class AnyInTop(val k: Int) : PassSpec()

    /** Precision@k over graded libs must be >= min. */
    data class PrecisionAt(val k: Int, val min: Double) : PassSpec()

    /** Every one of the top k results must support all requested platforms (k defaults to result count). */
    data class AllSupportPlatform(val k: Int? = null) : PassSpec()

    /** Results must be non-empty. */
    data object NonEmpty : PassSpec()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvalCase(
    val id: String,
    val cls: EvalClass,
    val query: String,
    val weight: Double,
    val graded: Boolean = false,
    val expected: List<String> = emptyList(),
    val also: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val pass: PassSpec,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QueriesFile(val cases: List<EvalCase>)

/** Regression floor: case ids proven to pass on the frozen snapshot. Regression asserts these stay green. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Floor(val ids: List<String> = emptyList())

/** Eval baseline: last committed eval outcome on the prod-copy — a new run diffs against it to show progress. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EvalBaseline(val headline: Double = 0.0, val passing: List<String> = emptyList())

/** One search hit reduced to what scoring needs: `ownerLogin/name` (lowercased) + platform names. */
data class SearchResult(val key: String, val platforms: Set<String>)
