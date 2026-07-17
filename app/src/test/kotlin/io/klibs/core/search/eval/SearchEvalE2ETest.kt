package io.klibs.core.search.eval

import io.awspring.cloud.s3.S3Template
import io.klibs.app.Application
import io.klibs.core.search.service.SearchService
import io.klibs.integration.ai.AiService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc

/**
 * Search-eval EVAL tier (KTL-4710): the aspirational upper bound.
 * Run:
 * ```
 * ./kotlin test --include-classes '*SearchEvalE2ETest' --jvm-args '-Dsearch.eval.tier=eval'
 * ```
 * Runs every case against a live **prod-copy** DB (`SEARCH_EVAL_DB_*` env; defaults to a local `klibs` DB).
 */
@EnabledIfSystemProperty(named = "search.eval.tier", matches = "eval")
@ActiveProfiles("test")
@SpringBootTest(classes = [Application::class])
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = [
    OpenAiChatAutoConfiguration::class,
    OpenAiAudioTranscriptionAutoConfiguration::class,
    OpenAiAudioSpeechAutoConfiguration::class,
    OpenAiEmbeddingAutoConfiguration::class,
    OpenAiImageAutoConfiguration::class,
    OpenAiModerationAutoConfiguration::class,
])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchEvalE2ETest {

    @MockitoBean
    private lateinit var aiService: AiService

    @MockitoBean
    private lateinit var s3Template: S3Template

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var searchService: SearchService

    private val cases = SearchEvalData.loadCases()
    private lateinit var report: RunReport

    @BeforeAll
    fun runBenchmark() {
        searchService.refreshSearchViews()
        report = Scorer.report(cases) { mockMvc.searchProjects(it) }
        val passing = report.outcomes.filter { it.pass }.map { it.case.id }
        log.info("eval: {}/{} cases pass, headline={}", passing.size, cases.size, "%.4f".format(report.headline))
        if (overwriteBaseline) SearchEvalData.writeBaseline(EvalBaseline(report.headline, passing.sorted()))
        else reportProgress(passing.toSet())
    }

    @TestFactory
    fun `eval - every case should pass (aspirational 100 percent - reds are the signal)`(): List<DynamicTest> {
        if (overwriteBaseline) return emptyList()
        return report.outcomes.map { outcome ->
            DynamicTest.dynamicTest("${outcome.case.cls}:${outcome.case.id}") {
                assertTrue(outcome.pass) { outcome.failureMessage("eval") }
            }
        }
    }

    private fun reportProgress(passing: Set<String>) {
        val baseline = SearchEvalData.loadBaseline()
        val gained = (passing - baseline.passing.toSet()).sorted()
        val lost = (baseline.passing.toSet() - passing).sorted()
        log.info(
            "eval vs baseline: headline {} -> {} ({})  gained={}  lost={}",
            "%.4f".format(baseline.headline), "%.4f".format(report.headline),
            "%+.4f".format(report.headline - baseline.headline), gained, lost,
        )
    }

    private val overwriteBaseline get() = System.getProperty("search.baseline.overwrite") != null

    companion object {
        private val log = LoggerFactory.getLogger(SearchEvalE2ETest::class.java)

        private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { env("SEARCH_EVAL_DB_URL", "jdbc:postgresql://localhost:5432/klibs") }
            registry.add("spring.datasource.username") { env("SEARCH_EVAL_DB_USER", "klibs") }
            registry.add("spring.datasource.password") { env("SEARCH_EVAL_DB_PASSWORD", "klibs") }
            // Corpus is a prod-copy; never seed the `test` profile's data.sql fixtures.
            registry.add("spring.sql.init.mode") { "never" }
            registry.add("klibs.readme.s3.bucket-name") { "test-bucket" }
            registry.add("klibs.readme.s3.prefix") { "readme" }
            registry.add("klibs.integration.github.cache.request-cache-path") { "build/tmp/gh-req-cache" }
        }
    }
}
