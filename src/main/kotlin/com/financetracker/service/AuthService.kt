package com.financetracker.service

import com.financetracker.model.LoginResponse
import com.financetracker.model.LoginResult
import com.financetracker.model.RegisterResponse
import com.financetracker.model.RegisterResult
import com.financetracker.model.SessionData
import com.financetracker.redis.RedisClient
import com.financetracker.repository.RefreshTokenRepository
import com.financetracker.repository.UserRepository
import com.financetracker.util.JwtUtil
import com.financetracker.util.PasswordUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.days

private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

sealed interface RefreshResult {
    data class Success(val accessToken: String, val refreshToken: String) : RefreshResult

    data object Revoked : RefreshResult

    data object Expired : RefreshResult

    data object Invalid : RefreshResult
}

sealed interface LogoutResult {
    data object Success : LogoutResult

    data object AlreadyRevoked : LogoutResult

    data object Invalid : LogoutResult
}

open class AuthService(
    private val userRepository: UserRepository = UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository = RefreshTokenRepository,
) {
    open suspend fun register(
        email: String,
        password: String,
    ): RegisterResult {
        if (!EMAIL_REGEX.matches(email)) return RegisterResult.InvalidEmail
        if (password.length < 8) return RegisterResult.PasswordTooShort

        val hash =
            withContext(Dispatchers.Default) {
                PasswordUtil.hash(password)
            }

        return withContext(Dispatchers.IO) {
            transaction {
                if (userRepository.findByEmail(email) != null) {
                    return@transaction RegisterResult.EmailAlreadyRegistered
                }
                val userId = userRepository.insert(email, hash)
                RegisterResult.Success(RegisterResponse(userId.toString(), email))
            }
        }
    }

    open suspend fun login(
        email: String,
        password: String,
    ): LoginResult {
        val user =
            withContext(Dispatchers.IO) {
                transaction { userRepository.findByEmail(email) }
            } ?: return LoginResult.InvalidCredentials

        val passwordValid =
            withContext(Dispatchers.Default) {
                PasswordUtil.verify(password, user.passwordHash)
            }
        if (!passwordValid) return LoginResult.InvalidCredentials

        val accessToken = JwtUtil.issueAccessToken(user.id.toString())
        val rawRefreshToken = UUID.randomUUID().toString()
        val tokenHash = sha256(rawRefreshToken)
        val expiresAt = Clock.System.now() + 30.days

        withContext(Dispatchers.IO) {
            transaction {
                refreshTokenRepository.insert(user.id, tokenHash, expiresAt)
            }
        }

        withContext(Dispatchers.IO) {
            val sessionData =
                SessionData(
                    userId = user.id.toString(),
                    email = user.email,
                    createdAt = Clock.System.now().toString(),
                )
            RedisClient.setSession(token = accessToken, data = sessionData)
        }

        return LoginResult.Success(
            LoginResponse(
                accessToken = accessToken,
                refreshToken = rawRefreshToken,
                expiresIn = 900,
            ),
        )
    }

    open suspend fun refresh(rawToken: String): RefreshResult {
        val hash = sha256(rawToken)
        val tokenRow =
            withContext(Dispatchers.IO) {
                transaction { refreshTokenRepository.findAnyByTokenHash(hash) }
            }

        return when {
            tokenRow == null -> RefreshResult.Invalid
            tokenRow.revokedAt != null -> RefreshResult.Revoked
            tokenRow.expiresAt <= Clock.System.now() -> RefreshResult.Expired
            else -> {
                val newRawToken = UUID.randomUUID().toString()
                val newHash = sha256(newRawToken)
                val newExpiresAt = Clock.System.now() + 30.days

                val success =
                    withContext(Dispatchers.IO) {
                        refreshTokenRepository.revokeAndInsert(hash, tokenRow.userId, newHash, newExpiresAt)
                    }
                if (!success) return RefreshResult.Revoked

                val accessToken = JwtUtil.issueAccessToken(tokenRow.userId.toString())
                RefreshResult.Success(accessToken, newRawToken)
            }
        }
    }

    open suspend fun logout(
        refreshToken: String,
        accessToken: String,
    ): LogoutResult {
        val hash = sha256(refreshToken)
        val activeToken =
            withContext(Dispatchers.IO) {
                transaction { refreshTokenRepository.findActiveByTokenHash(hash) }
            }

        if (activeToken == null) {
            val anyRow =
                withContext(Dispatchers.IO) {
                    transaction { refreshTokenRepository.findAnyByTokenHash(hash) }
                }
            return if (anyRow?.revokedAt != null) LogoutResult.AlreadyRevoked else LogoutResult.Invalid
        }

        withContext(Dispatchers.IO) {
            transaction { refreshTokenRepository.revoke(hash) }
        }
        withContext(Dispatchers.IO) {
            RedisClient.deleteSession(accessToken)
        }
        return LogoutResult.Success
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
