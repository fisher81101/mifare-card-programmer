package com.mifare.encoder.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mifare.encoder.databinding.FragmentAdminLogsBinding
import com.mifare.encoder.utils.AuthManager

class AdminLogsFragment : Fragment() {
    
    private var _binding: FragmentAdminLogsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var authManager: AuthManager
    
    companion object {
        private const val TAG = "AdminLogsFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminLogsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        authManager = AuthManager.getInstance(requireContext())
        
        setupUI()
        refreshLogs()
    }
    
    private fun setupUI() {
        binding.refreshButton.setOnClickListener {
            refreshLogs()
        }
        
        binding.clearLogsButton.setOnClickListener {
            showClearLogsDialog()
        }
        
        binding.exportLogsButton.setOnClickListener {
            exportLogs()
        }
        
        binding.systemInfoButton.setOnClickListener {
            showSystemInfo()
        }
    }
    
    fun refreshLogs() {
        val logs = authManager.getAdminLogs()
        
        binding.logsTextView.text = if (logs.isNotEmpty()) {
            logs
        } else {
            "No admin activity logged yet.\n\nAdmin actions will appear here including:\n" +
                    "- Authentication events\n" +
                    "- Mode switches\n" +
                    "- Password changes\n" +
                    "- Configuration updates\n" +
                    "- Card operations\n"
        }
        
        // Update status
        val logLines = logs.split("\n").filter { it.isNotBlank() }
        binding.logCountText.text = "Log entries: ${logLines.size}"
        
        binding.lastRefreshText.text = "Last refresh: ${
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        }"
    }
    
    private fun showClearLogsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Admin Logs")
            .setMessage("This will permanently delete all admin activity logs. This action cannot be undone.\n\nContinue?")
            .setPositiveButton("Clear Logs") { _, _ ->
                authManager.clearAdminLogs()
                refreshLogs()
                showMessage("Admin logs cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun exportLogs() {
        val logs = authManager.getAdminLogs()
        
        if (logs.isBlank()) {
            showMessage("No logs to export")
            return
        }
        
        val exportData = buildString {
            append("MIFARE Admin Logs Export\n")
            append("Generated: ${
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            }\n")
            append("Device ID: ${
                android.provider.Settings.Secure.getString(
                    requireContext().contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
            }\n")
            append("=".repeat(50) + "\n\n")
            append(logs)
            append("\n\n" + "=".repeat(50))
            append("\nEnd of admin logs export")
        }
        
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Admin Logs", exportData)
        clipboard.setPrimaryClip(clip)
        
        showMessage("Admin logs exported to clipboard")
    }
    
    private fun showSystemInfo() {
        val systemInfo = buildString {
            append("=== SYSTEM INFORMATION ===\n\n")
            
            append("Device Information:\n")
            append("Device ID: ${
                android.provider.Settings.Secure.getString(
                    requireContext().contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
            }\n")
            append("Model: ${android.os.Build.MODEL}\n")
            append("Manufacturer: ${android.os.Build.MANUFACTURER}\n")
            append("Android Version: ${android.os.Build.VERSION.RELEASE}\n")
            append("API Level: ${android.os.Build.VERSION.SDK_INT}\n")
            append("Build: ${android.os.Build.DISPLAY}\n\n")
            
            append("NFC Information:\n")
            val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(requireContext())
            if (nfcAdapter != null) {
                append("NFC Supported: Yes\n")
                append("NFC Enabled: ${if (nfcAdapter.isEnabled) "Yes" else "No"}\n")
            } else {
                append("NFC Supported: No\n")
            }
            append("\n")
            
            append("App Information:\n")
            try {
                val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                append("Version: ${packageInfo.versionName}\n")
                append("Version Code: ${packageInfo.versionCode}\n")
            } catch (e: Exception) {
                append("Version: Unable to retrieve\n")
            }
            append("Package: ${requireContext().packageName}\n")
            append("Admin Mode: ${if (authManager.isAdminMode()) "Active" else "Inactive"}\n\n")
            
            append("Memory Information:\n")
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory() / (1024 * 1024)
            val freeMemory = runtime.freeMemory() / (1024 * 1024)
            val usedMemory = totalMemory - freeMemory
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            
            append("Total Memory: ${totalMemory}MB\n")
            append("Used Memory: ${usedMemory}MB\n")
            append("Free Memory: ${freeMemory}MB\n")
            append("Max Memory: ${maxMemory}MB\n\n")
            
            append("Security Information:\n")
            append("Admin Session Active: ${authManager.isAdminMode()}\n")
            append("Logs Available: ${authManager.getAdminLogs().isNotBlank()}\n")
            append("Timestamp: ${System.currentTimeMillis()}\n")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("System Information")
            .setMessage(systemInfo)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("System Info", systemInfo)
                clipboard.setPrimaryClip(clip)
                showMessage("System information copied to clipboard")
            }
            .show()
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}