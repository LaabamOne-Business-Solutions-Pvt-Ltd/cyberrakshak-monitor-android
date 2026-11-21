// Tests for CyberRakshakMonitor on a plain JVM (android.jar's classes are only stand-ins there, so the tests start the
// library with a folder instead of a Context), against src/test/fixtures/fake-server.mjs only (127.0.0.1: never a real
// Cyber Rakshak host). Run by MonitorJvmTest (./gradlew testReleaseUnitTest); by hand: MonitorTestKt <server> <vectors.json>.
// The console's tests/sdk/android/MonitorTest.kt, with main() turned into runAll().
import ai.cyberrakshak.monitor.CyberRakshakMonitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

const val KEY = "crk_00000000000000000000000000000000"
lateinit var base: String
var passed = 0
var failed = 0

class Failed(message: String) : Exception(message)

fun check(ok: Boolean, what: String) {
    if (!ok) throw Failed(what)
}

fun same(want: Any?, got: Any?, what: String = "") {
    if (want != got) throw Failed((if (what.isEmpty()) "" else "$what: ") + "expected $want, got $got")
}

// ── A small JSON reader, for the fake server's answers ─────────────────────────────────────────────────────────────────
class Json(private val s: String) {
    private var i = 0
    fun parse(): Any? = value().also { space() }
    private fun space() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        space()
        return when (s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); space(); if (s[i] == '}') { i++; return m }
                while (true) { space(); val k = string(); space(); i++; m[k] = value(); space(); if (s[i++] == '}') return m } }
            '[' -> { i++; val l = ArrayList<Any?>(); space(); if (s[i] == ']') { i++; return l }
                while (true) { l.add(value()); space(); if (s[i++] == ']') return l } }
            '"' -> string()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { val start = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(start, i).toDouble() }
        }
    }
    private fun string(): String {
        val sb = StringBuilder(); i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(s[i])
                }
            } else sb.append(s[i])
            i++
        }
        i++
        return sb.toString()
    }
}

fun http(path: String, body: String? = null): Any? {
    val c = URL(base + "/__control/" + path).openConnection() as HttpURLConnection
    c.requestMethod = if (body == null) "GET" else "POST"
    c.readTimeout = 30000
    if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
    return Json(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }).parse()
}

@Suppress("UNCHECKED_CAST")
fun requests(count: Int = 0, timeout: Int = 5000): List<Map<String, Any?>> =
    (if (count > 0) http("wait?count=$count&timeout=$timeout") else http("requests")) as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
fun problems(r: List<Map<String, Any?>>) = r.flatMap { ((it["body"] as Map<String, Any?>)["errors"] as List<Map<String, Any?>>) }

@Suppress("UNCHECKED_CAST")
fun clean(r: List<Map<String, Any?>>) = r.forEach { same(emptyList<Any?>(), it["violations"], "request ${it["n"]} broke the contract") }

fun plan(vararg answers: String) = http("plan", "{\"responses\":[" + answers.joinToString(",") + "]}")

lateinit var dir: File

fun start(
    endpoint: String = "$base/api/v1/app-reports",
    key: String = KEY,
    release: String? = null,
    environment: String? = null,
    enabled: Boolean = true,
    sampleRate: Double = 1.0,
    beforeSend: ((CyberRakshakMonitor.Problem) -> CyberRakshakMonitor.Problem?)? = null,
    context: Map<String, Any?> = emptyMap(),
) = CyberRakshakMonitor.start(dir, CyberRakshakMonitor.Options(key, endpoint, release, environment, enabled, sampleRate, beforeSend, false, context), "android 14")

fun test(name: String, body: () -> Unit) {
    CyberRakshakMonitor.close()
    http("reset", "{}")
    dir = Files.createTempDirectory("cr-android-sdk").toFile()
    val t = System.currentTimeMillis()
    try {
        body()
        passed++
        println("  ok    $name (${System.currentTimeMillis() - t} ms)")
    } catch (e: Throwable) {
        failed++
        println("  FAIL  $name\n        $e")
    } finally {
        CyberRakshakMonitor.close()
        dir.deleteRecursively()
    }
}

fun make(message: String) = RuntimeException(message)

