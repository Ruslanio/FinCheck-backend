package com.financetracker

import com.financetracker.plugins.configureAuth
import com.financetracker.plugins.configureDatabase
import com.financetracker.plugins.configureRouting
import com.financetracker.plugins.configureSerialization
import com.financetracker.plugins.createRedisPool
import com.financetracker.redis.RedisClient
import com.financetracker.repository.RefreshTokenRepository
import com.financetracker.repository.RefreshTokenRepositoryImpl
import com.financetracker.repository.TransactionRepository
import com.financetracker.repository.TransactionRepositoryImpl
import com.financetracker.repository.UserRepository
import com.financetracker.repository.UserRepositoryImpl
import com.financetracker.routing.configureAuthRouting
import com.financetracker.routing.configureTransactionRouting
import com.financetracker.service.AuthService
import com.financetracker.service.AuthServiceImpl
import com.financetracker.service.TransactionService
import com.financetracker.service.TransactionServiceImpl
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

fun Application.module() {
    configureDatabase()

    val jedisPool = createRedisPool()

    install(Koin) {
        modules(
            module {
                single<UserRepository> { UserRepositoryImpl() }
                single<RefreshTokenRepository> { RefreshTokenRepositoryImpl() }
                single<TransactionRepository> { TransactionRepositoryImpl() }
                single { RedisClient(jedisPool) }
                single<AuthService> { AuthServiceImpl(get(), get(), get()) }
                single<TransactionService> { TransactionServiceImpl(get()) }
            },
        )
    }

    configureSerialization()
    // configureRateLimit() — Task 14
    configureAuth()
    configureAuthRouting(get<AuthService>())
    configureTransactionRouting(get<TransactionService>())
    configureRouting()
}
