# cyberrakshak-monitor-android

Cyber Rakshak error monitoring for **Android (Kotlin or Java), API 21+**: what goes wrong in your app lands on your
site's **Errors** page in the Cyber Rakshak console, scrubbed of secrets on the phone before it leaves. One Kotlin
class, nothing but the Android SDK and the Kotlin standard library.

## What it catches

- **Uncaught exceptions on any thread** (a crash): written to the app's files folder at once, then the handler that was
  there before runs as it would have (the app closes), and the report is sent at the next start, on a background thread.
- Anything you report yourself: `captureException(e)` and `captureMessage(text)`.

**Not caught**: native (C/C++) crashes and ANRs.

## Install

From JitPack. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.LaabamOne-Business-Solutions-Pvt-Ltd:cyberrakshak-monitor-android:1.1.0")
}
```

The library's manifest adds `android.permission.INTERNET` to your app's (most apps have it already).

## Use

```kotlin
import ai.cyberrakshak.monitor.CyberRakshakMonitor
// in Application.onCreate():
CyberRakshakMonitor.init(this, CyberRakshakMonitor.Options(key = "crk_…", release = BuildConfig.VERSION_NAME))
CyberRakshakMonitor.setRoute("Checkout")                          // the screen now shown
CyberRakshakMonitor.captureException(e, context = mapOf("step" to 2))
```

The key is your site's app key (`crk_…`), made on the site's **Security scan** tab: under **Apps, servers and scripts**, press **Make an app key**. From Java:
`CyberRakshakMonitor.init(this, new CyberRakshakMonitor.Options("crk_…"));` (the capture functions are on
`CyberRakshakMonitor.INSTANCE`).

## Options (`CyberRakshakMonitor.Options`)

| Option | Meaning |
|---|---|
| `key` | Your site's app key, `crk_` and 32 letters or digits. Required; with no valid key the library does nothing. |
| `endpoint` | Where reports go. Default `https://app.cyberrakshak.ai/api/v1/app-reports`. |
| `release` | Your version or build (`A-Z a-z 0-9 . _ + -`, at most 60), so a problem shows the release it started in. |
| `environment` | `production`, `staging`… (`a-z 0-9 _ -`, at most 30). |
| `enabled` | `false` sends nothing (default `true`). |
| `sampleRate` | 0 to 1: the share of problems sent (default 1, all). |
| `beforeSend` | Gets each problem, already scrubbed; return it (changed if you like) or `null` to drop it. |
| `debug` | Says in Logcat (tag `CyberRakshakMonitor`) what the library does and why something was not sent. The key is never printed. |
| `context` | Values sent with every problem: at most 20, strings (300 characters), numbers, true/false. |
| `scrubNames` | Names of your own whose values are removed, like `password=`: whole words, `A-Z a-z 0-9 _ . -`, at most 50. Only adds to the built-in rules. |
| `scrubPatterns` | Java regular expressions of your own, as strings: what they match becomes `[removed]`. At most 20; one that does not compile, or matches an empty text, is left out. Only adds to the built-in rules. |
| `platform` | `mobile` (the default), `desktop`, `server`, `script` or `other`. |

Functions: `init(context, options)`, `setRoute(screen)`, `captureException(e, route, context, level)`,
`captureMessage(text, level = "warning", route, context)`, `flush(timeoutMs = 2000)` (blocks: call it off the main
thread; `true` when nothing is left waiting), `scrub(text)` (shows what a text would become), `close()`.

## What is sent, and what never is

Sent, per problem: its kind (exception, crash, log message), its message, its stack trace (classes, methods, files and
lines), the screen (`setRoute`), the level, how many times it happened and when last; with each request: the library's
name and version, `android` and its version, your release and environment.

Never sent: what a user types, form fields, request or response bodies, headers, cookies, device identifiers,
location, contacts.

**Scrubbed on the phone before anything leaves**: query strings; `Bearer`/`Basic` values; the value after `password`,
`secret`, `token`, `api_key` and the like; JSON Web Tokens; keys that say what they are (`crk_`, `sk_live_`, `ghp_`,
`AKIA`…); email addresses; IBANs (mod-97 checked); card numbers (Luhn-checked); the value after `pin`, `otp`, `cvv`,
`iban`, `sort_code`, `card_number`, `account_number`, `cookie`, `signature`, `authorization` and the like, as whole
words only (`company:` and `spinning:` stay); long hex and random-looking runs. They become `[removed]`, `[token]`,
`[email]`, `[iban]` or `[card]`, and a context entry whose name is one of these names is sent as `[removed]`.
`scrubNames` and `scrubPatterns` add names and patterns of your own; nothing switches the built-in rules off. The
console scrubs everything again when it arrives.

At most 10 problems go per request, each request under 64 KB; repeats of one problem are counted, not resent; what
could not be sent (no network, the console asked to wait) is kept in the files folder and tried again later, never
before `Retry-After`. Nothing in the library throws into your app.

Full documentation, the same library for other platforms, and every file's SHA-256:
**https://app.cyberrakshak.ai/sdk/README.md**

## Development

JDK 17 or later and the Android SDK (`local.properties` with `sdk.dir=…`, or `ANDROID_HOME`); Node.js 18+ for the
tests' fake server (127.0.0.1 only: no test talks to a real Cyber Rakshak host).

```sh
./gradlew assembleRelease                     # build/outputs/aar/cyberrakshak-monitor-android-release.aar
./gradlew testReleaseUnitTest                 # src/test/kotlin/MonitorTest.kt on the JVM, through MonitorJvmTest
./gradlew lintRelease
./gradlew publishReleasePublicationToMavenLocal   # what JitPack runs (jitpack.yml)
```

`src/main/kotlin/ai/cyberrakshak/monitor/CyberRakshakMonitor.kt`, `LICENSE` and the two files in `src/test/fixtures` are
byte for byte the ones served at https://app.cyberrakshak.ai/sdk/ and are not edited here: they are changed in the Cyber
Rakshak console's repository and copied by its `scripts/sdk-packages.mjs` (whose `--check` also checks `version` in
build.gradle.kts).

## For maintainers

**JitPack needs nothing set up**: it builds a tag the first time someone asks for it (or when you press "Get it" on
https://jitpack.io/#LaabamOne-Business-Solutions-Pvt-Ltd/cyberrakshak-monitor-android), with `jitpack.yml` (JDK 17,
`publishReleasePublicationToMavenLocal`). The version in the coordinates is the tag's name, so the tag is `1.0.0`, with
no `v`, the same as `version` in build.gradle.kts. A release: copy the new file in, set `version`, add the CHANGELOG
entry, commit, tag `X.Y.Z`, push the tag, then open the JitPack page above once to start the build and check its log.

## Licence

MIT. Copyright (c) 2026 Laabam One Business Solutions (OPC) Private Limited. See [LICENSE](LICENSE).
