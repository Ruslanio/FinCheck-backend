package com.financetracker.plugins

import com.financetracker.routing.authRoutes
import com.financetracker.routing.categoryRoutes
import com.financetracker.routing.healthRoutes
import com.financetracker.routing.transactionRoutes
import com.financetracker.service.AuthService
import com.financetracker.service.CategoryService
import com.financetracker.service.TransactionService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get

fun Application.configureRouting(
    authService: AuthService? = null,
    transactionService: TransactionService? = null,
    categoryService: CategoryService? = null,
    dbPing: suspend () -> Boolean = ::pingDatabase,
) {
    routing {
        healthRoutes(dbPing)
        if (authService != null) authRoutes(authService)
        if (transactionService != null) transactionRoutes(transactionService)
        if (categoryService != null) categoryRoutes(categoryService)
    }
}
