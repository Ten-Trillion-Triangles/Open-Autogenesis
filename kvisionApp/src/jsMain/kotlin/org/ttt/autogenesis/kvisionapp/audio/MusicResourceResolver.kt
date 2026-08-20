package org.ttt.autogenesis.kvisionapp.audio

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Resolves a catalog-style music name (the
 * [org.ttt.autogenesis.audio.AudioObject.resourceName] the server puts
 * on the wire) to a webpack import path in
 * `resources/audio/music/`. The resolver is the **client-side** mirror
 * of [org.ttt.autogenesis.audio.MusicTrackCatalog] — drift between
 * the two is the single biggest risk in the music system and is caught
 * by the unit test `everyCatalogNameResolvesToAPath`.
 *
 * ## Resolution strategy
 *
 * The resolver derives the webpack path from the [resourceName] using
 * a small set of prefix rules rather than a static list, so the
 * editor can add new variants (e.g. another `D-Track 6`, another
 * `Harmony-?` letter) without anyone having to touch this file. The
 * rules are:
 *
 *  1. **Exact match** against a small table of scenario/singleton
 *     tracks that live at the music root (`Initial Conditions wet 1`,
 *     `Nemesis wet 1`, `Terminal Conditions wet 1`,
 *     `Xilaron and Eleuryiyidict wet final`).
 *  2. **Prefix match** against a layer folder:
 *      - `D-Track`       → `audio/music/D-Tracks/`
 *      - `Melody-`       → `audio/music/Melody Tracks/`
 *      - `R-Track`       → `audio/music/R-Tracks/`
 *      - `Harmony-` or ` Harmony-` (with optional leading space, the
 *        editor has shipped both at different times) →
 *        `audio/music/Harmony Tracks/`
 *  3. **Fuzzy fallback** against the union of all known names via
 *     Levenshtein similarity, with a 0.5 threshold. This catches
 *     one-character typos, case-different spellings, and missing
 *     separators in catalogue names. When no candidate crosses the
 *     threshold the resolver returns `null` and the caller is expected
 *     to log a warning and skip the track — never throw, so a missing
 *     file can never break the music pipeline.
 */
object MusicResourceResolver
{
    private val log = Logger

    /**
     * Singleton scenario tracks that live at the music root. Mapped by
     * exact (case-insensitive) name. Adding a new scenario track
     * (e.g. a future `Game Over wet 1`) means adding a row here.
     */
    private val rootSingletons: Map<String, String> = mapOf(
        "Initial Conditions wet 1" to "audio/music/Initial Conditions wet 1.mp3",
        "Nemesis wet 1" to "audio/music/Nemesis wet 1.mp3",
        "Terminal Conditions wet 1" to "audio/music/Terminal Conditions wet 1.mp3",
        "Xilaron and Eleuryiyidict wet final" to "audio/music/Xilaron and Eleuryiyidict wet final.mp3"
    )

    /**
     * Prefix → folder mapping for the four layer categories. Names
     * are matched case-insensitively after trimming leading whitespace.
     * The same rules work for the base tracks and every
     * Retrograde / Inversion / Variant / Rotation / Centered /
     * Slow Rotation variant the editor produces, because the variant
     * suffix is appended to the base name (e.g.
     * `D-Track 1 Retrograde Inversion`).
     */
    private data class LayerRule(val prefix: String, val folder: String)

    private val layerRules: List<LayerRule> = listOf(
        LayerRule(prefix = "D-Track",   folder = "D-Tracks"),
        LayerRule(prefix = "Melody-",   folder = "Melody Tracks"),
        LayerRule(prefix = "R-Track",   folder = "R-Tracks"),
        LayerRule(prefix = "Harmony-",  folder = "Harmony Tracks")
    )

