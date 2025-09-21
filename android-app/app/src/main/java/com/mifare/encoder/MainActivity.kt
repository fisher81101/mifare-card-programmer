package com.mifare.encoder

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mifare.encoder.fragments.AdminScanFragment
import com.mifare.encoder.fragments.AdminWriteFragment
import com.mifare.encoder.utils.AuthManager

/**
 * Main Activity - Entry point that routes to appropriate mode
 * 
 * This activity determines whether to show the simplified user interface
 * or redirect to admin mode if already authenticated.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var authManager: AuthManager
    
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        authManager = AuthManager.getInstance(this)
        
        // Route to appropriate mode based on authentication state
        routeToAppropriateMode()
    }
    
    private fun routeToAppropriateMode() {
        if (authManager.isAdminMode()) {
            // User is already authenticated as admin
            startActivity(Intent(this, AdminModeActivity::class.java))
        } else {
            // Default to user mode
            startActivity(Intent(this, UserModeActivity::class.java))
        }
        
        finish() // Close MainActivity since it's just a router
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        
        // Handle NFC intents if they come to MainActivity
        // Forward to appropriate activity based on current mode
        intent?.let { nfcIntent ->
            if (NfcAdapter.ACTION_TECH_DISCOVERED == nfcIntent.action) {
                if (authManager.isAdminMode()) {
                    // Forward to admin mode
                    val adminIntent = Intent(this, AdminModeActivity::class.java)
                    adminIntent.action = nfcIntent.action
                    adminIntent.putExtras(nfcIntent.extras ?: Bundle())
                    startActivity(adminIntent)
                } else {
                    // Forward to user mode
                    val userIntent = Intent(this, UserModeActivity::class.java)
                    userIntent.action = nfcIntent.action
                    userIntent.putExtras(nfcIntent.extras ?: Bundle())
                    startActivity(userIntent)
                }
                finish()
            }
        }
    }
}