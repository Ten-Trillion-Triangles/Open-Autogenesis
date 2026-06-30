package org.ttt.autogenesis.kvisionapp.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the URL-construction contract for [AudioResourceLoader.publicUrlForBase]
 * so the Electron `file://` softlock cannot regress.
 *
 * ## Bug this pins down
 *
 * The pre-fix [AudioResourceLoader.publicUrl] used
 * `window.location.origin` as the URL prefix. That works for the
 * webpack dev server (`http://localhost:8080`) and the prod CDN
 * (`https://…`) because both expose the audio bundle at `/audio/…`.
 * It breaks in Electron: `mainWindow.loadFile(frontendHtml)` makes
 * `window.location.origin` literally `file://`, so the loader
 * produces `file:///audio/…` (the filesystem root) instead of
 * `file:///…/resources/frontend/audio/…` (the packaged frontend
 * dir). Every `fetch` then times out and the `AudioEngine`
 * `bufferCache` stays empty — no audio plays in Electron even
 * though the menu-music `Mp3AssetLoader` `canplaythrough` log
 * makes it look like audio is loading.
 *
 * ## The fix
 *
 * The loader now resolves the audio path against the document's
 * base URI (`document.baseURI`) so the result is correct for every
 * environment:
 *
 * | Environment          | `document.baseURI`                        | `publicUrl("audio/music/foo.mp3")` |
 * |----------------------|-------------------------------------------|--------------------------------------|
 * | webpack dev server   | `http://localhost:8080/`                  | `http://localhost:8080/audio/music/foo.mp3` |
 * | Prod CDN             | `https://app.autogenesis.dev/`            | `https://app.autogenesis.dev/audio/music/foo.mp3` |
 * | Electron (packaged)  | `file:///…/resources/frontend/index.html` | `file:///…/resources/frontend/audio/music/foo.mp3` |
 *
 * The pure helper [AudioResourceLoader.publicUrlForBase] takes the
 * base string as a parameter so the jsTest suite can verify the
 * contract without needing to manipulate the browser's `document`
 * or `window` from inside a `js("…")` body (which must be a
 * compile-time constant). Production calls flow through
 * [AudioResourceLoader.publicUrl] which reads `document.baseURI`
 * and delegates to the pure helper.
 */
class AudioResourceLoaderFileBaseUrlTest
{
    @Test
    fun publicUrl_httpBase_returnsHttpAbsoluteUrl()
    {
        // Simulate the webpack dev server: base URI is the dev-server root.
        val url = publicUrlForBase(
            base = "http://localhost:8080/",
            path = "audio/music/Xilaron and Eleuryiyidict wet final.mp3"
        )
        assertEquals(
            "http://localhost:8080/audio/music/Xilaron%20and%20Eleuryiyidict%20wet%20final.mp3",
            url,
            "HTTP base must produce an absolute URL that the dev server can serve"
        )
    }

    @Test
    fun publicUrl_httpsProdBase_returnsHttpsAbsoluteUrl()
    {
        // Simulate the prod CDN.
        val url = publicUrlForBase(
            base = "https://app.autogenesis.dev/",
            path = "audio/sfx/click.mp3"
        )
        assertEquals(
            "https://app.autogenesis.dev/audio/sfx/click.mp3",
            url,
            "HTTPS base must produce an absolute URL the CDN can serve"
        )
    }

    @Test
    fun publicUrl_electronFileBase_resolvesAgainstFrontendDirNotFilesystemRoot()
    {
        // This is THE regression guard for the Electron softlock. With
        // the pre-fix code, `window.location.origin` is `file://`, the
        // function returns `file:///audio/…` (filesystem root), and
        // every fetch times out. The fix must resolve against the
        // document's base URI, which in the packaged Electron app is
        // `file:///…/resources/frontend/index.html`.
        val url = publicUrlForBase(
            base = "file:///opt/Autogenesis/resources/frontend/index.html",
            path = "audio/music/Xilaron and Eleuryiyidict wet final.mp3"
        )
        assertEquals(
            "file:///opt/Autogenesis/resources/frontend/audio/music/Xilaron%20and%20Eleuryiyidict%20wet%20final.mp3",
            url,
            "Electron file:// base must resolve to the packaged frontend dir, not the filesystem root"
        )
        // Belt-and-suspenders: the bad pre-fix output (filesystem root)
        // must never come back.
        assertTrue(
            !url.startsWith("file:///audio/"),
            "publicUrl must not collapse the path to the filesystem root; got '$url'"
        )
    }

    @Test
    fun publicUrl_electronFileBase_doesNotDoubleSlash()
    {
        // The base in the previous test already ends with `/index.html`,
        // so the joiner must not emit a second `/` before the path.
        val url = publicUrlForBase(
            base = "file:///opt/Autogenesis/resources/frontend/index.html",
            path = "audio/sfx/click.mp3"
        )
        assertTrue(
            !url.contains("//audio/"),
            "publicUrl must not emit a double-slash before 'audio/'; got '$url'"
        )
        // The path must be a proper file:// URL on the original base.
        assertTrue(
            url.startsWith("file:///opt/Autogenesis/resources/frontend/"),
            "URL must preserve the base prefix; got '$url'"
        )
    }

    @Test
    fun publicUrl_baseWithTrailingSlash_normalizesBeforeJoin()
    {
        // Some <base href> values end with a trailing slash
        // (`http://localhost:8080/`); others don't. The helper must
        // normalize so the join is always `base + / + path`, never
        // `base + // + path`.
        val withSlash = publicUrlForBase(
            base = "http://localhost:8080/",
            path = "audio/sfx/click.mp3"
        )
        val withoutSlash = publicUrlForBase(
            base = "http://localhost:8080",
            path = "audio/sfx/click.mp3"
        )
        assertEquals(withSlash, withoutSlash, "trailing-slash presence on the base must not change the result")
        assertEquals("http://localhost:8080/audio/sfx/click.mp3", withSlash)
    }

    @Test
    fun publicUrl_emptyBase_returnsPathUnchanged()
    {
        // If `document.baseURI` is empty (some headless test runners)
        // and the origin is also empty, the helper degrades to
        // returning the path unchanged. The browser will resolve
        // it as a relative URL against whatever document eventually
        // makes the `fetch` call. This is a graceful degradation,
        // not the primary path; the assertion just pins the
        // behaviour so a future refactor cannot silently change it.
        val url = publicUrlForBase(base = "", path = "audio/sfx/click.mp3")
        assertEquals("audio/sfx/click.mp3", url)
    }

    @Test
    fun publicUrl_pathWithSpaces_encodesEachSegment()
    {
        // The catalog has filenames with literal spaces (e.g.
        // "Xilaron and Eleuryiyidict wet final.mp3"). The helper
        // must encode each segment so the URL is well-formed
        // regardless of the base.
        val url = publicUrlForBase(
            base = "http://localhost:8080/",
            path = "audio/music/Xilaron and Eleuryiyidict wet final.mp3"
        )
        assertEquals(
            "http://localhost:8080/audio/music/Xilaron%20and%20Eleuryiyidict%20wet%20final.mp3",
            url,
            "path segments with spaces must be percent-encoded"
        )
        assertTrue(!url.contains(" "), "URL must not contain raw spaces: '$url'")
    }
}
