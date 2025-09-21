package com.mifare.encoder

import android.content.Intent
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
        
        setupToolbar()
        setupTabs()
        
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
                if (newPassword.length >= 6) {
                    authManager.setAdminPassword(newPassword)
                    android.widget.Toast.makeText(this, "Password updated", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Password must be at least 6 characters", android.widget.Toast.LENGTH_SHORT).show()
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
        }
    }
}