package com.mifare.encoder

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.mifare.encoder.databinding.ActivityAdminModeBinding
import com.mifare.encoder.fragments.*
import com.mifare.encoder.utils.AuthManager

class AdminModeActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAdminModeBinding
    private lateinit var authManager: AuthManager
    
    // NFC handling
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    
    // Fragment instances
    private val scanFragment = AdminScanFragment()
    private val writeFragment = AdminWriteFragment()
    private val configFragment = AdminConfigFragment()
    private val logsFragment = AdminLogsFragment()
    
    companion object {
        private const val TAG = "AdminModeActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        authManager = AuthManager.getInstance(this)
        
        // Verify admin access
        if (!authManager.isAdminMode()) {
            // Not authenticated, return to user mode
            returnToUserMode()
            return
        }
        
        // SECURITY: Defensive migration check (secondary safety net)
        performDefensiveMigration()
        
        // Check if first-run password change is required
        if (authManager.isFirstRunPasswordChangeRequired()) {
            showFirstRunPasswordChangeDialog()
            return
        }
        
        setupToolbar()
        setupTabs()
        setupNFC()
        
        // Show scan fragment by default
        showFragment(scanFragment)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Admin Mode"
        supportActionBar?.subtitle = "Full Access Enabled"
    }
    
    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Scan").setIcon(R.drawable.ic_nfc_scan))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Write").setIcon(R.drawable.ic_nfc_write))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Config").setIcon(R.drawable.ic_settings))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Logs").setIcon(R.drawable.ic_logs))
        
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showFragment(scanFragment)
                    1 -> showFragment(writeFragment)
                    2 -> showFragment(configFragment)
                    3 -> showFragment(logsFragment)
                }
                
                // Extend session on activity
                authManager.extendSession()
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter != null) {
            pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE
            )
            
            val mifareFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            intentFilters = arrayOf(mifareFilter)
            techLists = arrayOf(arrayOf(MifareClassic::class.java.name))
        }
    }
    
    private fun showFirstRunPasswordChangeDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "New secure admin password"
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("First Run: Change Default Password")
            .setMessage("For security, you must change the default admin password before using admin features.\n\nNew password must be at least 8 characters.")
            .setView(input)
            .setPositiveButton("Change Password") { _, _ ->
                val newPassword = input.text.toString()
                if (newPassword.length >= 8) {
                    authManager.setAdminPassword(newPassword)
                    authManager.markFirstRunPasswordChanged()
                    android.widget.Toast.makeText(this, "Admin password updated successfully", android.widget.Toast.LENGTH_SHORT).show()
                    
                    // Now proceed with normal admin setup
                    setupToolbar()
                    setupTabs()
                    setupNFC()
                    showFragment(scanFragment)
                } else {
                    android.widget.Toast.makeText(this, "Password must be at least 8 characters", android.widget.Toast.LENGTH_LONG).show()
                    showFirstRunPasswordChangeDialog() // Try again
                }
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }
    
    private fun performDefensiveMigration() {
        // SECURITY: Secondary safety net for legacy plaintext cleanup
        try {
            // Clean up any remaining plaintext files as defensive measure
            deleteSharedPreferences("admin_config")
            deleteSharedPreferences("user_config")
        } catch (e: Exception) {
            // Fallback: clear content if deletion fails
            getSharedPreferences("admin_config", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("user_config", MODE_PRIVATE).edit().clear().apply()
        }
    }
    
    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.admin_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_user_mode -> {
                showUserModeDialog()
                true
            }
            R.id.action_change_password -> {
                showChangePasswordDialog()
                true
            }
            R.id.action_clear_logs -> {
                showClearLogsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showUserModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Switch to User Mode")
            .setMessage("Return to simplified user interface?")
            .setPositiveButton("Switch") { _, _ ->
                returnToUserMode()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showChangePasswordDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "New admin password"
        
        AlertDialog.Builder(this)
            .setTitle("Change Admin Password")
            .setMessage("Enter new password:")
            .setView(input)
            .setPositiveButton("Change") { _, _ ->
                val newPassword = input.text.toString()
                if (newPassword.length >= 8) {
                    authManager.setAdminPassword(newPassword)
                    android.widget.Toast.makeText(this, "Password updated", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Password must be at least 8 characters", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showClearLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Admin Logs")
            .setMessage("This will delete all admin activity logs. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                authManager.clearAdminLogs()
                android.widget.Toast.makeText(this, "Logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                
                // Refresh logs fragment if currently visible
                if (binding.tabLayout.selectedTabPosition == 3) {
                    logsFragment.refreshLogs()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun returnToUserMode() {
        authManager.setUserMode()
        startActivity(Intent(this, UserModeActivity::class.java))
        finish()
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if session is still valid
        if (!authManager.isAdminMode()) {
            returnToUserMode()
            return
        }
        
        // Enable NFC foreground dispatch for admin fragments
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Forward NFC intents to the appropriate admin fragment
        intent?.let { nfcIntent ->
            if (NfcAdapter.ACTION_TECH_DISCOVERED == nfcIntent.action) {
                when (binding.tabLayout.selectedTabPosition) {
                    0 -> scanFragment.handleNfcIntent(nfcIntent) // Scan fragment
                    1 -> writeFragment.handleNfcIntent(nfcIntent) // Write fragment
                }
            }
        }
    }
}