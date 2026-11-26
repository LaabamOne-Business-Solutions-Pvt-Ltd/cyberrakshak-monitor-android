# Changelog

## [1.1.1] - 2026-11-26

- Words only: where its key is made. Since 2026-11-26 the Cyber Rakshak console adds a mobile app, an API, an MCP server, or
  a script or cron job as an item of its own, with its own key and Errors page (**Apps & websites** → **Add**; the key
  is shown once on its **Set up** page). The header, the `key` option and the debug message say so. A key made before
  on a website's **Security scan** tab keeps working. Nothing changes in what is sent, or how.

## [1.1.0] - 2026-11-22

- Scrubbing, the same rules as every Cyber Rakshak library (the shared `scrub-vectors.json`): IBANs that pass the
  ISO 13616 mod-97 check become `[iban]`; the values after `authorization`, `cookie` / `set-cookie`, `signature`,
  `otp`, `pin`, `cvv`, `cvc`, `iban`, `bban`, `pan`, account number, sort code, card number and bank auth id are
  removed, as whole words only (`company:`, `spinning:` and `japan:` stay).
- `scrubNames` and `scrubPatterns` (the last `Options` parameters, so Java callers keep working): names and regular expressions of your own, applied after the built-in
  rules to the message, the stack, the route and context values. They only add: nothing switches the built-in
  rules off. A name or pattern that is not usable is left out (said with `debug`).
- A context entry whose name is a secret name (built in, or one of yours) is sent as `[removed]`.
- The app key's message names where it is made: the site's Security scan tab, Apps, servers and scripts.

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
