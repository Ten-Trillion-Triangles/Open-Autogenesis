package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Request payload for initiating a differential download between two builds.
 *
 * The differ service computes the minimal set of file assets that must be downloaded to
 * go from [destinationBuildId] to [sourceBuildId], returning a patch manifest rather
 * than the full binary.
 *
 * @property sourceBuildId the build to update to (the source of the differential).
 * @property destinationBuildId the build the client currently has installed (the baseline).
 * @property priority when true, the request is treated as high-priority by the differ queue.
 *                    Defaults to false.
 */
data class DiffRequest(
    val sourceBuildId : String,
    val destinationBuildId : String,
    val priority : Boolean = false
) : AccelByteRequest {
    override fun toJson() : Json = json(
        "sourceBuildId" to sourceBuildId,
        "destinationBuildId" to destinationBuildId,
        "priority" to priority
    )
}
