package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Trace
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.CarAudioProcessor
import me.timschneeberger.rootlessjamesdsp.audio.CarAudioSettings
import me.timschneeberger.rootlessjamesdsp.audio.CarAudioSettingsLoader
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.model.rootless.SessionRecordingPolicyEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.OnRootlessSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException

private inline fun <T> tracedPcm(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}


@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    // System services
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Media projection token
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    // Processing
    @Volatile
    private var recreateRecorderRequested = false
    private var recorderThread: Thread? = null
    @Volatile
    private var activeRecorder: AudioRecord? = null
    @Volatile
    private var activeTrack: AudioTrack? = null
    private lateinit var engine: JamesDspLocalEngine
    @Volatile
    private var carAudioProcessor: CarAudioProcessor? = null
    @Volatile
    private var carAudioSettings = CarAudioSettings()
    @Volatile
    private var carAudioTrack: AudioTrack? = null
    // Polled from the main thread so the realtime PCM loop only updates a volatile float.
    private val carAudioMeterHandler = Handler(Looper.getMainLooper())
    private val carAudioMeterPoller = object : Runnable {
        private var lastVolumeUpdateNanos = 0L

        override fun run() {
            if (isServiceDisposing) return
            val processor = carAudioProcessor
            val settings = carAudioSettings
            val meterVisible = preferencesVar.preferences.getBoolean(
                getString(R.string.key_is_activity_active),
                false,
            )
            if (processor != null) {
                processor.setMeterEnabled(settings.compressor.enabled && meterVisible)
                // AudioManager volume APIs cross into the framework through Binder. Keep that
                // work on the main/control thread; the PCM thread only reads the volatile gain.
                val nowNanos = System.nanoTime()
                if (settings.loudness.enabled && nowNanos - lastVolumeUpdateNanos >= VOLUME_UPDATE_INTERVAL_NANOS) {
                    processor.setEffectiveOutputGainDb(
                        determineEffectiveOutputGainDb(carAudioTrack, settings.outputPostGainDb)
                    )
                    lastVolumeUpdateNanos = nowNanos
                }
                if (settings.compressor.enabled && meterVisible) {
                    sendLocalBroadcast(Intent(Constants.ACTION_CAR_AUDIO_METER).apply {
                        putExtra(
                            Constants.EXTRA_CAR_AUDIO_GAIN_REDUCTION_DB,
                            processor.getGainReductionDb(),
                        )
                        putExtra(Constants.EXTRA_CAR_AUDIO_LOW_GAIN_REDUCTION_DB, processor.getLowGainReductionDb())
                        putExtra(Constants.EXTRA_CAR_AUDIO_MID_GAIN_REDUCTION_DB, processor.getMidGainReductionDb())
                        putExtra(Constants.EXTRA_CAR_AUDIO_HIGH_GAIN_REDUCTION_DB, processor.getHighGainReductionDb())
                    })
                }
            }
            val nextInterval = when {
                settings.compressor.enabled && meterVisible -> CAR_AUDIO_METER_INTERVAL_MS
                settings.loudness.enabled -> VOLUME_POLL_INTERVAL_MS
                else -> CAR_AUDIO_IDLE_POLL_INTERVAL_MS
            }
            carAudioMeterHandler.postDelayed(this, nextInterval)
        }
    }
    private val isRunning: Boolean
        get() = recorderThread != null

    // Session management
    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0

    // Idle detection
    @Volatile
    private var isProcessorIdle = false
    @Volatile
    private var suspendOnIdle = false

    // Exclude restricted apps flag
    private var excludeRestrictedSessions = false

    // Termination flags
    @Volatile
    private var isProcessorDisposing = false
    @Volatile
    private var isServiceDisposing = false

    // Shared preferences
    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if(isRunning)
            recreateRecorderRequested = true
    }

    override fun onCreate() {
        super.onCreate()

        Notifications.ensureChannels(this)

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup session manager
        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        // Setup core engine
        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()
        // Read DSP settings on the service/control thread. The realtime loop only consumes this
        // immutable snapshot and never touches SharedPreferences.
        carAudioSettings = CarAudioSettingsLoader.load(this)

        // Setup general-purpose broadcast receiver
        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        registerLocalReceiver(broadcastReceiver, filter)

        // Setup shared preferences
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)

        // No need to recreate in this stage
        recreateRecorderRequested = false

        carAudioMeterHandler.post(carAudioMeterPoller)

        // Launch foreground service
        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        Timber.d("onStartCommand")

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START -> {
                Timber.d("Starting service")
            }
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) {
            return START_NOT_STICKY
        }

        // Cancel outdated notifications
        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)

        // Setup media projection
        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)

        mediaProjection = try {
            mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                mediaProjectionStartIntent!!
            )
        }
        catch (ex: Exception) {
            Timber.e("Failed to acquire media projection")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            Timber.e(ex)
            null
        }

        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        } else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        isServiceDisposing = true
        carAudioMeterHandler.removeCallbacks(carAudioMeterPoller)

        // Stop recording and release engine
        stopRecording()
        engine.close()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Notify app about service termination
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)
        applicationScope.cancel()

        // Unregister receivers and release resources
        unregisterLocalReceiver(broadcastReceiver)
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection = null

        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()

        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

        stopSelf()
        super.onDestroy()
    }

    // Preferences listener
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        loadFromPreferences(key)
    }

    // Projection termination callback
    private val projectionCallback = object: MediaProjection.Callback() {
        override fun onStop() {
            if(isServiceDisposing) {
                // Planned shutdown
                return
            }

            if(preferencesVar.get<Boolean>(R.string.key_is_activity_active)) {
                // Activity in foreground, toast too disruptive
                return
            }

            Timber.w("Capture permission revoked. Stopping service.")

            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))

            this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))

            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopSelf()
        }
    }

    // General purpose broadcast receiver
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SAMPLE_RATE_UPDATED -> engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                ACTION_PREFERENCES_UPDATED -> {
                    engine.syncWithPreferences()
                    val updated = CarAudioSettingsLoader.load(this@RootlessAudioProcessorService)
                    carAudioSettings = updated
                    carAudioProcessor?.update(updated)
                }
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation()
            }
        }
    }

    // Session loss listener
    private val onSessionLossListener = object: RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_loss_ignore)) {
                // Check if retry count exceeded
                if(sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                    // Retry
                    sessionLossRetryCount++
                    Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                    sessionManager.pollOnce(false)
                    restartRecording()
                    return
                }
                else {
                    sessionLossRetryCount = 0
                    Timber.d("Giving up on saving session. User interaction required.")
                }

                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
                ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
                this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
                Timber.w("Terminating service due to session loss")
                stopSelf()
            }
        }
    }

    // Session change listener
    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.size == 0
            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")

            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                sessionList.map { it.value }.toTypedArray()
            )
        }
    }

    // App problem listener
    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) {
                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

                // Determine if we should redirect instantly, or push a non-intrusive notification
                if(preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                    preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)) {
                    startActivity(
                        ServiceNotificationHelper.createAppTroubleshootIntent(
                            this@RootlessAudioProcessorService,
                            mediaProjectionStartIntent,
                            uid,
                            directLaunch = true
                        )
                    )
                    notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
                }
                else
                    ServiceNotificationHelper.pushAppIssueNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent, uid)

                this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
                Timber.w("Terminating service due to app incompatibility; redirect user to troubleshooting options")
                stopSelf()
            }
        }
    }

    // Session policy change listener
    private val onSessionPolicyChangeListener = object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
        override fun onSessionRecordingPolicyChanged(sessionList: HashMap<String, SessionRecordingPolicyEntry>, isMinorUpdate: Boolean) {
            if(!this@RootlessAudioProcessorService.excludeRestrictedSessions) {
                Timber.d("onRestrictedSessionChanged: blocked; excludeRestrictedSessions disabled")
                return
            }

            if(!isMinorUpdate) {
                Timber.d("onRestrictedSessionChanged: major update detected; requesting soft-reboot")
                requestAudioRecordRecreation()
            }
            else {
                Timber.d("onRestrictedSessionChanged: minor update detected")
            }
        }
    }

    private fun loadFromPreferences(key: String?){
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
                Timber.d("Suspend on idle set to $suspendOnIdle")
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                Timber.d("Exclude restricted set to $excludeRestrictedSessions")

                requestAudioRecordRecreation()
            }
        }
    }

    // Request recreation of the AudioRecord object to update AudioPlaybackRecordingConfiguration
    fun requestAudioRecordRecreation() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("recreateAudioRecorder: service or processor already disposing")
            return
        }

        recreateRecorderRequested = true
    }

    // Start recording thread
    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        // Sanity check
        if (!hasRecordPermission()) {
            Timber.e("Record audio permission missing. Can't record")
            stopSelf()
            return
        }

        // Load preferences
        val encoding = AudioEncoding.fromInt(
            preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1
        )
        val bufferSize = preferences.get<Float>(R.string.key_audioformat_buffersize).toInt()
        val bufferSizeBytes = when (encoding) {
            AudioEncoding.PcmFloat -> bufferSize * Float.SIZE_BYTES
            else -> bufferSize * Short.SIZE_BYTES
        }
        val encodingFormat = when (encoding) {
            AudioEncoding.PcmShort -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val sampleRate = clamp(determineSamplingRate(), 44100, 48000)

        Timber.i("Sample rate: $sampleRate; Encoding: ${encoding.name}; " +
                "Buffer size: $bufferSize; Buffer size (bytes): $bufferSizeBytes ; " +
                "HAL buffer size (bytes): ${determineBufferSize()}")

        // Create recorder and track
        var recorder: AudioRecord
        val track: AudioTrack
        try {
            recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
            track = buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
        }
        catch(ex: Exception) {
            Timber.e("Failed to create initial audio record/track")
            Timber.e(ex)
            stopSelf()
            return
        }

        if(engine.sampleRate.toInt() != sampleRate || engine.streamBufferSamples != bufferSize) {
            Timber.d("Sampling rate changed to ${sampleRate}Hz")
            engine.configureStream(sampleRate.toFloat(), bufferSize, 2)
        }

        val pcmBuffers = createPcmProcessingBuffers(encoding, bufferSize)
        val localSettings = CarAudioSettingsLoader.load(this)
        carAudioSettings = localSettings
        val localCarProcessor = CarAudioProcessor(sampleRate).also {
            it.update(localSettings)
            if (pcmBuffers is ShortPcmProcessingBuffers) {
                it.prepare(bufferSize)
            }
        }
        carAudioProcessor = localCarProcessor
        carAudioTrack = track
        activeRecorder = recorder
        activeTrack = track

        // TODO Move all audio-related code to C++
        recorderThread = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                ServiceNotificationHelper.pushServiceNotification(applicationContext, arrayOf())
                var readErrorCount = 0

                while (!isProcessorDisposing) {
                    if(recreateRecorderRequested) {
                        recreateRecorderRequested = false
                        Timber.d("Recreating recorder without stopping thread...")

                        // Suspend track, release recorder
                        releaseAudioRecord(recorder)


                        if (mediaProjection == null) {
                            Timber.e("Media projection handle is null, stopping service")
                            stopSelf()
                            return@Thread
                        }

                        // Recreate recorder with new AudioPlaybackRecordingConfiguration
                        recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
                        activeRecorder = recorder
                        Timber.d("Recorder recreated")
                        if (isProcessorDisposing) break
                    }

                    // Suspend core while idle
                    if(isProcessorIdle && suspendOnIdle)
                    {
                        if(recorder.state == AudioRecord.STATE_INITIALIZED &&
                            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            recorder.stop()
                        if(track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState != AudioTrack.PLAYSTATE_STOPPED)
                            track.stop()

                        try {
                            Thread.sleep(50)
                        }
                        catch(e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    // Resume recorder if suspended
                    if(recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                        recorder.startRecording()
                    }
                    // Resume track if suspended
                    if(track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    // Choose encoding and process data. The same PCM array is reused across the
                    // recorder, optional car processor, native engine and track.
                    when (pcmBuffers) {
                        is ShortPcmProcessingBuffers -> {
                            val read = tracedPcm("JDSP.read") {
                                recorder.read(pcmBuffers.samples, 0, pcmBuffers.samples.size, AudioRecord.READ_BLOCKING)
                            }
                            if (read <= 0) {
                                if (read < 0) {
                                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                                        throw IOException("AudioRecord.read failed permanently: $read")
                                    }
                                    readErrorCount = retryAudioIo("AudioRecord.read", read, readErrorCount)
                                }
                                continue
                            }
                            readErrorCount = 0
                            val sampleCount = read - (read % 2)
                            if (sampleCount == 0) continue
                            if (localCarProcessor.requiresProcessing) {
                                tracedPcm("JDSP.car") {
                                    localCarProcessor.process(pcmBuffers.samples, pcmBuffers.samples, sampleCount)
                                }
                            }
                            tracedPcm("JDSP.engine") {
                                engine.processInt16(pcmBuffers.samples, pcmBuffers.samples, 0, sampleCount)
                            }
                            writePcm(track, pcmBuffers.samples, sampleCount)
                        }
                        is FloatPcmProcessingBuffers -> {
                            val read = tracedPcm("JDSP.read") {
                                recorder.read(pcmBuffers.samples, 0, pcmBuffers.samples.size, AudioRecord.READ_BLOCKING)
                            }
                            if (read <= 0) {
                                if (read < 0) {
                                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                                        throw IOException("AudioRecord.read failed permanently: $read")
                                    }
                                    readErrorCount = retryAudioIo("AudioRecord.read", read, readErrorCount)
                                }
                                continue
                            }
                            readErrorCount = 0
                            val sampleCount = read - (read % 2)
                            if (sampleCount == 0) continue
                            if (localCarProcessor.requiresProcessing) {
                                tracedPcm("JDSP.car") {
                                    localCarProcessor.process(pcmBuffers.samples, pcmBuffers.samples, sampleCount)
                                }
                            }
                            tracedPcm("JDSP.engine") {
                                engine.processFloat(pcmBuffers.samples, pcmBuffers.samples, 0, sampleCount)
                            }
                            writePcm(track, pcmBuffers.samples, sampleCount)
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.w(e)
                // ignore
            } catch (e: Exception) {
                Timber.e("Exception in recorderThread raised")
                Timber.e(e)
                stopSelf()
            } finally {
                // Clean up recorder and track
                if (carAudioTrack === track) {
                    carAudioTrack = null
                }
                releaseAudioRecord(recorder)
                releaseAudioTrack(track)
                if (activeRecorder === recorder) activeRecorder = null
                if (activeTrack === track) activeTrack = null
                if (carAudioProcessor === localCarProcessor) {
                    carAudioProcessor = null
                }
            }
        }
        recorderThread!!.start()
    }

    private fun writePcm(track: AudioTrack, samples: ShortArray, sampleCount: Int) {
        if (sampleCount < 0 || (sampleCount and 1) != 0 || sampleCount > samples.size) {
            throw IOException("Invalid stereo PCM sample count: $sampleCount")
        }
        var offset = 0
        var retryCount = 0
        while (offset < sampleCount) {
            val written = tracedPcm("JDSP.write") {
                track.write(samples, offset, sampleCount - offset, AudioTrack.WRITE_BLOCKING)
            }
            if (written <= 0) {
                if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                    throw IOException("AudioTrack.write failed permanently: $written")
                }
                retryCount = retryAudioIo("AudioTrack.write", written, retryCount)
                continue
            }
            retryCount = 0
            if ((written and 1) != 0) throw IOException("AudioTrack.write crossed stereo boundary: $written")
            offset += written
        }
    }

    private fun writePcm(track: AudioTrack, samples: FloatArray, sampleCount: Int) {
        if (sampleCount < 0 || (sampleCount and 1) != 0 || sampleCount > samples.size) {
            throw IOException("Invalid stereo PCM sample count: $sampleCount")
        }
        var offset = 0
        var retryCount = 0
        while (offset < sampleCount) {
            val written = tracedPcm("JDSP.write") {
                track.write(samples, offset, sampleCount - offset, AudioTrack.WRITE_BLOCKING)
            }
            if (written <= 0) {
                if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                    throw IOException("AudioTrack.write failed permanently: $written")
                }
                retryCount = retryAudioIo("AudioTrack.write", written, retryCount)
                continue
            }
            retryCount = 0
            if ((written and 1) != 0) throw IOException("AudioTrack.write crossed stereo boundary: $written")
            offset += written
        }
    }

    // Terminate recording thread
    fun stopRecording() {
        val thread = recorderThread ?: return
        isProcessorDisposing = true
        stopAudioRecord(activeRecorder)
        stopAudioTrack(activeTrack)
        thread.interrupt()
        thread.join(STOP_JOIN_TIMEOUT_MS)
        if (thread.isAlive) {
            // A blocking framework call can ignore interrupt(). Releasing the handles is the
            // fallback that unblocks AudioRecord/AudioTrack; never clear recorderThread before the
            // old thread has actually exited, otherwise a restart could create a second loop.
            releaseAudioRecord(activeRecorder)
            releaseAudioTrack(activeTrack)
            thread.join()
        }
        recorderThread = null
        activeRecorder = null
        activeTrack = null
    }

    private fun retryAudioIo(operation: String, errorCode: Int, retryCount: Int): Int {
        if (retryCount >= MAX_AUDIO_IO_RETRIES) {
            throw IOException("$operation failed after retries: $errorCode")
        }
        val nextRetryCount = retryCount + 1
        Timber.w("$operation returned $errorCode; retry $nextRetryCount/$MAX_AUDIO_IO_RETRIES")
        Thread.sleep(AUDIO_IO_RETRY_DELAY_MS)
        return nextRetryCount
    }

    private fun stopAudioRecord(record: AudioRecord?) {
        if (record == null) return
        runCatching {
            if (record.state != AudioRecord.STATE_UNINITIALIZED &&
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
    }

    private fun releaseAudioRecord(record: AudioRecord?) {
        if (record == null) return
        stopAudioRecord(record)
        runCatching { record.release() }
    }

    private fun stopAudioTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching {
            if (track.state != AudioTrack.STATE_UNINITIALIZED &&
                track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                track.stop()
            }
        }
    }

    private fun releaseAudioTrack(track: AudioTrack?) {
        if (track == null) return
        stopAudioTrack(track)
        runCatching { track.release() }
    }

    // Hard restart recording thread
    fun restartRecording() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("restartRecording: service or processor already disposing")
            return
        }

        stopRecording()
        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
    }

    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)

        sdkAbove(Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()

        val frameSizeInBytes: Int = if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            2 /* channels */ * 2 /* bytes */
        } else {
            2 /* channels */ * 4 /* bytes */
        }

        val bufferSize = if (((bufferSizeBytes % frameSizeInBytes) != 0 || bufferSizeBytes < 1)) {
            Timber.e("Invalid audio buffer size $bufferSizeBytes")
            128 * (bufferSizeBytes / 128)
        }
        else bufferSizeBytes

        Timber.d("Using buffer size $bufferSize")

        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributesBuilder.build())
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        if (!hasRecordPermission()) {
            Timber.e("buildAudioRecord: RECORD_AUDIO not granted")
            throw RuntimeException("RECORD_AUDIO not granted")
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        val excluded = (if(excludeRestrictedSessions)
            sessionManager.sessionPolicyDatabase.getRestrictedUids().toList()
        else {
            sessionManager.pollOnce(false)
            emptyList()
        }).toMutableList()

        blockedApps.value?.map { it.uid }?.let {
            excluded += it
        }
        excluded += Process.myUid()

        excluded.forEach { configBuilder.excludeUid(it) }
        sessionManager.sessionDatabase.setExcludedUids(excluded.toTypedArray())
        sessionManager.pollOnce(false)

        Timber.d("buildAudioRecord: Excluded UIDs: ${excluded.joinToString("; ")}")

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
    }

    // Determine HAL sampling rate
    private fun determineSamplingRate(): Int {
        val sampleRateStr: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val srate = sampleRateStr?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 48000
        Timber.i("Real HAL sampling rate is $srate")
        return srate
    }

    // Determine HAL buffer size
    private fun determineBufferSize(): Int {
        val framesPerBuffer: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 256
    }

    /**
     * Android exposes the routed music stream level in dB on API 28+. If a head unit does not
     * report it, the DSP post-gain remains a safe fallback instead of freezing loudness at a
     * stale volume value.
     */
    private fun determineEffectiveOutputGainDb(track: AudioTrack?, postGain: Float): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || track == null) return postGain

        return try {
            val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val deviceType = track.routedDevice?.type ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            val streamDb = audioManager.getStreamVolumeDb(
                AudioManager.STREAM_MUSIC,
                index,
                deviceType,
            )
            if (streamDb.isFinite()) streamDb + postGain else postGain
        } catch (ex: Exception) {
            Timber.d("Unable to read routed stream volume; using DSP post-gain")
            postGain
        }
    }

    companion object {
        const val SESSION_LOSS_MAX_RETRIES = 1

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"
        const val CAR_AUDIO_METER_INTERVAL_MS = 100L
        const val VOLUME_POLL_INTERVAL_MS = 200L
        const val CAR_AUDIO_IDLE_POLL_INTERVAL_MS = 1000L
        const val VOLUME_UPDATE_INTERVAL_NANOS = 200_000_000L
        private const val STOP_JOIN_TIMEOUT_MS = 500L
        private const val MAX_AUDIO_IO_RETRIES = 3
        private const val AUDIO_IO_RETRY_DELAY_MS = 2L

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        fun stop(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStopIntent(context))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }
    }
}
