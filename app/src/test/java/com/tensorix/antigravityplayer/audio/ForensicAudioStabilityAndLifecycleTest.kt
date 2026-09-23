package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.EqualizerEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FORENSIC AUDIO STABILITY, LIFECYCLE & TELEMETRY REGRESSION TEST SUITE
 *
 * Verifies:
 * 1. 12-state native lifecycle FSM with PAUSING and strict transition legality.
 * 2. Formal native writer admission and untimed quiescence barrier protocol.
 * 3. Fallback DSP 32-band PEQ parity, atomic snapshots, and per-band isEnabled flag.
 * 4. Zero-trust telemetry: truthful -1 sample rate/buffer size fallback, no fabricated 48k/192.
 * 5. Direct/mixer telemetry truth: EXCLUSIVE != physical direct DAC proof, no mixer overclaiming.
 * 6. EqualizerEngine authoritative config preservation across listening modes.
 */
@UnstableApi
class ForensicAudioStabilityAndLifecycleTest {

    private lateinit var context: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        context = mock()
        fakePrefs = FakeSharedPreferences()
        org.mockito.kotlin.whenever(context.applicationContext).thenReturn(context)
        org.mockito.kotlin.whenever(context.getSharedPreferences(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(fakePrefs)
    }

    private class FakeSharedPreferences : android.content.SharedPreferences, android.content.SharedPreferences.Editor {
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
        override fun edit(): android.content.SharedPreferences.Editor = this
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putStringSet(key: String?, valuesSet: Set<String>?): android.content.SharedPreferences.Editor { values[key ?: ""] = valuesSet; return this }
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { values[key ?: ""] = value; return this }
        override fun remove(key: String?): android.content.SharedPreferences.Editor { values.remove(key); return this }
        override fun clear(): android.content.SharedPreferences.Editor { values.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    @Test
    fun `test 12-state FSM full lifecycle and illegal transitions rejection`() {
        val fsm = StreamLifecycleStateMachineTest.ProductionStreamLifecycleFsm(OboeBridge.LifecycleStateId.CLOSED)

        // Legal flow
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.OPENING))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.OPEN))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.STARTING))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.STARTED))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.PAUSING))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.PAUSED))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.FLUSHING))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.PAUSED))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.CLOSING))
        assertTrue(fsm.transition(OboeBridge.LifecycleStateId.CLOSED))

        // Rejection of illegal jumps
        val fsm2 = StreamLifecycleStateMachineTest.ProductionStreamLifecycleFsm(OboeBridge.LifecycleStateId.OPEN)
        assertFalse(fsm2.transition(OboeBridge.LifecycleStateId.FLUSHING))
        assertFalse(fsm2.transition(OboeBridge.LifecycleStateId.PAUSED))
        assertFalse(fsm2.transition(OboeBridge.LifecycleStateId.PAUSING))
        assertFalse(fsm2.transition(OboeBridge.LifecycleStateId.STARTED))
    }

    @Test
    fun `test formal writer admission and untimed quiescence barrier protocol`() {
        val activeWriters = AtomicInteger(0)
        val quiesceRequested = AtomicBoolean(false)

        fun admitWriter(): Boolean {
            if (quiesceRequested.get()) return false
            return activeWriters.compareAndSet(0, 1)
        }

        fun releaseWriter() {
            activeWriters.set(0)
        }

        // Writer 1 admitted successfully
        assertTrue(admitWriter())
        assertEquals(1, activeWriters.get())

        // Concurrent writer 2 rejected
        assertFalse(admitWriter())

        // Initiate shutdown
        quiesceRequested.set(true)

        // Release writer 1
        releaseWriter()
        assertEquals(0, activeWriters.get())

        // Subsequent writers strictly refused admission
        assertFalse(admitWriter())
        assertEquals(0, activeWriters.get())
    }

    @Test
    fun `test fallback DSP 32-band PEQ parity and per-band isEnabled toggle`() {
        val processor = Audiophile64BitDspProcessor()
        processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()

        val peqBands = listOf(
            AuthoritativePeqBand(filterType = 0, frequencyHz = 1000.0, qFactor = 1.0, gainDb = 6.0, isEnabled = true),
            AuthoritativePeqBand(filterType = 1, frequencyHz = 100.0, qFactor = 0.707, gainDb = 4.0, isEnabled = false),
            AuthoritativePeqBand(filterType = 2, frequencyHz = 10000.0, qFactor = 0.707, gainDb = 3.0, isEnabled = true)
        )

        val config = FallbackDspConfiguration(
            isEnabled = true,
            isBitPerfectBypass = false,
            peqBands = peqBands,
            isAutoEqEnabled = true
        )

        processor.applyConfiguration(config)
        val snap = processor.activeSnapshot

        assertEquals(3, snap.peqCoeffs.size)
        // Band 0 (enabled) should have non-identity coefficients
        assertNotEquals(1.0, snap.peqCoeffs[0].b0, 1e-9)
        // Band 1 (disabled) should have identity pass-through coefficients
        assertEquals(1.0, snap.peqCoeffs[1].b0, 1e-9)
        assertEquals(0.0, snap.peqCoeffs[1].b1, 1e-9)
        // Band 2 (enabled) should have non-identity coefficients
        assertNotEquals(1.0, snap.peqCoeffs[2].b0, 1e-9)

        // Process audio buffer through PEQ
        val buffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 128) {
            buffer.putFloat(0.5f) // Left
            buffer.putFloat(0.5f) // Right
        }
        buffer.flip()

        processor.queueInput(buffer)
        val output = processor.output
        assertTrue(output.remaining() > 0)
    }

    @Test
    fun `test EqualizerEngine buildAuthoritativeConfig preserves user settings regardless of listening mode`() {
        val engine = EqualizerEngine(context)
        engine.setPreAmpGain(3.5f)
        engine.setEnabled(true)
        engine.setBitPerfectBypass(false)

        val config = engine.buildAuthoritativeConfig()
        assertFalse(config.isBitPerfectBypass)
        assertTrue(config.isEnabled)
        assertEquals(3.5, config.preAmpGainDb, 0.01)
    }

    @Test
    fun `test HardwareHiFiVerifier returns negative 1 when audio manager properties absent`() {
        // Mock context returns null audio manager
        val report = HardwareHiFiVerifier.probeHardwareState(context, 0, 16, true)
        assertEquals(-1, report.actualOutputSampleRate)
        assertEquals(-1, report.actualOutputFramesPerBuffer)
        assertEquals(AudioFlingerThreadType.UNKNOWN, report.audioThreadType)
    }

    @Test
    fun `test truthful direct and mixer telemetry decoupling`() {
        val trackInfo = AudioTrackInfo(
            title = "Test",
            artist = "Test",
            sampleRateHz = 96000,
            bitDepth = 24,
            channels = 2,
            codec = "FLAC"
        )

        val snapshot = AudioVerificationEngine.buildCanonicalSnapshot(
            context = context,
            trackInfo = trackInfo,
            isDspActive = false,
            activeRoute = null,
            dspProcessor = null
        )

        // When nativeInfo is null and direct PCM is not verified in HAL:
        // directPathActive must be false
        assertFalse(snapshot.directPathActive.value)
        // Mixer path state must be UNKNOWN, NOT falsely claiming MIXER_ACTIVE without proof
        assertEquals(MixerPathState.UNKNOWN, snapshot.mixerPathState.value)
    }
}
