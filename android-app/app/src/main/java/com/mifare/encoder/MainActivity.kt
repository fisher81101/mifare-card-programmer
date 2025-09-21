package com.mifare.encoder

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.mifare.encoder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFiltersArray: Array<IntentFilter>? = null
    private var techListsArray: Array<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNFC()
        setupUI()
        showSecurityWarning()
    }

    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_supported), Toast.LENGTH_LONG).show()
            return
        }
        
        // Create a PendingIntent object for foreground dispatch
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        
        // Setup intent filters
        val ndef = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFiltersArray = arrayOf(ndef)
        
        // Setup technology lists
        techListsArray = arrayOf(arrayOf<String>(MifareClassic::class.java.name))
    }

    private fun setupUI() {
        binding.scanCardButton.setOnClickListener {
            startActivity(Intent(this, CardScanActivity::class.java))
        }
        
        binding.writeCardButton.setOnClickListener {
            startActivity(Intent(this, CardWriteActivity::class.java))
        }
        
        binding.remoteConfigButton.setOnClickListener {
            startActivity(Intent(this, RemoteConfigActivity::class.java))
        }
    }
    
    private fun showSecurityWarning() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("security_warning_shown", false)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.security_warning))
                .setMessage(getString(R.string.security_message))
                .setPositiveButton(getString(R.string.i_understand)) { _, _ ->
                    prefs.edit().putBoolean("security_warning_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null && !nfcAdapter!!.isEnabled) {
            Toast.makeText(this, getString(R.string.nfc_disabled), Toast.LENGTH_LONG).show()
        }
        
        nfcAdapter?.enableForegroundDispatch(
            this, pendingIntent, intentFiltersArray, techListsArray
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
}