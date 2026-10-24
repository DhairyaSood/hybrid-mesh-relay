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
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.model.NodeType
import com.hybridmesh.relay.network.MeshTransportSelection
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun createThreeNodes(): ThreeNodes {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.filesDir, "mesh-engine-integration-${UUID.randomUUID()}").apply { mkdirs() }
        testRoot = root
        val network = FakeNetwork()
        val a = createNode(base, root, network, nodeA, 1)
        val b = createNode(base, root, network, nodeB, 2)
        val c = createNode(base, root, network, nodeC, 3)
        nodes += listOf(a, b, c)
        nodes.forEach { it.engine.start() }
        return ThreeNodes(network, a, b, c)
    }

    private fun createNode(
        base: Context,
        root: File,
        network: FakeNetwork,
        nodeId: String,
        index: Int
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
                nowMillis = { clock }
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
        val attempts = mutableListOf<Attempt>()

        fun register(node: TestNode) = synchronized(lock) { nodes[node.nodeId] = node }

        fun connect(from: String, to: String, kind: MeshTransportKind) = synchronized(lock) {
            links += Link(from, to, kind)
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
            override fun isAvailable() = true
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
                val attempt = Attempt(from, peer.nodeId, kind, packet.packetType, packet.messageId, encodedPacket.copyOf())
                synchronized(lock) { attempts += attempt }
                if (!canReach(peer)) return MeshTransportSendResult.Unavailable(kind, "NO_FAKE_LINK")
                if (failBefore) return MeshTransportSendResult.Failed(kind, "INJECTED_PACKET_LOSS")

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
}
