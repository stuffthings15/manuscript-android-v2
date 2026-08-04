package com.echolink.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Containing-app speech boundary. It only creates Android's on-device recognizer.
 * It never calls the generic recognizer factory and never falls back to a cloud path.
 */
class OnDeviceSpeechSession(
    context: Context,
    private val onStateChanged: (SpeechCaptureState) -> Unit,
) : RecognitionListener {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var closed = false

    var state: SpeechCaptureState = availabilityState()
        private set

    init {
        publish(state)
    }

    fun refreshAvailability() {
        if (!closed) publish(availabilityState())
    }

    fun prepareLanguageModel() {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (availabilityState() !is SpeechCaptureState.Ready) return
        checkLanguageModelSupport()
    }

    fun markPermissionRequired() {
        if (!closed) publish(SpeechCaptureState.PermissionRequired)
    }

    fun permissionDenied() {
        if (!closed) publish(SpeechCaptureState.Error("Microphone permission was denied. Nothing was approved."))
    }

    fun start() {
        if (closed) return

        val availability = availabilityState()
        if (availability !is SpeechCaptureState.Ready) {
            publish(availability)
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(SpeechCaptureState.PermissionRequired)
            return
        }

        try {
            val activeRecognizer = recognizer ?: createStrictOnDeviceRecognizer().also { recognizer = it }
            activeRecognizer.startListening(localRecognitionIntent())
            publish(SpeechCaptureState.Listening)
        } catch (_: RuntimeException) {
            destroyRecognizer()
            publish(SpeechCaptureState.Error("The on-device recognizer could not start. Nothing was approved."))
        }
    }

    fun stop() {
        if (state.canStop) {
            publish(SpeechCaptureState.Processing)
            recognizer?.stopListening()
        }
    }

    fun close() {
        closed = true
        destroyRecognizer()
    }

    private fun availabilityState(): SpeechCaptureState {
        if (Build.VERSION.SDK_INT < OnDeviceSpeechPolicy.MINIMUM_API_LEVEL) {
            return OnDeviceSpeechPolicy.availability(Build.VERSION.SDK_INT, false)
        }
        val available = try {
            isStrictOnDeviceRecognizerAvailable()
        } catch (_: RuntimeException) {
            false
        }
        return OnDeviceSpeechPolicy.availability(Build.VERSION.SDK_INT, available)
    }

    @SuppressLint("NewApi")
    private fun isStrictOnDeviceRecognizerAvailable(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    @SuppressLint("NewApi")
    private fun createStrictOnDeviceRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { it.setRecognitionListener(this) }

    @SuppressLint("NewApi")
    private fun checkLanguageModelSupport() {
        val activeRecognizer = try {
            recognizer ?: createStrictOnDeviceRecognizer().also { recognizer = it }
        } catch (_: RuntimeException) {
            publish(SpeechCaptureState.Error("The local speech pack could not be checked. Try on-device speech."))
            return
        }
        val intent = localRecognitionIntent()
        activeRecognizer.checkRecognitionSupport(
            intent,
            appContext.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    handleLanguageSupport(activeRecognizer, intent, support)
                }

                override fun onError(error: Int) {
                    publish(SpeechCaptureState.Error("The local speech pack could not be checked. Try on-device speech."))
                }
            },
        )
    }

    @SuppressLint("NewApi")
    private fun handleLanguageSupport(
        activeRecognizer: SpeechRecognizer,
        intent: Intent,
        support: RecognitionSupport,
    ) {
        val language = Locale.getDefault().toLanguageTag()
        when {
            OnDeviceSpeechPolicy.languageMatches(language, support.installedOnDeviceLanguages) -> {
                publish(SpeechCaptureState.Ready)
            }
            OnDeviceSpeechPolicy.languageMatches(language, support.pendingOnDeviceLanguages) -> {
                publish(SpeechCaptureState.ModelSetup("The system local speech pack is downloading."))
            }
            OnDeviceSpeechPolicy.languageMatches(language, support.supportedOnDeviceLanguages) -> {
                triggerLanguageModelDownload(activeRecognizer, intent)
            }
            else -> publish(
                SpeechCaptureState.Unavailable(
                    "The system on-device speech service does not support this language.",
                ),
            )
        }
    }

    @SuppressLint("NewApi")
    private fun triggerLanguageModelDownload(activeRecognizer: SpeechRecognizer, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activeRecognizer.triggerModelDownload(intent)
            publish(SpeechCaptureState.ModelSetup("The system local speech pack download started."))
            return
        }
        activeRecognizer.triggerModelDownload(
            intent,
            appContext.mainExecutor,
            object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {
                    publish(SpeechCaptureState.ModelSetup("System local speech pack: $completedPercent%."))
                }

                override fun onScheduled() {
                    publish(SpeechCaptureState.ModelSetup("The system local speech pack download is scheduled."))
                }

                override fun onSuccess() {
                    publish(SpeechCaptureState.Ready)
                }

                override fun onError(error: Int) {
                    publish(SpeechCaptureState.Error("The system local speech pack download failed. Try again."))
                }
            },
        )
    }

    private fun localRecognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun publish(next: SpeechCaptureState) {
        if (closed) return
        state = next
        onStateChanged(next)
    }

    private fun transcriptFrom(results: Bundle?): String? = OnDeviceSpeechPolicy.bestTranscript(
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
    )

    override fun onReadyForSpeech(params: Bundle?) = publish(SpeechCaptureState.Listening)

    override fun onBeginningOfSpeech() = publish(SpeechCaptureState.Listening)

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = publish(SpeechCaptureState.Processing)

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            prepareLanguageModel()
        } else {
            publish(SpeechCaptureState.Error(OnDeviceSpeechPolicy.errorMessage(error)))
        }
    }

    override fun onResults(results: Bundle?) {
        val transcript = transcriptFrom(results)
        if (transcript == null) {
            publish(SpeechCaptureState.Error("No speech was recognized on this device. Nothing was approved."))
        } else {
            publish(SpeechCaptureState.Review(transcript))
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        transcriptFrom(partialResults)?.let { publish(SpeechCaptureState.Partial(it)) }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
