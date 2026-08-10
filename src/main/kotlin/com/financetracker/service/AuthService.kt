package com.financetracker.service

import com.financetracker.model.LoginResponse
import com.financetracker.model.LoginResult
import com.financetracker.model.RegisterResponse
import com.financetracker.model.RegisterResult
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

        return LoginResult.Success(
            LoginResponse(
                accessToken = accessToken,
                refreshToken = rawRefreshToken,
                expiresIn = 900,
            ),
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
