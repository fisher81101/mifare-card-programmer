package com.mifare.encoder.fragments

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mifare.encoder.R
import com.mifare.encoder.adapters.CardDataAdapter
import com.mifare.encoder.databinding.FragmentAdminScanBinding
import com.mifare.encoder.models.CardData
import com.mifare.encoder.utils.MifareUtils
import com.mifare.encoder.utils.SaltoProtocol
import kotlinx.coroutines.launch

class AdminScanFragment : Fragment() {
    
    private var _binding: FragmentAdminScanBinding? = null
    private val binding get() = _binding!!
    
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    
    private lateinit var cardDataAdapter: CardDataAdapter
    private val scannedCards = mutableListOf<CardData>()
    
    companion object {
        private const val TAG = "AdminScanFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminScanBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupNFC()
        setupRecyclerView()
        setupUI()
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        
        if (nfcAdapter == null) {
            showMessage("NFC not supported on this device")
            return
        }
        
        pendingIntent = PendingIntent.getActivity(
            requireContext(), 0,
            Intent(requireContext(), requireActivity().javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        
        val mifareFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFilters = arrayOf(mifareFilter)
        techLists = arrayOf(arrayOf(MifareClassic::class.java.name))
    }
    
    private fun setupRecyclerView() {
        cardDataAdapter = CardDataAdapter(scannedCards) { cardData ->
            showCardDetails(cardData)
        }
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = cardDataAdapter
        }
    }
    
    private fun setupUI() {
        binding.scanButton.setOnClickListener {
            if (nfcAdapter?.isEnabled == true) {
                showMessage("Ready to scan. Hold card near device...")
                binding.statusText.text = "Waiting for NFC card..."
            } else {
                showMessage("Please enable NFC in device settings")
            }
        }
        
        binding.clearButton.setOnClickListener {
            scannedCards.clear()
            cardDataAdapter.notifyDataSetChanged()
            binding.statusText.text = "Scan history cleared"
        }
        
        binding.exportButton.setOnClickListener {
            exportScanData()
        }
    }
    
    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(
            requireActivity(),
            pendingIntent,
            intentFilters,
            techLists
        )
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(requireActivity())
    }
    
