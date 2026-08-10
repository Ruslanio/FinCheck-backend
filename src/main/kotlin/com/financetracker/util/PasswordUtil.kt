package com.financetracker.util

import at.favre.lib.crypto.bcrypt.BCrypt

object PasswordUtil {
    fun hash(plain: String): String =
        BCrypt.withDefaults().hashToString(12, plain.toCharArray())

    fun verify(plain: String, hash: String): Boolean =
        BCrypt.verifyer().verify(plain.toCharArray(), hash).verified
}
