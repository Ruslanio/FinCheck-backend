package com.financetracker.repository

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

object RefreshTokens : Table("refresh_tokens") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id")
    val tokenHash = text("token_hash").uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

data class RefreshTokenRow(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
)

object RefreshTokenRepository {
    fun insert(
        userId: UUID,
        tokenHash: String,
        expiresAt: Instant,
    ) {
        RefreshTokens.insert {
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.tokenHash] = tokenHash
            it[RefreshTokens.expiresAt] = expiresAt
        }
    }

    fun findActiveByTokenHash(tokenHash: String): RefreshTokenRow? {
        val now = Clock.System.now()
        return RefreshTokens.selectAll()
            .where {
                (RefreshTokens.tokenHash eq tokenHash) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }
            .singleOrNull()
            ?.let {
                RefreshTokenRow(
                    id = it[RefreshTokens.id],
                    userId = it[RefreshTokens.userId],
                    tokenHash = it[RefreshTokens.tokenHash],
                    expiresAt = it[RefreshTokens.expiresAt],
                )
            }
    }

    fun findAnyByTokenHash(tokenHash: String): RefreshTokenRow? =
        RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq tokenHash }
            .singleOrNull()
            ?.let {
                RefreshTokenRow(
                    id = it[RefreshTokens.id],
                    userId = it[RefreshTokens.userId],
                    tokenHash = it[RefreshTokens.tokenHash],
                    expiresAt = it[RefreshTokens.expiresAt],
                    revokedAt = it[RefreshTokens.revokedAt],
                )
            }

    fun revokeAndInsert(
        oldTokenHash: String,
        userId: UUID,
        newTokenHash: String,
        newExpiresAt: Instant,
    ): Boolean =
        transaction {
            val updated =
                RefreshTokens.update({
                    (RefreshTokens.tokenHash eq oldTokenHash) and
                        RefreshTokens.revokedAt.isNull() and
                        (RefreshTokens.expiresAt greater Clock.System.now())
                }) {
                    it[revokedAt] = Clock.System.now()
                }
            if (updated == 0) return@transaction false
            RefreshTokens.insert {
                it[RefreshTokens.userId] = userId
                it[RefreshTokens.tokenHash] = newTokenHash
                it[RefreshTokens.expiresAt] = newExpiresAt
            }
            true
        }

    fun revoke(tokenHash: String) {
        RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) {
            it[revokedAt] = Clock.System.now()
        }
    }
}
