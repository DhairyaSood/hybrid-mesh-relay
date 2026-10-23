package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.network.MeshTransportSelection

/** Dependency seam for deterministic local mesh-engine integration tests. */
internal data class MeshEngineTestOverrides(
    val repository: MessagingRepository,
    val localNodeId: () -> String,
    val peerDirectory: MeshPeerDirectory,
    val transports: List<MeshTransport>,
    val transportSelection: MeshTransportSelection = MeshTransportSelection(),
    val nowMillis: () -> Long = System::currentTimeMillis
)
