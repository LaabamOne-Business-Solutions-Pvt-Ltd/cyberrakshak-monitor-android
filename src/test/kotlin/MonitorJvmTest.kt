import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs every test in MonitorTest.kt (the console's own tests for this library) on the JVM Gradle tests with, against
 * src/test/fixtures/fake-server.mjs: started here with Node.js 18+ (on the PATH, or CR_NODE), unless CR_TEST_SERVER
 * names one already running. It listens on 127.0.0.1 only: no test talks to a real Cyber Rakshak host.
 */
class MonitorJvmTest {
    @Test
    fun everyLibraryTestPasses() {
        val fixtures = File("src/test/fixtures").absoluteFile
        val vectors = File(fixtures, "scrub-vectors.json").path
        val given = System.getenv("CR_TEST_SERVER")
        if (!given.isNullOrEmpty()) {
            assertEquals("tests failed: see the standard output above", 0, runAll(given, vectors))
            return
        }
        val server = ProcessBuilder(System.getenv("CR_NODE") ?: "node", File(fixtures, "fake-server.mjs").path)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val first = server.inputStream.bufferedReader().readLine()
                ?: error("the fake server did not start: is Node.js 18 or later on the PATH?")
            val url = Regex("\"url\":\"([^\"]+)\"").find(first)?.groupValues?.get(1) ?: error("the fake server said: $first")
            assertEquals("tests failed: see the standard output above", 0, runAll(url, vectors))
        } finally {
            // The fake server stops when its standard input closes.
            server.outputStream.close()
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroy()
        }
    }
}
