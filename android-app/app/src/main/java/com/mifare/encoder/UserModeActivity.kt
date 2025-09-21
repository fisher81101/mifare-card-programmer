package com.mifare.encoder

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mifare.encoder.databinding.ActivityUserModeBinding
import com.mifare.encoder.utils.ApiClient
import com.mifare.encoder.utils.AuthManager
import com.mifare.encoder.utils.MifareUtils
import kotlinx.coroutines.launch

class UserModeActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityUserModeBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    private lateinit var authManager: AuthManager
    private var apiClient: ApiClient? = null
    
    companion object {
        private const val TAG = "UserModeActivity"
        private const val ADMIN_GESTURE_CLICKS = 7
        private var logoClickCount = 0
        private var lastClickTime = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        authManager = AuthManager.getInstance(this)
        
        setupNFC()
        setupUI()
        loadBackendConfig()
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            showError("This device does not support NFC")
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            showError("NFC is disabled. Please enable NFC in settings")
            return
        }
        
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        
        val mifareFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFilters = arrayOf(mifareFilter)
        techLists = arrayOf(arrayOf(MifareClassic::class.java.name))
    }
    
    private fun setupUI() {
        // Hidden admin access gesture - 7 quick clicks on logo
        binding.logoImageView.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastClickTime < 500) { // Within 500ms
                logoClickCount++
            } else {
                logoClickCount = 1
            }
            
            lastClickTime = currentTime
            
            if (logoClickCount >= ADMIN_GESTURE_CLICKS) {
                logoClickCount = 0
                showAdminPasswordDialog()
            }
        }
        
        // Simple status display
        updateStatus("Ready to program cards")
        binding.progressBar.visibility = View.GONE
    }
    
    private fun loadBackendConfig() {
        // Load pre-configured backend settings for user mode
        val prefs = getSharedPreferences("user_config", MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "http://10.0.2.2:5000") // Default for emulator
        val apiKey = prefs.getString("api_key", "user-mode-key")
        
        if (!backendUrl.isNullOrEmpty()) {
            apiClient = ApiClient(backendUrl, apiKey ?: "")
            Log.d(TAG, "Backend configured: $backendUrl")
        }
    }
    
    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNfcIntent(it) }
    }
    
    private fun handleNfcIntent(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        
        updateStatus("Card detected...")
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val mifareCard = MifareClassic.get(tag)
                if (mifareCard != null) {
                    programCard(mifareCard)
                } else {
                    showError("Unsupported card type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "NFC handling error", e)
                showError("Card operation failed")
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
    
    private suspend fun programCard(mifareCard: MifareClassic) {
        try {
            updateStatus("Connecting to card...")
            mifareCard.connect()
            
            // Get device/user identifier for backend request
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver, 
                android.provider.Settings.Secure.ANDROID_ID
            )
            
            updateStatus("Fetching configuration...")
            
            // Automatic configuration fetch from backend
            val config = fetchUserConfiguration(deviceId)
            if (config == null) {
                showError("No programming data available. Contact admin.")
                return
            }
            
            updateStatus("Programming card...")
            
            // Program the card with fetched configuration
            val success = MifareUtils.writeCardData(mifareCard, config)
            
            if (success) {
                updateStatus("Programming complete!")
                showSuccess("Card programmed successfully")
                
                // Report success to backend
                apiClient?.submitProgrammingResult(
                    userId = config["userId"] ?: "unknown",
                    cardUid = MifareUtils.bytesToHex(mifareCard.tag.id),
                    success = true
                )
            } else {
                showError("Programming failed. Please try again.")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Card programming error", e)
            showError("Programming error: ${e.message}")
        } finally {
            try {
                mifareCard.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing card", e)
            }
        }
    }
    
    private suspend fun fetchUserConfiguration(deviceId: String): Map<String, String>? {
        return try {
            apiClient?.let { client ->
                val response = client.generateConfig(mapOf(
                    "deviceId" to deviceId,
                    "mode" to "user_auto"
                ))
                
                if (response.success && response.cardData.isNotEmpty()) {
                    // Parse the configuration from response
                    val configJson = String(android.util.Base64.decode(response.cardData, android.util.Base64.DEFAULT))
                    com.google.gson.Gson().fromJson(configJson, Map::class.java) as? Map<String, String>
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch user configuration", e)
            null
        }
    }
    
    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.statusTextView.text = message
        }
    }
    
    private fun showError(message: String) {
        runOnUiThread {
            updateStatus("Error: $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showSuccess(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showAdminPasswordDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        
        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setMessage("Enter admin password:")
            .setView(input)
            .setPositiveButton("Login") { _, _ ->
                val password = input.text.toString()
                if (authManager.authenticateAdmin(password)) {
                    // Switch to admin mode
                    startActivity(Intent(this, AdminModeActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "Invalid password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}