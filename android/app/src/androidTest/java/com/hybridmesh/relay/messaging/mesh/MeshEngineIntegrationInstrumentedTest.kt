package com.hybridmesh.relay.messaging.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.messaging.attachment.AttachmentFileStore
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferStatus
import com.hybridmesh.relay.messaging.data.MessagingDatabase
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.model.NodeType
import com.hybridmesh.relay.network.MeshTransportSelection
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshEngineIntegrationInstrumentedTest {
    private val nodes = mutableListOf<TestNode>()
    private var testRoot: File? = null

    private val nodeA = "HMR-11111111-1111-1111-1111-111111111111"
    private val nodeB = "HMR-22222222-2222-2222-2222-222222222222"
    private val nodeC = "HMR-33333333-3333-3333-3333-333333333333"

    @After
    fun closeFixtures() {
        nodes.asReversed().forEach { node ->
            node.engine.stop()
            node.database.close()
        }
        nodes.clear()
        testRoot?.deleteRecursively()
        testRoot = null
    }

    @Test
    fun threeNodeMixedBearerDeliveryRetainsPredecessorAndReturnsFinalAck() = runBlocking {
        val (network, a, b, c) = createThreeNodes()
        configureMixedTopology(network, a, b, c)

        b.peerDirectory.replaceBlePeers(listOf(blePeer(a)), now = b.now)
        b.engine.onBleGattConnectionChanged(a.bleAddress, connectionId = 1L, connected = true)
        // Expire discovery well past the old ingress cache while leaving the GATT session active.
        b.peerDirectory.replaceBlePeers(emptyList(), now = b.now + 10 * 60 * 1_000L)
        assertEquals(a.nodeId, b.peerDirectory.findNodeIdByBleAddress(a.bleAddress))
        assertNotNull(b.peerDirectory.findByNodeId(a.nodeId)?.wifiAddress)
        // C is independently reachable over both bearers. Wi-Fi accepts the packet,
        // then reports a simulated lost transport receipt so production fallback sends BLE.
        b.peerDirectory.replaceBlePeers(listOf(blePeer(c)), now = b.now + 10 * 60 * 1_000L)
        c.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = c.now)
        network.connect(b.nodeId, c.nodeId, MeshTransportKind.BLE_GATT)
        network.failNext(
            from = b.nodeId,
            to = c.nodeId,
            kind = MeshTransportKind.WIFI_DIRECT,
            packetType = MeshPacket.PacketType.DATA,
            afterReceiverAccepts = true
        )

        val outgoing = a.repository.addOutgoing("A to C over two phones", c.nodeId, MessageType.NORMAL.name)
        assertTrue(a.engine.processOutgoingMessage(outgoing))

        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        val relay = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals(a.nodeId, relay.receivedFromNodeId)
        assertEquals("BLE_MESH", network.attempts.single { it.from == a.nodeId && it.messageId == outgoing.messageId }.kind.persistedName)
        val relayEvents = b.repository.observeTraceEvents(outgoing.messageId).first()
        assertTrue(relayEvents.any { it.eventType == "PACKET_RECEIVED_FOR_RELAY" && it.resultCode == "VALIDATED" })
        assertTrue(relayEvents.any { it.eventType == "RELAY_ENQUEUED" && it.resultCode == "PERSISTED" })

        b.engine.processForwardRecord(relay, b.now + 5_000L)
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == c.nodeId && it.packetType == MeshPacket.PacketType.DATA &&
                it.kind == MeshTransportKind.WIFI_DIRECT
        })
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == c.nodeId && it.packetType == MeshPacket.PacketType.DATA &&
                it.kind == MeshTransportKind.BLE_GATT
        })
        assertFalse(network.attempts.any {
            it.from == b.nodeId && it.to == a.nodeId && it.packetType == MeshPacket.PacketType.DATA
        })
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        val destinationEvents = c.repository.observeTraceEvents(outgoing.messageId).first()
        assertTrue(destinationEvents.any { it.eventType == "DESTINATION_PERSISTED" && it.resultCode == "PERSISTED" })
        assertTrue(destinationEvents.any { it.eventType == "DELIVERY_ACK_QUEUED" && it.resultCode == "PERSISTED" })

        processForwarding(c, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))
        processForwarding(b, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))

        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == a.nodeId && it.packetType == MeshPacket.PacketType.DELIVERY_ACK &&
                it.kind == MeshTransportKind.WIFI_DIRECT
        })
    }

    @Test
    fun lateFinalAckCanCorrectAnExpiredOriginMessage() = runBlocking {
        val (_, a, _, c) = createThreeNodes()
        val outgoing = a.repository.addOutgoing("Delivered before its ack arrived", c.nodeId, MessageType.NORMAL.name)
        a.repository.markFailed(outgoing.messageId, transport = null, error = "DELIVERY_EXPIRED")
        val ack = MeshPacket(
            packetId = MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId),
            messageId = outgoing.messageId,
            originNodeId = c.nodeId,
            destinationNodeId = a.nodeId,
            createdAt = a.now,
            expiresAt = a.now + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.ACK_INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DELIVERY_ACK,
            messageType = "DELIVERY_ACK",
            content = "",
            deliveryHopCount = 1,
            routeTransportCodes = listOf(1, 1),
            routeTraceComplete = true
        )

        val accepted = a.engine.onTransportPacket(
            sourceNodeId = c.nodeId,
            sourceAddress = c.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(ack)
        )

        assertTrue(accepted)
        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)

        // A stale expiration candidate must not overwrite a delivery ACK that won
        // the race and changed the durable message state first.
        a.repository.markFailed(outgoing.messageId, transport = null, error = "DELIVERY_EXPIRED")
        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)
    }

    @Test
    fun improvedDuplicateRepairsSeenCacheAheadOfForwardingRow() = runBlocking {
        val (_, a, b, c) = createThreeNodes()
        val origin = "HMR-44444444-4444-4444-4444-444444444444"
        val destination = "HMR-55555555-5555-5555-5555-555555555555"
        val messageId = "HMRM-44444444-4444-4444-4444-444444444444::stale-route"
        val packetId = MeshPacketCodec.stableDataPacketId(messageId)
        val firstCopy = MeshPacket(
            packetId = packetId,
            messageId = messageId,
            originNodeId = origin,
            destinationNodeId = destination,
            createdAt = b.now,
            expiresAt = b.now + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.INITIAL_TTL - 3,
            hopCount = 3,
            packetType = MeshPacket.PacketType.DATA,
            messageType = MessageType.NORMAL.name,
            content = "route improvement recovery",
            routeTransportCodes = listOf(1, 1, 1),
            routeTraceComplete = true
        )
        assertTrue(b.engine.onTransportPacket(
            sourceNodeId = a.nodeId,
            sourceAddress = a.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(firstCopy)
        ))

        // This is the durable state left by process death after updating the seen
        // cache but before the old implementation updated the forwarding record.
        b.repository.markForwardExpired(packetId)
        b.repository.updateSeenObservation(
            packetId = packetId,
            hopCount = 2,
            expiresAt = firstCopy.expiresAt + 2 * 60 * 1_000L
        )
        assertEquals(3, requireNotNull(b.repository.getForwarding(packetId)).hopCount)

        val improvedCopy = firstCopy.copy(
            ttl = MeshPacketCodec.INITIAL_TTL - 2,
            hopCount = 2,
            routeTransportCodes = listOf(2, 2)
        )
        assertTrue(b.engine.onTransportPacket(
            sourceNodeId = c.nodeId,
            sourceAddress = c.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(improvedCopy)
        ))

        val repaired = requireNotNull(b.repository.getForwarding(packetId))
        assertEquals(2, repaired.hopCount)
        assertEquals(c.nodeId, repaired.receivedFromNodeId)
        assertEquals("2,2,1", repaired.routeTrace)
    }

    @Test
    fun lateFinalAckRecoversRelayExpiryAndStaleTerminalWritesCannotEraseIt() = runBlocking {
        val (_, a, b, c) = createThreeNodes()
        val outgoing = a.repository.addOutgoing("Late relay ACK", c.nodeId, MessageType.NORMAL.name)
        val data = MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(outgoing.messageId),
            messageId = outgoing.messageId,
            originNodeId = a.nodeId,
            destinationNodeId = c.nodeId,
            createdAt = outgoing.createdAt,
            expiresAt = outgoing.createdAt + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = outgoing.messageType,
            content = outgoing.content
        )
        assertTrue(b.engine.onTransportPacket(
            sourceNodeId = a.nodeId,
            sourceAddress = a.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(data)
        ))
        val dataPacketId = data.packetId

        // Model a delayed final ACK arriving after the relay's DATA row crossed
        // its local expiry transition but before maintenance purged that row.
        b.repository.markForwardExpired(dataPacketId)
        val ack = MeshPacket(
            packetId = MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId),
            messageId = outgoing.messageId,
            originNodeId = c.nodeId,
            destinationNodeId = a.nodeId,
            createdAt = b.now,
            expiresAt = b.now + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.ACK_INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DELIVERY_ACK,
            messageType = "DELIVERY_ACK",
            content = "",
            deliveryHopCount = 1,
            routeTransportCodes = listOf(1, 1),
            routeTraceComplete = true
        )
        assertTrue(b.engine.onTransportPacket(
            sourceNodeId = c.nodeId,
            sourceAddress = c.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(ack)
        ))
        assertEquals("DELIVERED", b.repository.getForwarding(dataPacketId)?.state)

        // Stale terminal work that loses the race to the ACK must not replace the
        // relay's durable delivery confirmation.
        b.repository.markForwardExpired(dataPacketId)
        b.repository.markForwardInvalid(dataPacketId, "STALE_RELAY_ERROR")
        assertEquals("DELIVERED", b.repository.getForwarding(dataPacketId)?.state)
    }

    @Test
    fun queuedMessageExpiresWhileAllTransportsAreUnavailable() = runBlocking {
        val (network, a, _, _) = createThreeNodes(runOriginWorker = true)
        network.available = false

        val outgoing = a.repository.addOutgoing(
            "Expire while offline",
            nodeC,
            MessageType.NORMAL.name
        )
        a.advance(MeshEngine.MESSAGE_LIFETIME_MS + 1L)
        a.engine.onPeerAvailabilityChanged()

        withTimeout(TEST_WORKER_TIMEOUT_MS) {
            a.repository.allMessages.first { records ->
                records.firstOrNull { it.messageId == outgoing.messageId }?.status == DeliveryStatus.FAILED.name
            }
        }
        assertEquals("DELIVERY_EXPIRED", a.repository.getById(outgoing.messageId)?.lastError)
    }

    @Test
    fun bleOnlyThreeNodeDeliveryPersistsAtDestinationAndReturnsFinalAck() = runBlocking {
        val (network, a, b, c) = createThreeNodes()
        configureBleOnlyTopology(network, a, b, c)

        val outgoing = a.repository.addOutgoing("BLE only A to C", c.nodeId, MessageType.NORMAL.name)
        assertTrue(a.engine.processOutgoingMessage(outgoing))
        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        val relay = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals(a.nodeId, relay.receivedFromNodeId)
        assertTrue(network.attempts.any {
            it.from == a.nodeId && it.to == b.nodeId && it.kind == MeshTransportKind.BLE_GATT &&
                it.packetType == MeshPacket.PacketType.DATA
        })

        b.engine.processForwardRecord(relay, b.now + 5_000L)
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        processForwarding(c, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))
        processForwarding(b, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))

        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)
        assertEquals("1,1", a.repository.getById(outgoing.messageId)?.routeTrace)
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == c.nodeId && it.kind == MeshTransportKind.BLE_GATT &&
                it.packetType == MeshPacket.PacketType.DATA
        })
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == a.nodeId && it.kind == MeshTransportKind.BLE_GATT &&
                it.packetType == MeshPacket.PacketType.DELIVERY_ACK
        })
    }

    @Test
    fun wifiToBleRelayReturnsFinalAckOverWifi() = runBlocking {
        val (network, a, b, c) = createThreeNodes()
        configureWifiToBleTopology(network, a, b, c)

        val outgoing = a.repository.addOutgoing("Wi-Fi to BLE A to C", c.nodeId, MessageType.NORMAL.name)
        assertTrue(a.engine.processOutgoingMessage(outgoing))
        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        val relay = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals(a.nodeId, relay.receivedFromNodeId)
        b.engine.processForwardRecord(relay, b.now + 5_000L)
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        processForwarding(c, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))
        processForwarding(b, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))

        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)
        assertEquals("2,1", a.repository.getById(outgoing.messageId)?.routeTrace)
        assertTrue(network.attempts.any {
            it.from == a.nodeId && it.to == b.nodeId && it.kind == MeshTransportKind.WIFI_DIRECT &&
                it.packetType == MeshPacket.PacketType.DATA
        })
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == c.nodeId && it.kind == MeshTransportKind.BLE_GATT &&
                it.packetType == MeshPacket.PacketType.DATA
        })
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == a.nodeId && it.kind == MeshTransportKind.WIFI_DIRECT &&
                it.packetType == MeshPacket.PacketType.DELIVERY_ACK
        })
    }

    @Test
    fun forwardingWorkerRelaysQueuedPacketAndOriginWaitsForFinalAck() = runBlocking {
        val (network, a, b, c) = createThreeNodes(runForwardingWorker = true)
        configureBleOnlyTopology(network, a, b, c)
        val onwardData = network.holdNext(
            from = b.nodeId,
            to = c.nodeId,
            kind = MeshTransportKind.BLE_GATT,
            packetType = MeshPacket.PacketType.DATA
        )
        val finalAck = network.holdNext(
            from = b.nodeId,
            to = a.nodeId,
            kind = MeshTransportKind.BLE_GATT,
            packetType = MeshPacket.PacketType.DELIVERY_ACK
        )

        val outgoing = a.repository.addOutgoing("Scheduled BLE relay", c.nodeId, MessageType.NORMAL.name)
        assertTrue(a.engine.processOutgoingMessage(outgoing))

        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        val queued = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals(a.nodeId, queued.receivedFromNodeId)
        assertEquals(0, queued.hopCount)
        assertEquals("1", queued.routeTrace)
        assertTrue(queued.routeTraceComplete)

        // This gate is reached only when the production forwardingLoop claims the
        // durable row and invokes its selected next-hop transport.
        onwardData.awaitStarted()
        val claimed = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals("FORWARDING", claimed.state)
        assertEquals(1, claimed.attemptCount)
        assertEquals(0, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        onwardData.release()

        // C persists DATA and schedules its ACK through its normal forwardingLoop.
        finalAck.awaitStarted()
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        assertEquals("FORWARDING", b.repository.getForwarding(
            MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId)
        )?.state)
        assertEquals(DeliveryStatus.IN_FLIGHT.name, a.repository.getById(outgoing.messageId)?.status)

        finalAck.release()
        withTimeout(TEST_WORKER_TIMEOUT_MS) {
            a.repository.allMessages.first { records ->
                records.firstOrNull { it.messageId == outgoing.messageId }?.status == DeliveryStatus.DELIVERED.name
            }
        }

        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        assertEquals("DELIVERED", b.repository.getForwarding(dataPacketId)?.state)
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == c.nodeId && it.packetType == MeshPacket.PacketType.DATA
        })
        assertTrue(network.attempts.any {
            it.from == b.nodeId && it.to == a.nodeId && it.packetType == MeshPacket.PacketType.DELIVERY_ACK
        })
    }

    @Test
    fun forwardingWorkerRetriesWhenNextHopAppearsAfterNoPeerBackoff() = runBlocking {
        val (network, a, b, c) = createThreeNodes(runForwardingWorker = true)
        a.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = a.now)
        b.peerDirectory.replaceBlePeers(listOf(blePeer(a)), now = b.now)
        network.connect(a.nodeId, b.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(b.nodeId, a.nodeId, MeshTransportKind.BLE_GATT)

        val outgoing = a.repository.addOutgoing("Retry after peer appears", c.nodeId, MessageType.NORMAL.name)
        assertTrue(a.engine.processOutgoingMessage(outgoing))
        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        b.repository.observeTraceEvents(outgoing.messageId).first { events ->
            events.any { it.eventType == "RETRY_SCHEDULED" && it.resultCode == "NO_MESH_NEIGHBOR" }
        }
        val deferred = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals("PENDING", deferred.state)
        assertEquals("NO_MESH_NEIGHBOR", deferred.lastError)

        val onwardData = network.holdNext(
            from = b.nodeId,
            to = c.nodeId,
            kind = MeshTransportKind.BLE_GATT,
            packetType = MeshPacket.PacketType.DATA
        )
        b.peerDirectory.replaceBlePeers(listOf(blePeer(a), blePeer(c)), now = b.now)
        c.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = c.now)
        network.connect(b.nodeId, c.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(c.nodeId, b.nodeId, MeshTransportKind.BLE_GATT)

        val retryAt = requireNotNull(deferred.nextAttemptAt)
        b.advance((retryAt - b.now).coerceAtLeast(0L))
        b.engine.onPeerAvailabilityChanged()

        // No internal processForwardRecord call: this is the normal worker retry.
        onwardData.awaitStarted()
        val retried = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals("FORWARDING", retried.state)
        assertEquals(2, retried.attemptCount)
        onwardData.release()

        withTimeout(TEST_WORKER_TIMEOUT_MS) {
            a.repository.allMessages.first { records ->
                records.firstOrNull { it.messageId == outgoing.messageId }?.status == DeliveryStatus.DELIVERED.name
            }
        }
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
    }

    @Test
    fun lostFinalAckTriggersBoundedDataReplayAndRecoversExactlyOneDestinationCopy() = runBlocking {
        val (network, a, b, c) = createThreeNodes()
        configureWifiGroupOwnerTopology(network, a, b, c)
        network.failNext(
            from = b.nodeId,
            to = a.nodeId,
            kind = MeshTransportKind.WIFI_DIRECT,
            packetType = MeshPacket.PacketType.DELIVERY_ACK,
            afterReceiverAccepts = false
        )

        val outgoing = a.repository.addOutgoing("Recover after a lost ACK", c.nodeId, MessageType.NORMAL.name)
        a.engine.processOutgoingMessage(outgoing)
        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        processForwarding(b, dataPacketId)
        processForwarding(c, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))
        processForwarding(b, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))

        assertEquals(DeliveryStatus.IN_FLIGHT.name, a.repository.getById(outgoing.messageId)?.status)
        assertEquals("PENDING", b.repository.getForwarding(MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))?.state)
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        assertTrue(b.repository.observeTraceEvents(outgoing.messageId).first().any {
            it.eventType == "RETRY_SCHEDULED" && it.resultCode == "MESH_TRANSPORT_FAILED"
        })

        advanceClocks(120_000L)
        a.repository.requeueIfInFlight(outgoing.messageId, a.now, "INTEGRATION_TEST_ORIGIN_RETRY")
        assertTrue(a.engine.processOutgoingMessage(requireNotNull(a.repository.getById(outgoing.messageId))))
        val replay = requireNotNull(b.repository.getForwarding(dataPacketId))
        assertEquals("PENDING", replay.state)
        b.engine.processForwardRecord(replay, b.now + 5_000L)

        processForwarding(c, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))
        processForwarding(b, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))

        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)
        assertEquals("DELIVERED", b.repository.getForwarding(dataPacketId)?.state)
        assertTrue((b.repository.getForwarding(dataPacketId)?.attemptCount ?: Int.MAX_VALUE) <= 3)
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })
        assertTrue(network.attempts.count {
            it.from == b.nodeId && it.to == a.nodeId && it.packetType == MeshPacket.PacketType.DELIVERY_ACK
        } >= 2)
    }

    @Test
    fun attachmentMetadataDoesNotCompleteFileAndVerifiedRecipientAckCompletesOrigin() = runBlocking {
        val (network, a, b, c) = createThreeNodes()
        configureWifiGroupOwnerTopology(network, a, b, c)

        val payload = "bytes persisted and SHA-256 checked at the recipient".toByteArray()
        val sourceFile = File(a.context.cacheDir, "outgoing-${UUID.randomUUID()}.png").apply {
            writeBytes(payload)
        }
        val sha256 = payload.sha256()
        val (outgoing, queuedAttachment) = a.repository.addOutgoingAttachment(
            recipientNodeId = c.nodeId,
            mimeType = "image/png",
            displayName = "test.png",
            sizeBytes = payload.size.toLong(),
            sha256 = sha256,
            localPath = sourceFile.absolutePath
        )
        assertTrue(a.engine.processOutgoingMessage(outgoing))
        val dataPacketId = MeshPacketCodec.stableDataPacketId(outgoing.messageId)
        processForwarding(b, dataPacketId)
        processForwarding(c, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))
        processForwarding(b, MeshPacketCodec.stableDeliveryAckPacketId(outgoing.messageId))

        assertEquals(DeliveryStatus.DELIVERED.name, a.repository.getById(outgoing.messageId)?.status)
        assertEquals(AttachmentTransferStatus.QUEUED, a.repository.getAttachment(outgoing.messageId)?.status)
        assertFalse(a.repository.getAttachment(outgoing.messageId)?.status == AttachmentTransferStatus.DELIVERED)
        assertEquals(AttachmentTransferStatus.WAITING_WIFI, c.repository.getAttachment(outgoing.messageId)?.status)

        val firstHopManifest = AttachmentTransferManifest(
            messageId = outgoing.messageId,
            senderNodeId = a.nodeId,
            recipientNodeId = c.nodeId,
            mimeType = queuedAttachment.mimeType,
            displayName = queuedAttachment.displayName,
            sizeBytes = payload.size.toLong(),
            sha256 = sha256,
            createdAt = outgoing.createdAt,
            hopCount = 1
        )
        assertEquals(0L, b.engine.onAttachmentBegin(a.nodeId, firstHopManifest, MeshTransportKind.WIFI_DIRECT))
        assertEquals(payload.size.toLong(), b.engine.onAttachmentChunk(a.nodeId, firstHopManifest, 0L, payload, MeshTransportKind.WIFI_DIRECT))
        assertTrue(b.engine.onAttachmentFinish(a.nodeId, firstHopManifest, MeshTransportKind.WIFI_DIRECT))
        assertEquals(AttachmentTransferStatus.RELAY_QUEUED, b.repository.getAttachment(outgoing.messageId)?.status)

        val relayedRecord = requireNotNull(b.repository.getAttachment(outgoing.messageId))
        val relayedFile = File(requireNotNull(relayedRecord.localPath))
        val conflictingHash = firstHopManifest.copy(
            sizeBytes = payload.size + 1L,
            sha256 = "b".repeat(64)
        )
        assertNull(b.engine.onAttachmentBegin(a.nodeId, conflictingHash, MeshTransportKind.WIFI_DIRECT))
        assertTrue("a conflicting BEGIN must preserve the accepted relay file", relayedFile.isFile)
        assertEquals(sha256, AttachmentFileStore.sha256(relayedFile))

        val conflictingName = firstHopManifest.copy(displayName = "replacement.png")
        assertNull(b.engine.onAttachmentBegin(a.nodeId, conflictingName, MeshTransportKind.WIFI_DIRECT))
        assertEquals("test.png", b.repository.getAttachment(outgoing.messageId)?.displayName)

        val secondHopManifest = firstHopManifest.copy(hopCount = 2)
        assertEquals(0L, c.engine.onAttachmentBegin(b.nodeId, secondHopManifest, MeshTransportKind.WIFI_DIRECT))
        assertEquals(payload.size.toLong(), c.engine.onAttachmentChunk(b.nodeId, secondHopManifest, 0L, payload, MeshTransportKind.WIFI_DIRECT))
        assertTrue(c.engine.onAttachmentFinish(b.nodeId, secondHopManifest, MeshTransportKind.WIFI_DIRECT))

        val received = requireNotNull(c.repository.getAttachment(outgoing.messageId))
        assertEquals(AttachmentTransferStatus.DELIVERED, received.status)
        val receivedFile = requireNotNull(received.localPath).let(::File)
        assertEquals(sha256, AttachmentFileStore.sha256(receivedFile))
        assertEquals(1, c.repository.allMessages.first().count { it.messageId == outgoing.messageId })

        val ackId = MeshPacketCodec.stableAttachmentAckPacketId(outgoing.messageId)
        processForwarding(c, ackId)
        processForwarding(b, ackId)
        assertEquals(AttachmentTransferStatus.DELIVERED, a.repository.getAttachment(outgoing.messageId)?.status)

        val deliveredAckBytes = network.attempts.last {
            it.from == b.nodeId && it.to == a.nodeId && it.packetType == MeshPacket.PacketType.ATTACHMENT_ACK
        }.payload
        assertTrue(a.engine.onWifiTransportPacket(b.nodeId, deliveredAckBytes))
        assertEquals(AttachmentTransferStatus.DELIVERED, a.repository.getAttachment(outgoing.messageId)?.status)
    }

    @Test
    fun lateChunkCannotRecreatePartialFileAfterAttachmentDelivery() = runBlocking {
        val (_, a, b, _) = createThreeNodes()
        val payload = "already verified attachment".toByteArray()
        val messageId = "HMRM-${a.nodeId.removePrefix("HMR-")}::late-chunk"
        val manifest = AttachmentTransferManifest(
            messageId = messageId,
            senderNodeId = a.nodeId,
            recipientNodeId = b.nodeId,
            mimeType = "image/png",
            displayName = "late.png",
            sizeBytes = payload.size.toLong(),
            sha256 = payload.sha256(),
            createdAt = b.now,
            hopCount = 0
        )

        assertEquals(0L, b.engine.onAttachmentBegin(a.nodeId, manifest))
        assertEquals(payload.size.toLong(), b.engine.onAttachmentChunk(a.nodeId, manifest, 0L, payload))
        assertTrue(b.engine.onAttachmentFinish(a.nodeId, manifest))
        assertEquals(AttachmentTransferStatus.DELIVERED, b.repository.getAttachment(messageId)?.status)

        val receivePart = AttachmentFileStore.receivePart(b.context, messageId)
        assertFalse(receivePart.exists())
        assertNull(b.engine.onAttachmentChunk(a.nodeId, manifest, 0L, payload))
        assertFalse("a late chunk must not recreate a .part file", receivePart.exists())
    }

    @Test
    fun finishFromDifferentPeerCannotFinalizeAcceptedAttachment() = runBlocking {
        val (_, a, b, c) = createThreeNodes()
        val payload = "finish must come from accepted peer".toByteArray()
        val messageId = "HMRM-${a.nodeId.removePrefix("HMR-")}::finish-peer"
        val manifest = AttachmentTransferManifest(
            messageId = messageId,
            senderNodeId = a.nodeId,
            recipientNodeId = b.nodeId,
            mimeType = "image/png",
            displayName = "finish.png",
            sizeBytes = payload.size.toLong(),
            sha256 = payload.sha256(),
            createdAt = b.now,
            hopCount = 0
        )

        assertEquals(0L, b.engine.onAttachmentBegin(a.nodeId, manifest))
        assertEquals(payload.size.toLong(), b.engine.onAttachmentChunk(a.nodeId, manifest, 0L, payload))

        assertFalse(b.engine.onAttachmentFinish(c.nodeId, manifest))
        assertTrue(AttachmentFileStore.receivePart(b.context, messageId).exists())
        assertFalse(AttachmentFileStore.receivedFile(b.context, messageId).exists())
        assertEquals(AttachmentTransferStatus.RECEIVING, b.repository.getAttachment(messageId)?.status)

        assertTrue(b.engine.onAttachmentFinish(a.nodeId, manifest))
        assertEquals(AttachmentTransferStatus.DELIVERED, b.repository.getAttachment(messageId)?.status)
    }

    @Test
    fun attachmentBeginCannotReuseAnExistingNonAttachmentMessageId() = runBlocking {
        val (_, a, b, _) = createThreeNodes()
        val messageId = "HMRM-${a.nodeId.removePrefix("HMR-")}::text-message-id"
        b.database.messageDao().insertIncoming(
            MessageRecordEntity(
                messageId = messageId,
                senderNodeId = a.nodeId,
                recipientNodeId = b.nodeId,
                content = "an existing text message",
                createdAt = b.now,
                messageType = MessageType.NORMAL.name,
                status = DeliveryStatus.DELIVERED.name,
                lastTransport = "WIFI_DIRECT",
                deliveredAt = b.now
            )
        )
        val manifest = AttachmentTransferManifest(
            messageId = messageId,
            senderNodeId = a.nodeId,
            recipientNodeId = b.nodeId,
            mimeType = "image/png",
            displayName = "unexpected.png",
            sizeBytes = 1L,
            sha256 = "a".repeat(64),
            createdAt = b.now
        )

        assertNull(b.engine.onAttachmentBegin(a.nodeId, manifest))
        assertNull(b.repository.getAttachment(messageId))
        assertFalse(AttachmentFileStore.receivePart(b.context, messageId).exists())
    }

    @Test
    fun conflictingDataDuplicateCannotReceiveDeliveryAcceptance() = runBlocking {
        val (_, a, b, _) = createThreeNodes()
        val messageId = "HMRM-${a.nodeId.removePrefix("HMR-")}::duplicate-content"
        val packet = MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = a.nodeId,
            destinationNodeId = b.nodeId,
            createdAt = b.now,
            expiresAt = b.now + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = MessageType.NORMAL.name,
            content = "first durable content"
        )
        assertTrue(b.engine.onTransportPacket(
            sourceNodeId = a.nodeId,
            sourceAddress = a.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(packet)
        ))
        val ackPacketId = MeshPacketCodec.stableDeliveryAckPacketId(messageId)
        val queuedAckBeforeConflict = requireNotNull(b.repository.getForwarding(ackPacketId))

        val conflict = packet.copy(content = "conflicting content under the same ID")
        assertFalse(b.engine.onTransportPacket(
            sourceNodeId = a.nodeId,
            sourceAddress = a.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(conflict)
        ))
        assertEquals("first durable content", b.repository.getById(messageId)?.content)
        assertEquals(queuedAckBeforeConflict, b.repository.getForwarding(ackPacketId))
    }

    @Test
    fun zeroHopDataCannotClaimAnOriginDifferentFromItsIngressPeer() = runBlocking {
        val (_, a, b, c) = createThreeNodes()
        val outgoing = a.repository.addOutgoing("Origin binding", b.nodeId, MessageType.NORMAL.name)
        val packet = MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(outgoing.messageId),
            messageId = outgoing.messageId,
            originNodeId = a.nodeId,
            destinationNodeId = b.nodeId,
            createdAt = outgoing.createdAt,
            expiresAt = outgoing.createdAt + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = outgoing.messageType,
            content = outgoing.content
        )

        assertFalse(b.engine.onTransportPacket(
            sourceNodeId = c.nodeId,
            sourceAddress = c.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(packet)
        ))
        assertNull(b.repository.getById(outgoing.messageId))

        assertTrue(b.engine.onTransportPacket(
            sourceNodeId = a.nodeId,
            sourceAddress = a.bleAddress,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(packet)
        ))
        assertEquals(outgoing.content, b.repository.getById(outgoing.messageId)?.content)
    }

    @Test
    fun relayedDataCannotEnterWithoutAnAssociatedIngressPeer() = runBlocking {
        val (_, a, b, c) = createThreeNodes()
        val messageId = "HMRM-${a.nodeId.removePrefix("HMR-")}::missing-predecessor"
        val packet = MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = a.nodeId,
            destinationNodeId = c.nodeId,
            createdAt = b.now,
            expiresAt = b.now + MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = MeshPacketCodec.INITIAL_TTL - 1,
            hopCount = 1,
            packetType = MeshPacket.PacketType.DATA,
            messageType = MessageType.NORMAL.name,
            content = "relayed without peer binding"
        )

        assertFalse(b.engine.onTransportPacket(
            sourceNodeId = null,
            sourceAddress = "unassociated-ble-address",
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = MeshPacketCodec.encode(packet)
        ))
        assertNull(b.repository.getForwarding(packet.packetId))
        assertNull(b.repository.getSeenPacket(packet.packetId))
    }

    private suspend fun processForwarding(node: TestNode, packetId: String) {
        val record = requireNotNull(node.repository.getForwarding(packetId))
        node.engine.processForwardRecord(record, node.now + 5_000L)
    }

    private fun advanceClocks(millis: Long) = nodes.forEach { it.advance(millis) }

    private data class ThreeNodes(
        val network: FakeNetwork,
        val a: TestNode,
        val b: TestNode,
        val c: TestNode
    )

    private fun createThreeNodes(
        runForwardingWorker: Boolean = false,
        runOriginWorker: Boolean = false
    ): ThreeNodes {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.filesDir, "mesh-engine-integration-${UUID.randomUUID()}").apply { mkdirs() }
        testRoot = root
        val network = FakeNetwork()
        val a = createNode(base, root, network, nodeA, 1, runForwardingWorker, runOriginWorker)
        val b = createNode(base, root, network, nodeB, 2, runForwardingWorker, runOriginWorker)
        val c = createNode(base, root, network, nodeC, 3, runForwardingWorker, runOriginWorker)
        nodes += listOf(a, b, c)
        nodes.forEach { it.engine.start() }
        return ThreeNodes(network, a, b, c)
    }

    private fun createNode(
        base: Context,
        root: File,
        network: FakeNetwork,
        nodeId: String,
        index: Int,
        runForwardingWorker: Boolean,
        runOriginWorker: Boolean
    ): TestNode {
        require(NodeIdGenerator.isValid(nodeId))
        val nodeRoot = File(root, nodeId.takeLast(4)).apply { mkdirs() }
        val context = IsolatedNodeContext(base, nodeRoot)
        val database = Room.inMemoryDatabaseBuilder(context, MessagingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MessagingRepository(context, database) { nodeId }
        val directory = MeshPeerDirectory()
        var clock = System.currentTimeMillis() + 5_000L
        val transports = listOf(
            network.transport(nodeId, MeshTransportKind.BLE_GATT),
            network.transport(nodeId, MeshTransportKind.WIFI_DIRECT)
        )
        val engine = MeshEngine(
            context,
            MeshEngineTestOverrides(
                repository = repository,
                localNodeId = { nodeId },
                peerDirectory = directory,
                transports = transports,
                transportSelection = MeshTransportSelection(),
                nowMillis = { clock },
                runForwardingWorker = runForwardingWorker,
                runOriginWorker = runOriginWorker
            )
        )
        val node = TestNode(
            nodeId = nodeId,
            bleAddress = "02:00:00:00:00:%02X".format(index),
            context = context,
            root = nodeRoot,
            database = database,
            repository = repository,
            peerDirectory = directory,
            engine = engine,
            readClock = { clock },
            advanceClock = { clock += it }
        )
        network.register(node)
        return node
    }

    private fun configureWifiGroupOwnerTopology(
        network: FakeNetwork,
        a: TestNode,
        b: TestNode,
        c: TestNode
    ) {
        a.peerDirectory.observeWifi(b.nodeId, "B", "192.0.2.2", 41000, 2, true, true, a.now)
        b.peerDirectory.observeWifi(a.nodeId, "A", "192.0.2.1", 41000, 2, true, true, b.now)
        b.peerDirectory.observeWifi(c.nodeId, "C", "192.0.2.3", 41000, 2, true, true, b.now)
        c.peerDirectory.observeWifi(b.nodeId, "B", "192.0.2.2", 41000, 2, true, true, c.now)
        network.connect(a.nodeId, b.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(b.nodeId, a.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(b.nodeId, c.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(c.nodeId, b.nodeId, MeshTransportKind.WIFI_DIRECT)
    }

    private fun configureMixedTopology(
        network: FakeNetwork,
        a: TestNode,
        b: TestNode,
        c: TestNode
    ) {
        a.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = a.now)
        b.peerDirectory.replaceBlePeers(listOf(blePeer(a)), now = b.now)
        b.peerDirectory.observeWifi(a.nodeId, "A", "192.0.2.1", 41000, 2, true, true, b.now)
        b.peerDirectory.observeWifi(c.nodeId, "C", "192.0.2.3", 41000, 2, true, true, b.now)
        c.peerDirectory.observeWifi(b.nodeId, "B", "192.0.2.2", 41000, 2, true, true, c.now)
        network.connect(a.nodeId, b.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(b.nodeId, a.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(b.nodeId, c.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(c.nodeId, b.nodeId, MeshTransportKind.WIFI_DIRECT)
    }

    private fun configureBleOnlyTopology(
        network: FakeNetwork,
        a: TestNode,
        b: TestNode,
        c: TestNode
    ) {
        a.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = a.now)
        b.peerDirectory.replaceBlePeers(listOf(blePeer(a), blePeer(c)), now = b.now)
        c.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = c.now)
        network.connect(a.nodeId, b.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(b.nodeId, a.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(b.nodeId, c.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(c.nodeId, b.nodeId, MeshTransportKind.BLE_GATT)
    }

    private fun configureWifiToBleTopology(
        network: FakeNetwork,
        a: TestNode,
        b: TestNode,
        c: TestNode
    ) {
        a.peerDirectory.observeWifi(b.nodeId, "B", "192.0.2.2", 41000, 2, true, true, a.now)
        b.peerDirectory.observeWifi(a.nodeId, "A", "192.0.2.1", 41000, 2, true, true, b.now)
        b.peerDirectory.replaceBlePeers(listOf(blePeer(c)), now = b.now)
        c.peerDirectory.replaceBlePeers(listOf(blePeer(b)), now = c.now)
        network.connect(a.nodeId, b.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(b.nodeId, a.nodeId, MeshTransportKind.WIFI_DIRECT)
        network.connect(b.nodeId, c.nodeId, MeshTransportKind.BLE_GATT)
        network.connect(c.nodeId, b.nodeId, MeshTransportKind.BLE_GATT)
    }

    private fun blePeer(node: TestNode) = BlePeer(
        nodeId = node.nodeId,
        deviceName = node.nodeId.takeLast(4),
        deviceType = NodeType.RELAY,
        address = node.bleAddress,
        rssi = -40,
        lastSeen = node.now,
        meshProtocolVersion = MeshPacketCodec.PROTOCOL_VERSION,
        canRelay = true,
        canStoreForward = true
    )

    private data class TestNode(
        val nodeId: String,
        val bleAddress: String,
        val context: Context,
        val root: File,
        val database: MessagingDatabase,
        val repository: MessagingRepository,
        val peerDirectory: MeshPeerDirectory,
        val engine: MeshEngine,
        private val readClock: () -> Long,
        private val advanceClock: (Long) -> Unit
    ) {
        val now: Long get() = readClock()
        fun advance(millis: Long) = advanceClock(millis)
    }

    private class IsolatedNodeContext(base: Context, private val isolatedRoot: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("mesh-test-${isolatedRoot.name}-$name", mode)
    }

    private class FakeNetwork {
        private data class Link(val from: String, val to: String, val kind: MeshTransportKind)
        private data class FailureKey(val link: Link, val packetType: MeshPacket.PacketType, val afterAccept: Boolean)
        private data class GateKey(val link: Link, val packetType: MeshPacket.PacketType)
        class SendGate internal constructor() {
            private val started = CompletableDeferred<Unit>()
            private val released = CompletableDeferred<Unit>()

            suspend fun awaitStarted() = withTimeout(TEST_WORKER_TIMEOUT_MS) { started.await() }
            fun release() { released.complete(Unit) }
            internal suspend fun awaitRelease() { released.await() }
            internal fun signalStarted() { started.complete(Unit) }
        }
        data class Attempt(
            val from: String,
            val to: String,
            val kind: MeshTransportKind,
            val packetType: MeshPacket.PacketType,
            val messageId: String,
            val payload: ByteArray
        )

        private val lock = Any()
        private val nodes = LinkedHashMap<String, TestNode>()
        private val links = mutableSetOf<Link>()
        private val failures = mutableMapOf<FailureKey, Int>()
        private val sendGates = mutableMapOf<GateKey, MutableList<SendGate>>()
        @Volatile var available = true
        val attempts = mutableListOf<Attempt>()

        fun register(node: TestNode) = synchronized(lock) { nodes[node.nodeId] = node }

        fun connect(from: String, to: String, kind: MeshTransportKind) = synchronized(lock) {
            links += Link(from, to, kind)
        }

        fun holdNext(
            from: String,
            to: String,
            kind: MeshTransportKind,
            packetType: MeshPacket.PacketType
        ): SendGate = synchronized(lock) {
            val key = GateKey(Link(from, to, kind), packetType)
            SendGate().also { sendGates.getOrPut(key) { mutableListOf() }.add(it) }
        }

        fun failNext(
            from: String,
            to: String,
            kind: MeshTransportKind,
            packetType: MeshPacket.PacketType,
            afterReceiverAccepts: Boolean
        ) = synchronized(lock) {
            val key = FailureKey(Link(from, to, kind), packetType, afterReceiverAccepts)
            failures[key] = (failures[key] ?: 0) + 1
        }

        fun transport(from: String, transportKind: MeshTransportKind) = object : MeshTransport {
            override val kind = transportKind
            override fun isAvailable() = available
            override fun canReach(peer: MeshTransportPeer): Boolean = synchronized(lock) {
                Link(from, peer.nodeId, kind) in links
            }

            override suspend fun send(
                peer: MeshTransportPeer,
                encodedPacket: ByteArray,
                messageId: String?
            ): MeshTransportSendResult {
                val packet = MeshPacketCodec.decode(encodedPacket)
                    ?: return MeshTransportSendResult.Failed(kind, "INVALID_TEST_PACKET")
                val link = Link(from, peer.nodeId, kind)
                val beforeKey = FailureKey(link, packet.packetType, afterAccept = false)
                val afterKey = FailureKey(link, packet.packetType, afterAccept = true)
                val failBefore = takeFailure(beforeKey)
                val failAfter = !failBefore && takeFailure(afterKey)
                val gate = synchronized(lock) {
                    sendGates[GateKey(link, packet.packetType)]?.removeFirstOrNull()
                        ?.also { if (sendGates[GateKey(link, packet.packetType)].isNullOrEmpty()) sendGates.remove(GateKey(link, packet.packetType)) }
                }
                val attempt = Attempt(from, peer.nodeId, kind, packet.packetType, packet.messageId, encodedPacket.copyOf())
                synchronized(lock) { attempts += attempt }
                if (!canReach(peer)) return MeshTransportSendResult.Unavailable(kind, "NO_FAKE_LINK")
                if (failBefore) return MeshTransportSendResult.Failed(kind, "INJECTED_PACKET_LOSS")

                if (gate != null) {
                    gate.signalStarted()
                    gate.awaitRelease()
                }

                val target = synchronized(lock) { nodes[peer.nodeId] }
                    ?: return MeshTransportSendResult.Failed(kind, "UNKNOWN_FAKE_NODE")
                val sender = synchronized(lock) { nodes[from] }
                    ?: return MeshTransportSendResult.Failed(kind, "UNKNOWN_FAKE_SENDER")
                val accepted = when (kind) {
                    MeshTransportKind.BLE_GATT -> target.engine.onBleAddressTransportPacket(sender.bleAddress, encodedPacket)
                    MeshTransportKind.WIFI_DIRECT -> target.engine.onWifiTransportPacket(from, encodedPacket)
                    MeshTransportKind.LORA -> false
                }
                if (!accepted) return MeshTransportSendResult.Failed(kind, "REMOTE_MESH_REJECTED")
                if (failAfter) return MeshTransportSendResult.Failed(kind, "INJECTED_POST_ACCEPT_LOSS")
                return MeshTransportSendResult.Accepted(kind)
            }
        }

        private fun takeFailure(key: FailureKey): Boolean = synchronized(lock) {
            val remaining = failures[key] ?: return@synchronized false
            if (remaining <= 1) failures.remove(key) else failures[key] = remaining - 1
            true
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEST_WORKER_TIMEOUT_MS = 10_000L
    }
}