    fun handleNfcIntent(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        
        binding.statusText.text = "Processing card..."
        
        lifecycleScope.launch {
            try {
                val cardData = scanCard(tag)
                if (cardData != null) {
                    scannedCards.add(0, cardData) // Add to top
                    cardDataAdapter.notifyItemInserted(0)
                    binding.recyclerView.scrollToPosition(0)
                    
                    binding.statusText.text = "Card scanned successfully (${cardData.cardType})"
                    showMessage("Card data decoded and added to list")
                } else {
                    binding.statusText.text = "Failed to read card data"
                    showMessage("Unable to read card. Check card type.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Card scan error", e)
                binding.statusText.text = "Scan error: ${e.message}"
                showMessage("Error scanning card: ${e.message}")
            }
        }
    }
    
    private suspend fun scanCard(tag: Tag): CardData? {
        val mifareCard = MifareClassic.get(tag) ?: return null
        
        return try {
            mifareCard.connect()
            
            val uid = MifareUtils.bytesToHex(tag.id)
            val cardType = when (mifareCard.type) {
                MifareClassic.TYPE_CLASSIC -> "MIFARE Classic 1K"
                MifareClassic.TYPE_PLUS -> "MIFARE Plus"
                MifareClassic.TYPE_PRO -> "MIFARE Pro"
                else -> "MIFARE Classic ${mifareCard.size} bytes"
            }
            
            // Read all accessible sectors
            val sectorData = mutableMapOf<Int, String>()
            val rawHexData = StringBuilder()
            
            for (sectorIndex in 0 until mifareCard.sectorCount) {
                try {
                    if (mifareCard.authenticateSectorWithKeyA(sectorIndex, MifareClassic.KEY_DEFAULT)) {
                        val blockCount = mifareCard.getBlockCountInSector(sectorIndex)
                        val sectorStart = mifareCard.sectorToBlock(sectorIndex)
                        
                        val sectorHex = StringBuilder()
                        for (blockIndex in 0 until blockCount) {
                            val blockData = mifareCard.readBlock(sectorStart + blockIndex)
                            val hexData = MifareUtils.bytesToHex(blockData)
                            sectorHex.append("Block ${sectorStart + blockIndex}: $hexData\n")
                            rawHexData.append(hexData)
                        }
                        
                        sectorData[sectorIndex] = sectorHex.toString()
                    } else {
                        sectorData[sectorIndex] = "Authentication failed (custom keys)"
                    }
                } catch (e: Exception) {
                    sectorData[sectorIndex] = "Read error: ${e.message}"
                }
            }
            
            // Analyze for Salto protocol
            val saltoAnalysis = SaltoProtocol.analyzeCardData(rawHexData.toString())
            
            CardData(
                uid = uid,
                cardType = cardType,
                timestamp = System.currentTimeMillis(),
                sectorData = sectorData,
                saltoData = saltoAnalysis,
                rawHexData = rawHexData.toString()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading card", e)
            null
        } finally {
            try {
                mifareCard.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing card", e)
            }
        }
    }
    
    private fun showCardDetails(cardData: CardData) {
        val details = buildString {
            append("Card UID: ${cardData.uid}\n")
            append("Type: ${cardData.cardType}\n")
            append("Scanned: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(cardData.timestamp))}\n\n")
            
            append("=== SECTOR DATA ===\n")
            cardData.sectorData.forEach { (sector, data) ->
                append("Sector $sector:\n$data\n")
            }
            
            append("\n=== SALTO ANALYSIS ===\n")
            if (cardData.saltoData != null) {
                append("User ID: ${cardData.saltoData.userId ?: "Unknown"}\n")
                append("Access Doors: ${cardData.saltoData.accessDoors?.joinToString(", ") ?: "None"}\n")
                append("Valid From: ${cardData.saltoData.validFrom ?: "Unknown"}\n")
                append("Valid Until: ${cardData.saltoData.validUntil ?: "Unknown"}\n")
                append("Card Status: ${cardData.saltoData.cardStatus ?: "Unknown"}\n")
                append("Issuer ID: ${cardData.saltoData.issuerId ?: "Unknown"}\n")
            } else {
                append("No Salto protocol data detected\n")
            }
            
            append("\n=== RAW HEX DATA ===\n")
            append(cardData.rawHexData.chunked(32).joinToString("\n"))
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Card Details: ${cardData.uid}")
            .setMessage(details)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Card Data", details)
                clipboard.setPrimaryClip(clip)
                showMessage("Card data copied to clipboard")
            }
            .show()
    }
    
    private fun exportScanData() {
        if (scannedCards.isEmpty()) {
            showMessage("No scan data to export")
            return
        }
        
        val exportData = buildString {
            append("MIFARE Card Scan Export\n")
            append("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
            append("Total Cards: ${scannedCards.size}\n")
            append("="*50 + "\n\n")
            
            scannedCards.forEachIndexed { index, card ->
                append("Card ${index + 1}: ${card.uid}\n")
                append("Type: ${card.cardType}\n")
                append("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(card.timestamp))}\n")
                
                if (card.saltoData != null) {
                    append("Salto User ID: ${card.saltoData.userId}\n")
                    append("Access Doors: ${card.saltoData.accessDoors?.joinToString(", ")}\n")
                }
                
                append("Raw Data: ${card.rawHexData.take(100)}...\n")
                append("-"*30 + "\n\n")
            }
        }
        
        // Copy to clipboard for now (could be enhanced to save to file)
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Scan Export", exportData)
        clipboard.setPrimaryClip(clip)
        
        showMessage("Scan data exported to clipboard")
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}