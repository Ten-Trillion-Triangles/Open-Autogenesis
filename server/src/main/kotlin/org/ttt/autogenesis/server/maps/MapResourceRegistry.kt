package org.ttt.autogenesis.server.maps

import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashSet

/**
 * Helper that enumerates static `maps/` resources shipped with the server (classpath resources).
 *
 * Consumers such as diagnostics or a new RPC can call [listPackagedMaps] to see every
 * `.map` asset that lives under the `maps/` directory, both on disk during development and inside the packaged JAR.
 */
object MapResourceRegistry
{
    private const val MAPS_ROOT = "maps"

    /**
     * Lists every packaged resource whose path starts with `maps/`.
     *
     * Returns both files that exist directly on disk (development) and files packaged inside
     * jars. Entries are deduplicated while preserving discovery order so callers can reliably
     * consume the same list whether they run from the IDE or bundled artifact.
     */
    fun listPackagedMaps(): List<String>
    {
        val classLoader = Thread.currentThread().contextClassLoader
        val collector = LinkedHashSet<String>()

        classLoader.getResources(MAPS_ROOT).asSequence().forEach { url ->
            when (url.protocol)
            {
                "file" -> collectFromDirectory(url, collector)
                "jar" -> collectFromJar(url, collector)
                else -> {
                    // fallback: try to handle as URI that points to a JAR or directory
                    try
                    {
                        val uri = url.toURI()
                        if (uri.scheme == "file")
                        {
                            collectFromDirectory(Paths.get(uri), collector)
                        }
                    }
                    catch (_: Exception)
                    {
                        // ignore unknown protocols
                    }
                }
            }
        }

        return collector.toList()
    }

    private fun collectFromDirectory(url: URL, collector: MutableSet<String>)
    {
        val directory = Paths.get(url.toURI())
        collectFromDirectory(directory, collector)
    }

    /**
     * Walks `root` and adds every regular file whose relative path is non-empty.
     */
    private fun collectFromDirectory(root: Path, collector: MutableSet<String>)
    {
        if (!Files.isDirectory(root)) return

        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                val relative = root.relativize(path).toString().replace('\\', '/')
                if (relative.isNotBlank())
                {
                    collector.add("$MAPS_ROOT/$relative")
                }
            }
        }
    }

    /**
     * Iterates the entries of the jar that contains the `maps` resource and records all file names
     * under `maps/`.
     */
    private fun collectFromJar(url: URL, collector: MutableSet<String>)
    {
        val connection = url.openConnection() as? JarURLConnection ?: return
        connection.jarFile.use { jarFile ->
            val entries = jarFile.entries()
            while (entries.hasMoreElements())
            {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith("$MAPS_ROOT/") && !entry.isDirectory)
                {
                    collector.add(name)
                }
            }
        }
    }
}
