package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/connector-instances` 的可用连接器摘要。 */
@Serializable
data class ConnectorInstanceDto(
    @SerialName("instance_id") val instanceId: String,
    val kind: String,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    val status: String = "",
    @SerialName("mcp_server_name") val mcpServerName: String,
    @SerialName("has_credentials") val hasCredentials: Boolean = false,
    val usable: Boolean = true,
)

fun ConnectorInstanceDto.isReadyForAutomation(): Boolean =
    usable && status == "active" && hasCredentials && mcpServerName.isNotBlank()
