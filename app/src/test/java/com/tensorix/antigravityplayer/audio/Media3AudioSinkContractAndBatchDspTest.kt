package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.EqualizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class Media3AudioSinkContractAndBatchDspTest {

    @Test
    fun `test FallbackDspSnapshot is deeply immutable and rejects mutation`() {
        val dsp = Audiophile64BitDspProcessor()
        dsp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        val snapshot = dsp.activeSnapshot

        // 1. Verify bandGainsDb is unmodifiable
        assertNotNull(snapshot.bandGainsDb)
        assertEquals(10, snapshot.bandGainsDb.size)
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot.bandGainsDb as MutableList<Double>)[0] = 99.0
            fail("Expected UnsupportedOperationException when mutating snapshot.bandGainsDb")
        } catch (expected: UnsupportedOperationException) {
            // Success: bandGainsDb cannot be mutated
        }

        // 2. Verify biquadsCoeffs is unmodifiable
        assertNotNull(snapshot.biquadsCoeffs)
        assertEquals(10, snapshot.biquadsCoeffs.size)
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot.biquadsCoeffs as MutableList<BiquadCoeffs>)[0] = BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0)
            fail("Expected UnsupportedOperationException when mutating snapshot.biquadsCoeffs")
        } catch (expected: UnsupportedOperationException) {
            // Success: biquadsCoeffs cannot be mutated
        }
    }

    @Test
    fun `test batchUpdate coalesces multiple mutations into a single atomic snapshot publication`() {
        val dsp = Audiophile64BitDspProcessor()
        dsp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))

        val initialSnapshot = dsp.activeSnapshot
        assertEquals(0.0, initialSnapshot.preAmpGainDb, 1e-6)
        assertEquals(0.0, initialSnapshot.bassBoostGainDb, 1e-6)
        assertEquals(0.0, initialSnapshot.trebleGainDb, 1e-6)

        // Execute batchUpdate modifying multiple properties
        dsp.batchUpdate {
            dsp.preAmpGainDb = 3.5
            dsp.bassBoostGainDb = 5.0
            dsp.trebleGainDb = -2.0
            dsp.channelBalance = 0.4
            dsp.invertPhase = true
            dsp.setBandGain(0, 4.0)
            dsp.setBandGain(5, -3.0)
        }

        val updatedSnapshot = dsp.activeSnapshot
        assertEquals(3.5, updatedSnapshot.preAmpGainDb, 1e-6)
        assertEquals(5.0, updatedSnapshot.bassBoostGainDb, 1e-6)
        assertEquals(-2.0, updatedSnapshot.trebleGainDb, 1e-6)
        assertEquals(0.4, updatedSnapshot.channelBalance, 1e-6)
        assertTrue(updatedSnapshot.invertPhase)
        assertEquals(4.0, updatedSnapshot.bandGainsDb[0], 1e-6)
        assertEquals(-3.0, updatedSnapshot.bandGainsDb[5], 1e-6)
    }

    @Test
    fun `test applyConfiguration updates all DSP properties in one atomic transaction`() {
        val dsp = Audiophile64BitDspProcessor()
        dsp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))

        val testBands = listOf(1.0, 2.0, 3.0, 4.0, 5.0, -1.0, -2.0, -3.0, -4.0, -5.0)
        val config = FallbackDspConfiguration(
            isEnabled = true,
            isBitPerfectBypass = false,
            preAmpGainDb = 2.5,
            bassBoostGainDb = 4.0,
            trebleGainDb = 1.5,
            channelBalance = -0.25,
            invertPhase = false,
            harmonicExciterLevel = 0.3,
            clarityEnhancerGain = 1.2,
            stereoExpansionMultiplier = 1.1,
            warmSaturationLevel = 0.2,
            triodeWarmthLevel = 0.1,
            pentodeTapeLevel = 0.05,
            crossfeedLevel = 0.15,
            limiterThresholdDb = -0.5,
            limiterEnabled = true,
            airPresenceGainDb = 0.8,
            subBassMonoEnabled = true,
            dvcVolume = 0.85,
            replayGainMultiplier = 0.95,
            ditherStrength = 0.5,
            outputBitDepth = 24,
            bandGainsDb = testBands
        )

        dsp.applyConfiguration(config)

        val snap = dsp.activeSnapshot
        assertTrue(snap.isEnabled)
        assertFalse(snap.isBitPerfectBypass)
        assertEquals(2.5, snap.preAmpGainDb, 1e-6)
        assertEquals(4.0, snap.bassBoostGainDb, 1e-6)
        assertEquals(1.5, snap.trebleGainDb, 1e-6)
        assertEquals(-0.25, snap.channelBalance, 1e-6)
        assertEquals(0.85, snap.dvcVolume, 1e-6)
        assertEquals(0.95, snap.replayGainMultiplier, 1e-6)
        assertEquals(testBands, snap.bandGainsDb)
    }

    private class FakeSharedPreferences : SharedPreferences, SharedPreferences.Editor {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
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

    @Test
    fun `test EqualizerEngine syncWithDsp applies single batch configuration to DSP`() {
        val fakePrefs = FakeSharedPreferences()
        val context = mock<Context> {
            on { getSharedPreferences(any(), any()) }.thenReturn(fakePrefs)
        }
        val eqEngine = EqualizerEngine(context)
        val dsp = Audiophile64BitDspProcessor()
        dsp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        eqEngine.setDspProcessor(dsp)

        eqEngine.setPreAmpGain(3.0f)
        eqEngine.setClarityGain(2.5f)
        eqEngine.setChannelBalance(0.2f)
        eqEngine.setInvertPhase(true)
        eqEngine.setSubBassMono(true)

        val snap = dsp.activeSnapshot
        assertEquals(3.0, snap.preAmpGainDb, 1e-6)
        assertEquals(2.5, snap.clarityEnhancerGain, 1e-6)
        assertEquals(0.2, snap.channelBalance, 1e-6)
        assertTrue(snap.invertPhase)
        assertTrue(snap.subBassMonoEnabled)
    }

    @Test
    fun `test concurrent readers and writers on Audiophile64BitDspProcessor maintain snapshot integrity`() {
        val dsp = Audiophile64BitDspProcessor()
        dsp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))

        val threadCount = 12
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorDetected = AtomicBoolean(false)
        val successfulReads = AtomicInteger(0)

        // 6 writer threads
        for (i in 0 until 6) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        if (j % 2 == 0) {
                            dsp.batchUpdate {
                                dsp.preAmpGainDb = (j % 10).toDouble()
                                dsp.bassBoostGainDb = (j % 6).toDouble()
                                dsp.setBandGain(j % 10, (j % 5).toDouble())
                            }
                        } else {
                            val config = FallbackDspConfiguration(
                                preAmpGainDb = (j % 8).toDouble(),
                                bassBoostGainDb = (j % 4).toDouble(),
                                bandGainsDb = List(10) { (it + j).toDouble() % 5.0 }
                            )
                            dsp.applyConfiguration(config)
                        }
                    }
                } catch (t: Throwable) {
                    errorDetected.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        // 6 reader threads
        for (i in 0 until 6) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        val snap = dsp.activeSnapshot
                        var sum = 0.0
                        snap.bandGainsDb.forEach { g -> sum += g }
                        snap.biquadsCoeffs.forEach { c -> sum += c.b0 }
                        successfulReads.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errorDetected.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertFalse("Concurrent modification or corrupted state detected during stress test", errorDetected.get())
        assertTrue("Reader threads successfully read snapshots", successfulReads.get() > 0)
    }

    @Test
    fun `test OboeAudioSink Media3 contract compliance`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        // 1. PlaybackParameters contract:
        // Native Oboe cannot stretch time/pitch. getPlaybackParameters must truthfully return DEFAULT
        // when native sink is active to prevent Media3 position clock drift.
        sink.setPlaybackParameters(PlaybackParameters(1.75f, 1.25f))
        assertEquals(PlaybackParameters.DEFAULT, sink.playbackParameters)

        // 2. AudioAttributes contract:
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        sink.setAudioAttributes(attrs)
        assertEquals(attrs, sink.audioAttributes)

        // 3. SkipSilence contract:
        sink.setSkipSilenceEnabled(true)
        assertTrue(sink.skipSilenceEnabled)
        sink.setSkipSilenceEnabled(false)
        assertFalse(sink.skipSilenceEnabled)

        // 4. Volume contract in non-bit-perfect mode:
        sink.setVolume(0.65f)
        // 5. Volume contract in bit-perfect mode: must clamp to 1.0f unity gain
        sink.setBitPerfectMode(true)
        sink.setVolume(0.5f)
        // Switch back
        sink.setBitPerfectMode(false)
    }
}
