package com.financetracker.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

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
    val dataSource = HikariDataSource(hikariConfig)

    val migrationsLocation =
        config.propertyOrNull("database.migrationsLocation")?.getString()
            ?: "classpath:db/migration"

    Flyway.configure()
        .dataSource(dataSource)
        .locations(migrationsLocation)
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
