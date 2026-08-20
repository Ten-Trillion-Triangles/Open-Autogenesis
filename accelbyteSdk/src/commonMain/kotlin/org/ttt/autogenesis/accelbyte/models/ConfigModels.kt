package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for fetching a paginated list of server configurations.
 * Use with [org.ttt.autogenesis.accelbyte.facades.ConfigFacade.listConfigs].
 *
 * @property limit maximum number of configs to return per page. Null omits the param.
 * @property offset number of configs to skip before collecting the page. Null omits the param.
 */
data class ConfigListParams(val limit : Int? = null, val offset : Int? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("limit" to limit, "offset" to offset)
}

/**
 * Query parameters for retrieving email configuration, optionally including templates.
 * Use with [org.ttt.autogenesis.accelbyte.facades.ConfigFacade.getEmailConfig].
 *
 * @property includeEmailTemplates when true, the response includes full email templates
 *                                  alongside the config. Defaults to false.
 */
data class EmailConfigParams(val includeEmailTemplates : Boolean = false) : AccelByteRequest
{
    override fun toJson() : Json = json("includeEmailTemplates" to includeEmailTemplates)
}

/**
 * Query parameters for fetching a paginated list of linked sender identities.
 * Use with [org.ttt.autogenesis.accelbyte.facades.ConfigFacade.listLinkedSenders].
 *
 * @property limit maximum number of senders to return per page. Null omits the param.
 * @property offset number of senders to skip before collecting the page. Null omits the param.
 */
data class LinkedSendersParams(val limit : Int? = null, val offset : Int? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("limit" to limit, "offset" to offset)
}