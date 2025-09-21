package com.mifare.encoder

import android.app.Application
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Application class for global initialization and security migration
 */
class MifareApplication : Application() {
    
    companion object {
        private const val TAG = "MifareApplication"
        private const val MIGRATION_FLAG_KEY = "global_migration_v2_complete"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // CRITICAL: Perform global one-time migration on app startup
        performGlobalSecurityMigration()
    }
    
    private fun performGlobalSecurityMigration() {
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val securePrefs = EncryptedSharedPreferences.create(
                this,
                "admin_config_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            // Check if global migration already completed
            val migrationComplete = securePrefs.getBoolean(MIGRATION_FLAG_KEY, false)
            
            if (!migrationComplete) {
                Log.i(TAG, "Starting global security migration...")
                
                // Migrate legacy user_config (user mode settings)
                migrateUserConfig(securePrefs)
                
                // Migrate legacy admin_config (admin mode settings) 
                migrateAdminConfig(securePrefs)
                
                // Mark migration as complete
                securePrefs.edit()
                    .putBoolean(MIGRATION_FLAG_KEY, true)
                    .apply()
                
                Log.i(TAG, "Global security migration completed successfully")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Global security migration failed", e)
        }
    }
    
    private fun migrateUserConfig(securePrefs: android.content.SharedPreferences) {
        val userPrefs = getSharedPreferences("user_config", MODE_PRIVATE)
        val existingUrl = userPrefs.getString("backend_url", null)
        val existingApiKey = userPrefs.getString("api_key", null)
        
        if (!existingUrl.isNullOrEmpty() || !existingApiKey.isNullOrEmpty()) {
            // Migrate to secure storage
            securePrefs.edit()
                .putString("user_mode_url", existingUrl ?: "http://10.0.2.2:5000")
                .putString("user_mode_api_key", existingApiKey ?: "user-mode-key")
                .apply()
            
            Log.i(TAG, "Migrated user_config to encrypted storage")
        }
        
        // Clean up plaintext storage
        try {
            deleteSharedPreferences("user_config")
            Log.i(TAG, "Deleted plaintext user_config file")
        } catch (e: Exception) {
            userPrefs.edit().clear().apply()
            Log.w(TAG, "Cleared user_config (deletion failed)", e)
        }
    }
    
    private fun migrateAdminConfig(securePrefs: android.content.SharedPreferences) {
        val adminPrefs = getSharedPreferences("admin_config", MODE_PRIVATE)
        val existingUrl = adminPrefs.getString("backend_url", null)
        val existingApiKey = adminPrefs.getString("api_key", null)
        val existingUserUrl = adminPrefs.getString("user_mode_url", null)
        val existingUserApiKey = adminPrefs.getString("user_mode_api_key", null)
        
        if (!existingUrl.isNullOrEmpty() || !existingApiKey.isNullOrEmpty() ||
            !existingUserUrl.isNullOrEmpty() || !existingUserApiKey.isNullOrEmpty()) {
            
            // Migrate admin config to secure storage
            val editor = securePrefs.edit()
            
            if (!existingUrl.isNullOrEmpty()) {
                editor.putString("backend_url", existingUrl)
            }
            if (!existingApiKey.isNullOrEmpty()) {
                editor.putString("api_key", existingApiKey)
            }
            if (!existingUserUrl.isNullOrEmpty()) {
                editor.putString("user_mode_url", existingUserUrl)
            }
            if (!existingUserApiKey.isNullOrEmpty()) {
                editor.putString("user_mode_api_key", existingUserApiKey)
            }
            
            editor.apply()
            Log.i(TAG, "Migrated admin_config to encrypted storage")
        }
        
        // Clean up plaintext storage
        try {
            deleteSharedPreferences("admin_config")
            Log.i(TAG, "Deleted plaintext admin_config file")
        } catch (e: Exception) {
            adminPrefs.edit().clear().apply()
            Log.w(TAG, "Cleared admin_config (deletion failed)", e)
        }
    }
}