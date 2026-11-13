package com.leftovers.app.data.cohabit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

open class CohabitApiException(val statusCode: Int, message: String) : Exception(message)
class CohabitUnauthorizedException(message: String = "Unauthorized: Invalid API key or expired token.") : CohabitApiException(401, message)
class CohabitForbiddenException(message: String = "Forbidden: Cross-user access or encryption scope violation.") : CohabitApiException(403, message)

class CohabitBridgeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "CohabitBridgeService"
        const val DEFAULT_BASE_URL = "https://ais-pre-fakno4cgzgk52rt56zapwg-628972999672.asia-southeast1.run.app"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun buildRequest(url: String, apiKey: String, builderAction: Request.Builder.() -> Unit = {}): Request {
        val cleanKey = apiKey.trim()
        val builder = Request.Builder()
            .url(url)
            .addHeader("x-api-key", cleanKey)
            .addHeader("Authorization", "Bearer $cleanKey")
            .addHeader("Accept", "application/json")
        builder.builderAction()
        return builder.build()
    }

    private fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().removeSuffix("/")

    /**
     * 1. Master Encrypted Bridge Sync (`GET /api/v1/bridge/sync`)
     */
    suspend fun getMasterSync(baseUrl: String, apiKey: String): Result<CohabitBridgeSyncResult> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/bridge/sync"
        try {
            val request = buildRequest(cleanUrl, apiKey) { get() }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> return@withContext Result.failure(CohabitUnauthorizedException())
                    403 -> return@withContext Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val json = JSONObject(bodyStr)
                        val dataObj = json.optJSONObject("data") ?: JSONObject()
                        
                        // Parse shifts
                        val shifts = parseShifts(dataObj.optJSONArray("shifts"))
                        // Parse shopping list
                        val shoppingList = parseShoppingList(dataObj.optJSONArray("shoppingList"))
                        // Parse expenses
                        val expenses = parseExpenses(dataObj.optJSONArray("expenses"))
                        // Parse personal finance
                        val pfObj = dataObj.optJSONObject("personalFinance")
                        val pf = pfObj?.let { parsePersonalFinance(it) }

                        val result = CohabitBridgeSyncResult(
                            success = json.optBoolean("success", true),
                            authenticatedOwner = json.optString("authenticatedOwner", "Ozric"),
                            encryptionScope = json.optString("encryptionScope", "USER_OZRIC"),
                            shifts = shifts,
                            shoppingList = shoppingList,
                            expenses = expenses,
                            personalFinance = pf,
                        )
                        Result.success(result)
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network failure during master sync", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Parsing failure during master sync", e)
            Result.failure(e)
        }
    }

    /**
     * 2. Shopping List & Leftover Food Inventory
     */
    suspend fun getShoppingList(baseUrl: String, apiKey: String, completed: Boolean = false): Result<List<CohabitShoppingItem>> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/shopping-list?completed=$completed"
        try {
            val request = buildRequest(cleanUrl, apiKey) { get() }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val array = if (bodyStr.trim().startsWith("[")) {
                            JSONArray(bodyStr)
                        } else {
                            val obj = JSONObject(bodyStr)
                            obj.optJSONArray("items") ?: JSONArray()
                        }
                        Result.success(parseShoppingList(array))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addShoppingItem(
        baseUrl: String,
        apiKey: String,
        title: String,
        quantity: String,
        category: String,
        priority: String,
        notes: String,
        estimatedCost: Double?
    ): Result<CohabitShoppingItem> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/shopping-list"
        try {
            val json = JSONObject().apply {
                put("title", title)
                put("quantity", quantity.ifBlank { "1 item" })
                put("category", category)
                put("priority", priority)
                put("notes", notes)
                if (estimatedCost != null) put("estimatedCost", estimatedCost)
            }
            val request = buildRequest(cleanUrl, apiKey) {
                post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val obj = JSONObject(bodyStr)
                        val item = if (obj.has("item")) obj.getJSONObject("item") else obj
                        Result.success(parseSingleShoppingItem(item))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateShoppingItem(
        baseUrl: String,
        apiKey: String,
        id: String,
        completed: Boolean? = null,
        title: String? = null,
        quantity: String? = null,
    ): Result<CohabitShoppingItem> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/shopping-list/$id"
        try {
            val json = JSONObject().apply {
                if (completed != null) put("completed", completed)
                if (title != null) put("title", title)
                if (quantity != null) put("quantity", quantity)
            }
            val request = buildRequest(cleanUrl, apiKey) {
                patch(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val obj = JSONObject(bodyStr)
                        val item = if (obj.has("item")) obj.getJSONObject("item") else obj
                        Result.success(parseSingleShoppingItem(item))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteShoppingItem(
        baseUrl: String,
        apiKey: String,
        id: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/shopping-list/$id"
        try {
            val request = buildRequest(cleanUrl, apiKey) { delete() }
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> Result.success(true)
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 3. Work Shifts & Meal Prep Schedule (`GET /api/v1/shifts`)
     */
    suspend fun getShifts(baseUrl: String, apiKey: String): Result<List<CohabitShift>> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/shifts"
        try {
            val request = buildRequest(cleanUrl, apiKey) { get() }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val array = if (bodyStr.trim().startsWith("[")) {
                            JSONArray(bodyStr)
                        } else {
                            val obj = JSONObject(bodyStr)
                            obj.optJSONArray("shifts") ?: JSONArray()
                        }
                        Result.success(parseShifts(array))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 4. Shared Grocery & Food Expenses (`/api/v1/expenses`)
     */
    suspend fun getExpenses(baseUrl: String, apiKey: String): Result<Pair<List<CohabitExpense>, CohabitLedgerSummary>> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/expenses"
        try {
            val request = buildRequest(cleanUrl, apiKey) { get() }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val obj = JSONObject(bodyStr)
                        val expenses = parseExpenses(obj.optJSONArray("expenses"))
                        val ledgerObj = obj.optJSONObject("ledger")
                        val ledger = CohabitLedgerSummary(
                            debtSummary = ledgerObj?.optString("debtSummary") ?: "Balanced",
                            netDifference = ledgerObj?.optDouble("netDifference") ?: 0.0,
                        )
                        Result.success(Pair(expenses, ledger))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logExpense(
        baseUrl: String,
        apiKey: String,
        title: String,
        amount: Double,
        category: String = "Shared Groceries",
        notes: String = ""
    ): Result<CohabitExpense> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/expenses"
        try {
            val json = JSONObject().apply {
                put("title", title)
                put("amount", amount)
                put("splitType", "50/50")
                put("category", category)
                put("notes", notes)
            }
            val request = buildRequest(cleanUrl, apiKey) {
                post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val obj = JSONObject(bodyStr)
                        val expenseObj = if (obj.has("expense")) obj.getJSONObject("expense") else obj
                        Result.success(parseSingleExpense(expenseObj))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 5. Private Personal Finance Vault (`GET /api/v1/personal-finance`)
     */
    suspend fun getPersonalFinance(baseUrl: String, apiKey: String): Result<CohabitPersonalFinance> = withContext(Dispatchers.IO) {
        val cleanUrl = "${normalizeBaseUrl(baseUrl)}/api/v1/personal-finance"
        try {
            val request = buildRequest(cleanUrl, apiKey) { get() }
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> Result.failure(CohabitUnauthorizedException())
                    403 -> Result.failure(CohabitForbiddenException())
                    in 200..299 -> {
                        val obj = JSONObject(bodyStr)
                        Result.success(parsePersonalFinance(obj))
                    }
                    else -> Result.failure(CohabitApiException(response.code, "HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Parsing helpers
    private fun parseShoppingList(array: JSONArray?): List<CohabitShoppingItem> {
        if (array == null) return emptyList()
        val list = mutableListOf<CohabitShoppingItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(parseSingleShoppingItem(obj))
        }
        return list
    }

    private fun parseSingleShoppingItem(obj: JSONObject): CohabitShoppingItem {
        return CohabitShoppingItem(
            id = obj.optString("id", obj.optString("_id", System.currentTimeMillis().toString())),
            title = obj.optString("title", "Untitled Item"),
            quantity = obj.optString("quantity", "1"),
            category = obj.optString("category", "Produce & Groceries"),
            priority = obj.optString("priority", "Normal"),
            addedBy = obj.optString("addedBy", ""),
            completed = obj.optBoolean("completed", false),
            notes = obj.optString("notes", ""),
            estimatedCost = if (obj.has("estimatedCost") && !obj.isNull("estimatedCost")) obj.optDouble("estimatedCost") else null,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
        )
    }

    private fun parseShifts(array: JSONArray?): List<CohabitShift> {
        if (array == null) return emptyList()
        val list = mutableListOf<CohabitShift>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(
                CohabitShift(
                    id = obj.optString("id", i.toString()),
                    user = obj.optString("user", ""),
                    date = obj.optString("date", ""),
                    startTime = obj.optString("startTime", ""),
                    endTime = obj.optString("endTime", ""),
                    title = obj.optString("title", "Shift"),
                    employerName = obj.optString("employerName", ""),
                    netHours = obj.optDouble("netHours", 0.0),
                    isSickLeave = obj.optBoolean("isSickLeave", false),
                    isDayOff = obj.optBoolean("isDayOff", false),
                    completed = obj.optBoolean("completed", false),
                    notes = obj.optString("notes", ""),
                )
            )
        }
        return list
    }

    private fun parseExpenses(array: JSONArray?): List<CohabitExpense> {
        if (array == null) return emptyList()
        val list = mutableListOf<CohabitExpense>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(parseSingleExpense(obj))
        }
        return list
    }

    private fun parseSingleExpense(obj: JSONObject): CohabitExpense {
        return CohabitExpense(
            id = obj.optString("id", System.currentTimeMillis().toString()),
            title = obj.optString("title", "Expense"),
            amount = obj.optDouble("amount", 0.0),
            paidBy = obj.optString("paidBy", "Ozric"),
            splitType = obj.optString("splitType", "50/50"),
            category = obj.optString("category", "Shared Groceries"),
            notes = obj.optString("notes", ""),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
        )
    }

    private fun parsePersonalFinance(obj: JSONObject): CohabitPersonalFinance {
        val summaryObj = obj.optJSONObject("summary")
        val totalBal = summaryObj?.optDouble("totalBalanceEur") ?: obj.optDouble("totalBalanceEur", 0.0)
        val accountsArr = obj.optJSONArray("accounts")
        val txArr = obj.optJSONArray("transactions")

        return CohabitPersonalFinance(
            totalBalanceEur = totalBal,
            accountsCount = accountsArr?.length() ?: 0,
            transactionsCount = txArr?.length() ?: 0,
        )
    }
}

