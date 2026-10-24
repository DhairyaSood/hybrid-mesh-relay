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
            assertEquals(0L, AttachmentFileStore.prepareReceive(context, manifest))
            assertEquals(8L, AttachmentFileStore.appendChunk(context, manifest.messageId, 0L, payload.copyOfRange(0, 8), payload.size.toLong()))

            // A new transfer session resumes the durable .part file at its actual offset.
            assertEquals(8L, AttachmentFileStore.prepareReceive(context, manifest))
            assertEquals(
                payload.size.toLong(),
                AttachmentFileStore.appendChunk(
                    context,
                    manifest.messageId,
                    8L,
                    payload.copyOfRange(8, payload.size),
                    payload.size.toLong()
                )
            )
            val completed = AttachmentFileStore.finishReceive(context, manifest)
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
            assertEquals(0L, AttachmentFileStore.prepareReceive(context, manifest))
            assertEquals(actual.size.toLong(), AttachmentFileStore.appendChunk(context, manifest.messageId, 0L, actual, actual.size.toLong()))
            assertNull(AttachmentFileStore.finishReceive(context, manifest))
            assertTrue(!AttachmentFileStore.receivedFile(context, manifest.messageId).exists())
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
