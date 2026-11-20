package ir.kalapod.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskListener
import org.vosk.android.SpeechService

/** Callbacks from a recognizer; all invoked on the main thread. */
interface SpeechCallbacks {
    fun onReady() {}
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(message: String)
}

/** A speech-to-text engine. Exactly one session at a time. */
interface SpeechEngine {
    val id: String
    fun start(callbacks: SpeechCallbacks)
    /** Stops listening; a final result is still delivered for what was heard. */
    fun stop()
    fun cancel()
}

/**
 * Online: Android's system recognizer (Google on most phones) in Persian. Audio leaves the device,
 * so it is only used after the user consents (see [VoiceSettings]).
 */
class SystemSpeechEngine(private val context: Context) : SpeechEngine {
    override val id = "online"
    private var recognizer: SpeechRecognizer? = null

    companion object {
        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun start(callbacks: SpeechCallbacks) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(callbacks::onPartial)
            }
            override fun onResults(results: Bundle?) {
                callbacks.onFinal(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) = callbacks.onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "صدایی شنیده نشد. دوباره بگویید."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "اینترنت در دسترس نیست."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "اجازه میکروفون داده نشده است."
                    else -> "تشخیص گفتار انجام نشد (کد $error)."
                },
            )
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Shop commands are long; don't cut the user off at the first pause.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L),
        )
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.destroy(); recognizer = null }
}

/** Offline: Vosk with the small Persian model. Accuracy is lower, but nothing leaves the phone. */
class VoskSpeechEngine(private val model: Model) : SpeechEngine {
    override val id = "offline"
    private var service: SpeechService? = null
    private var heard = StringBuilder()

    override fun start(callbacks: SpeechCallbacks) {
        cancel()
        heard = StringBuilder()
        val rec = Recognizer(model, 16_000f)
        val s = SpeechService(rec, 16_000f)
        service = s
        s.startListening(object : VoskListener {
            override fun onPartialResult(hypothesis: String?) {
                val p = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
                callbacks.onPartial((heard.toString() + " " + p).trim())
            }
            override fun onResult(hypothesis: String?) {
                // Vosk splits on pauses; keep collecting until the user taps stop.
                hypothesis?.let { JSONObject(it).optString("text") }?.takeIf { it.isNotBlank() }?.let { heard.append(' ').append(it) }
                callbacks.onPartial(heard.toString().trim())
            }
            override fun onFinalResult(hypothesis: String?) {
                hypothesis?.let { JSONObject(it).optString("text") }?.takeIf { it.isNotBlank() }?.let { heard.append(' ').append(it) }
                callbacks.onFinal(heard.toString().trim())
            }
            override fun onError(exception: Exception?) = callbacks.onError("خطای میکروفون: ${exception?.message.orEmpty()}")
            override fun onTimeout() { s.stop() }
        })
        callbacks.onReady()
    }

    override fun stop() { service?.stop() }
    override fun cancel() { service?.shutdown(); service = null }
}
