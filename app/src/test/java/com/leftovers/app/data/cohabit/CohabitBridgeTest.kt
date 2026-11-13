package com.leftovers.app.data.cohabit

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CohabitBridgeTest {

    private lateinit var server: MockWebServer
    private lateinit var service: CohabitBridgeService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = CohabitBridgeService()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testRoommateScopesAndDefaultKeys() {
        val ozric = CohabitRoommate.fromName("Ozric")
        assertEquals("USER_OZRIC", ozric.scope)
        assertEquals("leaftover-ozric-key-2026", ozric.defaultKey)

        val pawan = CohabitRoommate.fromName("Pawan")
        assertEquals("USER_PAWAN", pawan.scope)
        assertEquals("leaftover-pawan-key-2026", pawan.defaultKey)

        // Case insensitivity
        assertEquals(CohabitRoommate.PAWAN, CohabitRoommate.fromName("pAwAn"))
        assertEquals(CohabitRoommate.OZRIC, CohabitRoommate.fromName("unknown"))
    }

    @Test
    fun testShiftPackedMealCalculation() {
        val regularShift = CohabitShift(
            id = "s1",
            user = "Ozric",
            date = "2026-11-14",
            startTime = "07:00",
            endTime = "15:30",
            title = "Morning Shift",
            netHours = 8.0,
            isSickLeave = false,
            isDayOff = false,
        )
        assertTrue(regularShift.needsPackedMeal)

        val dayOff = regularShift.copy(isDayOff = true)
        assertFalse(dayOff.needsPackedMeal)

        val sickLeave = regularShift.copy(isSickLeave = true)
        assertFalse(sickLeave.needsPackedMeal)

        val zeroHours = regularShift.copy(netHours = 0.0)
        assertFalse(zeroHours.needsPackedMeal)
    }

    @Test
    fun testShoppingItemPriority() {
        val urgent = CohabitShoppingItem(
            id = "1",
            title = "Milk",
            priority = "Urgent (Running Low)",
        )
        assertTrue(urgent.isUrgent)

        val normal = urgent.copy(priority = "Normal")
        assertFalse(normal.isUrgent)
    }

    @Test
    fun testMasterBridgeSyncSuccess() = runBlocking {
        val mockJson = """
            {
              "success": true,
              "authenticatedOwner": "Ozric",
              "encryptionScope": "USER_OZRIC",
              "data": {
                "shifts": [
                  {
                    "id": "sh_101",
                    "user": "Ozric",
                    "date": "2026-11-15",
                    "startTime": "08:00",
                    "endTime": "16:00",
                    "title": "Kitchen Prep",
                    "employerName": "Bistro",
                    "netHours": 7.5,
                    "isSickLeave": false,
                    "isDayOff": false,
                    "completed": false,
                    "notes": "Bring packed lunch"
                  }
                ],
                "shoppingList": [
                  {
                    "id": "sp_201",
                    "title": "Leftover Lasagna",
                    "quantity": "2 containers",
                    "category": "Produce & Groceries",
                    "priority": "Urgent (Running Low)",
                    "addedBy": "Ozric",
                    "completed": false,
                    "notes": "Top fridge shelf",
                    "estimatedCost": null
                  }
                ],
                "expenses": [
                  {
                    "id": "ex_301",
                    "title": "Aldi Groceries",
                    "amount": 34.50,
                    "paidBy": "Ozric",
                    "splitType": "50/50",
                    "category": "Shared Groceries",
                    "notes": "Veggies and eggs"
                  }
                ],
                "personalFinance": {
                  "summary": { "totalBalanceEur": 1420.50 },
                  "accounts": [{"id": "acc1", "name": "Main Checking"}],
                  "transactions": [{"id": "tx1", "description": "Coffee"}]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val baseUrl = server.url("").toString()
        val result = service.getMasterSync(baseUrl, "leaftover-ozric-key-2026")

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals("Ozric", data.authenticatedOwner)
        assertEquals("USER_OZRIC", data.encryptionScope)

        assertEquals(1, data.shifts.size)
        assertEquals("sh_101", data.shifts[0].id)
        assertTrue(data.shifts[0].needsPackedMeal)

        assertEquals(1, data.shoppingList.size)
        assertEquals("Leftover Lasagna", data.shoppingList[0].title)
        assertTrue(data.shoppingList[0].isUrgent)

        assertEquals(1, data.expenses.size)
        assertEquals(34.50, data.expenses[0].amount, 0.001)

        assertNotNull(data.personalFinance)
        assertEquals(1420.50, data.personalFinance!!.totalBalanceEur, 0.001)
        assertEquals(1, data.personalFinance!!.accountsCount)

        // Verify headers sent to server
        val recorded = server.takeRequest()
        assertEquals("leaftover-ozric-key-2026", recorded.getHeader("x-api-key"))
        assertEquals("Bearer leaftover-ozric-key-2026", recorded.getHeader("Authorization"))
        assertEquals("/api/v1/bridge/sync", recorded.path)
    }

    @Test
    fun testAddShoppingItem() = runBlocking {
        val mockResponse = """
            {
              "item": {
                "id": "new_123",
                "title": "Greek Yogurt",
                "quantity": "1 tub",
                "category": "Dairy & Eggs",
                "priority": "Normal",
                "completed": false,
                "notes": "",
                "estimatedCost": 2.99
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockResponse))

        val baseUrl = server.url("").toString()
        val result = service.addShoppingItem(
            baseUrl = baseUrl,
            apiKey = "leaftover-ozric-key-2026",
            title = "Greek Yogurt",
            quantity = "1 tub",
            category = "Dairy & Eggs",
            priority = "Normal",
            notes = "",
            estimatedCost = 2.99,
        )

        assertTrue(result.isSuccess)
        val item = result.getOrThrow()
        assertEquals("new_123", item.id)
        assertEquals("Greek Yogurt", item.title)
        assertEquals(2.99, item.estimatedCost!!, 0.001)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/shopping-list", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("Greek Yogurt"))
    }

    @Test
    fun testLogExpense5050() = runBlocking {
        val mockResponse = """
            {
              "expense": {
                "id": "exp_888",
                "title": "Supermarket Weekly",
                "amount": 50.00,
                "paidBy": "Ozric",
                "splitType": "50/50",
                "category": "Shared Groceries",
                "notes": "Shared pantry items"
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockResponse))

        val baseUrl = server.url("").toString()
        val result = service.logExpense(
            baseUrl = baseUrl,
            apiKey = "leaftover-ozric-key-2026",
            title = "Supermarket Weekly",
            amount = 50.00,
            category = "Shared Groceries",
            notes = "Shared pantry items",
        )

        assertTrue(result.isSuccess)
        val expense = result.getOrThrow()
        assertEquals(50.00, expense.amount, 0.001)
        assertEquals("50/50", expense.splitType)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/expenses", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("50/50"))
    }

    @Test
    fun testUnauthorizedError401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid credentials"))

        val baseUrl = server.url("").toString()
        val result = service.getMasterSync(baseUrl, "bad-key")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CohabitUnauthorizedException)
    }

    @Test
    fun testForbiddenCrossUserScopeError403() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Cross-user scope prohibited"))

        val baseUrl = server.url("").toString()
        val result = service.getPersonalFinance(baseUrl, "leaftover-ozric-key-2026")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CohabitForbiddenException)
    }
}

