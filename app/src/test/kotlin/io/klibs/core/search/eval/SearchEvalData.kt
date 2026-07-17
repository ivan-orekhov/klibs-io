package io.klibs.core.search.eval

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** Loads the answer key, the eval baseline, and the regression floor from `/search-eval` (test classpath). */
object SearchEvalData {

    private val mapper = jacksonObjectMapper()

    fun loadCases(): List<EvalCase> = read("/search-eval/queries.json", QueriesFile::class.java).cases

    fun loadBaseline(): EvalBaseline = read("/search-eval/baseline.json", EvalBaseline::class.java)

    /** `-Psearch.baseline.overwrite`: record the current eval outcome as the new baseline. */
    fun writeBaseline(baseline: EvalBaseline) = write("baseline.json", baseline)

    fun loadFloor(): Set<String> = read("/search-eval/floor.json", Floor::class.java).ids.toSet()

    /** `-Psearch.floor.overwrite`: record the ids passing now as the new floor. */
    fun writeFloor(ids: Collection<String>) = write("floor.json", Floor(ids.sorted()))

    private fun <T> read(path: String, type: Class<T>): T =
        (javaClass.getResourceAsStream(path) ?: error("resource not found: $path"))
            .use { mapper.readValue(it, type) }

    private fun write(name: String, value: Any) {
        File("src/test/resources/search-eval/$name")
            .apply { parentFile.mkdirs() }
            .writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
    }
}
