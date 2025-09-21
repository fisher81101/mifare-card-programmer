package com.mifare.encoder.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mifare.encoder.databinding.FragmentAdminConfigBinding
import com.mifare.encoder.utils.ApiClient
import com.mifare.encoder.utils.AuthManager
import kotlinx.coroutines.launch

class AdminConfigFragment : Fragment() {
    
    private var _binding: FragmentAdminConfigBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var securePrefs: SharedPreferences
    private lateinit var authManager: AuthManager
    private var apiClient: ApiClient? = null
    
    companion object {
        private const val TAG = "AdminConfigFragment"
        private const val SECURE_PREFS_NAME = "admin_config_secure"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_USER_MODE_URL = "user_mode_url"
        private const val KEY_USER_MODE_API_KEY = "user_mode_api_key"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminConfigBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Use encrypted shared preferences for secure storage of API keys
        val masterKey = androidx.security.crypto.MasterKey.Builder(requireContext())
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            requireContext(),
            SECURE_PREFS_NAME,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        authManager = AuthManager.getInstance(requireContext())
        
        setupUI()
        loadSavedConfig()
    }
    
    private fun setupUI() {
        // Backend Configuration
        binding.testConnectionButton.setOnClickListener {
            testBackendConnection()
        }
        
        binding.saveConfigButton.setOnClickListener {
            saveConfiguration()
        }
        
        binding.pushConfigButton.setOnClickListener {
            pushConfigurationToBackend()
        }
        
        // User Mode Configuration
        binding.saveUserConfigButton.setOnClickListener {
            saveUserModeConfiguration()
        }
        
        binding.testUserConnectionButton.setOnClickListener {
            testUserModeConnection()
        }
        
        // System Actions
        binding.changePasswordButton.setOnClickListener {
            showChangePasswordDialog()
        }
        
        binding.exportConfigButton.setOnClickListener {
            exportConfiguration()
        }
        
        binding.importConfigButton.setOnClickListener {
            showImportConfigDialog()
        }
    }
    
    private fun loadSavedConfig() {
        // Load admin backend config from encrypted storage
        val savedUrl = securePrefs.getString(KEY_BACKEND_URL, "http://10.0.2.2:5000")
        val savedApiKey = securePrefs.getString(KEY_API_KEY, "admin-api-key")
        
        binding.backendUrlEditText.setText(savedUrl)
        binding.apiKeyEditText.setText(savedApiKey)
        
        // Load user mode config from encrypted storage
        val userUrl = securePrefs.getString(KEY_USER_MODE_URL, "http://10.0.2.2:5000")
        val userApiKey = securePrefs.getString(KEY_USER_MODE_API_KEY, "user-mode-key")
        
        binding.userModeUrlEditText.setText(userUrl)
        binding.userModeApiKeyEditText.setText(userApiKey)
        
        if (!savedUrl.isNullOrEmpty() && !savedApiKey.isNullOrEmpty()) {
            updateConnectionStatus(true, "Saved configuration loaded")
        }
    }
    
    private fun saveConfiguration() {
        val url = binding.backendUrlEditText.text.toString().trim()
        val apiKey = binding.apiKeyEditText.text.toString().trim()
        
        if (url.isEmpty()) {
            showMessage("Backend URL is required")
            return
        }
        
        if (apiKey.isEmpty()) {
            showMessage("API key is required")
            return
        }
        
        securePrefs.edit()
            .putString(KEY_BACKEND_URL, url)
            .putString(KEY_API_KEY, apiKey)
            .apply()
        
        apiClient = ApiClient(url, apiKey)
        showMessage("Configuration saved")
        
        authManager.extendSession()
    }
    
    private fun saveUserModeConfiguration() {
        val url = binding.userModeUrlEditText.text.toString().trim()
        val apiKey = binding.userModeApiKeyEditText.text.toString().trim()
        
        // SECURITY FIX: Only save to encrypted preferences, remove plaintext storage
        securePrefs.edit()
            .putString(KEY_USER_MODE_URL, url)
            .putString(KEY_USER_MODE_API_KEY, apiKey)
            .apply()
        
        // Migrate any existing plaintext user config to secure storage
        migrateUserConfigToSecureStorage()
        
        showMessage("User mode configuration saved securely")
    }
    
    private fun migrateUserConfigToSecureStorage() {
        // One-time migration from plaintext to encrypted storage
        val userPrefs = requireContext().getSharedPreferences("user_config", android.content.Context.MODE_PRIVATE)
        val existingUrl = userPrefs.getString("backend_url", null)
        val existingApiKey = userPrefs.getString("api_key", null)
        
        if (!existingUrl.isNullOrEmpty() || !existingApiKey.isNullOrEmpty()) {
            // Migrate to secure storage
            securePrefs.edit()
                .putString(KEY_USER_MODE_URL, existingUrl ?: "")
                .putString(KEY_USER_MODE_API_KEY, existingApiKey ?: "")
                .apply()
            
            // Clear plaintext storage
            userPrefs.edit().clear().apply()
            
            android.util.Log.i(TAG, "Migrated user config from plaintext to encrypted storage")
        }
    }
    
