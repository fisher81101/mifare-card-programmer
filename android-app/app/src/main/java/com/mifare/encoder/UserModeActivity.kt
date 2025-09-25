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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.EditText
import android.widget.Button
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.FormBody
import org.json.JSONObject

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
        
        // Distribution link input handling
        val editTextLink: EditText = findViewById(R.id.editTextDistributionLink)
        val buttonSubmit: Button = findViewById(R.id.buttonSubmitLink)
        
        buttonSubmit.setOnClickListener {
            val link = editTextLink.text.toString().trim()
            if (link.isEmpty()) {
                Toast.makeText(this, "Please enter a valid link", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Validate link format - check if it contains expected patterns
            if (!link.contains("/program/") && link.length < 20) {
                Toast.makeText(this, "Invalid distribution link format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processDistributionLink(link)
        }
    }
    
    private fun loadBackendConfig() {
        // SECURITY FIX: Ensure app-wide migration and load from encrypted storage only
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(this)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                this,
                "admin_config_secure",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            // Migration handled globally by MifareApplication.onCreate()
            
            val backendUrl = securePrefs.getString("user_mode_url", "http://10.0.2.2:5000") // Default for emulator
            val apiKey = securePrefs.getString("user_mode_api_key", "user-mode-key")
            
            if (!backendUrl.isNullOrEmpty()) {
                apiClient = ApiClient(backendUrl, apiKey ?: "")
                Log.d(TAG, "Backend configured securely: $backendUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secure backend config", e)
            // Fallback to default config for user mode
            apiClient = ApiClient("http://10.0.2.2:5000", "user-mode-key")
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
            
            updateStatus("Preparing program data...")
            
            // First check for distribution link program data
            var config: Map<String, String>? = null
            val sharedPrefs = getSharedPreferences("user_program", MODE_PRIVATE)
            val currentProgram = sharedPrefs.getString("current_program", null)
            
            if (currentProgram != null) {
                // Use distribution link program data
                try {
                    val json = JSONObject(currentProgram)
                    if (json.getBoolean("success")) {
                        val program = json.getJSONObject("program")
                        val sectorsData = program.getJSONObject("sectors_data")
                        
                        // Convert JSON program data to config format for MifareUtils
                        config = mutableMapOf<String, String>().apply {
                            put("programId", program.getString("id"))
                            put("programName", program.getString("name"))
                            put("sectorsData", sectorsData.toString())
                            put("userId", "distribution_user")
                        }
                        updateStatus("Using distribution link program: ${program.getString("name")}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing distribution program data", e)
                }
            }
            
            // Fallback to automatic configuration fetch if no distribution link data
            if (config == null) {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, 
                    android.provider.Settings.Secure.ANDROID_ID
                )
                updateStatus("Fetching configuration...")
                config = fetchUserConfiguration(deviceId)
            }
            
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
    
    private fun processDistributionLink(link: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Extract token from distribution link
                val token = if (link.contains("/program/")) {
                    link.substringAfter("/program/")
                } else {
                    link
                }
                
                // Retrieve user's Bearer token from shared preferences or existing auth system
                val sharedPref = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val authToken = token // Use the distribution token directly for access
                
                if (authToken.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UserModeActivity, "Invalid distribution link", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val client = OkHttpClient()
                // Use existing backend API that accepts distribution tokens
                val backendUrl = "http://10.0.2.2:5000" // Default for emulator
                val request = Request.Builder()
                    .url("$backendUrl/api/android/programs")
                    .addHeader("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                
                val response: Response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")
                    
                    if (json.getBoolean("success")) {
                        // Handle success: extract program data
                        val program = json.getJSONObject("program")
                        val programName = program.getString("name")
                        val programId = program.getString("id")
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@UserModeActivity, "Program accessed successfully: $programName", Toast.LENGTH_LONG).show()
                            updateStatus("Program loaded: $programName - Hold NFC card to phone")
                            
                            // Store the program data for NFC programming
                            val sharedPrefs = getSharedPreferences("user_program", MODE_PRIVATE)
                            with(sharedPrefs.edit()) {
                                putString("current_program", responseBody)
                                putString("current_token", authToken)
                                apply()
                            }
                        }
                    } else {
                        val message = json.optString("message", "Unknown error")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@UserModeActivity, "Failed to access program: $message", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UserModeActivity, "Failed to access: ${response.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Distribution link processing error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserModeActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}