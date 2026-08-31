package com.financetracker.service

import com.financetracker.model.RegisterResult
import com.financetracker.redis.RedisClient
import com.financetracker.repository.CategoryRepository
import com.financetracker.repository.RefreshTokenRepository
import com.financetracker.repository.UserRepository
import com.financetracker.repository.UserRow
import com.financetracker.util.PasswordUtil
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertIs

class AuthServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val refreshTokenRepository = mockk<RefreshTokenRepository>()
    private val redisClient = mockk<RedisClient>()
    private val categoryRepository = mockk<CategoryRepository>()

    private val service: AuthService =
        AuthServiceImpl(userRepository, refreshTokenRepository, redisClient, categoryRepository)

    @BeforeEach
    fun setUp() {
        mockkObject(PasswordUtil)
        every { PasswordUtil.hash(any()) } returns "hashed"

        mockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        // Exposed 0.57: transaction(db: Database? = null, statement: Transaction.() -> T)
        every { transaction(isNull<org.jetbrains.exposed.sql.Database>(), any<Transaction.() -> Any>()) } answers {
            secondArg<Transaction.() -> Any>().invoke(mockk(relaxed = true))
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `register seeds exactly the categories for a newly created user`() =
        runBlocking {
            val userId = UUID.randomUUID()
            every { userRepository.findByEmail("new@example.com") } returns null
            every { userRepository.insert("new@example.com", "hashed") } returns userId
            justRun { categoryRepository.seedDefaults(userId) }

            val result = service.register("new@example.com", "password123")

            assertIs<RegisterResult.Success>(result)
            verify(exactly = 1) { categoryRepository.seedDefaults(userId) }
        }

    @Test
    fun `register does not seed categories when email already exists`() =
        runBlocking {
            every { userRepository.findByEmail("dup@example.com") } returns
                UserRow(UUID.randomUUID(), "dup@example.com", "hash")

            val result = service.register("dup@example.com", "password123")

            assertIs<RegisterResult.EmailAlreadyRegistered>(result)
            verify(exactly = 0) { categoryRepository.seedDefaults(any()) }
        }

    @Test
    fun `register returns InvalidEmail for malformed address without touching DB`() =
        runBlocking {
            val result = service.register("not-an-email", "password123")

            assertIs<RegisterResult.InvalidEmail>(result)
            verify(exactly = 0) { userRepository.findByEmail(any()) }
            verify(exactly = 0) { categoryRepository.seedDefaults(any()) }
        }

    @Test
    fun `register returns PasswordTooShort without touching DB`() =
        runBlocking {
            val result = service.register("user@example.com", "short")

            assertIs<RegisterResult.PasswordTooShort>(result)
            verify(exactly = 0) { categoryRepository.seedDefaults(any()) }
        }
}
