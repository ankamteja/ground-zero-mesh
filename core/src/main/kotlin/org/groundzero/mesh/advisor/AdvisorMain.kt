package org.groundzero.mesh.advisor

import org.groundzero.mesh.llm.BoardView
import org.groundzero.mesh.llm.KnowledgeBase
import org.groundzero.mesh.llm.LlmAdvisor
import org.groundzero.mesh.llm.OllamaClient
import java.io.File

/**
 * Runs the responder laptop's advisor: a local model, the rescue corpus, and a small HTTP
 * service the dashboard talks to. See [AdvisorServer] for why it lives here and not on the
 * phone.
 *
 * ### Running it
 *
 * ```
 * ollama serve &                                   # once, if it is not already up
 * ./gradlew :core:runAdvisor                       # :8787, model chosen from what ollama holds
 *
 * # pin a model, point at the phone's board, add an incident-specific corpus:
 * ./gradlew :core:runAdvisor -PadvisorArgs="--model qwen3:8b --gateway http://192.168.43.1:8080 --corpus ./notes"
 * ```
 *
 * Then open the phone's dashboard and the Advisor panel finds it on `localhost:8787`.
 *
 * ### Checking it without a browser
 *
 * ```
 * curl -s localhost:8787/health
 * curl -s localhost:8787/brief                     # needs --gateway
 * curl -s "localhost:8787/ask?q=who+do+I+send+the+boat+to+first"
 * ```
 *
 * ### Flags
 *
 * | flag | default | meaning |
 * |---|---|---|
 * | `--port` | 8787 | port this service listens on |
 * | `--ollama` | `http://localhost:11434` | model server root; may be another machine |
 * | `--model` | auto | pin a model name; auto picks from `LlmAdvisor.MODEL_PREFERENCE` |
 * | `--gateway` | none | phone gateway root, for `/brief` and for `GET /ask` |
 * | `--corpus` | none | a directory of extra `.md` / `.txt` retrieved alongside the bundled corpus |
 * | `--ask` | none | answer one question on stdout and exit, instead of serving |
 */
object AdvisorMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args)

        val knowledge = KnowledgeBase.bundled() +
            (config.corpus?.let { KnowledgeBase.fromDirectory(it) } ?: KnowledgeBase(emptyList()))
        val advisor = LlmAdvisor(
            client = OllamaClient(config.ollamaUrl),
            knowledge = knowledge,
            preferredModel = config.model,
        )

        val status = advisor.status()
        println("advisor — corpus ${knowledge.size} passage(s) from ${knowledge.sources.size} document(s)")
        println("model server ${config.ollamaUrl}: " + if (status.ollamaUp) "up" else "NOT REACHABLE")
        if (status.models.isEmpty()) {
            println("  no models listed. `ollama serve` running? `ollama pull mistral` done?")
            println("  the advisor still answers — with the deterministic brief, marked as such.")
        } else {
            println("  models: ${status.models.joinToString(", ")}")
            println("  using:  ${status.model}")
            // Load it now rather than on the responder's first question. On a GPU that is
            // several seconds of weights going into VRAM, and it is better spent here, while
            // someone is watching a terminal, than in front of someone waiting for an answer.
            status.model?.let { model ->
                print("  loading $model… ")
                val loadedAt = System.currentTimeMillis()
                val ok = OllamaClient(config.ollamaUrl).warmUp(model)
                println(if (ok) "resident (${System.currentTimeMillis() - loadedAt} ms)" else "not preloaded")
            }
        }
        config.gateway?.let { println("phone gateway: $it") }

        if (config.ask != null) {
            askOnce(advisor, config)
            return
        }

        val server = AdvisorServer(port = config.port, advisor = advisor, gatewayUrl = config.gateway)
        server.start()
        println("listening on http://localhost:${server.boundPort()}  (health · ask · brief)")
        println("open the phone's dashboard; the Advisor panel finds this automatically.")
        Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
        // The HttpServer runs on its own executor; park this thread rather than spinning.
        Thread.currentThread().join()
    }

    /** One question, printed, no server — for a runbook check or a shell pipeline. */
    private fun askOnce(advisor: LlmAdvisor, config: Config) {
        val board = config.gateway?.let {
            runCatching { BoardView.fromSnapshotJson(AdvisorServer.httpGet("${it.trimEnd('/')}/snapshot")) }
                .getOrNull()
        }
        if (board == null) {
            println("\nno board to answer about — pass --gateway http://<phone-ip>:8080")
            return
        }
        val advisory = advisor.advise(board, config.ask)
        println("\n" + advisory.text)
        if (advisory.sources.isNotEmpty()) println("\nsources: " + advisory.sources.joinToString("; "))
        advisory.note?.let { println("\nnote: $it") }
    }

    private data class Config(
        val port: Int = AdvisorServer.DEFAULT_PORT,
        val ollamaUrl: String = OllamaClient.DEFAULT_BASE_URL,
        val model: String? = null,
        val gateway: String? = null,
        val corpus: File? = null,
        val ask: String? = null,
    )

    private fun parseArgs(args: Array<String>): Config {
        var config = Config()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val value = args.getOrNull(i + 1)
            when (arg) {
                "--port" -> { config = config.copy(port = requireValue(arg, value).toInt()); i++ }
                "--ollama" -> { config = config.copy(ollamaUrl = requireValue(arg, value)); i++ }
                "--model" -> { config = config.copy(model = requireValue(arg, value)); i++ }
                "--gateway" -> { config = config.copy(gateway = normaliseUrl(requireValue(arg, value))); i++ }
                "--corpus" -> { config = config.copy(corpus = File(requireValue(arg, value))); i++ }
                "--ask" -> {
                    // Everything after --ask is the question, so it needs no quoting through
                    // Gradle's -PadvisorArgs, which splits on spaces.
                    config = config.copy(ask = args.drop(i + 1).joinToString(" "))
                    return config
                }
                else -> error("unknown argument '$arg'. See AdvisorMain's doc for the flag list.")
            }
            i++
        }
        return config
    }

    private fun requireValue(flag: String, value: String?): String =
        value ?: error("$flag needs a value")

    /** `192.168.43.1:8080` and `http://192.168.43.1:8080` should both work from a runbook. */
    private fun normaliseUrl(raw: String): String =
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
}
