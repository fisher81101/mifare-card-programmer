package com.mifare.encoder.utils

import android.util.Log
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

object MifareUtils {
    
    private const val TAG = "MifareUtils"
    
    /**
     * Convert byte array to hexadecimal string
     */
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val result = StringBuilder(bytes.size * 2)
        
        for (byte in bytes) {
            val i = byte.toInt()
            result.append(hexChars[i shr 4 and 0x0F])
            result.append(hexChars[i and 0x0F])
        }
        
        return result.toString()
    }
    
    /**
     * Convert hexadecimal string to byte array
     */
    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace(":", "")
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + 
                          Character.digit(cleanHex[i + 1], 16)).toByte()
        }
        
        return data
    }
    
    /**
     * Calculate CRC16 checksum (common in RFID protocols)
     */
    fun calculateCRC16(data: ByteArray): Int {
        var crc = 0xFFFF
        
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (i in 0 until 8) {
                if (crc and 0x0001 != 0) {
                    crc = (crc shr 1) xor 0xA001
                } else {
                    crc = crc shr 1
                }
            }
        }
        
        return crc and 0xFFFF
    }
    
    /**
     * Calculate MD5 hash
     */
    fun calculateMD5(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data)
        return bytesToHex(digest)
    }
    
    /**
     * Extract integer from byte array at given offset
     */
    fun bytesToInt(bytes: ByteArray, offset: Int = 0, length: Int = 4): Int {
        val buffer = ByteBuffer.allocate(4)
        val dataLength = minOf(length, bytes.size - offset)
        
        if (dataLength <= 0) return 0
        
        // Fill with zeros first, then add actual data
        buffer.put(ByteArray(4 - dataLength))
        buffer.put(bytes, offset, dataLength)
        buffer.flip()
        
        return buffer.int
    }
    
    /**
     * Convert integer to byte array
     */
    fun intToBytes(value: Int, length: Int = 4): ByteArray {
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(value)
        val fullBytes = buffer.array()
        
        return if (length < 4) {
            fullBytes.sliceArray((4 - length) until 4)
        } else {
            fullBytes
        }
    }
    
    /**
     * Extract timestamp from byte array (assumes Unix timestamp format)
     */
    fun bytesToTimestamp(bytes: ByteArray, offset: Int = 0): Date? {
        return try {
            val timestamp = bytesToInt(bytes, offset).toLong()
            if (timestamp > 0) Date(timestamp * 1000) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse timestamp", e)
            null
        }
    }
    
    /**
     * Convert timestamp to byte array
     */
    fun timestampToBytes(date: Date): ByteArray {
        val timestamp = (date.time / 1000).toInt()
        return intToBytes(timestamp)
    }
    
    /**
     * Parse ISO 8601 date string to Date
     */
    fun parseISODate(dateString: String): Date? {
        return try {
            val calendar = Calendar.getInstance()
            // Simple ISO date parsing - could be enhanced with proper ISO parser
            val parts = dateString.split("T", "-", ":")
            if (parts.size >= 6) {
                calendar.set(
                    parts[0].toInt(), // year
                    parts[1].toInt() - 1, // month (0-based)
                    parts[2].toInt(), // day
                    parts[3].toInt(), // hour
                    parts[4].toInt(), // minute
                    parts[5].substringBefore('.').substringBefore('Z').toInt() // second
                )
                calendar.time
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ISO date: $dateString", e)
            null
        }
    }
    
    /**
     * Format date to ISO 8601 string
     */
    fun formatISODate(date: Date): String {
        val calendar = Calendar.getInstance()
        calendar.time = date
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }
    
    /**
     * Validate MIFARE Classic block data
     */
    fun isValidBlockData(data: ByteArray): Boolean {
        return data.size == 16 // MIFARE Classic block size
    }
    
    /**
     * Check if data contains potential text/ASCII content
     */
    fun containsReadableText(data: ByteArray): Boolean {
        var readableCount = 0
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            if (b in 32..126 || b == 9 || b == 10 || b == 13) { // Printable ASCII + common whitespace
                readableCount++
            }
        }
        return readableCount > data.size / 2 // More than half should be readable
    }
    
    /**
     * Extract readable text from byte array
     */
    fun extractReadableText(data: ByteArray): String {
        return String(data.filter { byte ->
            val b = byte.toInt() and 0xFF
            b in 32..126 || b == 9 || b == 10 || b == 13
        }.toByteArray()).trim()
    }
    
    /**
     * Write card data to MIFARE card (simplified implementation for user mode)
     */
    fun writeCardData(mifareCard: android.nfc.tech.MifareClassic, config: Map<String, String>): Boolean {
        return try {
            Log.d(TAG, "Writing card data to MIFARE card")
            
            // Extract configuration data
            val userId = config["userId"] ?: "default"
            val accessCode = config["accessCode"] ?: "1234"
            val validUntil = config["validUntil"] ?: formatISODate(java.util.Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000))
            
            // Create data block with user info
            val userData = "$userId:$accessCode:$validUntil"
            val dataBytes = userData.toByteArray()
            
            // Pad to 16 bytes (MIFARE block size)
            val paddedData = ByteArray(16)
            System.arraycopy(dataBytes, 0, paddedData, 0, minOf(dataBytes.size, 16))
            
            // Write to sector 1, block 1 (avoiding sector trailer)
            val sectorIndex = 1
            val blockIndex = mifareCard.sectorToBlock(sectorIndex) + 1
            
            // Authenticate with default key
            val defaultKey = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            
            if (mifareCard.authenticateSectorWithKeyA(sectorIndex, defaultKey)) {
                mifareCard.writeBlock(blockIndex, paddedData)
                Log.d(TAG, "Card data written successfully")
                true
            } else {
                Log.e(TAG, "Authentication failed for sector $sectorIndex")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write card data", e)
            false
        }
    }
    
    /**
     * Read card data from MIFARE card
     */
    fun readCardData(mifareCard: android.nfc.tech.MifareClassic): Map<String, String>? {
        return try {
            Log.d(TAG, "Reading card data from MIFARE card")
            
            val sectorIndex = 1
            val blockIndex = mifareCard.sectorToBlock(sectorIndex) + 1
            
            // Authenticate with default key
            val defaultKey = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            
            if (mifareCard.authenticateSectorWithKeyA(sectorIndex, defaultKey)) {
                val data = mifareCard.readBlock(blockIndex)
                val userData = extractReadableText(data)
                
                if (userData.isNotEmpty()) {
                    val parts = userData.split(":")
                    if (parts.size >= 3) {
                        mapOf(
                            "userId" to parts[0],
                            "accessCode" to parts[1],
                            "validUntil" to parts[2],
                            "rawData" to bytesToHex(data)
                        )
                    } else {
                        mapOf("rawData" to bytesToHex(data))
                    }
                } else {
                    mapOf("rawData" to bytesToHex(data))
                }
            } else {
                Log.e(TAG, "Authentication failed for sector $sectorIndex")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read card data", e)
            null
        }
    }
}