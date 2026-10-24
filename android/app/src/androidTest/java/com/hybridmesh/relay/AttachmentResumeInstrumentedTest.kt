package com.hybridmesh.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hybridmesh.relay.messaging.attachment.AttachmentFileStore
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentResumeInstrumentedTest {
    @Test
    fun interruptedReceiveResumesAndOnlyFinishesAfterSha256Verification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = "verified attachment payload".toByteArray()
        val manifest = manifest(payload)

        try {
            var receive = AttachmentFileStore.prepareReceive(context, manifest)
            assertNotNull(receive)
            assertEquals(0L, receive!!.offset)
            assertEquals(
                8L,
                AttachmentFileStore.appendChunk(
                    context, manifest.messageId, receive, 0L,
                    payload.copyOfRange(0, 8), payload.size.toLong()
                )
            )

            // A new transfer session resumes the durable .part file at its actual offset.
            receive = AttachmentFileStore.prepareReceive(context, manifest)
            assertNotNull(receive)
            assertEquals(8L, receive!!.offset)
            assertEquals(
                payload.size.toLong(),
                AttachmentFileStore.appendChunk(
                    context, manifest.messageId, receive, 8L,
                    payload.copyOfRange(8, payload.size),
                    payload.size.toLong()
                )
            )
            val completed = AttachmentFileStore.finishReceive(context, manifest, receive)
            assertNotNull(completed)
            assertEquals(manifest.sha256, AttachmentFileStore.sha256(completed!!))
            assertEquals(payload.toList(), completed.readBytes().toList())
        } finally {
            AttachmentFileStore.deleteReceivedFiles(context, manifest.messageId)
        }
    }

    @Test
    fun integrityMismatchDoesNotFinalizeReceivedFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = "actual bytes".toByteArray()
        val manifest = manifest(actual).copy(sha256 = "0".repeat(64))

        try {
            val receive = AttachmentFileStore.prepareReceive(context, manifest)
            assertNotNull(receive)
            assertEquals(
                actual.size.toLong(),
                AttachmentFileStore.appendChunk(
                    context, manifest.messageId, receive!!, 0L, actual, actual.size.toLong()
                )
            )
            assertNull(AttachmentFileStore.finishReceive(context, manifest, receive))
            assertTrue(!AttachmentFileStore.receivedFile(context, manifest.messageId).exists())
        } finally {
            AttachmentFileStore.deleteReceivedFiles(context, manifest.messageId)
        }
    }

    @Test
    fun deletedReceiveSessionRejectsDelayedWritesAndAllowsAFreshSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = "session generation payload".toByteArray()
        val manifest = manifest(payload).copy(
            messageId = "HMRM-11111111-1111-1111-1111-111111111111::deleted-session"
        )

        try {
            val oldSession = AttachmentFileStore.prepareReceive(context, manifest)
            assertNotNull(oldSession)
            AttachmentFileStore.deleteReceivedFiles(context, manifest.messageId)

            assertNull(
                AttachmentFileStore.appendChunk(
                    context,
                    manifest.messageId,
                    oldSession!!,
                    0L,
                    payload,
                    payload.size.toLong()
                )
            )
            assertTrue(!AttachmentFileStore.receivePart(context, manifest.messageId).exists())

            val newSession = AttachmentFileStore.prepareReceive(context, manifest)
            assertNotNull(newSession)
            assertEquals(
                payload.size.toLong(),
                AttachmentFileStore.appendChunk(
                    context,
                    manifest.messageId,
                    newSession!!,
                    0L,
                    payload,
                    payload.size.toLong()
                )
            )
        } finally {
            AttachmentFileStore.deleteReceivedFiles(context, manifest.messageId)
        }
    }

    @Test
    fun deletingDistinctReceiveSessionsDoesNotPreventLaterReceives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = byteArrayOf(1, 2, 3)
        val manifests = (0 until 128).map { index ->
            manifest(payload).copy(
                messageId = "HMRM-11111111-1111-1111-1111-111111111111::deleted-$index"
            )
        }

        manifests.forEach { current ->
            val session = AttachmentFileStore.prepareReceive(context, current)
            assertNotNull(session)
            AttachmentFileStore.deleteReceivedFiles(context, current.messageId)
            assertNull(
                AttachmentFileStore.appendChunk(
                    context, current.messageId, session!!, 0L, payload, payload.size.toLong()
                )
            )
        }

        val finalManifest = manifests.last()
        val freshSession = AttachmentFileStore.prepareReceive(context, finalManifest)
        assertNotNull(freshSession)
        assertEquals(
            payload.size.toLong(),
            AttachmentFileStore.appendChunk(
                context, finalManifest.messageId, freshSession!!, 0L, payload, payload.size.toLong()
            )
        )
        manifests.forEach { AttachmentFileStore.deleteReceivedFiles(context, it.messageId) }
    }

    @Test
    fun orphanCleanupInvalidatesAnActiveReceiveHandleBeforeDeletingItsPart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = "orphan cleanup payload".toByteArray()
        val manifest = manifest(payload).copy(
            messageId = "HMRM-11111111-1111-1111-1111-111111111111::orphan-cleanup"
        )

        try {
            val receive = AttachmentFileStore.prepareReceive(context, manifest)
            assertNotNull(receive)
            assertEquals(
                4L,
                AttachmentFileStore.appendChunk(
                    context, manifest.messageId, receive!!, 0L,
                    payload.copyOfRange(0, 4), payload.size.toLong()
                )
            )
            assertTrue(AttachmentFileStore.receivePart(context, manifest.messageId).setLastModified(0L))

            assertEquals(
                1,
                AttachmentFileStore.deleteStaleOrphanReceiveParts(
                    context, emptySet(), System.currentTimeMillis()
                )
            )
            assertNull(
                AttachmentFileStore.appendChunk(
                    context, manifest.messageId, receive, 4L,
                    payload.copyOfRange(4, payload.size), payload.size.toLong()
                )
            )
            assertTrue(!AttachmentFileStore.receivePart(context, manifest.messageId).exists())
        } finally {
            AttachmentFileStore.deleteReceivedFiles(context, manifest.messageId)
        }
    }

    private fun manifest(bytes: ByteArray) = AttachmentTransferManifest(
        messageId = "HMRM-11111111-1111-1111-1111-111111111111::instrumented-attachment",
        senderNodeId = "HMR-11111111-1111-1111-1111-111111111111",
        recipientNodeId = "HMR-22222222-2222-2222-2222-222222222222",
        mimeType = "image/png",
        displayName = "resume-test.png",
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        createdAt = System.currentTimeMillis()
    )
}
