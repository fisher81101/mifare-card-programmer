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
import com.mifare.encoder.databinding.FragmentAdminWriteBinding
import com.mifare.encoder.utils.MifareUtils
import com.mifare.encoder.utils.SaltoProtocol
import kotlinx.coroutines.launch

class AdminWriteFragment : Fragment() {
    
    private var _binding: FragmentAdminWriteBinding? = null
    private val binding get() = _binding!!
    
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    
    private var isWriteModeEnabled = false
    
    companion object {
        private const val TAG = "AdminWriteFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminWriteBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupNFC()
        setupUI()
        loadDefaultValues()
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        
        if (nfcAdapter == null) {
            showMessage("NFC not supported on this device")
            binding.writeButton.isEnabled = false
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
    
    private fun setupUI() {
        binding.writeButton.setOnClickListener {
            if (validateInputs()) {
                enableWriteMode()
            }
        }
        
        binding.generateButton.setOnClickListener {
            generateSampleData()
        }
        
        binding.previewButton.setOnClickListener {
            previewCardData()
        }
        
        binding.resetButton.setOnClickListener {
            resetForm()
        }
        
        // Date picker setup for start/end dates
        binding.startDateEditText.setOnClickListener {
            showDatePicker { date ->
                binding.startDateEditText.setText(date)
            }
        }
        
        binding.endDateEditText.setOnClickListener {
            showDatePicker { date ->
                binding.endDateEditText.setText(date)
            }
        }
    }
    
    private fun loadDefaultValues() {
        // Load some example values
        binding.userIdEditText.setText("12345")
        binding.accessDoorsEditText.setText("101,102,103,201")
        binding.startDateEditText.setText("2025-01-01T08:00:00Z")
        binding.endDateEditText.setText("2025-12-31T18:00:00Z")
        binding.accessLevelSpinner.setSelection(0) // Guest
        binding.notesEditText.setText("Admin generated key")
    }
    
    private fun validateInputs(): Boolean {
        val userId = binding.userIdEditText.text.toString().trim()
        val doors = binding.accessDoorsEditText.text.toString().trim()
        val startDate = binding.startDateEditText.text.toString().trim()
        val endDate = binding.endDateEditText.text.toString().trim()
        
        if (userId.isEmpty()) {
            showMessage("User ID is required")
            return false
        }
        
        if (doors.isEmpty()) {
            showMessage("Access doors are required")
            return false
        }
        
        if (startDate.isEmpty() || endDate.isEmpty()) {
            showMessage("Start and end dates are required")
            return false
        }
        
        // Validate date format
        try {
            java.time.Instant.parse(startDate)
            java.time.Instant.parse(endDate)
        } catch (e: Exception) {
            showMessage("Invalid date format. Use ISO format: YYYY-MM-DDTHH:MM:SSZ")
            return false
        }
        
        return true
    }
    
    private fun enableWriteMode() {
        isWriteModeEnabled = true
        binding.statusText.text = "Write mode enabled. Hold card near device to program..."
        binding.statusText.setBackgroundColor(resources.getColor(android.R.color.holo_orange_light, null))
        binding.writeButton.text = "Cancel Write"
        binding.writeButton.setOnClickListener {
            disableWriteMode()
        }
        
        showMessage("Ready to write. Hold MIFARE card near device.")
    }
    
    private fun disableWriteMode() {
        isWriteModeEnabled = false
        binding.statusText.text = "Write mode disabled"
        binding.statusText.setBackgroundColor(resources.getColor(android.R.color.transparent, null))
        binding.writeButton.text = "Write to Card"
        binding.writeButton.setOnClickListener {
            if (validateInputs()) {
                enableWriteMode()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (isWriteModeEnabled) {
            nfcAdapter?.enableForegroundDispatch(
                requireActivity(),
                pendingIntent,
                intentFilters,
                techLists
            )
        }
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(requireActivity())
    }
    
    fun handleNfcIntent(intent: Intent) {
        if (!isWriteModeEnabled) return
        
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        
        binding.statusText.text = "Programming card..."
        
        lifecycleScope.launch {
            try {
                val success = writeCardData(tag)
                if (success) {
                    binding.statusText.text = "Card programmed successfully!"
                    binding.statusText.setBackgroundColor(resources.getColor(android.R.color.holo_green_light, null))
                    showMessage("Card programming completed successfully")
                } else {
                    binding.statusText.text = "Programming failed. Check card and try again."
                    binding.statusText.setBackgroundColor(resources.getColor(android.R.color.holo_red_light, null))
                    showMessage("Programming failed. Please try again.")
                }
                
                disableWriteMode()
                
            } catch (e: Exception) {
                Log.e(TAG, "Card write error", e)
                binding.statusText.text = "Write error: ${e.message}"
                binding.statusText.setBackgroundColor(resources.getColor(android.R.color.holo_red_light, null))
                showMessage("Error: ${e.message}")
                disableWriteMode()
            }
        }
    }
    
    private suspend fun writeCardData(tag: Tag): Boolean {
        val mifareCard = MifareClassic.get(tag) ?: return false
        
        return try {
            mifareCard.connect()
            
            // Generate card data based on form inputs
            val cardData = generateCardConfiguration()
            
            // Write to card using MifareUtils
            val writeSuccess = MifareUtils.writeCardData(mifareCard, cardData)
            
            if (writeSuccess) {
                Log.d(TAG, "Card programming successful")
                
                // Log the programming action with details
                val uid = MifareUtils.bytesToHex(tag.id)
                Log.i(TAG, "Card programmed - UID: $uid, User: ${cardData["userId"]}, Doors: ${cardData["doors"]}")
            }
            
            writeSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to card", e)
            false
        } finally {
            try {
                mifareCard.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing card", e)
            }
        }
    }
    
    private fun generateCardConfiguration(): Map<String, String> {
        return mapOf(
            "userId" to binding.userIdEditText.text.toString().trim(),
            "doors" to binding.accessDoorsEditText.text.toString().trim(),
            "startDate" to binding.startDateEditText.text.toString().trim(),
            "endDate" to binding.endDateEditText.text.toString().trim(),
            "accessLevel" to getSelectedAccessLevel(),
            "notes" to binding.notesEditText.text.toString().trim(),
            "issuerData" to binding.issuerDataEditText.text.toString().trim(),
            "cardType" to "mifare_classic_1k",
            "timestamp" to System.currentTimeMillis().toString()
        )
    }
    
    private fun getSelectedAccessLevel(): String {
        return when (binding.accessLevelSpinner.selectedItemPosition) {
            0 -> "guest"
            1 -> "resident"
            2 -> "staff"
            3 -> "manager"
            4 -> "admin"
            else -> "guest"
        }
    }
    
    private fun generateSampleData() {
        binding.userIdEditText.setText((10000..99999).random().toString())
        
        val sampleDoors = listOf("101", "102", "103", "201", "202", "301", "302", "401", "501")
        val selectedDoors = sampleDoors.shuffled().take((2..5).random())
        binding.accessDoorsEditText.setText(selectedDoors.joinToString(","))
        
        val now = java.time.Instant.now()
        val startDate = now.plusSeconds((0..7200).random().toLong()) // 0-2 hours from now
        val endDate = startDate.plusSeconds((86400..2592000).random().toLong()) // 1-30 days later
        
        binding.startDateEditText.setText(startDate.toString())
        binding.endDateEditText.setText(endDate.toString())
        
        binding.accessLevelSpinner.setSelection((0..4).random())
        binding.notesEditText.setText("Auto-generated sample data")
        
        showMessage("Sample data generated")
    }
    
    private fun previewCardData() {
        if (!validateInputs()) return
        
        val config = generateCardConfiguration()
        val saltoData = SaltoProtocol.generateSaltoPayload(config)
        
        val preview = buildString {
            append("=== CARD CONFIGURATION PREVIEW ===\n\n")
            append("User ID: ${config["userId"]}\n")
            append("Access Doors: ${config["doors"]}\n")
            append("Valid From: ${config["startDate"]}\n")
            append("Valid Until: ${config["endDate"]}\n")
            append("Access Level: ${config["accessLevel"]}\n")
            append("Notes: ${config["notes"]}\n\n")
            
            append("=== GENERATED SALTO DATA ===\n")
            append("Payload Size: ${saltoData.size} bytes\n")
            append("Hex Data: ${MifareUtils.bytesToHex(saltoData)}\n\n")
            
            append("=== WRITE PREVIEW ===\n")
            append("This data will be written to MIFARE sectors\n")
            append("Authentication will use default keys\n")
            append("Existing data will be overwritten\n")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Card Configuration Preview")
            .setMessage(preview)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Card Preview", preview)
                clipboard.setPrimaryClip(clip)
                showMessage("Preview copied to clipboard")
            }
            .show()
    }
    
    private fun resetForm() {
        binding.userIdEditText.text?.clear()
        binding.accessDoorsEditText.text?.clear()
        binding.startDateEditText.text?.clear()
        binding.endDateEditText.text?.clear()
        binding.notesEditText.text?.clear()
        binding.issuerDataEditText.text?.clear()
        binding.accessLevelSpinner.setSelection(0)
        
        binding.statusText.text = "Form reset. Enter new configuration."
        binding.statusText.setBackgroundColor(resources.getColor(android.R.color.transparent, null))
        
        disableWriteMode()
        showMessage("Form cleared")
    }
    
    private fun showDatePicker(onDateSelected: (String) -> Unit) {
        val calendar = java.util.Calendar.getInstance()
        
        val datePickerDialog = android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val timePickerDialog = android.app.TimePickerDialog(
                    requireContext(),
                    { _, hourOfDay, minute ->
                        val instant = java.time.LocalDateTime.of(year, month + 1, dayOfMonth, hourOfDay, minute)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toInstant()
                        onDateSelected(instant.toString())
                    },
                    calendar.get(java.util.Calendar.HOUR_OF_DAY),
                    calendar.get(java.util.Calendar.MINUTE),
                    true
                )
                timePickerDialog.show()
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
        
        datePickerDialog.show()
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}