    private fun testBackendConnection() {
        val url = binding.backendUrlEditText.text.toString().trim()
        val apiKey = binding.apiKeyEditText.text.toString().trim()
        
        if (url.isEmpty()) {
            showMessage("Enter backend URL first")
            return
        }
        
        updateConnectionStatus(false, "Testing connection...")
        
        lifecycleScope.launch {
            try {
                val testClient = ApiClient(url, apiKey)
                val response = testClient.testConnection()
                
                if (response.success) {
                    updateConnectionStatus(true, "Connected successfully")
                    apiClient = testClient
                    showMessage("Connection successful!")
                } else {
                    updateConnectionStatus(false, "Connection failed: ${response.message}")
                    showMessage("Connection failed: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test error", e)
                updateConnectionStatus(false, "Connection error: ${e.message}")
                showMessage("Connection error: ${e.message}")
            }
        }
    }
    
    private fun testUserModeConnection() {
        val url = binding.userModeUrlEditText.text.toString().trim()
        val apiKey = binding.userModeApiKeyEditText.text.toString().trim()
        
        if (url.isEmpty()) {
            showMessage("Enter user mode URL first")
            return
        }
        
        binding.userConnectionStatus.text = "Testing user mode connection..."
        
        lifecycleScope.launch {
            try {
                val testClient = ApiClient(url, apiKey)
                val response = testClient.testConnection()
                
                if (response.success) {
                    binding.userConnectionStatus.text = "User mode connection successful"
                    binding.userConnectionStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    showMessage("User mode connection successful!")
                } else {
                    binding.userConnectionStatus.text = "User mode connection failed: ${response.message}"
                    binding.userConnectionStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                }
            } catch (e: Exception) {
                Log.e(TAG, "User mode connection test error", e)
                binding.userConnectionStatus.text = "User mode connection error: ${e.message}"
                binding.userConnectionStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            }
        }
    }
    
    private fun pushConfigurationToBackend() {
        if (apiClient == null) {
            showMessage("Please test connection first")
            return
        }
        
        val config = mapOf(
            "deviceId" to android.provider.Settings.Secure.getString(
                requireContext().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ),
            "deviceType" to "admin_device",
            "configVersion" to "1.0",
            "userModeUrl" to binding.userModeUrlEditText.text.toString(),
            "capabilities" to "scan,write,admin",
            "timestamp" to System.currentTimeMillis().toString()
        )
        
        lifecycleScope.launch {
            try {
                val response = apiClient!!.generateConfig(config)
                
                if (response.success) {
                    showMessage("Configuration pushed to backend")
                    binding.pushStatus.text = "Last push: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                } else {
                    showMessage("Failed to push configuration: ${response.message}")
                    binding.pushStatus.text = "Push failed: ${response.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Push config error", e)
                showMessage("Push error: ${e.message}")
                binding.pushStatus.text = "Push error: ${e.message}"
            }
        }
    }
    
    private fun showChangePasswordDialog() {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "New admin password"
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Change Admin Password")
            .setMessage("Enter new password (minimum 8 characters):")
            .setView(input)
            .setPositiveButton("Change") { _, _ ->
                val newPassword = input.text.toString()
                if (newPassword.length >= 8) {
                    authManager.setAdminPassword(newPassword)
                    showMessage("Password updated successfully")
                } else {
                    showMessage("Password must be at least 8 characters")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun exportConfiguration() {
        val config = buildString {
            append("MIFARE Admin Configuration Export\n")
            append("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
            append("="*40 + "\n\n")
            
            append("Backend Configuration:\n")
            append("URL: ${binding.backendUrlEditText.text}\n")
            append("API Key: ${binding.apiKeyEditText.text.toString().take(8)}...\n\n")
            
            append("User Mode Configuration:\n")
            append("URL: ${binding.userModeUrlEditText.text}\n")
            append("API Key: ${binding.userModeApiKeyEditText.text.toString().take(8)}...\n\n")
            
            append("Device Information:\n")
            append("Device ID: ${android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)}\n")
            append("Android Version: ${android.os.Build.VERSION.RELEASE}\n")
            append("Model: ${android.os.Build.MODEL}\n")
        }
        
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Admin Config", config)
        clipboard.setPrimaryClip(clip)
        
        showMessage("Configuration exported to clipboard")
    }
    
    private fun showImportConfigDialog() {
        val input = android.widget.EditText(requireContext())
        input.hint = "Paste configuration data here"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 3
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Import Configuration")
            .setMessage("Paste exported configuration:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val configData = input.text.toString()
                // Simple parsing - in production would use JSON
                if (configData.contains("Backend Configuration:")) {
                    showMessage("Configuration import placeholder - feature coming soon")
                } else {
                    showMessage("Invalid configuration format")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateConnectionStatus(connected: Boolean, message: String) {
        binding.connectionStatus.text = message
        binding.connectionStatus.setTextColor(
            resources.getColor(
                if (connected) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
                null
            )
        )
        binding.pushConfigButton.isEnabled = connected
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}