package com.financetracker.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterResponse(val userId: String, val email: String)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class ErrorResponse(val error: String)

sealed interface RegisterResult {
    data class Success(val response: RegisterResponse) : RegisterResult
    data object EmailAlreadyRegistered : RegisterResult
    data object InvalidEmail : RegisterResult
    data object PasswordTooShort : RegisterResult
}

sealed interface LoginResult {
    data class Success(val response: LoginResponse) : LoginResult
    data object InvalidCredentials : LoginResult
}
