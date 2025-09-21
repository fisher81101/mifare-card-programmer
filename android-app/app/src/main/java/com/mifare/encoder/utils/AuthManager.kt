package com.mifare.encoder.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class AuthManager(private val context: Context) {
    
    private val encryptedPrefs: SharedPreferences
    
    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_ADMIN_PASSWORD_HASH = "admin_password_hash"
        private const val KEY_IS_ADMIN_MODE = "is_admin_mode"
        private const val KEY_ADMIN_SESSION_TIMEOUT = "admin_session_timeout"
        private const val DEFAULT_ADMIN_PASSWORD = "admin123"
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
        
        @Volatile
        private var INSTANCE: AuthManager? = null
        
        fun getInstance(context: Context): AuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        // Initialize default password if not set
        if (!encryptedPrefs.contains(KEY_ADMIN_PASSWORD_HASH)) {
            setAdminPassword(DEFAULT_ADMIN_PASSWORD)
        }
    }
    
    fun isAdminMode(): Boolean {
        val isAdminMode = encryptedPrefs.getBoolean(KEY_IS_ADMIN_MODE, false)
        val sessionTimeout = encryptedPrefs.getLong(KEY_ADMIN_SESSION_TIMEOUT, 0)
        
        // Check if session has expired
        if (isAdminMode && System.currentTimeMillis() > sessionTimeout) {
            setUserMode()
            return false
        }
        
        return isAdminMode
    }
    
    fun authenticateAdmin(password: String): Boolean {
        val inputHash = hashPassword(password)
        val storedHash = encryptedPrefs.getString(KEY_ADMIN_PASSWORD_HASH, "")
        
        val isValid = inputHash == storedHash
        
        if (isValid) {
            setAdminMode()
            logAdminAction("Admin authentication successful")
        } else {
            logAdminAction("Admin authentication failed for password attempt")
        }
        
        return isValid
    }
    
    fun setAdminMode() {
        val sessionTimeout = System.currentTimeMillis() + SESSION_TIMEOUT_MS
        encryptedPrefs.edit()
            .putBoolean(KEY_IS_ADMIN_MODE, true)
            .putLong(KEY_ADMIN_SESSION_TIMEOUT, sessionTimeout)
            .apply()
        
        logAdminAction("Switched to admin mode")
    }
    
    fun setUserMode() {
        encryptedPrefs.edit()
            .putBoolean(KEY_IS_ADMIN_MODE, false)
            .putLong(KEY_ADMIN_SESSION_TIMEOUT, 0)
            .apply()
        
        logAdminAction("Switched to user mode")
    }
    
    fun setAdminPassword(newPassword: String) {
        val passwordHash = hashPassword(newPassword)
        encryptedPrefs.edit()
            .putString(KEY_ADMIN_PASSWORD_HASH, passwordHash)
            .apply()
        
        logAdminAction("Admin password updated")
    }
    
    fun extendSession() {
        if (isAdminMode()) {
            val newTimeout = System.currentTimeMillis() + SESSION_TIMEOUT_MS
            encryptedPrefs.edit()
                .putLong(KEY_ADMIN_SESSION_TIMEOUT, newTimeout)
                .apply()
        }
    }
    
    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun logAdminAction(action: String) {
        val timestamp = System.currentTimeMillis()
        Log.i(TAG, "[$timestamp] Admin Action: $action")
        
        // Store admin action logs in preferences for viewing in admin mode
        val existingLogs = encryptedPrefs.getString("admin_logs", "")
        val newLog = "[$timestamp] $action\n"
        val updatedLogs = (existingLogs + newLog).takeLast(5000) // Keep last 5000 chars
        
        encryptedPrefs.edit()
            .putString("admin_logs", updatedLogs)
            .apply()
    }
    
    fun getAdminLogs(): String {
        return encryptedPrefs.getString("admin_logs", "No admin actions logged.") ?: "No admin actions logged."
    }
    
    fun clearAdminLogs() {
        encryptedPrefs.edit()
            .putString("admin_logs", "")
            .apply()
        logAdminAction("Admin logs cleared")
    }
}