package com.mifare.encoder.utils

import android.util.Log
import com.google.gson.Gson
import com.mifare.encoder.BuildConfig
import com.mifare.encoder.models.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

class ApiClient(private val baseUrl: String, private val apiKey: String) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(getCertificatePinner())
        .build()
    
    private val gson = Gson()
    
    companion object {
        private const val TAG = "ApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
    
    /**
     * Create certificate pinner for enhanced security
     * Production domains should have their actual certificate pins
     */
    private fun getCertificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        
        try {
            val host = URL(baseUrl).host
            
            // Certificate pinning infrastructure ready for production
            // TODO: Enable for production with actual certificate pins
            // To get pins: openssl s_client -connect domain:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
            
            // Certificate pinning temporarily disabled to prevent production breakage
            // Enable only after obtaining real SPKI SHA-256 pins for actual hostnames
            Log.d(TAG, "Certificate pinning infrastructure ready for host: $host")
            Log.d(TAG, "Production pinning disabled until real pins are configured")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring certificate pinning", e)
        }
        
        return builder.build()
    }
    
    /**
     * Test connection to the backend
     */
    suspend fun testConnection(): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/test"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    ApiResponse(
                        success = true,
                        message = "Connection successful"
                    )
                } else {
                    ApiResponse(
                        success = false,
                        message = "HTTP ${response.code}: ${response.message}"
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Test connection failed", e)
                ApiResponse(
                    success = false,
                    message = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    /**
     * Generate card configuration from backend
     */
    suspend fun generateConfig(config: Map<String, String>): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/android/generate-config"
                val jsonBody = gson.toJson(config)
                
                val requestBody = jsonBody.toRequestBody(JSON)
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                    apiResponse ?: ApiResponse(
                        success = false,
                        message = "Invalid response format"
                    )
                } else {
                    ApiResponse(
                        success = false,
                        message = "HTTP ${response.code}: $responseBody"
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Generate config failed", e)
                ApiResponse(
                    success = false,
                    message = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    /**
     * Submit card programming result to backend
     */
    suspend fun submitProgrammingResult(
        userId: String,
        cardUid: String,
        success: Boolean,
        errorMessage: String? = null
    ): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/android/programming-result"
                val data = mapOf(
                    "userId" to userId,
                    "cardUid" to cardUid,
                    "success" to success,
                    "errorMessage" to (errorMessage ?: ""),
                    "timestamp" to System.currentTimeMillis().toString()
                )
                
                val jsonBody = gson.toJson(data)
                val requestBody = jsonBody.toRequestBody(JSON)
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    ApiResponse(
                        success = true,
                        message = "Result submitted successfully"
                    )
                } else {
                    ApiResponse(
                        success = false,
                        message = "HTTP ${response.code}: $responseBody"
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Submit result failed", e)
                ApiResponse(
                    success = false,
                    message = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    /**
     * Get available card programs from backend
     */
    suspend fun getAvailablePrograms(): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/android/programs"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    ApiResponse(
                        success = true,
                        message = "Programs retrieved successfully",
                        cardData = responseBody
                    )
                } else {
                    ApiResponse(
                        success = false,
                        message = "HTTP ${response.code}: $responseBody"
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Get programs failed", e)
                ApiResponse(
                    success = false,
                    message = e.message ?: "Unknown error"
                )
            }
        }
    }
}