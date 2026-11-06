package com.cosmic.ai.data.keystore

import android.app.Application
import com.cosmic.ai.data.local.db.AuditHeadAnchor
import com.cosmic.ai.data.local.keystore.SecureAuditHeadAnchor
import com.cosmic.ai.data.local.keystore.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SecureAuditHeadAnchorTest.TestApplication::class, sdk = [35])
class SecureAuditHeadAnchorTest {
    class TestApplication : Application()

    @Test fun keystoreBackedCheckpointSurvivesAnchorRecreation() {
        val store = SecureStore(RuntimeEnvironment.getApplication())
        val first = SecureAuditHeadAnchor(store)
        val expected = AuditHeadAnchor.Head(42, "f".repeat(64))
        kotlinx.coroutines.runBlocking { first.write(expected) }
        val reopened = SecureAuditHeadAnchor(SecureStore(RuntimeEnvironment.getApplication()))
        kotlinx.coroutines.runBlocking { assertEquals(expected, reopened.read()) }
    }

    @Test fun checkpointCannotMoveBackwardsOrForkAtSameSequence() {
        val anchor = SecureAuditHeadAnchor(SecureStore(RuntimeEnvironment.getApplication()))
        kotlinx.coroutines.runBlocking {
            anchor.write(AuditHeadAnchor.Head(5, "a".repeat(64)))
            assertTrue(kotlin.runCatching { anchor.write(AuditHeadAnchor.Head(4, "b".repeat(64))) }.isFailure)
            assertTrue(kotlin.runCatching { anchor.write(AuditHeadAnchor.Head(5, "c".repeat(64))) }.isFailure)
        }
    }

}
