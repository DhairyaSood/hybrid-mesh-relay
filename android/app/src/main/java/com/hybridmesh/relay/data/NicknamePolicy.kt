package com.hybridmesh.relay.data

/**
 * Single source of truth for the public username shown to nearby Neyra nodes.
 *
 * Policy:
 * - 1..50 ASCII characters
 * - letters, digits, and the explicitly supported common punctuation:
 *   _ . - @ & $ ! # ^ ~
 *
 * Keeping this ASCII-only makes the value deterministic across UI, persistence,
 * BLE advertisement encoding, logs, and future transports.
 */
object NicknamePolicy {
    const val MAX_LENGTH = 50

    private val allowedPattern = Regex("[A-Za-z0-9_.@&$!#^~-]+")

    fun isValid(value: String): Boolean =
        value.length in 1..MAX_LENGTH && allowedPattern.matches(value)

    /**
     * Canonical user input. Leading/trailing whitespace is never part of an
     * identity; internal whitespace remains invalid.
     */
    fun clean(value: String): String =
        value.trim().take(MAX_LENGTH)

    fun errorMessage(value: String): String? {
        val cleaned = clean(value)
        return when {
            cleaned.isEmpty() -> "Enter a username."
            value.trim().length > MAX_LENGTH -> "Username must be at most $MAX_LENGTH characters."
            !allowedPattern.matches(cleaned) ->
                "Use only letters, numbers, _ . - @ & \$ ! # ^ ~."
            else -> null
        }
    }
}