    /**
     * Union of every name this resolver knows how to resolve, used by
     * the fuzzy-match fallback. Built from [rootSingletons] keys
     * (exact names) plus a derived list of every legal
     * `{prefix}{variant}` name — for the moment we enumerate the
     * editor's known names by hand, but the resolver is robust to
     * missing entries because the prefix rules will still derive a
     * path; the fuzzy fallback just buys a chance to find a name
     * whose exact spelling doesn't match a prefix.
     */
    val knownNames: List<String> = rootSingletons.keys.toList() + listOf(
        // Drone layer (D-Tracks/).
        "D-Track 1", "D-Track 1 Retrograde", "D-Track 1 Retrograde Inversion",
        "D-Track 2", "D-Track 2 Retrograde", "D-Track 2 Retrograde Inversion",
        "D-Track 3", "D-Track 3 Retrograde", "D-Track 3 Retrograde Inversion",
        "D-Track 4", "D-Track 4 Retrograde", "D-Track 4 Retrograde Inversion",
        "D-Track 5", "D-Track 5 Retrograde", "D-Track 5 Variant",
        "D-Track 5 Variant Inversion", "D-Track 5 Variant Inversion Rotation",

        // Melody layer (Melody Tracks/).
        "Melody-Etnahta", "Melody-Etnahta Retrograde", "Melody-Etnahta Retrograde Inversion",
        "Melody-Mayela and Khefulah", "Melody-Mayela and Khefulah Retrograde",
        "Melody-Mayela and Khefulah Retrograde Inversion",
        "Melody-Pashta", "Melody-Pashta Inversion",
        "Melody-Shalshelet", "Melody-Shalshelet Inversion",
        "Melody-Siluk", "Melody-Siluk Retrograde", "Melody-Siluk Retrograde Inversion",
        "Melody-Tevir", "Melody-Tevir Inversion",
        "Melody-Tippeha", "Melody-Tippeha Retrograde", "Melody-Tippeha Retrograde Inversion",
        "Melody-Zakef", "Melody-Zakef Retrograde", "Melody-Zakef Retrograde Inversion",

        // Rhythm layer (R-Tracks/).
        "R-Track 1", "R-Track 1 Retrograde",
        "R-Track 2", "R-Track 2 Retrograde", "R-Track 2 Rotated",
        "R-Track 3", "R-Track 3 Retrograde", "R-Track 3 Slow Rotation",
        "R-Track 4", "R-Track 4 Retrograde", "R-Track 4 Rotated", "R-Track 4 centered",
        "R-Track 5", "R-Track 5 Retrograde",
        "R-Track 6", "R-Track 6 Retrograde", "R-Track 6 Rotated",

        // Harmony layer (Harmony Tracks/). Leading-space variant appears
        // in older editor exports, so list both spellings.
        "Harmony-1", "Harmony-1 Retrograde", "Harmony-1 Retrograde Inversion",
        "Harmony-2", "Harmony-2 Retrograde", "Harmony-2 Retrograde Inversion",
        "Harmony-3", "Harmony-3 Retrograde Inversion",
        "Harmony-E", "Harmony-E Retrograde", "Harmony-E Retrograde Inversion",
        "Harmony-F", "Harmony-F Retrograde", "Harmony-F Retrograde Inversion", "Harmony-F Variant",
        "Harmony-R", "Harmony-R Retrograde", "Harmony-R Retrograde Inversion",
        "Harmony-Y", "Harmony-Y Retrograde", "Harmony-Y Retrograde Inversion",
        " Harmony-Y Retrograde Inversion"
    )

    /**
     * Minimum similarity score (0..1) for the fuzzy fallback. Below
     * this the resolver returns null rather than guess.
     */
    private const val SIMILARITY_THRESHOLD: Double = 0.5

    init {
        log.info(
            LogCategory.SYSTEM,
            "MusicResourceResolver: initialized — ${rootSingletons.size} root singletons, " +
            "${layerRules.size} layer prefix rules, ${knownNames.size} known names for fuzzy match"
        )
    }

