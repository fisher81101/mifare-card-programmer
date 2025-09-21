package com.mifare.encoder.models

data class CardData(
    var uid: String = "",
    var type: String = "",
    var size: String = "",
    var sectors: List<String> = emptyList(),
    var saltoData: SaltoData? = null
)

data class SaltoData(
    var userId: String = "",
    var accessRights: List<String> = emptyList(),
    var doorList: List<String> = emptyList(),
    var startTimestamp: String = "",
    var endTimestamp: String = "",
    var checksum: String = "",
    var rawData: Map<String, String> = emptyMap(),
    var isValid: Boolean = false
)

data class CustomCardConfig(
    var userId: String = "",
    var accessDoors: String = "",
    var startDate: String = "",
    var endDate: String = "",
    var notes: String = "",
    var additionalData: Map<String, String> = emptyMap()
)

data class RemoteConfig(
    var backendUrl: String = "",
    var apiKey: String = "",
    var isConnected: Boolean = false,
    var lastSync: String = ""
)

data class ApiResponse(
    var success: Boolean = false,
    var message: String = "",
    var data: ByteArray? = null,
    var cardData: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ApiResponse

        if (success != other.success) return false
        if (message != other.message) return false
        if (data != null) {
            if (other.data == null) return false
            if (!data.contentEquals(other.data)) return false
        } else if (other.data != null) return false
        if (cardData != other.cardData) return false

        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + cardData.hashCode()
        return result
    }
}