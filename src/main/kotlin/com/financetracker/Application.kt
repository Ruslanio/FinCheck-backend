package com.financetracker

import com.financetracker.plugins.configureDatabase
import com.financetracker.plugins.configureRouting
import com.financetracker.plugins.configureSerialization
import io.ktor.server.application.Application

fun Application.module() {
    configureDatabase()
    configureSerialization()
    configureRouting()
}
