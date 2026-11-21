/*
 * Cyber Rakshak error monitoring for Android (Kotlin), version 1.1.0
 * Docs: https://app.cyberrakshak.ai/sdk/README.md
 * Copyright Laabam One Business Solutions (OPC) Private Limited
 * Released under the MIT licence: https://app.cyberrakshak.ai/sdk/LICENSE
 *
 * One Kotlin file, nothing but the Android SDK (API 21+) and the Kotlin standard library: add it to your app's source.
 * Needs <uses-permission android:name="android.permission.INTERNET" />. Sends what goes wrong in your app to your
 * site's Errors page in the Cyber Rakshak console: the exception's text and stack trace, the screen, when and how often,
 * and the Android version. Secrets are scrubbed on the phone before anything leaves (email addresses, tokens and keys,
 * password=... values, query strings, card numbers). Nothing the user types, no device id, no location, no contacts.
 *
 *   class App : Application() {
 *       override fun onCreate() {
 *           super.onCreate()
 *           CyberRakshakMonitor.init(this, CyberRakshakMonitor.Options(key = "crk_…", release = BuildConfig.VERSION_NAME,
 *               environment = if (BuildConfig.DEBUG) "development" else "production"))
 *       }
 *   }
 *   CyberRakshakMonitor.setRoute("Checkout")                          // the screen now shown
 *   CyberRakshakMonitor.captureException(e, context = mapOf("step" to 2))
 *   CyberRakshakMonitor.captureMessage("Payment provider answered slowly")
 *
 * Caught by itself: an uncaught exception on any thread (a crash). It is written to the app's files folder at once,
 * then the handler that was there before runs as it would have (on Android: the app closes), and the report is sent at
 * the next start, on a background thread. Repeats of one problem are counted, not resent; at most 10 per request; what
 * could not be sent (no network, the console asked to wait) is kept in the files folder and tried again later.
 * Not caught: native (C/C++) crashes and ANRs.
 *
 * Nothing in here may throw into your app or slow it: a monitor that fails must never become the app's second problem.
 */
package ai.cyberrakshak.monitor

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

object CyberRakshakMonitor {
    const val VERSION = "1.1.0"
    const val DEFAULT_ENDPOINT = "https://app.cyberrakshak.ai/api/v1/app-reports"

    private const val SDK_NAME = "cyberrakshak-android"
    private const val TAG = "CyberRakshakMonitor"
    private const val MAX_BATCH = 10
    private const val MAX_WAITING = 50
    private const val MAX_FILES = 20
    private const val MAX_MESSAGE = 4000
    private const val MAX_STACK = 16000
    private const val MAX_ROUTE = 200
    private const val MAX_CONTEXT = 20
    private const val MAX_CONTEXT_KEY = 40
    private const val MAX_CONTEXT_VALUE = 300
    private const val MAX_COUNT = 100000
    private const val BODY_LIMIT = 60000
    private const val SEND_AFTER_MS = 2000L
    private const val SEND_TIMEOUT_MS = 5000
    private const val BACKOFF_FIRST_MS = 2000L
    private const val BACKOFF_MAX_MS = 300000L
    private const val RETRY_AFTER_MAX_MS = 86400000L
    private const val BLOCKED_MS = 3600000L

    private val KINDS = setOf("exception", "crash", "promise", "log", "http", "script")
    private val PLATFORMS = setOf("mobile", "server", "script", "desktop", "other")
    private val KEY_RE = Pattern.compile("^crk_[A-Za-z0-9]{32}$")
    private val RELEASE_RE = Pattern.compile("^[A-Za-z0-9._+\\-]{1,60}$")
    private val ENVIRONMENT_RE = Pattern.compile("^[a-z0-9_-]{1,30}$")
    private val ISO_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?(Z|[+-]\\d{2}:?\\d{2})$")

    /** Settings. beforeSend may change a problem or return null to drop it; context goes with every problem. */
    class Options @JvmOverloads constructor(
        val key: String,
        val endpoint: String = DEFAULT_ENDPOINT,
        val release: String? = null,
        val environment: String? = null,
        val enabled: Boolean = true,
        val sampleRate: Double = 1.0,
        val beforeSend: ((Problem) -> Problem?)? = null,
        val debug: Boolean = false,
        val context: Map<String, Any?> = emptyMap(),
        val platform: String = "mobile",
        /** Names of your own whose values are removed, like password= (whole words, A-Z a-z 0-9 _ . -, at most 50). Only adds to the built-in rules. */
        val scrubNames: List<String> = emptyList(),
        /** Regular expressions of your own: what they match is removed (at most 20). Only adds to the built-in rules. */
        val scrubPatterns: List<String> = emptyList(),
    )

