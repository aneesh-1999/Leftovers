package com.leftovers.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaymentAppPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: PaymentAppPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("payment_apps", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PaymentAppPrefs(context)
    }

    @Test
    fun defaultAppsContainPopularPresets() {
        val appList = prefs.apps.value
        val pkgs = appList.map { it.pkg }.toSet()

        assertTrue(pkgs.contains("com.revolut.revolut"))
        assertTrue(pkgs.contains("com.f1soft.esewa"))
        assertTrue(pkgs.contains("com.khalti"))
        assertTrue(pkgs.contains("co.uk.getmondo"))
        assertTrue(pkgs.contains("com.wise.android"))

        val revolut = appList.first { it.pkg == "com.revolut.revolut" }
        assertEquals("Revolut", revolut.name)
        assertFalse(revolut.isCustom)
    }

    @Test
    fun addAndRemoveCustomApp() {
        val customPkg = "com.nepal.bank.smart"
        val customName = "Nepal Smart Bank"

        prefs.addCustomApp(pkg = customPkg, name = customName, read = true, accountId = 42L)

        val updated = prefs.apps.value
        val customApp = updated.firstOrNull { it.pkg == customPkg }
        assertNotNull(customApp)
        assertEquals(customName, customApp?.name)
        assertTrue(customApp?.read == true)
        assertTrue(customApp?.isCustom == true)
        assertEquals(42L, customApp?.accountId)
        assertEquals(customName, prefs.nameFor(customPkg))
        assertEquals(42L, prefs.accountFor(customPkg))
        assertTrue(prefs.isRead(customPkg))

        // Re-load in a fresh instance to test persistence
        val freshPrefs = PaymentAppPrefs(context)
        val persistedApp = freshPrefs.apps.value.firstOrNull { it.pkg == customPkg }
        assertNotNull(persistedApp)
        assertEquals(customName, persistedApp?.name)
        assertTrue(persistedApp?.isCustom == true)

        // Remove custom app
        freshPrefs.removeCustomApp(customPkg)
        val afterRemoval = freshPrefs.apps.value.firstOrNull { it.pkg == customPkg }
        assertNull(afterRemoval)
        assertFalse(freshPrefs.isRead(customPkg))
        assertNull(freshPrefs.accountFor(customPkg))
    }

    @Test
    fun toggleReadAndAccountRouting() {
        val revolutPkg = "com.revolut.revolut"
        prefs.setRead(revolutPkg, false)
        assertFalse(prefs.isRead(revolutPkg))

        prefs.setAccount(revolutPkg, 101L)
        assertEquals(101L, prefs.accountFor(revolutPkg))

        prefs.setAccount(revolutPkg, null)
        assertNull(prefs.accountFor(revolutPkg))
    }
}
