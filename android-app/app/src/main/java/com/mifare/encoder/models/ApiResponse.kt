package com.mifare.encoder.models

/**
 * API Response model for backend communication
 */
data class ApiResponse(
    val success: Boolean = false,
    val message: String = "",
    val cardData: String = "",
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)