    /**
     * Look up a track by its catalog name. Returns the webpack import
     * path or null if the name is too far from anything known.
     *
     * @param name the catalog-style music name (e.g. "D-Track 1",
     *   "Initial Conditions wet 1"). Case-insensitive, whitespace-trimmed.
     */
    fun resolve(name: String): String?
    {
        val query = name.trim()
        if (query.isEmpty()) {
            log.warn(
                LogCategory.NETWORK,
                "MusicResourceResolver.resolve: empty/whitespace name — returning null"
            )
            return null
        }

        // 1) Exact match (case-insensitive) against the root singletons.
        for ((catalogName, path) in rootSingletons)
        {
            if (catalogName.equals(query, ignoreCase = true))
            {
                log.debug(
                    LogCategory.NETWORK,
                    "MusicResourceResolver.resolve: ROOT-SINGLETON hit for '$query' → '$path'"
                )
                return path
            }
        }

        // 2) Prefix rule: peel any leading whitespace off, match the
        //    layer prefix, and derive a path under that layer's folder.
        for (rule in layerRules)
        {
            if (query.startsWith(rule.prefix, ignoreCase = true))
            {
                val path = "audio/music/${rule.folder}/$query.mp3"
                log.debug(
                    LogCategory.NETWORK,
                    "MusicResourceResolver.resolve: PREFIX hit for '$query' (prefix='${rule.prefix}') → '$path'"
                )
                return path
            }
        }

        // 3) Fuzzy fallback: pick the known name with the highest
        //    Levenshtein-similarity score. Above
        //    [SIMILARITY_THRESHOLD] we return a derived path for the
        //    best match; otherwise null.
        val queryLower = query.lowercase()
        var bestName: String? = null
        var bestScore: Double = 0.0
        for (known in knownNames)
        {
            val score = similarity(known.lowercase(), queryLower)
            if (score > bestScore)
            {
                bestScore = score
                bestName = known
            }
        }
        if (bestScore < SIMILARITY_THRESHOLD || bestName == null) {
            log.warn(
                LogCategory.NETWORK,
                "MusicResourceResolver.resolve: NO MATCH for '$query' — best fuzzy score " +
                "was ${(bestScore * 1000.0).toLong() / 1000.0} (threshold=$SIMILARITY_THRESHOLD) " +
                "against knownNames; will return null and let caller skip the track"
            )
            return null
        }
        val derived = derivePath(bestName)
        if (derived == null) {
            log.warn(
                LogCategory.NETWORK,
                "MusicResourceResolver.resolve: fuzzy hit for '$query' on '$bestName' " +
                "(score=${(bestScore * 1000.0).toLong() / 1000.0}) but derivePath returned null — bailing out"
            )
            return null
        }
        log.info(
            LogCategory.NETWORK,
            "MusicResourceResolver.resolve: FUZZY hit for '$query' → matched '$bestName' " +
            "(score=${(bestScore * 1000.0).toLong() / 1000.0}, threshold=$SIMILARITY_THRESHOLD) → '$derived'"
        )
        return derived
    }

    /**
     * Apply the prefix/singleton rules to a known name to derive its
     * webpack path. Used by the fuzzy fallback so a fuzzy match returns
     * the same path the exact match would have.
     */
    private fun derivePath(name: String): String?
    {
        for ((catalogName, path) in rootSingletons)
        {
            if (catalogName.equals(name, ignoreCase = true)) return path
        }
        for (rule in layerRules)
        {
            if (name.startsWith(rule.prefix, ignoreCase = true))
            {
                return "audio/music/${rule.folder}/$name.mp3"
            }
        }
        return null
    }

    /**
     * Levenshtein-similarity in `[0.0, 1.0]`. 1.0 means identical
     * strings; 0.0 means disjoint (one of them empty).
     */
    private fun similarity(a: String, b: String): Double
    {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        val distance = levenshteinDistance(a, b)
        return 1.0 - distance.toDouble() / maxLen.toDouble()
    }

    /**
     * Standard O(n·m) dynamic-programming edit distance. Both inputs
     * are short (track names ≤ 64 chars) so the table is tiny.
     */
    private fun levenshteinDistance(a: String, b: String): Int
    {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length)
        {
            curr[0] = i
            for (j in 1..b.length)
            {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,        // insertion
                    prev[j] + 1,            // deletion
                    prev[j - 1] + cost      // substitution
                )
            }
            for (k in prev.indices)
            {
                prev[k] = curr[k]
            }
        }
        return prev[b.length]
    }
}