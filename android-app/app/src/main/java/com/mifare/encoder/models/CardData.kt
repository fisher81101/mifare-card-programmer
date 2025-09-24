package com.mifare.encoder.models

data class CardData(
    var uid: String = "",
    var cardType: String = "",
    var size: String = "",
    var sectors: List<String> = emptyList(),
    var sectorData: Map<Int, String> = emptyMap(),
    var saltoData: SaltoData? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var rawHexData: String = ""
)

data class SaltoData(
    var userId: String = "",
    var accessRights: List<String> = emptyList(),
    var doorList: List<String> = emptyList(),
    var accessDoors: List<String>? = null,
    var startTimestamp: String = "",
    var endTimestamp: String = "",
    var validFrom: String? = null,
    var validUntil: String? = null,
    var cardStatus: String? = null,
    var issuerId: String? = null,
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

