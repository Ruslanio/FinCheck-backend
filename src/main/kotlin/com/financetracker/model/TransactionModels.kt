package com.financetracker.model

import com.financetracker.repository.TransactionRow
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
data class CreateTransactionRequest(
    val amount: Double,
    val categoryId: String,
    val description: String? = null,
    val idempotencyKey: String? = null,
    @Serializable(with = InstantSerializer::class)
    val occurredAt: Instant? = null,
)

@Serializable
data class TransactionResponse(
    val id: String,
    val userId: String,
    val amount: Double,
    val categoryId: String,
    val description: String?,
    val idempotencyKey: String?,
    @Serializable(with = InstantSerializer::class)
    val occurredAt: Instant,
)

@Serializable
data class PagedTransactionsResponse(
    val data: List<TransactionResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
)

fun TransactionRow.toResponse() =
    TransactionResponse(
        id = id.toString(),
        userId = userId.toString(),
        amount = amount.toDouble(),
        categoryId = categoryId.toString(),
        description = description,
        idempotencyKey = idempotencyKey,
        occurredAt = occurredAt,
    )
