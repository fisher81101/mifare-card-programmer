package com.mifare.encoder

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.mifare.encoder.databinding.ActivityCardWriteBinding
import com.mifare.encoder.models.CustomCardConfig
import com.mifare.encoder.utils.SaltoProtocol

class CardWriteActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCardWriteBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var customConfig: CustomCardConfig? = null
    
    companion object {
        private const val TAG = "CardWriteActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardWriteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupNFC()
        setupUI()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.write_card)
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_supported), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
    }
    
    private fun setupUI() {
        binding.encodeButton.setOnClickListener {
            validateAndPrepareData()
        }
        
        // Set example dates
        val currentTime = System.currentTimeMillis()
        val startDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date(currentTime))
        val endDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date(currentTime + 30 * 24 * 60 * 60 * 1000L)) // 30 days later
        
        binding.startDateEditText.setText(startDate)
        binding.endDateEditText.setText(endDate)
        
        // Set example data
        binding.userIdEditText.setText("12345")
        binding.accessDoorsEditText.setText("101,102,103,201")
        binding.notesEditText.setText("Hotel guest access")
    }
    
    private fun validateAndPrepareData() {
        val config = CustomCardConfig(
            userId = binding.userIdEditText.text.toString().trim(),
            accessDoors = binding.accessDoorsEditText.text.toString().trim(),
            startDate = binding.startDateEditText.text.toString().trim(),
            endDate = binding.endDateEditText.text.toString().trim(),
            notes = binding.notesEditText.text.toString().trim()
        )
        
        val errors = SaltoProtocol.validateCustomConfig(config)
        
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Validation Errors")
                .setMessage(errors.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        customConfig = config
        binding.statusText.text = "Configuration valid. Hold a blank MIFARE card near the device to write."
        binding.encodeButton.isEnabled = false
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent?.action && customConfig != null) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                writeCard(tag)
            }
        }
    }
    
    private fun writeCard(tag: Tag) {
        binding.statusText.text = "Writing card..."
        
        try {
            val mifareCard = MifareClassic.get(tag)
            if (mifareCard == null) {
                Toast.makeText(this, "Not a MIFARE Classic card", Toast.LENGTH_SHORT).show()
                return
            }
            
            mifareCard.connect()
            
            val cardData = SaltoProtocol.generateCustomCardData(customConfig!!)
            val success = writeCardData(mifareCard, cardData)
            
            if (success) {
                binding.statusText.text = getString(R.string.card_write_success)
                Toast.makeText(this, getString(R.string.card_write_success), Toast.LENGTH_SHORT).show()
                
                // Reset form
                customConfig = null
                binding.encodeButton.isEnabled = true
            } else {
                binding.statusText.text = getString(R.string.card_write_failed)
                Toast.makeText(this, getString(R.string.card_write_failed), Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing card", e)
            binding.statusText.text = getString(R.string.card_write_failed) + ": ${e.message}"
            Toast.makeText(this, getString(R.string.card_write_failed), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun writeCardData(mifareCard: MifareClassic, data: ByteArray): Boolean {
        try {
            val sectorCount = mifareCard.sectorCount
            var dataOffset = 0
            
            // Start from sector 1 (skip sector 0 which contains manufacturer data)
            for (sectorIndex in 1 until sectorCount) {
                if (!authenticateWriteSector(mifareCard, sectorIndex)) {
                    Log.w(TAG, "Failed to authenticate sector $sectorIndex for writing")
                    continue
                }
                
                val firstBlock = mifareCard.sectorToBlock(sectorIndex)
                val blockCount = mifareCard.getBlockCountInSector(sectorIndex)
                
                // Write all blocks except the trailer block
                for (blockIndex in 0 until blockCount - 1) {
                    if (dataOffset + 16 <= data.size) {
                        val blockData = data.sliceArray(dataOffset until dataOffset + 16)
                        mifareCard.writeBlock(firstBlock + blockIndex, blockData)
                        dataOffset += 16
                    }
                }
            }
            
            // Verify by reading back
            return verifyWrittenData(mifareCard, data)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing card data", e)
            return false
        } finally {
            try {
                mifareCard.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing card connection", e)
            }
        }
    }
    
    private fun authenticateWriteSector(mifareCard: MifareClassic, sectorIndex: Int): Boolean {
        // Try default keys for writing
        val defaultKeys = arrayOf(
            MifareClassic.KEY_DEFAULT,
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        )
        
        for (key in defaultKeys) {
            try {
                if (mifareCard.authenticateSectorWithKeyA(sectorIndex, key)) {
                    return true
                }
            } catch (e: Exception) {
                // Continue trying other keys
            }
        }
        
        return false
    }
    
    private fun verifyWrittenData(mifareCard: MifareClassic, originalData: ByteArray): Boolean {
        try {
            // Sample verification - read a few key blocks and compare
            var dataOffset = 64 // Start from sector 1
            
            for (sectorIndex in 1..3) { // Verify first few sectors
                if (authenticateWriteSector(mifareCard, sectorIndex)) {
                    val firstBlock = mifareCard.sectorToBlock(sectorIndex)
                    val readData = mifareCard.readBlock(firstBlock)
                    val expectedData = originalData.sliceArray(dataOffset until dataOffset + 16)
                    
                    if (!readData.contentEquals(expectedData)) {
                        Log.w(TAG, "Verification failed at sector $sectorIndex")
                        return false
                    }
                    
                    dataOffset += 64 // Next sector
                }
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying written data", e)
            return false
        }
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val intentFiltersArray = arrayOf(intentFilter)
        val techListsArray = arrayOf(arrayOf<String>(MifareClassic::class.java.name))
        
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}