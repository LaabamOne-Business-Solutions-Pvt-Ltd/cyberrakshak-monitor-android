# Changelog

## [1.0.0] - 2026-11-21

First release on JitPack: the Android library served at https://app.cyberrakshak.ai/sdk/android/ since 2026-11-21, as
`com.github.LaabamOne-Business-Solutions-Pvt-Ltd:cyberrakshak-monitor-android:1.0.0` (package
`ai.cyberrakshak.monitor`, minSdk 21).

- Reports uncaught exceptions on any thread: written at once, sent at the next start (the previous handler still
  runs). Not native crashes or ANRs.
- `init`, `setRoute`, `captureException`, `captureMessage`, `flush`, `scrub`, `close`; options `key`, `endpoint`,
  `release`, `environment`, `enabled`, `sampleRate`, `beforeSend`, `debug`, `context`, `platform`.
- Secrets scrubbed on the phone before anything leaves, by the rules every Cyber Rakshak library shares. The library's
  manifest adds the INTERNET permission.