    /** One problem as it is sent (beforeSend gets it scrubbed). */
    class Problem(
        var kind: String,
        var message: String,
        var stack: String?,
        var route: String?,
        var level: String,
        var count: Int,
        var at: String,
        var context: MutableMap<String, Any?>?,
    )

    private class State(
        val dir: File,
        val key: String,
        val endpoint: String,
        val release: String?,
        val environment: String?,
        val sampleRate: Double,
        val beforeSend: ((Problem) -> Problem?)?,
        val debug: Boolean,
        val context: Map<String, Any?>,
        val platform: String,
        val runtime: String,
        val executor: ScheduledExecutorService,
    ) {
        val queue = LinkedHashMap<String, Problem>()
        var timer: ScheduledFuture<*>? = null
        var route: String? = null
        var notBefore = 0L
        var backoff = 0L
        var blockedUntil = 0L
        val logged = HashSet<String>()
        var previous: Thread.UncaughtExceptionHandler? = null
        var own: Thread.UncaughtExceptionHandler? = null
        var fileCounter = 0
        var extraNames: Pattern? = null
        var extraKeys: Pattern? = null
        var extraPatterns: List<Pattern> = emptyList()
    }

    @Volatile
    private var state: State? = null
    private val reported: MutableSet<Throwable> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Throwable, Boolean>()))
    private val busy = ThreadLocal<Boolean>()

    // ── Scrubbing: the same rules, in the same order, in every Cyber Rakshak library ─────────────────────────────────
    private class Rule(val pattern: Pattern, val replace: (Matcher) -> String?)

    private val STRONG = Pattern.compile("[0-9A-Z._~+/=]")
    private val RULES: List<Rule> = listOf(
        // A query string: ?name=... up to a space or quote. Where a request went is kept, never what it asked.
        Rule(Pattern.compile("""\?[A-Za-z0-9_.%-]+[=&][^ \t\r\n#"'<>]*""")) { "" },
        // Authorization values.
        Rule(Pattern.compile("""(bearer|basic) +([A-Za-z0-9._~+/=-]{8,})""", Pattern.CASE_INSENSITIVE)) { m ->
            if (STRONG.matcher(m.group(2) ?: "").find()) (m.group(1) ?: "") + " [token]" else null
        },
        // password=..., "client_secret": "...", x-api-key: ... (the name is kept, the value is not). The match starts at
        // the word (what comes before it is kept as it is) and allows 40 more name characters after it.
        Rule(
            Pattern.compile(
                """((?:password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|session[_-]?id)[A-Za-z0-9_-]{0,40}\\?["']?[ ]*[=:][ ]*\\?["']?)[^ \t\r\n&,;"'<>\\]+""",
                Pattern.CASE_INSENSITIVE,
            ),
        ) { m -> (m.group(1) ?: "") + "[removed]" },
        // JSON Web Tokens (a header of at most 500 characters).
        Rule(Pattern.compile("""eyJ[A-Za-z0-9_-]{1,500}\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?""")) { "[token]" },
        // Keys that say what they are.
        Rule(
            Pattern.compile(
                """(?<![A-Za-z0-9])(?:crk_|crw_|sk_live_|sk_test_|pk_live_|pk_test_|rk_live_|ghp_|gho_|ghs_|ghu_|github_pat_|glpat-|xoxb-|xoxp-|xoxa-|AKIA|ASIA|AIza)[A-Za-z0-9_-]{10,}""",
            ),
        ) { "[token]" },
        // Email addresses (at most 64 characters before the @, as in an address).
        Rule(Pattern.compile("""[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")) { "[email]" },
        // IBANs: two capital letters, two digits, then 11 to 30 capitals or digits (or the same in groups of four, single
        // spaces between), 15 to 34 in all, passing the ISO 13616 mod-97 check. Before cards: an IBAN holds a digit run.
        Rule(Pattern.compile("""(?<![A-Za-z0-9])[A-Z]{2}[0-9]{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)(?![A-Za-z0-9])""")) { m ->
            if (ibanOk(m.group())) "[iban]" else null
        },
        // Card numbers: 13 to 19 digits (single spaces or dashes between) that pass the Luhn check.
        Rule(Pattern.compile("""(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}""")) { m ->
            if (luhn(m.group().replace(" ", "").replace("-", ""))) "[card]" else null
        },
        // pin: 1234, "iban": "...", set-cookie: ..., x-tink-signature=...: these names only as whole words (not right after
        // a letter or digit, the separator right after the name), so company:, spinning: and japan: stay; the value goes as
        // in the password rule. After IBANs and cards, so a value with spaces in it has gone whole already. A value that is
        // Bearer or Basic and a space was handled above.
        Rule(
            Pattern.compile(
                """(?<![A-Za-z0-9])((?:authorization|cookie|signature|otp|pin|cvv|cvc|iban|bban|pan|account[_-]?(?:no|number|num)|sort[_-]?code|card[_-]?number|bank[_-]?auth[_-]?id)\\?["']?[ ]*[=:][ ]*\\?["']?)(?!(?:bearer|basic) )[^ \t\r\n&,;"'<>\\]+""",
                Pattern.CASE_INSENSITIVE,
            ),
        ) { m -> (m.group(1) ?: "") + "[removed]" },
        // Long hex runs: session ids, hashes, keys.
        Rule(Pattern.compile("""[0-9a-fA-F]{32,}""")) { "[token]" },
        // Long random-looking runs: a digit, an upper-case and a lower-case letter in 40 or more of A-Z a-z 0-9 + _ -.
        Rule(Pattern.compile("""[A-Za-z0-9+_-]{40,}""")) { m ->
            val s = m.group()
            if (s.any { it in '0'..'9' } && s.any { it in 'A'..'Z' } && s.any { it in 'a'..'z' }) "[token]" else null
        },
    )

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (double) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            double = !double
        }
        return sum % 10 == 0
    }

    /** ISO 13616: the first four characters moved to the end, letters as 10..35, the whole number mod 97 is 1. */
    private fun ibanOk(text: String): Boolean {
        val s = text.replace(" ", "")
        if (s.length < 15 || s.length > 34) return false
        val moved = s.substring(4) + s.substring(0, 4)
        var rest = 0
        for (ch in moved) {
            val v = if (ch >= 'A') ch - 'A' + 10 else ch - '0'
            rest = if (v > 9) (rest * 100 + v) % 97 else (rest * 10 + v) % 97
        }
        return rest == 1
    }

    // A context entry's name that says its value is a secret: the words of the password rule anywhere in it, or one of
    // the whole-word names (at the end, not right after a letter or digit: user_pin, x-tink-signature, not spinning).
    private val SECRET_NAME_RE = Pattern.compile(
        """password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|session[_-]?id""",
        Pattern.CASE_INSENSITIVE,
    )
    private val WHOLE_NAME_RE = Pattern.compile(
        """(?:^|[^A-Za-z0-9])(?:authorization|cookie|signature|otp|pin|cvv|cvc|iban|bban|pan|account[_-]?(?:no|number|num)|sort[_-]?code|card[_-]?number|bank[_-]?auth[_-]?id)$""",
        Pattern.CASE_INSENSITIVE,
    )
    private val EXTRA_NAME_RE = Pattern.compile("^[A-Za-z0-9_.-]{1,40}$")
    private const val MAX_EXTRA_NAMES = 50
    private const val MAX_EXTRA_PATTERNS = 20

    private fun secretName(name: String): Boolean {
        val s = state
        return SECRET_NAME_RE.matcher(name).find() || WHOLE_NAME_RE.matcher(name).find() || (s?.extraKeys?.matcher(name)?.find() ?: false)
    }

    private fun applyRule(text: String, pattern: Pattern, replace: (Matcher) -> String?): String {
        val m = pattern.matcher(text)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            val replaced = replace(m) ?: continue
            sb.append(text, last, m.start()).append(replaced)
            last = m.end()
        }
        if (last == 0 && sb.isEmpty()) return text
        sb.append(text, last, text.length)
        return sb.toString()
    }

    /**
     * What a text looks like once scrubbed: what this library would send. The built-in rules always run, first; then the
     * names and patterns given as scrubNames and scrubPatterns, which can only add to them.
     */
    @JvmStatic
    fun scrub(text: String): String {
        return try {
            var out = text
            for (rule in RULES) out = applyRule(out, rule.pattern, rule.replace)
            val s = state
            val names = s?.extraNames
            if (names != null) out = applyRule(out, names) { m -> (m.group(1) ?: "") + "[removed]" }
            for (pattern in s?.extraPatterns ?: emptyList()) {
                try {
                    out = pattern.matcher(out).replaceAll("[removed]")
                } catch (e: Throwable) {
                    // A pattern of yours that fails is skipped; the built-in rules have run.
                }
            }
            out
        } catch (e: Throwable) {
            ""
        }
    }

    /** scrubNames: names of your own, as whole words, their values removed like the built-in ones; at most 50. */
    private fun extraNames(list: List<String>, debug: Boolean): List<String> {
        val names = ArrayList<String>()
        for (name in list) {
            if (!EXTRA_NAME_RE.matcher(name).matches()) {
                say(debug, "scrubNames: a name was left out: only A-Z a-z 0-9 _ . - (1 to 40)")
                continue
            }
            if (names.size >= MAX_EXTRA_NAMES) {
                say(debug, "scrubNames: only the first $MAX_EXTRA_NAMES are used")
                break
            }
            val lower = name.lowercase(Locale.ROOT)
            if (lower !in names) names.add(lower)
        }
        return names
    }

    /** scrubPatterns: regular expressions of your own; what they match goes. A bad one, or one matching "", is left out. */
    private fun extraPatterns(list: List<String>, debug: Boolean): List<Pattern> {
        val patterns = ArrayList<Pattern>()
        for (source in list) {
            if (patterns.size >= MAX_EXTRA_PATTERNS) {
                say(debug, "scrubPatterns: only the first $MAX_EXTRA_PATTERNS are used")
                break
            }
            try {
                val pattern = Pattern.compile(source)
                if (pattern.matcher("").find()) {
                    say(debug, "scrubPatterns: a pattern that matches an empty text was left out")
                    continue
                }
                patterns.add(pattern)
            } catch (e: Throwable) {
                say(debug, "scrubPatterns: a pattern was left out: $e")
            }
        }
        return patterns
    }

    // ── API ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    /** Starts monitoring; call it once, in Application.onCreate(). */
    @JvmStatic
    fun init(context: Context, options: Options) {
        try {
            val dir = File(context.applicationContext.filesDir, "cyberrakshak-monitor")
            var runtime = "android"
            try {
                runtime = "android " + android.os.Build.VERSION.RELEASE
            } catch (e: Throwable) {
                // "android", then.
            }
            start(dir, options, runtime)
        } catch (e: Throwable) {
            say(options.debug, "not started: $e")
        }
    }

    /** The start itself, without a Context (the library's own tests run it on a plain JVM). */
    @JvmStatic
    internal fun start(dir: File, options: Options, runtime: String) {
        try {
            close()
            if (!options.enabled) return
            val key = options.key.trim()
            if (!KEY_RE.matcher(key).matches()) {
                say(options.debug, "not started: key must be your site's crk_ app key (its Security scan tab, Apps, servers and scripts)")
                return
            }
            var release = options.release
            if (release != null && !RELEASE_RE.matcher(release).matches()) {
                say(options.debug, "release left out: it may hold only A-Z a-z 0-9 . _ + - (at most 60)")
                release = null
            }
            var environment = options.environment?.lowercase(Locale.ROOT)
            if (environment != null && !ENVIRONMENT_RE.matcher(environment).matches()) {
                say(options.debug, "environment left out: it may hold only a-z 0-9 _ - (at most 30)")
                environment = null
            }
            val endpoint = if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(options.endpoint)) options.endpoint else DEFAULT_ENDPOINT
            dir.mkdirs()
            val executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "cyberrakshak-monitor").apply { isDaemon = true }
            }
            val s = State(
                dir, key, endpoint, release, environment,
                if (options.sampleRate.isNaN()) 1.0 else options.sampleRate.coerceIn(0.0, 1.0),
                options.beforeSend, options.debug, options.context,
                if (options.platform in PLATFORMS) options.platform else "mobile",
                clip(runtime, 60), executor,
            )
            val names = extraNames(options.scrubNames, options.debug)
            if (names.isNotEmpty()) {
                val alternation = names.joinToString("|") { Pattern.quote(it) }
                s.extraNames = Pattern.compile(
                    """(?<![A-Za-z0-9])((?:""" + alternation + """)\\?["']?[ ]*[=:][ ]*\\?["']?)[^ \t\r\n&,;"'<>\\]+""",
                    Pattern.CASE_INSENSITIVE,
                )
                s.extraKeys = Pattern.compile("(?:^|[^A-Za-z0-9])(?:$alternation)$", Pattern.CASE_INSENSITIVE)
            }
            s.extraPatterns = extraPatterns(options.scrubPatterns, options.debug)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val own = Thread.UncaughtExceptionHandler { thread, error -> onCrash(s, thread, error) }
            s.previous = previous
            s.own = own
            state = s
            Thread.setDefaultUncaughtExceptionHandler(own)
            // What the last run could not send (its crash, most often) goes now, in the background.
            executor.execute { drain(s, SEND_TIMEOUT_MS) }
            debug(s, "started: $SDK_NAME $VERSION reporting to $endpoint")
        } catch (e: Throwable) {
            state = null
            say(options.debug, "not started: $e")
        }
    }

    /** Stops monitoring and gives back the uncaught-exception handler that was there before. */
    @JvmStatic
    fun close() {
        val s = state ?: return
        state = null
        try {
            if (Thread.getDefaultUncaughtExceptionHandler() === s.own) Thread.setDefaultUncaughtExceptionHandler(s.previous)
            s.executor.shutdownNow()
        } catch (e: Throwable) {
            // Never a second problem.
        }
    }

    /** Reports an exception you caught. */
    @JvmStatic
    @JvmOverloads
    fun captureException(error: Throwable, route: String? = null, context: Map<String, Any?> = emptyMap(), level: String = "error") {
        try {
            capture("exception", error, null, null, route, context, level)
        } catch (e: Throwable) {
            // Never a second problem.
        }
    }

    /** Reports a message; level "warning" (the default) or "error". */
    @JvmStatic
    @JvmOverloads
    fun captureMessage(text: String, level: String = "warning", route: String? = null, context: Map<String, Any?> = emptyMap()) {
        try {
            capture("log", null, text, null, route, context, level)
        } catch (e: Throwable) {
            // Never a second problem.
        }
    }

    /** The screen now shown: sent as the route of what goes wrong next. */
    @JvmStatic
    fun setRoute(route: String?) {
        state?.route = route
    }

    /**
     * Sends what is waiting now and waits for it, at most timeoutMs. True when nothing is left. It blocks: call it off
     * the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 2000): Boolean {
        val s = state ?: return true
        return try {
            val future = s.executor.submit<Boolean> {
                cancelTimer(s)
                sendQueue(s, timeoutMs.toInt().coerceIn(100, SEND_TIMEOUT_MS))
                drain(s, timeoutMs.toInt().coerceIn(100, SEND_TIMEOUT_MS))
                synchronized(s) { s.queue.isEmpty() } && pendingFiles(s).isEmpty()
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            false
        }
    }

    // ── Capturing ────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun capture(kind: String, error: Throwable?, message: String?, stackGiven: String?, route: String?, context: Map<String, Any?>?, level: String?) {
        val s = state ?: return
        if (busy.get() == true) return
        busy.set(true)
        try {
            val problem = build(s, kind, error, message, stackGiven, route, context, level) ?: return
            enqueue(s, problem)
            schedule(s, SEND_AFTER_MS)
        } catch (e: Throwable) {
            debug(s, "could not capture: $e")
        } finally {
            busy.set(false)
        }
    }

    private fun build(s: State, kind: String, error: Throwable?, message: String?, stackGiven: String?, route: String?, context: Map<String, Any?>?, level: String?): Problem? {
        if (error != null && !reported.add(error)) return null
        val now = System.currentTimeMillis()
        if (now < s.blockedUntil) return null
        if (s.sampleRate < 1.0 && Math.random() >= s.sampleRate) return null
        val stack = stackGiven ?: error?.let { stackOf(it) }
        val draft = Problem(
            kind = kind,
            message = clean(message ?: error?.toString() ?: "", MAX_MESSAGE).ifBlank { "(no message)" },
            stack = stack?.let { clean(it, MAX_STACK) }?.takeIf { it.isNotEmpty() },
            route = (route ?: s.route)?.let { clean(it.split('?', '#')[0], MAX_ROUTE) }?.takeIf { it.isNotEmpty() },
            level = if (level == "error" || level == "warning") level else if (kind == "log") "warning" else "error",
            count = 1,
            at = iso(now),
            context = cleanContext(s.context, context),
        )
        val changed = if (s.beforeSend != null) {
            try {
                s.beforeSend.invoke(draft)
            } catch (e: Throwable) {
                debug(s, "beforeSend threw, so the problem was not sent: $e")
                return null
            }
        } else {
            draft
        }
        return changed?.let { normalise(it, kind) }
    }

    private fun stackOf(error: Throwable): String? = try {
        val w = StringWriter()
        error.printStackTrace(PrintWriter(w))
        w.toString()
    } catch (e: Throwable) {
        null
    }

    private fun topFrame(stack: String?): String {
        if (stack == null) return ""
        for (line in stack.split('\n')) {
            val t = line.trim()
            if (t.startsWith("at ")) return t
        }
        return ""
    }

    private fun cleanContext(vararg sources: Map<String, Any?>?): MutableMap<String, Any?>? {
        val out = LinkedHashMap<String, Any?>()
        for (source in sources) {
            if (source == null) continue
            for ((name, value) in source) {
                val key = clip(name, MAX_CONTEXT_KEY)
                val kept: Any? = if (secretName(name)) "[removed]" else when (value) {
                    null -> null
                    is Boolean -> value
                    is Int, is Long, is Short, is Byte -> (value as Number).toLong()
                    is Float, is Double -> (value as Number).toDouble().takeIf { !it.isNaN() && !it.isInfinite() } ?: continue
                    is String -> clean(value, MAX_CONTEXT_VALUE)
                    else -> continue
                }
                if (!out.containsKey(key) && out.size >= MAX_CONTEXT) continue
                out[key] = kept
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** The shape the console accepts, whatever beforeSend handed back; null when it is not a problem any more. */
    private fun normalise(p: Problem, fallbackKind: String): Problem? {
        val message = clip(p.message, MAX_MESSAGE)
        if (message.isBlank()) return null
        val context = p.context?.let { source ->
            val kept = LinkedHashMap<String, Any?>()
            for ((name, value) in source) {
                if (kept.size >= MAX_CONTEXT) break
                when (value) {
                    null, is Boolean, is Long, is Int -> kept[clip(name, MAX_CONTEXT_KEY)] = value
                    is Double -> if (!value.isNaN() && !value.isInfinite()) kept[clip(name, MAX_CONTEXT_KEY)] = value
                    is String -> kept[clip(name, MAX_CONTEXT_KEY)] = clip(value, MAX_CONTEXT_VALUE)
                }
            }
            if (kept.isEmpty()) null else kept
        }
        return Problem(
            kind = if (p.kind in KINDS) p.kind else fallbackKind,
            message = message,
            stack = p.stack?.let { clip(it, MAX_STACK) }?.takeIf { it.isNotEmpty() },
            route = p.route?.let { clip(it, MAX_ROUTE) }?.takeIf { it.isNotEmpty() },
            level = if (p.level == "warning") "warning" else "error",
            count = p.count.coerceIn(1, MAX_COUNT),
            at = if (p.at.length <= 40 && ISO_RE.matcher(p.at).matches()) p.at else iso(System.currentTimeMillis()),
            context = context,
        )
    }

    private fun fingerprint(p: Problem) = p.kind + "\n" + p.message + "\n" + topFrame(p.stack)

    private fun enqueue(s: State, problem: Problem) {
        synchronized(s) {
            val id = fingerprint(problem)
            val have = s.queue[id]
            if (have != null) {
                have.count = (have.count + problem.count).coerceAtMost(MAX_COUNT)
                have.at = problem.at
            } else {
                s.queue[id] = problem
                while (s.queue.size > MAX_WAITING) s.queue.remove(s.queue.keys.first())
            }
        }
    }

    private fun schedule(s: State, delayMs: Long) {
        try {
            synchronized(s) {
                if (s.timer != null || state !== s) return
                s.timer = s.executor.schedule({
                    synchronized(s) { s.timer = null }
                    sendQueue(s, SEND_TIMEOUT_MS)
                    drain(s, SEND_TIMEOUT_MS)
                }, maxOf(delayMs, s.notBefore - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            }
        } catch (e: Throwable) {
            // Never a second problem.
        }
    }

    private fun cancelTimer(s: State) {
        synchronized(s) {
            s.timer?.cancel(false)
            s.timer = null
        }
    }

    // ── Crash ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun onCrash(s: State, thread: Thread, error: Throwable) {
        try {
            if (state === s && busy.get() != true) {
                busy.set(true)
                val crash = build(s, "crash", error, null, null, null, mapOf("thread" to thread.name), "error")
                if (crash != null) enqueue(s, crash)
                // Written now, while the process still lives: everything waiting, the crash with it. Sent at the next start.
                val entries = synchronized(s) {
                    val list = s.queue.entries.map { it.key to it.value }
                    s.queue.clear()
                    list
                }
                writeBatches(s, entries)
            }
        } catch (e: Throwable) {
            // Never a second problem.
        }
        val previous = s.previous
        if (previous != null) {
            previous.uncaughtException(thread, error)
        } else {
            // No handler before this one: what Java does then, and the thread ends.
            System.err.print("Exception in thread \"" + thread.name + "\" ")
            error.printStackTrace(System.err)
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun envelope(s: State, problems: List<Problem>): String {
        val sb = StringBuilder(4096)
        sb.append("{\"sdk\":{\"name\":\"").append(SDK_NAME).append("\",\"version\":\"").append(VERSION).append("\"},\"platform\":")
        json(sb, s.platform)
        sb.append(",\"runtime\":")
        json(sb, s.runtime)
        if (s.release != null) {
            sb.append(",\"release\":")
            json(sb, s.release)
        }
        if (s.environment != null) {
            sb.append(",\"environment\":")
            json(sb, s.environment)
        }
        sb.append(",\"errors\":[")
        problems.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"kind\":")
            json(sb, p.kind)
            sb.append(",\"message\":")
            json(sb, p.message)
            val stack = p.stack
            if (stack != null) {
                sb.append(",\"stack\":")
                json(sb, stack)
            }
            val route = p.route
            if (route != null) {
                sb.append(",\"route\":")
                json(sb, route)
            }
            sb.append(",\"level\":")
            json(sb, p.level)
            sb.append(",\"count\":").append(p.count).append(",\"at\":")
            json(sb, p.at)
            val context = p.context
            if (context != null && context.isNotEmpty()) {
                sb.append(",\"context\":{")
                var first = true
                for ((k, v) in context) {
                    if (!first) sb.append(',')
                    first = false
                    json(sb, k)
                    sb.append(':')
                    when (v) {
                        null -> sb.append("null")
                        is Boolean, is Long, is Int -> sb.append(v.toString())
                        is Double -> sb.append(if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
                        else -> json(sb, v.toString())
                    }
                }
                sb.append('}')
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun json(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Bodies under 64 KB: long stacks are halved first, then fewer problems go in one body. */
    private fun fit(s: State, problems: MutableList<Problem>): Pair<ByteArray, List<Problem>> {
        val rest = ArrayList<Problem>()
        var body = envelope(s, problems).toByteArray(Charsets.UTF_8)
        var round = 0
        while (body.size > BODY_LIMIT && round++ < 200) {
            val longest = problems.filter { (it.stack?.length ?: 0) > 1000 }.maxByOrNull { it.stack!!.length }
            if (longest != null) {
                longest.stack = clip(longest.stack!!, longest.stack!!.length / 2) + "\n[cut]"
            } else if (problems.size > 1) {
                rest.add(0, problems.removeAt(problems.size - 1))
            } else {
                val p = problems[0]
                when {
                    p.message.length > 1000 -> p.message = clip(p.message, p.message.length / 2)
                    p.context != null -> p.context = null
                    p.stack != null -> p.stack = null
                    else -> break
                }
            }
            body = envelope(s, problems).toByteArray(Charsets.UTF_8)
        }
        return body to rest
    }

    /** Writes problems as bodies ready to send, one file each, in the files folder (the oldest go past MAX_FILES). */
    private fun writeBatches(s: State, entries: List<Pair<String, Problem>>) {
        var left = entries.map { it.second }
        while (left.isNotEmpty()) {
            val batch = left.take(MAX_BATCH).toMutableList()
            left = left.drop(batch.size)
            val (body, rest) = fit(s, batch)
            left = rest + left
            val file = File(s.dir, "pending-" + System.currentTimeMillis() + "-" + (s.fileCounter++) + ".json")
            FileOutputStream(file).use { out ->
                out.write(body)
                out.flush()
                try {
                    out.fd.sync()
                } catch (e: Throwable) {
                    // Written, if not yet on the disk.
                }
            }
        }
        val files = pendingFiles(s)
        if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun pendingFiles(s: State): List<File> =
        (s.dir.listFiles { f -> f.name.startsWith("pending-") && f.name.endsWith(".json") } ?: emptyArray())
            .sortedWith(compareBy({ it.name.split('-').getOrNull(1)?.toLongOrNull() ?: 0L }, { it.name.split('-', '.').getOrNull(2)?.toLongOrNull() ?: 0L }))

    /** Sends what waits in memory now; what the console cannot take now becomes a file, tried again later. */
    private fun sendQueue(s: State, timeoutMs: Int) {
        while (true) {
            val batch = synchronized(s) {
                if (s.queue.isEmpty()) return
                s.queue.entries.take(MAX_BATCH).map { it.key to it.value }.also { taken -> taken.forEach { s.queue.remove(it.first) } }
            }
            if (System.currentTimeMillis() < s.blockedUntil) {
                synchronized(s) { s.queue.clear() }
                return
            }
            if (System.currentTimeMillis() < s.notBefore) {
                writeBatches(s, batch)
                continue
            }
            val problems = batch.map { it.second }.toMutableList()
            val (body, rest) = fit(s, problems)
            if (rest.isNotEmpty()) {
                synchronized(s) {
                    val merged = LinkedHashMap<String, Problem>()
                    rest.forEach { merged[fingerprint(it)] = it }
                    merged.putAll(s.queue)
                    s.queue.clear()
                    s.queue.putAll(merged)
                }
            }
            when (answer(s, post(s, body, timeoutMs))) {
                Outcome.KEEP -> writeBody(s, body)
                Outcome.STOP -> return
                Outcome.DONE -> {}
            }
        }
    }

    private fun writeBody(s: State, body: ByteArray) {
        try {
            File(s.dir, "pending-" + System.currentTimeMillis() + "-" + (s.fileCounter++) + ".json").writeBytes(body)
        } catch (e: Throwable) {
            // Never a second problem.
        }
    }

    /** Sends the files that wait, oldest first, until one cannot go yet. */
    private fun drain(s: State, timeoutMs: Int) {
        try {
            for (file in pendingFiles(s)) {
                if (System.currentTimeMillis() < s.blockedUntil) {
                    file.delete()
                    continue
                }
                if (System.currentTimeMillis() < s.notBefore) {
                    schedule(s, 0)
                    return
                }
                val body = try {
                    file.readBytes()
                } catch (e: Throwable) {
                    file.delete()
                    continue
                }
                when (answer(s, post(s, body, timeoutMs))) {
                    Outcome.KEEP -> {
                        schedule(s, 0)
                        return
                    }
                    else -> file.delete()
                }
            }
        } catch (e: Throwable) {
            debug(s, "could not send: $e")
        }
    }

    private enum class Outcome { DONE, KEEP, STOP }

    private class Answer(val status: Int, val retryAfterMs: Long?)

    private fun answer(s: State, a: Answer): Outcome {
        val status = a.status
        if (status in 200..299) {
            s.backoff = 0
            return Outcome.DONE
        }
        if (status == 0 || status == 429 || status >= 500) {
            var wait = a.retryAfterMs
            if (wait == null) {
                s.backoff = if (s.backoff == 0L) BACKOFF_FIRST_MS else minOf(BACKOFF_MAX_MS, s.backoff * 2)
                wait = s.backoff
            }
            s.notBefore = System.currentTimeMillis() + wait
            debug(s, "the console answered ${if (status == 0) "nothing" else status.toString()}; trying again in ${wait / 1000} s")
            return Outcome.KEEP
        }
        if (status == 401 || status == 403) {
            s.blockedUntil = System.currentTimeMillis() + BLOCKED_MS
            synchronized(s) { s.queue.clear() }
            pendingFiles(s).forEach { it.delete() }
            debugOnce(s, status.toString(), "the console answered $status (${if (status == 401) "the key is not known" else "the site is not verified or its monitoring is off"}): nothing is sent for an hour")
            return Outcome.STOP
        }
        debugOnce(s, status.toString(), "the console answered $status: those problems were dropped")
        return Outcome.DONE
    }

    private fun post(s: State, body: ByteArray, timeoutMs: Int): Answer {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(s.endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Authorization", "Bearer " + s.key)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "$SDK_NAME/$VERSION")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            try {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
            } catch (e: Throwable) {
                // The answer's body is not needed.
            }
            Answer(status, retryAfter(connection.getHeaderField("Retry-After")))
        } catch (e: Throwable) {
            debug(s, "could not reach ${s.endpoint}: $e")
            Answer(0, null)
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Throwable) {
                // Never a second problem.
            }
        }
    }

    private fun retryAfter(value: String?): Long? {
        val text = value?.trim() ?: return null
        text.toLongOrNull()?.let { return minOf(RETRY_AFTER_MAX_MS, it * 1000) }
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val at = format.parse(text)?.time ?: return null
            (at - System.currentTimeMillis()).coerceIn(0, RETRY_AFTER_MAX_MS)
        } catch (e: Throwable) {
            null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────────────
    /** At most max UTF-16 units, never cut inside a surrogate pair, never holding a lone one. */
    private fun clip(text: String, max: Int): String {
        var s = if (text.length > max) text.substring(0, max) else text
        if (s.isNotEmpty() && Character.isHighSurrogate(s[s.length - 1])) s = s.substring(0, s.length - 1)
        var sb: StringBuilder? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val lone = (Character.isHighSurrogate(c) && (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1]))) || (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s[i - 1])))
            if (lone) {
                if (sb == null) sb = StringBuilder(s.substring(0, i))
                sb.append('�')
            } else {
                sb?.append(c)
            }
            i++
        }
        return sb?.toString() ?: s
    }

    private fun clean(text: String, max: Int) = clip(scrub(clip(text, max * 2)), max)

    private fun iso(millis: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }

    private fun say(debug: Boolean, message: String) {
        if (!debug) return
        try {
            android.util.Log.w(TAG, message)
        } catch (e: Throwable) {
            // Not on a phone (the library's tests): standard error, then.
            try {
                System.err.println("[cyberrakshak-monitor] $message")
            } catch (e2: Throwable) {
                // Never a second problem.
            }
        }
    }

    private fun debug(s: State, message: String) = say(s.debug, message)

    private fun debugOnce(s: State, id: String, message: String) {
        if (s.logged.add(id)) debug(s, message)
    }
}
