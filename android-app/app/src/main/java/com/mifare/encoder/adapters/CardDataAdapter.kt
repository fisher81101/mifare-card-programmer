package com.mifare.encoder.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mifare.encoder.R
import com.mifare.encoder.models.CardData

class CardDataAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private var cardData: CardData? = null
    
    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SECTOR = 1
        private const val TYPE_SALTO_DATA = 2
    }
    
    fun updateData(newCardData: CardData?) {
        cardData = newCardData
        notifyDataSetChanged()
    }
    
    override fun getItemViewType(position: Int): Int {
        return when (position) {
            0 -> TYPE_HEADER
            in 1..(cardData?.sectors?.size ?: 0) -> TYPE_SECTOR
            else -> TYPE_SALTO_DATA
        }
    }
    
    override fun getItemCount(): Int {
        val card = cardData ?: return 0
        return 1 + card.sectors.size + if (card.saltoData != null) 1 else 0
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_card_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_SECTOR -> {
                val view = inflater.inflate(R.layout.item_sector_data, parent, false)
                SectorViewHolder(view)
            }
            TYPE_SALTO_DATA -> {
                val view = inflater.inflate(R.layout.item_salto_data, parent, false)
                SaltoDataViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val card = cardData ?: return
        
        when (holder) {
            is HeaderViewHolder -> holder.bind(card)
            is SectorViewHolder -> {
                val sectorIndex = position - 1
                if (sectorIndex < card.sectors.size) {
                    holder.bind(card.sectors[sectorIndex])
                }
            }
            is SaltoDataViewHolder -> {
                card.saltoData?.let { holder.bind(it) }
            }
        }
    }
    
    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val uidText: TextView = itemView.findViewById(R.id.uidText)
        private val typeText: TextView = itemView.findViewById(R.id.typeText)
        private val sizeText: TextView = itemView.findViewById(R.id.sizeText)
        
        fun bind(cardData: CardData) {
            uidText.text = "UID: ${cardData.uid}"
            typeText.text = "Type: ${cardData.cardType}"
            sizeText.text = "Size: ${cardData.size}"
        }
    }
    
    class SectorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sectorText: TextView = itemView.findViewById(R.id.sectorText)
        
        fun bind(sectorData: String) {
            sectorText.text = sectorData
        }
    }
    
    class SaltoDataViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val userIdText: TextView = itemView.findViewById(R.id.userIdText)
        private val accessRightsText: TextView = itemView.findViewById(R.id.accessRightsText)
        private val doorListText: TextView = itemView.findViewById(R.id.doorListText)
        private val timestampsText: TextView = itemView.findViewById(R.id.timestampsText)
        private val checksumText: TextView = itemView.findViewById(R.id.checksumText)
        private val validText: TextView = itemView.findViewById(R.id.validText)
        
        fun bind(saltoData: com.mifare.encoder.models.SaltoData) {
            userIdText.text = "User ID: ${saltoData.userId}"
            accessRightsText.text = "Access Rights: ${saltoData.accessRights.joinToString(", ")}"
            doorListText.text = "Doors: ${saltoData.doorList.joinToString(", ")}"
            timestampsText.text = "Valid: ${saltoData.startTimestamp} - ${saltoData.endTimestamp}"
            checksumText.text = "Checksum: ${saltoData.checksum}"
            validText.text = if (saltoData.isValid) "Valid Salto Data" else "Invalid/Unknown Format"
            validText.setTextColor(
                if (saltoData.isValid) 
                    itemView.context.getColor(R.color.success)
                else 
                    itemView.context.getColor(R.color.error)
            )
        }
    }
}