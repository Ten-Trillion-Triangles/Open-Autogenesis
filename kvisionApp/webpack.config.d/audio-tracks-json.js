/**
 * Webpack rule for the designer-authored audio-tracks catalog.
 *
 * The same JSON file (`sharedModel/src/commonMain/resources/audio/audio-tracks.json`)
 * is the VCS-versioned source of truth for both:
 *   - the server's `MusicSelector` (loaded via classpath), and
 *   - the client's `MenuMusicPlayer` (loaded via this webpack rule).
 *
 * We treat the JSON as a generic asset/resource and emit it under
 * the `audio/` output folder so the public URL is stable:
 *   `audio/audio-tracks.json`
 * which is exactly the URL `MenuMusicPlayer.BUNDLED_CATALOG_URL`
 * fetches at runtime. Keeping the URL aligned with the server's
 * resource path means the same logical file is the source for both
 * ends.
 *
 * ## Rule ordering fix
 *
 * The KVision / Kotlin/JS webpack template installs a default
 * `type: "json"` rule that matches any `*.json` file. Webpack 5
 * applies the FIRST matching rule per file, so if our `asset/resource`
 * rule is pushed AFTER the default JSON rule, the JSON rule wins
 * and our file gets inlined into the bundle as JSON instead of
 * emitted as a separate asset. Webpack 5's `enforce: "pre"` syntax
 * is rejected in this codebase (we observed
 * `Error: Compiling RuleSet failed: Properties enforce are unknown`),
 * so we instead use `config.module.rules.unshift(...)` to place our
 * rule at the head of the list — same effect, valid in this version.
 */
config.module.rules.unshift({
  test: /audio-tracks\.json$/,
  type: "asset/resource",
  generator: {
    filename: "audio/[name][ext]"
  }
});