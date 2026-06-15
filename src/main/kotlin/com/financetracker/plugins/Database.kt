package com.financetracker.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

private lateinit var dataSource: HikariDataSource

fun Application.configureDatabase() {
    val config = environment.config
    val hikariConfig =
        HikariConfig().apply {
            jdbcUrl = config.property("database.url").getString()
            username = config.property("database.user").getString()
            password = config.property("database.password").getString()
            maximumPoolSize = config.property("database.poolSize").getString().toInt()
            driverClassName = "org.postgresql.Driver"
        }
    dataSource = HikariDataSource(hikariConfig)

    Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()

    Database.connect(dataSource)
}

suspend fun pingDatabase(): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            transaction { exec("SELECT 1") }
        }.isSuccess
    }
