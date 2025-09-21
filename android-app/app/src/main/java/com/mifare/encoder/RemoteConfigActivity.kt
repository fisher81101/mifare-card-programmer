package com.mifare.encoder

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mifare.encoder.databinding.ActivityRemoteConfigBinding
import com.mifare.encoder.models.ApiResponse
import com.mifare.encoder.models.RemoteConfig
import com.mifare.encoder.utils.ApiClient
import kotlinx.coroutines.launch

class RemoteConfigActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityRemoteConfigBinding
    private lateinit var prefs: SharedPreferences
    private var apiClient: ApiClient? = null
    
    companion object {
        private const val TAG = "RemoteConfigActivity"
        private const val PREFS_NAME = "remote_config"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_API_KEY = "api_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupPreferences()
        setupUI()
        loadSavedConfig()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.remote_config)
    }
    
    private fun setupPreferences() {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    
    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            testConnection()
        }
        
        binding.fetchConfigButton.setOnClickListener {
            fetchRemoteConfig()
        }
        
        // Set default backend URL if empty
        if (binding.backendUrlEditText.text.isNullOrEmpty()) {
            binding.backendUrlEditText.setText("http://localhost:5000")
        }
    }
    
    private fun loadSavedConfig() {
        val savedUrl = prefs.getString(KEY_BACKEND_URL, "")
        val savedApiKey = prefs.getString(KEY_API_KEY, "")
        
        if (!savedUrl.isNullOrEmpty()) {
            binding.backendUrlEditText.setText(savedUrl)
        }
        
        if (!savedApiKey.isNullOrEmpty()) {
            binding.apiKeyEditText.setText(savedApiKey)
        }
        
        if (!savedUrl.isNullOrEmpty() && !savedApiKey.isNullOrEmpty()) {
            updateConnectionStatus(true, "Saved configuration loaded")
        }
    }
    
    private fun saveConfig() {
        val url = binding.backendUrlEditText.text.toString().trim()
        val apiKey = binding.apiKeyEditText.text.toString().trim()
        
        prefs.edit()
            .putString(KEY_BACKEND_URL, url)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }
    
    private fun testConnection() {
        val url = binding.backendUrlEditText.text.toString().trim()
        val apiKey = binding.apiKeyEditText.text.toString().trim()
        
        if (url.isEmpty()) {
            Toast.makeText(this, "Backend URL is required", Toast.LENGTH_SHORT).show()
            return
        }
        
        updateConnectionStatus(false, "Testing connection...")
        
        lifecycleScope.launch {
            try {
                apiClient = ApiClient(url, apiKey)
                val response = apiClient!!.testConnection()
                
                if (response.success) {
                    updateConnectionStatus(true, "Connected successfully")
                    saveConfig()
                    Toast.makeText(this@RemoteConfigActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
                } else {
                    updateConnectionStatus(false, "Connection failed: ${response.message}")
                    Toast.makeText(this@RemoteConfigActivity, "Connection failed: ${response.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                updateConnectionStatus(false, "Connection error: ${e.message}")
                Toast.makeText(this@RemoteConfigActivity, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun fetchRemoteConfig() {
        if (apiClient == null) {
            Toast.makeText(this, "Please connect first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Example of fetching a pre-configured card program
        val userId = binding.sampleUserIdEditText.text.toString().trim()
        val doors = binding.sampleDoorsEditText.text.toString().trim()
        
        if (userId.isEmpty() || doors.isEmpty()) {
            Toast.makeText(this, "Please fill in user ID and doors", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.fetchStatus.text = "Fetching configuration..."
        
        lifecycleScope.launch {
            try {
                val config = mapOf(
                    "userId" to userId,
                    "doors" to doors,
                    "startDate" to "2025-01-01T00:00:00Z",
                    "endDate" to "2025-12-31T23:59:59Z",
                    "notes" to "Remote generated config"
                )
                
                val response = apiClient!!.generateConfig(config)
                
                if (response.success && response.cardData.isNotEmpty()) {
                    binding.fetchStatus.text = "Configuration received successfully!\nCard data: ${response.cardData.take(50)}..."
                    Toast.makeText(this@RemoteConfigActivity, "Configuration fetched successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.fetchStatus.text = "Failed to fetch configuration: ${response.message}"
                    Toast.makeText(this@RemoteConfigActivity, "Failed to fetch configuration", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Fetch config error", e)
                binding.fetchStatus.text = "Error fetching configuration: ${e.message}"
                Toast.makeText(this@RemoteConfigActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun updateConnectionStatus(connected: Boolean, message: String) {
        binding.connectionStatus.text = message
        binding.connectionStatus.setTextColor(
            getColor(if (connected) R.color.success else R.color.error)
        )
        binding.fetchConfigButton.isEnabled = connected
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}