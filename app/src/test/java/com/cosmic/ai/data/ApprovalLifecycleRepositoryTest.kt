package com.cosmic.ai.data

import android.app.Application
import androidx.room.Room
import com.cosmic.ai.data.local.db.ApprovalLifecycleRepository
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.TaskLifecycleRepository
import com.cosmic.ai.data.local.db.SecurityAuditRepository
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.domain.agent.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = ApprovalLifecycleRepositoryTest.TestApplication::class, sdk = [35])
class ApprovalLifecycleRepositoryTest {
    class TestApplication : Application()
    private lateinit var name: String
    private lateinit var db: CosmicDatabase
    private lateinit var tasks: TaskLifecycleRepository
    private lateinit var approvals: ApprovalLifecycleRepository

    @Before fun setUp() {
        name = "approval-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, name).allowMainThreadQueries().build()
        tasks = TaskLifecycleRepository(db)
        approvals = ApprovalLifecycleRepository(db)
    }

    @After fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(name)
    }

    @Test fun denialCancelsTaskAndNeverClaimsToolExecution() = runBlocking {
        prepareCall()
        val request = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        val denied = approvals.decide(request.id, allow = false, mode = "DENY")
        assertEquals("DENIED", denied.status)
        assertEquals(TaskStatus.CANCELLED.name, db.taskDao().getById("task")?.status)
        assertEquals("FAILED", db.toolExecutionDao().get("task", "call")?.state)
        assertTrue(runCatching { approvals.claimApproved(request.id) }.isFailure)
        val decisions = db.securityAuditDao().observeAll().first().map { it.decision }
        assertTrue(decisions.containsAll(listOf("REQUESTED", "DENIED")))
    }

    @Test fun approvalIsSingleUseAndCompletionPersistsAuditAndToolResult() = runBlocking {
        prepareCall()
        val request = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        assertEquals("APPROVED", approvals.decide(request.id, true, "ALLOW_ONCE").status)
        val (claimed, tool) = approvals.claimApproved(request.id)
        assertEquals("RUNNING", tool.state)
        assertTrue(runCatching { approvals.claimApproved(request.id) }.isFailure)
        approvals.finishApproved(claimed, true, "{\"ok\":true,\"output\":\"created\"}", "created", null)
        assertEquals("SUCCEEDED", db.toolExecutionDao().get("task", "call")?.state)
        assertEquals(TaskStatus.QUEUED.name, db.taskDao().getById("task")?.status)
        val decisions = db.securityAuditDao().observeAll().first().map { it.decision }
        assertTrue(decisions.containsAll(listOf("REQUESTED", "APPROVED", "EXECUTION_STARTED", "COMPLETED")))
    }

    @Test fun expiredRequestFailsClosed() = runBlocking {
        prepareCall()
        val request = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash", ttlMillis = -1)
        val result = approvals.decide(request.id, true, "ALLOW_ONCE")
        assertEquals("EXPIRED", result.status)
        assertEquals(TaskStatus.CANCELLED.name, db.taskDao().getById("task")?.status)
        assertEquals("FAILED", db.toolExecutionDao().get("task", "call")?.state)
    }

    @Test fun pendingApprovalAndAuditSurviveDatabaseReopen() = runBlocking {
        prepareCall()
        val request = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        db.close()
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, name)
            .allowMainThreadQueries().build()
        approvals = ApprovalLifecycleRepository(db)
        val recovered = approvals.observePending().first().single()
        assertEquals(request.id, recovered.id)
        assertEquals("PENDING", recovered.status)
        assertTrue(db.securityAuditDao().observeAll().first().any { it.decision == "REQUESTED" })
        val verified = SecurityAuditRepository(db).verifyIntegrity()
        assertTrue(verified.valid)
    }

    @Test fun terminalPermissionRequestIsDurableAndDenialCancelsSyntheticTask() = runBlocking {
        val request = approvals.createTerminalRequest("rm -r example", "Destructive command")
        assertEquals("terminal", request.agentId)
        assertEquals(TaskStatus.AWAITING_APPROVAL.name, db.taskDao().getById(request.taskId)?.status)
        assertEquals("NEEDS_APPROVAL", db.toolExecutionDao().get(request.taskId, request.callId)?.state)
        approvals.decide(request.id, false, "DENY")
        assertEquals(TaskStatus.CANCELLED.name, db.taskDao().getById(request.taskId)?.status)
        assertEquals("FAILED", db.toolExecutionDao().get(request.taskId, request.callId)?.state)
    }

    @Test fun allowForTaskIsStillBoundToThePersistedCallScope() = runBlocking {
        prepareCall()
        val request = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        val approved = approvals.decide(request.id, true, "ALLOW_FOR_TASK")
        assertEquals("ALLOW_FOR_TASK", approved.decisionMode)
        assertEquals("hash", approved.argumentsHash)
        assertTrue(runCatching {
            approvals.request("task", "call", "builder", "write_file", "write", "src/other.kt", "MEDIUM", "Other file", "different-hash")
        }.isFailure)
    }


    @Test fun concurrentDecisionAndClaimCannotAuthorizeOrRunTwice() = runBlocking {
        prepareCall()
        val request = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        val decisions = coroutineScope {
            listOf(
                async(Dispatchers.IO) { runCatching { approvals.decide(request.id, true, "ALLOW_ONCE") } },
                async(Dispatchers.IO) { runCatching { approvals.decide(request.id, true, "ALLOW_ONCE") } }
            ).awaitAll()
        }
        assertEquals(1, decisions.count { it.isSuccess })
        val claims = coroutineScope {
            listOf(
                async(Dispatchers.IO) { runCatching { approvals.claimApproved(request.id) } },
                async(Dispatchers.IO) { runCatching { approvals.claimApproved(request.id) } }
            ).awaitAll()
        }
        assertEquals(1, claims.count { it.isSuccess })
        assertEquals("EXECUTING", approvals.requestById(request.id)?.status)
    }

    @Test fun auditEventsAreChainedAndTamperingIsDetected() = runBlocking {
        val audit = SecurityAuditRepository(db)
        audit.record("request", "task", "builder", "write_file", "write", "src/file.kt", "APPROVED", error = "api_key=supersecret")
        audit.record("request", "task", "builder", "write_file", "write", "src/file.kt", "COMPLETED")
        assertFalse(db.securityAuditDao().allBySequence().any { it.sanitizedError?.contains("supersecret") == true })
        val verified = audit.verifyIntegrity()
        assertTrue(verified.valid)
        assertEquals(2, verified.verifiedEvents)
        val row = db.securityAuditDao().allBySequence().first()
        db.openHelper.writableDatabase.execSQL("UPDATE security_audit_events SET outcome = 'tampered' WHERE eventId = ?", arrayOf(row.eventId))
        val tampered = audit.verifyIntegrity()
        assertFalse(tampered.valid)
        assertEquals(row.sequence, tampered.firstInvalidSequence)
    }

    @Test fun secureHeadAnchorDetectsLatestEventDeletionAfterRepositoryReopen() = runBlocking {
        val anchor = object : com.cosmic.ai.data.local.db.AuditHeadAnchor {
            private var head: Head? = null
            override suspend fun read() = head
            override suspend fun write(head: Head) { this.head = head }
        }
        val audit = SecurityAuditRepository(db, anchor)
        audit.record(null, "task", "tester", "tool", "read", "scope", "STARTED")
        audit.record(null, "task", "tester", "tool", "read", "scope", "COMPLETED")
        assertTrue(audit.verifyIntegrity().valid)
        db.openHelper.writableDatabase.execSQL("DELETE FROM security_audit_events WHERE sequence = 2")
        val reopenedRepository = SecurityAuditRepository(db, anchor)
        assertTrue(runCatching { reopenedRepository.verifyIntegrity() }.isFailure)
    }

    @Test fun concurrentAuditAppendsAllocateAContiguousVerifiedChain() = runBlocking {
        val audit = SecurityAuditRepository(db)
        coroutineScope {
            (0 until 16).map { index -> async(Dispatchers.IO) {
                audit.record("request-$index", "task", "agent", "tool", "action", "resource-$index", "EVENT_$index")
            } }.awaitAll()
        }
        val result = audit.verifyIntegrity()
        assertTrue(result.valid)
        assertEquals(16, result.verifiedEvents)
        assertEquals((1L..16L).toList(), db.securityAuditDao().allBySequence().map { it.sequence })
    }

    @Test fun stdioLaunchApprovalPersistsOnlyServerReferenceAndClaimsOnlyAfterDecision() = runBlocking {
        val configHash = "a".repeat(64)
        val request = approvals.createMcpLaunchRequest("server-id", "Private server", configHash)
        assertEquals("PENDING", request.status)
        assertEquals("mcp_server_launch", request.toolName)
        assertTrue(request.action.contains("server-id"))
        val call = db.toolExecutionDao().get(request.taskId, request.callId)!!
        assertFalse(call.argumentsJson.contains("secret"))
        assertEquals("NEEDS_APPROVAL", call.state)
        assertEquals("AWAITING_APPROVAL", db.taskDao().getById(request.taskId)?.status)
        val allowed = approvals.decide(request.id, true, "ALLOW_ONCE")
        assertEquals("APPROVED", allowed.status)
        val (claimed, operation) = approvals.claimApproved(request.id)
        assertEquals("EXECUTING", claimed.status)
        assertEquals("RUNNING", operation.state)
    }

    @Test fun duplicateApprovalCreationReturnsSamePendingRequest() = runBlocking {
        prepareCall()
        val first = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        val second = approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Duplicate submission", "hash")
        assertEquals(first.id, second.id)
        assertEquals(1, approvals.observePending().first().size)
    }

    @Test fun duplicateCallIdentityCannotChangeApprovedActionOrArguments() = runBlocking {
        prepareCall()
        approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Create source file", "hash")
        assertTrue(runCatching {
            approvals.request("task", "call", "builder", "write_file", "write", "src/a.kt", "MEDIUM", "Changed content", "different-hash")
        }.isFailure)
        assertTrue(runCatching {
            approvals.request("task", "call", "builder", "run_command", "execute", "command", "HIGH", "Changed tool", "hash")
        }.isFailure)
    }

    private suspend fun prepareCall() {
        tasks.create(TaskEntity("task", "session", TaskStatus.QUEUED.name, "[]", "Queued", 1, 1))
        tasks.transition("task", TaskStatus.STARTING, "Starting")
        tasks.transition("task", TaskStatus.WAITING_FOR_MODEL, "Model")
        tasks.transition("task", TaskStatus.WAITING_FOR_TOOL, "Tool")
        tasks.prepareToolCall("task", "call", "write_file", "{\"path\":\"src/a.kt\"}", "hash", false)
        tasks.startToolCall("task", "call")
    }
}
