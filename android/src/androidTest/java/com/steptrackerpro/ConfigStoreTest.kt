package com.steptrackerpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steptrackerpro.core.ConfigStore
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.util.SecretVault
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remote sync headers never reach SharedPreferences in the clear: they are
 * sealed with a Keystore key, read back through it, and a 1.x config that
 * stored them in the clear is migrated on its first read.
 */
@RunWith(AndroidJUnit4::class)
class ConfigStoreTest {

    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences(ConfigStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun headersAreSealedNotStoredInTheClear() {
        ConfigStore(context).save(StepTrackerConfig(remoteSyncHeaders = mapOf("Authorization" to "Bearer s3cret")))
        Thread.sleep(100) // apply() is asynchronous
        val json = prefs.getString("config_json", null)!!
        assertFalse(json.contains("s3cret"))
        assertFalse(JSONObject(json).has("remoteSyncHeaders"))
        val sealed = prefs.getString("remote_headers_sealed", null)!!
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(sealed.contains("s3cret"))
        // A fresh store - a new process - reads them back through the Keystore.
        assertEquals("Bearer s3cret", ConfigStore(context).get().remoteSyncHeaders["Authorization"])
    }

    @Test
    fun clearingTheHeadersRemovesTheSealedEntry() {
        val store = ConfigStore(context)
        store.save(StepTrackerConfig(remoteSyncHeaders = mapOf("Authorization" to "x")))
        store.save(StepTrackerConfig())
        Thread.sleep(100)
        assertNull(prefs.getString("remote_headers_sealed", null))
        assertTrue(ConfigStore(context).get().remoteSyncHeaders.isEmpty())
    }

    @Test
    fun plaintextHeadersFromOneXAreMigratedOnFirstRead() {
        // What every 1.x release wrote: the headers inside the config JSON.
        val legacy = StepTrackerConfig(remoteSyncHeaders = mapOf("Authorization" to "Bearer legacy")).toJson()
        prefs.edit().putString("config_json", legacy.toString()).commit()
        assertEquals("Bearer legacy", ConfigStore(context).get().remoteSyncHeaders["Authorization"])
        Thread.sleep(100)
        assertFalse(prefs.getString("config_json", null)!!.contains("Bearer legacy"))
        assertTrue(prefs.getString("remote_headers_sealed", null)!!.startsWith("v1:"))
        assertEquals("Bearer legacy", ConfigStore(context).get().remoteSyncHeaders["Authorization"])
    }

    @Test
    fun theVaultRoundTripsAndRejectsTampering() {
        val sealed = SecretVault.seal("hello")!!
        assertEquals("hello", SecretVault.open(sealed))
        val tampered = sealed.dropLast(4) + "AAAA"
        assertNull(SecretVault.open(tampered))
        assertNull(SecretVault.open("not sealed"))
    }
}
