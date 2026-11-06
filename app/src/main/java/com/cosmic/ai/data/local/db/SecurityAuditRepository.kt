package com.cosmic.ai.data.local.db

import androidx.room.withTransaction
import com.cosmic.ai.data.local.db.entity.SecurityAuditEventEntity
import com.cosmic.ai.security.SensitiveTextRedactor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Transactional SHA-256 audit chain with an optional independently stored head checkpoint. */
interface AuditHeadAnchor {
    data class Head(val sequence: Long, val hash: String)
    suspend fun read(): Head?
    suspend fun write(head: Head)
}

class SecurityAuditRepository(
    private val db: CosmicDatabase,
    private val headAnchor: AuditHeadAnchor? = installedDefaultAnchor
) {
    suspend fun record(
        requestId: String?, taskId: String?, agentId: String?, toolName: String,
        action: String, resource: String, decision: String, outcome: String? = null, error: String? = null
    ) = append(SecurityAuditEventEntity(
        eventId = UUID.randomUUID().toString(), requestId = requestId, taskId = taskId,
        agentId = agentId?.take(80), toolName = toolName.take(120), action = action.take(120),
        resourceScope = SensitiveTextRedactor.redact(resource).take(300), decision = decision.take(64),
        occurredAt = System.currentTimeMillis(), outcome = outcome?.let { SensitiveTextRedactor.redact(it).take(250) },
        sanitizedError = error?.let { SensitiveTextRedactor.redact(it).take(300) }
    ))

    suspend fun append(event: SecurityAuditEventEntity) {
        val written = db.withTransaction {
            sealLegacyRowsIfNeeded()
            val safeEvent = event.copy(
                agentId = event.agentId?.let { SensitiveTextRedactor.redact(it).take(80) },
                toolName = SensitiveTextRedactor.redact(event.toolName).take(120),
                action = SensitiveTextRedactor.redact(event.action).take(120),
                resourceScope = SensitiveTextRedactor.redact(event.resourceScope).take(300),
                decision = SensitiveTextRedactor.redact(event.decision).take(64),
                outcome = event.outcome?.let { SensitiveTextRedactor.redact(it).take(250) },
                sanitizedError = event.sanitizedError?.let { SensitiveTextRedactor.redact(it).take(300) }
            )
            val previous = db.securityAuditDao().latest()
            val next = safeEvent.copy(
                sequence = (previous?.sequence ?: 0L) + 1L,
                previousHash = previous?.eventHash ?: GENESIS,
                eventHash = ""
            )
            val sealed = next.copy(eventHash = hash(next))
            db.securityAuditDao().insert(sealed)
            sealed
        }
        // This checkpoint is outside the Room transaction for top-level writes. Nested callers may
        // still be inside their parent transaction; startup verification reconciles that narrow gap.
        headAnchor?.write(AuditHeadAnchor.Head(written.sequence, written.eventHash))
    }

    /** Seals pre-v4 rows in deterministic order once, validates the chain and reconciles the external head. */
    suspend fun verifyIntegrity(): AuditIntegrity = db.withTransaction {
        val result = verifyOrSealLegacy()
        if (result.valid) verifyAndAdvanceAnchor()
        result
    }

    private suspend fun sealLegacyRowsIfNeeded() {
        val result = verifyOrSealLegacy()
        check(result.valid) { "Security audit integrity check failed at sequence ${result.firstInvalidSequence}" }
        verifyAndAdvanceAnchor()
    }

    private suspend fun verifyOrSealLegacy(): AuditIntegrity {
        val dao = db.securityAuditDao()
        val rows = dao.allChronologically()
        if (rows.isEmpty()) return AuditIntegrity(true, 0, null)
        val legacy = rows.any { it.eventHash.isBlank() || it.previousHash.isBlank() || it.sequence <= 0L }
        if (legacy) {
            // Do not silently overwrite a partially chained or inconsistent ledger.
            require(rows.all { it.eventHash.isBlank() && it.previousHash.isBlank() && it.sequence == 0L }) {
                "Audit ledger contains a partially sealed chain"
            }
            var seq = 0L
            var prev = GENESIS
            rows.forEach { row ->
                seq++
                // Normalize old entries before sealing so credentials found in legacy fields are removed,
                // and the digest authenticates the exact persisted canonical values.
                val sanitized = row.copy(
                    agentId = row.agentId?.let { SensitiveTextRedactor.redact(it).take(80) },
                    toolName = SensitiveTextRedactor.redact(row.toolName).take(120),
                    action = SensitiveTextRedactor.redact(row.action).take(120),
                    resourceScope = SensitiveTextRedactor.redact(row.resourceScope).take(300),
                    decision = SensitiveTextRedactor.redact(row.decision).take(64),
                    outcome = row.outcome?.let { SensitiveTextRedactor.redact(it).take(250) },
                    sanitizedError = row.sanitizedError?.let { SensitiveTextRedactor.redact(it).take(300) }
                )
                val sealed = sanitized.copy(sequence = seq, previousHash = prev, eventHash = "")
                val digest = hash(sealed)
                check(dao.update(sealed.copy(eventHash = digest)) == 1) { "Could not seal legacy audit event" }
                prev = digest
            }
        }
        val chain = dao.allBySequence()
        var expectedPrevious = GENESIS
        var expectedSequence = 1L
        for (row in chain) {
            if (row.sequence != expectedSequence || row.previousHash != expectedPrevious || row.eventHash != hash(row.copy(eventHash = ""))) {
                return AuditIntegrity(false, chain.size, row.sequence)
            }
            expectedPrevious = row.eventHash
            expectedSequence++
        }
        return AuditIntegrity(true, chain.size, null)
    }

    private suspend fun verifyAndAdvanceAnchor() {
        val anchor = headAnchor ?: return
        val rows = db.securityAuditDao().allBySequence()
        val current = rows.lastOrNull()?.let { AuditHeadAnchor.Head(it.sequence, it.eventHash) }
            ?: AuditHeadAnchor.Head(0L, GENESIS)
        val stored = anchor.read()
        if (stored == null) {
            anchor.write(current) // First install/legacy upgrade: establishes the initial local checkpoint.
            return
        }
        require(stored.sequence >= 0 && stored.hash.isNotBlank()) { "Audit head checkpoint is malformed" }
        if (stored.sequence > current.sequence) {
            throw IllegalStateException("Audit head checkpoint is ahead of the database; tail deletion or rollback detected")
        }
        val anchoredHash = if (stored.sequence == 0L) GENESIS else rows.getOrNull((stored.sequence - 1).toInt())?.eventHash
        if (anchoredHash != stored.hash) {
            throw IllegalStateException("Audit head checkpoint does not match the database chain at sequence ${stored.sequence}")
        }
        if (current.sequence > stored.sequence) {
            // Handles a process death after Room commit but before the checkpoint write, provided
            // the previous anchored event is still present and links into the verified chain.
            anchor.write(current)
        }
    }

    private fun hash(event: SecurityAuditEventEntity): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            listOf(
                event.sequence.toString(), event.previousHash, event.eventId, event.requestId.orEmpty(),
                event.taskId.orEmpty(), event.agentId.orEmpty(), event.toolName, event.action,
                event.resourceScope, event.decision,
                event.occurredAt.toString(), event.outcome.orEmpty(), event.sanitizedError.orEmpty()
            ).forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    data class AuditIntegrity(val valid: Boolean, val verifiedEvents: Int, val firstInvalidSequence: Long?)

    companion object {
        private const val GENESIS = "COSMIC-CODEX-AUDIT-GENESIS-v1"
        @Volatile private var installedDefaultAnchor: AuditHeadAnchor? = null
        fun installDefaultAnchor(anchor: AuditHeadAnchor) { installedDefaultAnchor = anchor }
    }
}
