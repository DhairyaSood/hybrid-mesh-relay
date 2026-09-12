package com.hybridmesh.relay.model

data class RouteInfo(
    val transport: String,
    val hopCount: Int,
    val nextHopNodeId: String? = null
)