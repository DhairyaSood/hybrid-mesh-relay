package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.ble.BleDiscoveryCodec
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.model.LocalIdentity
import com.hybridmesh.relay.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Policy-level A -> B -> C routing. Each node's peer view is built from real
 * advertisement bytes (advertiser encoding -> scanner parsing -> peer directory),
 * and next hops are chosen with MeshEngine's transport-compatibility predicates
 * over fake transports.
 */
class MeshThreeNodeRoutingTest {
    private data class Node(val identity: LocalIdentity, val advertisedAddress: String) {
        val nodeId: String get() = identity.nodeId
    }

    private val a = Node(LocalIdentity("HMR-00000000-0000-0000-0000-00000000000a", "Alice"), "4A:00:00:00:00:0A")
    private val b = Node(LocalIdentity("HMR-00000000-0000-0000-0000-00000000000b", "Bob"), "4B:00:00:00:00:0B")
    private val c = Node(LocalIdentity("HMR-00000000-0000-0000-0000-00000000000c", "Carol"), "4C:00:00:00:00:0C")
    private val now = 1_800_000_000_000L

    /** Same reachability rule as BleGattMeshTransport.canReach. */
    private class FakeBleTransport : MeshTransport {
        override val kind = MeshTransportKind.BLE_GATT
        override fun isAvailable() = true
        override fun canReach(peer: MeshTransportPeer) =
            !peer.bleAddress.isNullOrBlank() && !peer.bleAddress.equals("Unknown", true)
        override suspend fun send(peer: MeshTransportPeer, encodedPacket: ByteArray, messageId: String?) =
            MeshTransportSendResult.Accepted(kind)
    }

    /** Wi-Fi Direct with no session: present but never usable, as on phones without a group. */
    private class IdleWifiTransport : MeshTransport {
        override val kind = MeshTransportKind.WIFI_DIRECT
        override fun isAvailable() = false
        override fun canReach(peer: MeshTransportPeer) = false
        override suspend fun send(peer: MeshTransportPeer, encodedPacket: ByteArray, messageId: String?) =
            MeshTransportSendResult.Unavailable(kind, "WIFI_DIRECT_SESSION_UNAVAILABLE")
    }

    private val selector = TransportSelector { listOf(IdleWifiTransport(), FakeBleTransport()) }

    /** What [observer]'s scanner and peer directory hold after hearing [visible]'s advertisements. */
    private fun viewOf(observer: Node, vararg visible: Node): List<MeshPeer> {
        val directory = MeshPeerDirectory()
        val blePeers = visible.map { node ->
            val parsed = BleDiscoveryCodec.decode(BleDiscoveryCodec.encode(node.identity), observer.nodeId)
                as BleDiscoveryCodec.DecodeResult.Parsed
            BlePeer(
                nodeId = parsed.discovery.nodeId,
                deviceName = parsed.discovery.deviceName,
                deviceType = NodeType.PHONE,
                address = node.advertisedAddress,
                rssi = -60,
                lastSeen = now,
                meshProtocolVersion = parsed.discovery.meshProtocolVersion,
                canRelay = parsed.discovery.canRelay,
                canStoreForward = parsed.discovery.canStoreForward,
                supportsAttachmentHopIdentity = parsed.discovery.supportsAttachmentHopIdentity
            )
        }
        directory.replaceBlePeers(blePeers, now)
        return directory.snapshot.value
    }

    private fun originNextHops(view: List<MeshPeer>, local: Node, destination: Node): List<String> =
        MeshForwardingPolicy.select(
            peers = view,
            localNodeId = local.nodeId,
            destinationNodeId = destination.nodeId,
            excludeNodeId = null,
            excludeAddress = null,
            packetId = "HMR-00000000-0000-0000-0000-0000000000f1",
            isTransportUsable = { MeshTransportCompatibility.hasCompatibleTransport(it, selector) },
            isRelayTransportUsable = { MeshTransportCompatibility.hasCompatibleRelayTransport(it, selector) }
        ).map { it.nodeId }

    private fun forwardNextHops(
        view: List<MeshPeer>,
        local: Node,
        receivedFromNodeId: String?,
        receivedFromAddress: String?
    ): List<String> =
        MeshForwardingPolicy.selectForward(
            peers = view,
            localNodeId = local.nodeId,
            packet = dataPacket(origin = a, destination = c),
            receivedFromNodeId = receivedFromNodeId,
            receivedFromAddress = receivedFromAddress,
            isTransportUsable = { MeshTransportCompatibility.hasCompatibleTransport(it, selector) },
            isRelayTransportUsable = { MeshTransportCompatibility.hasCompatibleRelayTransport(it, selector) }
        ).map { it.nodeId }

    private fun dataPacket(origin: Node, destination: Node): MeshPacket {
        val messageId = "HMRM-${origin.nodeId.removePrefix("HMR-")}::00000000-0000-0000-0000-000000000001"
        return MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = origin.nodeId,
            destinationNodeId = destination.nodeId,
            createdAt = now,
            expiresAt = now + 30 * 60 * 1000L,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = "NORMAL",
            content = "hello"
        )
    }

    @Test
    fun originSendsDirectlyWhenDestinationIsVisible() {
        val view = viewOf(a, b, c)

        assertEquals(listOf(c.nodeId), originNextHops(view, local = a, destination = c))
    }

    @Test
    fun originRelaysThroughBWhenDestinationIsNotVisible() {
        val view = viewOf(a, b)

        assertEquals(listOf(b.nodeId), originNextHops(view, local = a, destination = c))
    }

    @Test
    fun relayForwardsToVisibleDestination() {
        val view = viewOf(b, a, c)

        assertEquals(
            listOf(c.nodeId),
            forwardNextHops(view, local = b, receivedFromNodeId = a.nodeId, receivedFromAddress = a.advertisedAddress)
        )
    }

    @Test
    fun relayForwardsToVisibleDestinationWhenPredecessorIsUnresolved() {
        // GATT ingress reports the sender's central-role address, which differs from
        // the address it advertises with, so the predecessor often cannot be resolved.
        val view = viewOf(b, a, c)

        assertEquals(
            listOf(c.nodeId),
            forwardNextHops(view, local = b, receivedFromNodeId = null, receivedFromAddress = "7E:11:22:33:44:55")
        )
    }
}