@Suppress("UNCHECKED_CAST")
fun runAll(server: String, vectorsFile: String): Int {
    base = server
    passed = 0
    failed = 0
    val vectors = (Json(File(vectorsFile).readText(Charsets.UTF_8)).parse() as Map<String, Any?>)["vectors"] as List<Map<String, String>>

    test("scrub: every shared vector") {
        for (v in vectors) same(v["out"], CyberRakshakMonitor.scrub(v["in"]!!), v["in"]!!)
        val worst = listOf("AbCdEfGh0123456789xyzXYZ+/".repeat(1231), "a".repeat(32000), "token".repeat(6400), "eyJ".repeat(10000))
        for (w in worst) {
            val t = System.currentTimeMillis()
            CyberRakshakMonitor.scrub(w)
            check(System.currentTimeMillis() - t < 3000, "scrubbing took ${System.currentTimeMillis() - t} ms")
        }
    }

    test("a captured exception in the contract's shape, scrubbed") {
        start(release = "1.4.2+57", environment = "Production", context = mapOf("build" to 57))
        CyberRakshakMonitor.captureException(RuntimeException("boom for asha@example.com"), "Checkout?step=2", mapOf("step" to 2, "ratio" to 0.5, "ok" to true, "who" to "x@example.org", "obj" to listOf(1)))
        same(true, CyberRakshakMonitor.flush(3000))
        val r = requests()
        same(1, r.size)
        clean(r)
        same("Bearer $KEY", r[0]["authorization"])
        val b = r[0]["body"] as Map<String, Any?>
        same(mapOf("name" to "cyberrakshak-android", "version" to "1.1.0"), b["sdk"])
        same("mobile", b["platform"])
        same("android 14", b["runtime"])
        same("1.4.2+57", b["release"])
        same("production", b["environment"])
        val p = problems(r)[0]
        same(listOf("exception", "java.lang.RuntimeException: boom for [email]", "Checkout", "error", 1.0), listOf(p["kind"], p["message"], p["route"], p["level"], p["count"]))
        check((p["stack"] as String).contains("MonitorTest"), "stack names the test")
        same(mapOf("build" to 57.0, "step" to 2.0, "ratio" to 0.5, "ok" to true, "who" to "[email]"), p["context"])
    }

    test("repeats are counted, not resent; setRoute gives the screen") {
        start()
        CyberRakshakMonitor.setRoute("Cart")
        repeat(5) { CyberRakshakMonitor.captureException(make("same problem")) }
        CyberRakshakMonitor.captureMessage("a message", "error")
        CyberRakshakMonitor.flush(3000)
        val r = requests()
        clean(r)
        same(1, r.size)
        val ps = problems(r)
        same(listOf("java.lang.RuntimeException: same problem", 5.0, "Cart"), listOf(ps[0]["message"], ps[0]["count"], ps[0]["route"]))
        same(listOf("log", "error", "a message"), listOf(ps[1]["kind"], ps[1]["level"], ps[1]["message"]))
    }

    test("at most 10 in one request; at most 50 waiting, the oldest go first") {
        start()
        repeat(25) { CyberRakshakMonitor.captureMessage("distinct $it") }
        CyberRakshakMonitor.flush(5000)
        same(listOf(10, 10, 5), requests().map { ((it["body"] as Map<String, Any?>)["errors"] as List<*>).size })
        http("reset", "{}")
        repeat(60) { CyberRakshakMonitor.captureMessage("waiting $it") }
        CyberRakshakMonitor.flush(5000)
        val all = problems(requests())
        same(50, all.size)
        same("waiting 10", all[0]["message"])
    }

    test("429: kept (in the files folder), sent again once Retry-After has passed") {
        plan("{\"status\":429,\"retryAfter\":\"1\"}")
        start()
        CyberRakshakMonitor.captureMessage("slow down")
        same(false, CyberRakshakMonitor.flush(3000))
        val r = requests(2, 6000)
        clean(r)
        same(2, r.size)
        val gap = (r[1]["at"] as Double) - (r[0]["at"] as Double)
        check(gap >= 950, "waited $gap ms")
        same("slow down", problems(listOf(r[1]))[0]["message"])
    }

    test("422: dropped, never retried") {
        plan("{\"status\":422}")
        start()
        CyberRakshakMonitor.captureMessage("first")
        CyberRakshakMonitor.flush(3000)
        CyberRakshakMonitor.captureMessage("second")
        CyberRakshakMonitor.flush(3000)
        Thread.sleep(300)
        same(listOf(listOf("first"), listOf("second")), requests().map { r -> problems(listOf(r)).map { it["message"] } })
    }

    test("401: dropped, and nothing more for an hour") {
        plan("{\"status\":401}")
        start()
        CyberRakshakMonitor.captureMessage("first")
        CyberRakshakMonitor.flush(3000)
        CyberRakshakMonitor.captureMessage("second")
        CyberRakshakMonitor.flush(1000)
        Thread.sleep(300)
        same(1, requests().size)
    }

    test("beforeSend: null drops, a change is sent as changed, one that throws drops") {
        start(beforeSend = { null })
        CyberRakshakMonitor.captureMessage("dropped")
        CyberRakshakMonitor.flush(1000)
        start(context = mapOf("tenant" to "t1"), beforeSend = { p -> p.message = "changed"; p.context = null; p })
        CyberRakshakMonitor.captureMessage("original")
        CyberRakshakMonitor.flush(3000)
        start(beforeSend = { throw IllegalStateException("bad filter") })
        CyberRakshakMonitor.captureMessage("also dropped")
        CyberRakshakMonitor.flush(1000)
        val r = requests()
        clean(r)
        same(1, r.size)
        same("changed", problems(r)[0]["message"])
        same(false, problems(r)[0].containsKey("context"))
    }

    test("sampleRate 0, enabled false and a wrong key send nothing") {
        start(sampleRate = 0.0)
        repeat(10) { CyberRakshakMonitor.captureMessage("sampled $it") }
        CyberRakshakMonitor.flush(1000)
        start(enabled = false)
        CyberRakshakMonitor.captureMessage("off")
        CyberRakshakMonitor.flush(1000)
        start(key = "crw_00000000000000000000000000000000")
        CyberRakshakMonitor.captureMessage("bad key")
        CyberRakshakMonitor.flush(1000)
        Thread.sleep(300)
        same(0, requests().size)
    }

    test("huge stack traces still go in bodies under 64 KB") {
        start()
        repeat(10) { n ->
            val e = RuntimeException("huge $n " + "é".repeat(3000))
            e.stackTrace = Array(5000) { StackTraceElement("com.example.Frame$n", "call$it", "Frame.kt", it) }
            CyberRakshakMonitor.captureException(e)
        }
        CyberRakshakMonitor.flush(5000)
        val r = requests()
        clean(r)
        same(10, problems(r).size)
        r.forEach { check((it["bytes"] as Double) <= 65536, "${it["bytes"]} bytes") }
    }

    test("nothing it is given makes it throw") {
        val odd = listOf("", "\uD800 lone", "\uDC00", "a".repeat(100000), "\u0000\u0001 control")
        for (x in odd) CyberRakshakMonitor.captureMessage(x)
        start()
        for (x in odd) {
            CyberRakshakMonitor.captureMessage(x, x, x, mapOf(x to x, "n" to Double.NaN, "i" to Double.POSITIVE_INFINITY, "l" to Long.MAX_VALUE, "o" to Any()))
            CyberRakshakMonitor.captureException(Throwable(x), x)
        }
        CyberRakshakMonitor.flush(5000)
        clean(requests())
    }

    test("a crash: written at once, the handler before runs, sent at the next start") {
        val seen = ArrayList<String>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen.add(e.message ?: "") }
        try {
            plan("{\"status\":204,\"delayMs\":0}")
            start(endpoint = "http://127.0.0.1:9/api/v1/app-reports")
            val t = Thread({ throw IllegalStateException("crash-boom") }, "worker-1")
            t.start()
            t.join()
            same(listOf("crash-boom"), seen, "the handler before ran")
            val files = dir.listFiles { f -> f.name.startsWith("pending-") }!!
            same(1, files.size, "pending files")
            // The next start of the app.
            CyberRakshakMonitor.close()
            start()
            val r = requests(1, 5000)
            clean(r)
            val p = problems(r)
            same(listOf("crash", "java.lang.IllegalStateException: crash-boom"), listOf(p[0]["kind"], p[0]["message"]))
            same(mapOf("thread" to "worker-1"), p[0]["context"])
            Thread.sleep(200)
            same(0, dir.listFiles { f -> f.name.startsWith("pending-") }!!.size, "sent files are gone")
        } finally {
            CyberRakshakMonitor.close()
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    test("a crash with no handler before: Java's own message, the thread ends, the app goes on") {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val err = System.err
        val captured = ByteArrayOutputStream()
        Thread.setDefaultUncaughtExceptionHandler(null)
        try {
            start()
            System.setErr(PrintStream(captured, true))
            val t = Thread({ throw IllegalStateException("plain-boom") }, "worker-2")
            t.start()
            t.join()
            System.setErr(err)
            check(captured.toString().contains("Exception in thread \"worker-2\" java.lang.IllegalStateException: plain-boom"), "printed: $captured")
        } finally {
            System.setErr(err)
            CyberRakshakMonitor.close()
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    test("close() gives back the handler that was there before") {
        val mine = Thread.UncaughtExceptionHandler { _, _ -> }
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(mine)
        try {
            start()
            check(Thread.getDefaultUncaughtExceptionHandler() !== mine, "installed")
            CyberRakshakMonitor.close()
            check(Thread.getDefaultUncaughtExceptionHandler() === mine, "given back")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    test("scrubNames and scrubPatterns add to the built-in rules; a secret context name goes as [removed]") {
        val options = CyberRakshakMonitor.Options(
            KEY, "$base/api/v1/app-reports", context = mapOf("client_secret" to "abc", "region" to "dub1"),
            scrubNames = listOf("x-brand-key", "face.key", "bad name"),
            scrubPatterns = listOf("faces/[A-Za-z0-9/_.-]+", "upload-[0-9]+", "(", "x*"),
        )
        CyberRakshakMonitor.start(dir, options, "android 14")
        CyberRakshakMonitor.captureException(
            RuntimeException("x-brand-key=B1 for faces/2026/a.jpg (upload-77)"), "Files face.key=K9",
            mapOf("user_pin" to 1234, "iban" to "IE29AIBK93115212345678", "x-tink-signature" to true, "spinning" to "yes", "japan" to 3),
        )
        same(true, CyberRakshakMonitor.flush(3000))
        val r = requests()
        clean(r)
        val p = problems(r)[0]
        same("java.lang.RuntimeException: x-brand-key=[removed] for [removed] ([removed])", p["message"])
        same("Files face.key=[removed]", p["route"])
        same(mapOf("client_secret" to "[removed]", "region" to "dub1", "user_pin" to "[removed]", "iban" to "[removed]", "x-tink-signature" to "[removed]", "spinning" to "yes", "japan" to 3.0), p["context"])
        same("pin=[removed] x-brand-key=[removed] password=[removed]", CyberRakshakMonitor.scrub("pin=1 x-brand-key=2 password=3"))
        CyberRakshakMonitor.close()
        same("x-brand-key=2", CyberRakshakMonitor.scrub("x-brand-key=2"))
    }

    test("scrubNames and scrubPatterns: at most 50 names and 20 patterns") {
        val options = CyberRakshakMonitor.Options(
            KEY, "$base/api/v1/app-reports",
            scrubNames = listOf("ok_name") + (0 until 60).map { "n$it" },
            scrubPatterns = listOf("fine") + (0 until 25).map { "p${it}q" },
        )
        CyberRakshakMonitor.start(dir, options, "android 14")
        same("ok_name=[removed] n48=[removed] n49=3 [removed] [removed] p19q", CyberRakshakMonitor.scrub("ok_name=1 n48=2 n49=3 fine p18q p19q"))
        same("password=[removed] card [card]", CyberRakshakMonitor.scrub("password=1 card 4111 1111 1111 1111"))
    }

    println("android (JVM ${System.getProperty("java.version")}): $passed passed, $failed failed")
    return failed
}

fun main(args: Array<String>) {
    System.exit(if (runAll(args[0], args[1]) > 0) 1 else 0)
}
