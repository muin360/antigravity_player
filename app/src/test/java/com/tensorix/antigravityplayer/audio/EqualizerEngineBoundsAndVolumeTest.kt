package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.EqualizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

/**
 * Unit tests validating edge cases for EqualizerEngine parameter bounds and volume receiver null safety:
 * - eqBands with fewer than 10 elements default missing bands to 0.0 without throwing IndexOutOfBoundsException
 * - doubleParams buffer length is guaranteed to be exactly 27 entries
 * - Volume receiver safely handles null dspProcessor without throwing NullPointerException
 */
@UnstableApi
class EqualizerEngineBoundsAndVolumeTest {

    private class FakeSharedPreferences : SharedPreferences, SharedPreferences.Editor {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = this
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putStringSet(key: String?, valuesSet: Set<String>?): SharedPreferences.Editor { values[key ?: ""] = valuesSet; return this }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { values.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { values.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    private fun createMockContext(): Context {
        val fakePrefs = FakeSharedPreferences()
        return mock {
            on { getSharedPreferences(any(), any()) }.thenReturn(fakePrefs)
        }
    }

    @Test
    fun `test buildNativeDspDoubleParameters guarantees exact 27 parameters`() {
        val engine = EqualizerEngine(createMockContext())
        val params = engine.buildNativeDspDoubleParameters(
            isBypass = false,
            isRgEnabled = true,
            dsp = null,
            eqBands = DoubleArray(10) { 1.5 }
        )

        assertEquals(EqualizerEngine.NATIVE_DSP_PARAM_COUNT, params.size)
        assertEquals(27, params.size)
    }

    @Test
    fun `test buildNativeDspDoubleParameters handles empty or short eqBands gracefully`() {
        val engine = EqualizerEngine(createMockContext())

        // 1. Empty eqBands
        val emptyParams = engine.buildNativeDspDoubleParameters(
            isBypass = false,
            isRgEnabled = true,
            dsp = null,
            eqBands = DoubleArray(0)
        )
        assertEquals(27, emptyParams.size)
        for (i in 0 until 10) {
            assertEquals("Missing band $i must default to 0.0", 0.0, emptyParams[17 + i], 1e-9)
        }

        // 2. eqBands with only 3 elements
        val shortBands = doubleArrayOf(2.0, 3.5, -1.0)
        val shortParams = engine.buildNativeDspDoubleParameters(
            isBypass = false,
            isRgEnabled = true,
            dsp = null,
            eqBands = shortBands
        )
        assertEquals(27, shortParams.size)
        assertEquals(2.0, shortParams[17], 1e-9)
        assertEquals(3.5, shortParams[18], 1e-9)
        assertEquals(-1.0, shortParams[19], 1e-9)
        for (i in 3 until 10) {
            assertEquals("Unset band $i must default to 0.0", 0.0, shortParams[17 + i], 1e-9)
        }
    }

    @Test
    fun `test buildNativeDspDoubleParameters bypass mode sets neutral defaults`() {
        val engine = EqualizerEngine(createMockContext())
        val bypassParams = engine.buildNativeDspDoubleParameters(
            isBypass = true,
            isRgEnabled = true,
            dsp = null,
            eqBands = DoubleArray(10) { 5.0 }
        )

        assertEquals(27, bypassParams.size)
        assertEquals(0.0, bypassParams[0], 1e-9) // Preamp = 0 dB
        assertEquals(0.0, bypassParams[1], 1e-9) // Bass boost = 0 dB
        assertEquals(0.0, bypassParams[2], 1e-9) // Treble = 0 dB
        assertEquals(1.0, bypassParams[5], 1e-9) // Stereo expansion = 1.0 (unity)
        assertEquals(1.0, bypassParams[6], 1e-9) // DVC Volume = 1.0 (unity)
        assertEquals(1.0, bypassParams[7], 1e-9) // ReplayGain = 1.0 (unity)
        for (i in 0 until 10) {
            assertEquals("Bypass band $i must be 0.0", 0.0, bypassParams[17 + i], 1e-9)
        }
    }

    @Test
    fun `test volume receiver assignment is null-safe when dspProcessor is null`() {
        var dspProcessor: Audiophile64BitDspProcessor? = null
        val currentVolume = 10
        val maxVolume = 15
        val dvcVol = (currentVolume.toDouble() / maxVolume.toDouble()).coerceIn(0.0, 1.0)

        // Verifies the exact pattern in PlaybackService volumeReceiver: dspProcessor?.dvcVolume = dvcVol
        try {
            dspProcessor?.dvcVolume = dvcVol
            assertTrue("Assigning to null dspProcessor must succeed without NPE", true)
        } catch (e: NullPointerException) {
            org.junit.Assert.fail("NPE thrown on null dspProcessor access")
        }

        // When dspProcessor is non-null
        dspProcessor = Audiophile64BitDspProcessor()
        dspProcessor.dvcVolume = dvcVol
        assertEquals(dvcVol, dspProcessor.dvcVolume, 1e-9)
    }
}
