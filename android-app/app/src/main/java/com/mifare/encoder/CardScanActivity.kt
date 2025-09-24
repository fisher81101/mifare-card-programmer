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
import androidx.recyclerview.widget.LinearLayoutManager
import com.mifare.encoder.adapters.CardDataAdapter
import com.mifare.encoder.databinding.ActivityCardScanBinding
import com.mifare.encoder.models.CardData
import com.mifare.encoder.utils.MifareUtils
import com.mifare.encoder.utils.SaltoProtocol

class CardScanActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCardScanBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private lateinit var cardDataAdapter: CardDataAdapter
    
    companion object {
        private const val TAG = "CardScanActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupNFC()
        setupRecyclerView()
        
        binding.instructionText.text = getString(R.string.hold_card_near)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.scan_card)
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
    }
    
    private fun setupRecyclerView() {
        cardDataAdapter = CardDataAdapter()
        binding.cardDataRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@CardScanActivity)
            adapter = cardDataAdapter
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent?.action) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                handleCard(tag)
            }
        }
    }
    
    private fun handleCard(tag: Tag) {
        binding.instructionText.text = getString(R.string.card_detected)
        
        try {
            val mifareCard = MifareClassic.get(tag)
            if (mifareCard != null) {
                readCard(mifareCard)
            } else {
                Toast.makeText(this, "Not a MIFARE Classic card", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling card", e)
            Toast.makeText(this, getString(R.string.card_read_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun readCard(mifareCard: MifareClassic) {
        try {
            mifareCard.connect()
            
            val cardData = CardData()
            cardData.uid = MifareUtils.bytesToHex(mifareCard.tag.id)
            cardData.cardType = when (mifareCard.type) {
                MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
                MifareClassic.TYPE_PLUS -> "MIFARE Plus"
                MifareClassic.TYPE_PRO -> "MIFARE Pro"
                else -> "Unknown"
            }
            cardData.size = "${mifareCard.size} bytes"
            
            // Read all sectors
            val allSectorData = mutableListOf<String>()
            val sectorCount = mifareCard.sectorCount
            
            for (sectorIndex in 0 until sectorCount) {
                if (authenticateSector(mifareCard, sectorIndex)) {
                    val sectorData = readSector(mifareCard, sectorIndex)
                    allSectorData.add("Sector $sectorIndex: ${MifareUtils.bytesToHex(sectorData)}")
                } else {
                    allSectorData.add("Sector $sectorIndex: Authentication failed")
                }
            }
            
            cardData.sectors = allSectorData
            
            // Analyze Salto data
            val saltoData = SaltoProtocol.analyzeCard(allSectorData)
            cardData.saltoData = saltoData
            
            // Update UI
            runOnUiThread {
                cardDataAdapter.updateData(cardData)
                binding.instructionText.text = getString(R.string.card_read_success)
                Toast.makeText(this, getString(R.string.card_read_success), Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading card", e)
            runOnUiThread {
                Toast.makeText(this, getString(R.string.card_read_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            try {
                mifareCard.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing card connection", e)
            }
        }
    }
    
    private fun authenticateSector(mifareCard: MifareClassic, sectorIndex: Int): Boolean {
        // Try common default keys
        val defaultKeys = arrayOf(
            MifareClassic.KEY_DEFAULT,
            MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,
            MifareClassic.KEY_NFC_FORUM,
            // Add more common keys as needed
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        )
        
        for (key in defaultKeys) {
            try {
                if (mifareCard.authenticateSectorWithKeyA(sectorIndex, key) ||
                    mifareCard.authenticateSectorWithKeyB(sectorIndex, key)) {
                    return true
                }
            } catch (e: Exception) {
                // Continue trying other keys
            }
        }
        
        return false
    }
    
    private fun readSector(mifareCard: MifareClassic, sectorIndex: Int): ByteArray {
        val firstBlockIndex = mifareCard.sectorToBlock(sectorIndex)
        val blockCount = mifareCard.getBlockCountInSector(sectorIndex)
        val sectorData = ByteArray(blockCount * MifareClassic.BLOCK_SIZE)
        
        for (i in 0 until blockCount - 1) { // Skip trailer block
            val blockData = mifareCard.readBlock(firstBlockIndex + i)
            System.arraycopy(blockData, 0, sectorData, i * MifareClassic.BLOCK_SIZE, MifareClassic.BLOCK_SIZE)
        }
        
        return sectorData
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