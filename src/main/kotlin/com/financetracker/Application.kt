package com.financetracker

import com.financetracker.plugins.configureDatabase
import com.financetracker.plugins.configureRedis
import com.financetracker.plugins.configureRouting
import com.financetracker.plugins.configureSerialization
import io.ktor.server.application.Application

fun Application.module() {
    configureDatabase()
    configureRedis()
    configureSerialization()
    // configureRateLimit() — Task 14
    // configureAuth() — Task 11
    configureRouting()
}
