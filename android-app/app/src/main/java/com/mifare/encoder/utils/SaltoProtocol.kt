package com.mifare.encoder.utils

import android.util.Log
import com.mifare.encoder.models.CustomCardConfig
import com.mifare.encoder.models.SaltoData
import java.util.*

object SaltoProtocol {
    
    private const val TAG = "SaltoProtocol"
    
    // Salto protocol assumptions based on common MIFARE access control patterns
    private const val MAD_SECTOR = 0
    private const val USER_DATA_SECTOR = 1
    private const val ACCESS_LIST_START_SECTOR = 2
    private const val ACCESS_LIST_END_SECTOR = 4
    private const val TIMESTAMP_SECTOR = 5
    private const val CHECKSUM_SECTOR = 15 // Usually last sector
    
    /**
     * Analyze card data to extract Salto protocol information
     */
    fun analyzeCard(sectorData: List<String>): SaltoData {
        val saltoData = SaltoData()
        val rawData = mutableMapOf<String, String>()
        
        try {
            // Process each sector
            for ((index, sector) in sectorData.withIndex()) {
                val sectorHex = sector.substringAfter(": ")
                if (sectorHex == "Authentication failed") continue
                
                val sectorBytes = MifareUtils.hexToBytes(sectorHex)
                rawData["sector_$index"] = sectorHex
                
                when (index) {
                    MAD_SECTOR -> {
                        // Sector 0 - MAD (MIFARE Application Directory)
                        analyzeMadSector(sectorBytes, saltoData)
                    }
                    USER_DATA_SECTOR -> {
                        // Sector 1 - User data
                        analyzeUserDataSector(sectorBytes, saltoData)
                    }
                    in ACCESS_LIST_START_SECTOR..ACCESS_LIST_END_SECTOR -> {
                        // Sectors 2-4 - Access lists
                        analyzeAccessListSector(sectorBytes, saltoData, index)
                    }
                    TIMESTAMP_SECTOR -> {
                        // Sector 5 - Timestamps
                        analyzeTimestampSector(sectorBytes, saltoData)
                    }
                    CHECKSUM_SECTOR -> {
                        // Last sector - Checksum/validation
                        analyzeChecksumSector(sectorBytes, saltoData)
                    }
                }
            }
            
            saltoData.rawData = rawData
            saltoData.isValid = validateSaltoData(saltoData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing card data", e)
            saltoData.isValid = false
        }
        
        return saltoData
    }
    
    /**
     * Analyze MAD sector (sector 0)
     */
    private fun analyzeMadSector(data: ByteArray, saltoData: SaltoData) {
        if (data.size < 16) return
        
        // Look for Salto application identifiers
        val madInfo = MifareUtils.extractReadableText(data)
        if (madInfo.contains("SALTO", ignoreCase = true)) {
            Log.d(TAG, "Salto MAD detected: $madInfo")
        }
    }
    
    /**
     * Analyze user data sector
     */
    private fun analyzeUserDataSector(data: ByteArray, saltoData: SaltoData) {
        if (data.size < 16) return
        
        try {
            // Assume user ID is in first 4 bytes
            val userId = MifareUtils.bytesToInt(data, 0, 4)
            if (userId > 0 && userId < 0xFFFFFF) { // Reasonable user ID range
                saltoData.userId = userId.toString()
            }
            
            // Look for readable text that might be user info
            val userText = MifareUtils.extractReadableText(data)
            if (userText.isNotEmpty()) {
                Log.d(TAG, "User data text: $userText")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing user data sector", e)
        }
    }
    
    /**
     * Analyze access list sectors
     */
    private fun analyzeAccessListSector(data: ByteArray, saltoData: SaltoData, sectorIndex: Int) {
        if (data.size < 16) return
        
        try {
            val doorList = mutableListOf<String>()
            
            // Parse door/room access rights (assume each door is 2 bytes)
            for (i in data.indices step 2) {
                if (i + 1 < data.size) {
                    val doorId = MifareUtils.bytesToInt(data, i, 2)
                    if (doorId > 0 && doorId < 9999) { // Reasonable door ID range
                        doorList.add(doorId.toString())
                    }
                }
            }
            
            if (doorList.isNotEmpty()) {
                saltoData.doorList = saltoData.doorList + doorList
            }
            
            // Look for access level flags
            val accessFlags = data[data.size - 1].toInt() and 0xFF
            if (accessFlags != 0) {
                val accessRights = mutableListOf<String>()
                if (accessFlags and 0x01 != 0) accessRights.add("ENTRY")
                if (accessFlags and 0x02 != 0) accessRights.add("EXIT")
                if (accessFlags and 0x04 != 0) accessRights.add("ADMIN")
                if (accessFlags and 0x08 != 0) accessRights.add("MAINTENANCE")
                
                saltoData.accessRights = saltoData.accessRights + accessRights
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing access list sector $sectorIndex", e)
        }
    }
    
    /**
     * Analyze timestamp sector
     */
    private fun analyzeTimestampSector(data: ByteArray, saltoData: SaltoData) {
        if (data.size < 16) return
        
        try {
            // Assume start timestamp in first 4 bytes
            val startTime = MifareUtils.bytesToTimestamp(data, 0)
            if (startTime != null) {
                saltoData.startTimestamp = MifareUtils.formatISODate(startTime)
            }
            
            // Assume end timestamp in next 4 bytes
            val endTime = MifareUtils.bytesToTimestamp(data, 4)
            if (endTime != null) {
                saltoData.endTimestamp = MifareUtils.formatISODate(endTime)
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing timestamp sector", e)
        }
    }
    
    /**
     * Analyze checksum sector
     */
    private fun analyzeChecksumSector(data: ByteArray, saltoData: SaltoData) {
        if (data.size < 16) return
        
        try {
            // Assume checksum in last 2 bytes
            val checksum = MifareUtils.bytesToInt(data, data.size - 2, 2)
            saltoData.checksum = String.format("%04X", checksum)
            
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing checksum sector", e)
        }
    }
    
    /**
     * Validate extracted Salto data
     */
    private fun validateSaltoData(saltoData: SaltoData): Boolean {
        return saltoData.userId.isNotEmpty() &&
               saltoData.doorList.isNotEmpty() &&
               saltoData.startTimestamp.isNotEmpty() &&
               saltoData.endTimestamp.isNotEmpty()
    }
    
    /**
     * Generate custom card data based on user input
     */
    fun generateCustomCardData(config: CustomCardConfig): ByteArray {
        val cardData = ByteArray(1024) // 1K card size
        var offset = 0
        
        try {
            // Skip sector 0 (manufacturer data)
            offset = 64 // Start at sector 1
            
            // Sector 1: User data
            val userId = config.userId.toIntOrNull() ?: 1
            System.arraycopy(MifareUtils.intToBytes(userId), 0, cardData, offset, 4)
            offset += 16 // Next block
            
            // Sectors 2-4: Access lists
            val doors = config.accessDoors.split(",").mapNotNull { it.trim().toIntOrNull() }
            for (door in doors.take(24)) { // Max 24 doors across 3 sectors
                System.arraycopy(MifareUtils.intToBytes(door, 2), 0, cardData, offset, 2)
                offset += 2
                if (offset % 16 == 0) offset += 16 // Skip to next block after each 16 bytes
            }
            
            // Align to sector 5 (timestamp sector)
            offset = 80 // Sector 5 start
            
            // Timestamps
            val startDate = MifareUtils.parseISODate(config.startDate)
            if (startDate != null) {
                System.arraycopy(MifareUtils.timestampToBytes(startDate), 0, cardData, offset, 4)
            }
            offset += 4
            
            val endDate = MifareUtils.parseISODate(config.endDate)
            if (endDate != null) {
                System.arraycopy(MifareUtils.timestampToBytes(endDate), 0, cardData, offset, 4)
            }
            
            // Calculate and add checksum at the end (sector 15)
            val checksumData = cardData.sliceArray(0 until 1008) // All data except checksum sector
            val checksum = MifareUtils.calculateCRC16(checksumData)
            val checksumOffset = 1008 // Last sector
            System.arraycopy(MifareUtils.intToBytes(checksum, 2), 0, cardData, checksumOffset, 2)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating custom card data", e)
        }
        
        return cardData
    }
    
    /**
     * Validate custom configuration
     */
    fun validateCustomConfig(config: CustomCardConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (config.userId.isEmpty() || config.userId.toIntOrNull() == null) {
            errors.add("User ID must be a valid number")
        }
        
        if (config.accessDoors.isEmpty()) {
            errors.add("Access doors cannot be empty")
        } else {
            val doors = config.accessDoors.split(",")
            for (door in doors) {
                if (door.trim().toIntOrNull() == null) {
                    errors.add("Invalid door number: ${door.trim()}")
                }
            }
        }
        
        if (config.startDate.isEmpty() || MifareUtils.parseISODate(config.startDate) == null) {
            errors.add("Start date must be in ISO format (YYYY-MM-DDTHH:MM:SSZ)")
        }
        
        if (config.endDate.isEmpty() || MifareUtils.parseISODate(config.endDate) == null) {
            errors.add("End date must be in ISO format (YYYY-MM-DDTHH:MM:SSZ)")
        }
        
        // Validate date order
        val startDate = MifareUtils.parseISODate(config.startDate)
        val endDate = MifareUtils.parseISODate(config.endDate)
        if (startDate != null && endDate != null && startDate.after(endDate)) {
            errors.add("Start date must be before end date")
        }
        
        // Validate future dates
        val now = Date()
        if (endDate != null && endDate.before(now)) {
            errors.add("End date must be in the future")
        }
        
        return errors
    }
}