package gameInit

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.server.maps.MapResourceDescriptor
import org.ttt.autogenesis.server.maps.MapResourceRegistry
import org.ttt.autogenesis.server.maps.MapResourceSource
import org.ttt.autogenesis.server.maps.UploadedMapRepository
import java.io.IOException
import kotlin.random.Random

/**
 * Helper that picks a map pack at random and loads its bytes for the gameplay server.
 */
object MapSelectionService
{
    private val random = Random.Default

    /**
     * Map resource paths that are reserved for non-matchmaking entry points
     * (e.g. guided tutorials) and must not be picked by the random fallback
     * pool. Pulled from `pickRandomDescriptor` so an exclusion here keeps the
     * map reachable by deterministic-name loaders while it stays out of the
     * random roll.
     */
    private val excludedRandomPoolPaths: Set<String> = setOf(
        "maps/tutorial.map"
    )

    /**
     * Finds every known map descriptor (packaged + uploaded) and chooses one at random.
     */
    fun pickRandomDescriptor(): MapResourceDescriptor?
    {
        val packaged = MapResourceRegistry.listPackagedMaps()
            .filterNot { path -> path in excludedRandomPoolPaths }
            .map { path ->
            MapResourceDescriptor(
                path = path,
                source = MapResourceSource.PACKAGED
            )
        }

        val uploaded = UploadedMapRepository.listUploadedMaps().map { metadata ->
            MapResourceDescriptor(
                path = metadata.mapId,
                source = MapResourceSource.UPLOADED,
                metadata = metadata
            )
        }

        val all = packaged + uploaded
        Logger.debug(LogCategory.GENERAL, "MapSelectionService: Available map descriptors (packaged=${packaged.size}, uploaded=${uploaded.size})")
        if(all.isEmpty())
        {
            Logger.warn(LogCategory.GENERAL, "MapSelectionService: No maps available to choose from")
            return null
        }

        val selected = all[random.nextInt(all.size)]
        Logger.debug(LogCategory.GENERAL, "MapSelectionService: Selected descriptor=${selected.path} source=${selected.source}")
        return selected
    }

    /**
     * Loads the bytes described by [descriptor], whether it’s a packaged resource or an uploaded pack.
     */
    fun loadBytes(descriptor: MapResourceDescriptor): ByteArray?
    {
        return try
        {
            when(descriptor.source)
            {
                MapResourceSource.PACKAGED ->
                    Thread.currentThread()
                        .also { Logger.debug(LogCategory.GENERAL, "MapSelectionService: Loading packaged map ${descriptor.path}") }
                        .contextClassLoader
                        .getResourceAsStream(descriptor.path)
                        ?.use { it.readBytes() }
                MapResourceSource.UPLOADED ->
                    UploadedMapRepository.getMapBytes(descriptor.path)
            }
        }
        catch(e: IOException)
        {
            Logger.error(LogCategory.GENERAL, "MapSelectionService: Failed to read ${descriptor.path}: ${e.message}")
            null
        }
    }

    /**
     * Attempts to load a random map pack, returning both the descriptor and the raw bytes.
     */
    fun loadRandomMapPack(): Pair<MapResourceDescriptor, ByteArray>?
    {
        val descriptor = pickRandomDescriptor() ?: return null
        val bytes = loadBytes(descriptor)
        return if(bytes == null)
        {
            Logger.warn(LogCategory.GENERAL, "MapSelectionService: Selected map ${descriptor.path} could not be loaded")
            null
        }
        else
        {
            descriptor to bytes
        }
    }

    /**
     * Looks up the raw bytes for a known map pack by its resource path/name.
     *
     * Used by the single-player resume path: [gameState.GameSnapshot] stores
     * `activeMapPackName` but the bytes themselves are not part of the snapshot,
     * so we re-load them by name after a rehydrate. The check order mirrors
     * [loadBytes] — uploaded packs first, packaged classpath resources as
     * a fallback — so a saved session that was originally running a packaged
     * map resolves the same way it was loaded originally.
     *
     * If [name] carries the `resource:` URI prefix (set by
     * [gameState.WorldManager.loadMapFromResources] for packaged maps),
     * the prefix is stripped before the classpath lookup. The prefix is
     * a *label*, not a path component — see [stripResourcePrefix].
     *
     * @param name The map resource path (e.g. `maps/StartMap.map`) or
     *   uploaded map id to look up. A leading `resource:` prefix is
     *   tolerated and stripped. Must be non-blank.
     * @return A copy of the packed bytes when found, or `null` when no map
     *   with the given name is currently registered.
     */
    fun loadBytesByName(name: String): ByteArray?
    {
        if (name.isBlank())
        {
            Logger.warn(LogCategory.GENERAL, "MapSelectionService.loadBytesByName: blank name, returning null")
            return null
        }

        // First try the uploaded map repository, since live session uploads
        // take precedence over the static classpath mirrors.
        val uploaded = try
        {
            UploadedMapRepository.getMapBytes(name)
        }
        catch (e: Exception)
        {
            Logger.warn(LogCategory.GENERAL, "MapSelectionService.loadBytesByName: UploadedMapRepository lookup failed for $name: ${e.message}")
            null
        }
        if (uploaded != null)
        {
            Logger.debug(LogCategory.GENERAL, "MapSelectionService.loadBytesByName: resolved $name via UploadedMapRepository (${uploaded.size} bytes)")
            return uploaded
        }

        // Fall back to the packaged classpath resource. ClassLoader lookup
        // works for both filesystem dev layouts and jar-packaged distributions.
        //
        // NOTE: the saved name may carry a `resource:` URI prefix set by
        // [gameState.WorldManager.loadMapFromResources] to mark a packaged
        // map pack. The prefix is a *label* on the saved name (telemetry
        // and save-payload readers rely on it being preserved verbatim);
        // it is NOT a path component for the classpath. Strip it before
        // the getResourceAsStream call so a saved session that originally
        // loaded `maps/IO-map.map` (and was tagged `resource:maps/IO-map.map`
        // in the snapshot) resolves the same way on rehydrate. See
        // [stripResourcePrefix].
        val resourcePath = stripResourcePrefix(name)
        return try
        {
            Thread.currentThread()
                .contextClassLoader
                .getResourceAsStream(resourcePath)
                ?.use { it.readBytes() }
                .also {
                    if (it == null)
                    {
                        Logger.warn(LogCategory.GENERAL, "MapSelectionService.loadBytesByName: no classpath resource for $resourcePath (original=$name)")
                    }
                    else
                    {
                        Logger.debug(LogCategory.GENERAL, "MapSelectionService.loadBytesByName: resolved $resourcePath via classpath (${it.size} bytes)")
                    }
                }
        }
        catch (e: IOException)
        {
            Logger.error(LogCategory.GENERAL, "MapSelectionService.loadBytesByName: failed to read $resourcePath (original=$name): ${e.message}")
            null
        }
    }

    /**
     * Strips a leading `resource:` URI prefix from [name], if present.
     *
     * [gameState.WorldManager.loadMapFromResources] tags packaged map
     * packs with a `resource:` prefix to distinguish them from uploaded
     * packs. The tag is preserved in the saved snapshot so downstream
     * readers (telemetry, save/load mirrors) can tell the two apart, but
     * the classpath loader treats the bare path as the lookup key. This
     * helper is a no-op for names that do not carry the prefix.
     *
     * @param name A map resource path or `resource:<path>` tag.
     * @return [name] with the leading `resource:` removed, or [name]
     *   unchanged if no such prefix is present.
     */
    private fun stripResourcePrefix(name: String): String
    {
        return name.removePrefix("resource:")
    }
}
