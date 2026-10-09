package com.jarvis.ai

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

enum class JState { IDLE, LISTENING, THINKING, SPEAKING }

/** Animated blue orb. Colour and speed change with the assistant state. */
class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    var state: JState = JState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private fun colorFor(): Int = when (state) {
        JState.IDLE -> Color.parseColor("#1E88E5")
        JState.LISTENING -> Color.parseColor("#00E5FF")
        JState.THINKING -> Color.parseColor("#FFB300")
        JState.SPEAKING -> Color.parseColor("#69F0AE")
    }

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val col = colorFor()
        val speed = when (state) {
            JState.IDLE -> 1
            JState.LISTENING -> 2
            JState.THINKING -> 4
            JState.SPEAKING -> 3
        }
        val angle = phase * speed
        val pulse = 1f + 0.06f * sin(angle * 2f * Math.PI.toFloat())
        val stroke = resources.displayMetrics.density

        // Soft glow
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(col, 200), withAlpha(col, 70), withAlpha(col, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 0.9f * pulse, paint)
        paint.shader = null

        // Rings
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(col, 230)
        paint.strokeWidth = 2f * stroke
        canvas.drawCircle(cx, cy, r * 0.6f, paint)

        paint.strokeWidth = 4f * stroke
        rect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        val start = angle * 360f
        canvas.drawArc(rect, start, 100f, false, paint)
        canvas.drawArc(rect, start + 180f, 100f, false, paint)

        paint.strokeWidth = 3f * stroke
        paint.color = withAlpha(col, 160)
        rect.set(cx - r * 0.45f, cy - r * 0.45f, cx + r * 0.45f, cy + r * 0.45f)
        canvas.drawArc(rect, -start, 70f, false, paint)
        canvas.drawArc(rect, -start + 120f, 70f, false, paint)
        canvas.drawArc(rect, -start + 240f, 70f, false, paint)

        // Core
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(Color.WHITE, 235)
        canvas.drawCircle(cx, cy, r * 0.12f * pulse, paint)
    }
}

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var orb: OrbView
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var replyText: TextView
    private lateinit var historyText: TextView
    private lateinit var historyScroll: ScrollView
    private lateinit var micButton: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val reqMic = 101
    private val reqPickContact = 202
    private var pendingAlias: String? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    }

    // Words that mean each saved contact
    private val aliasWords = mapOf(
        "father" to setOf("father", "papa", "dad", "daddy", "pita", "पापा", "पिता", "डैड"),
        "mother" to setOf("mother", "mummy", "mom", "maa", "mamma", "मम्मी", "माँ", "मां", "मॉम")
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(alias: String): String = alias.replaceFirstChar { it.uppercase() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildUi())
        setState(JState.IDLE)
        tts = TextToSpeech(this, this)
    }

    // ---------------------------------------------------------------- UI

    private fun makeChip(text: String, alias: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#80DEEA"))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#0A1A33"))
            setStroke(dp(1), Color.parseColor("#1E88E5"))
        }
        setOnClickListener { replyText.text = chooseContact(alias) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#000000"), Color.parseColor("#0A1A33"))
            )
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S"
            textSize = 22f
            letterSpacing = 0.3f
            setTextColor(Color.parseColor("#4FC3F7"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        statusText = TextView(this).apply {
            textSize = 13f
            letterSpacing = 0.15f
            setTextColor(Color.parseColor("#80DEEA"))
            gravity = Gravity.CENTER
        }
        root.addView(statusText)

        orb = OrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            topMargin = dp(12)
        })

        heardText = TextView(this).apply {
            text = "Tap the mic and speak"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(heardText)

        replyText = TextView(this).apply {
            text = "At your service, sir."
            textSize = 18f
            setTextColor(Color.parseColor("#B3E5FC"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(replyText)

        historyScroll = ScrollView(this)
        historyText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#78909C"))
        }
        historyScroll.addView(historyText)
        root.addView(
            historyScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        chips.addView(makeChip("Set Father", "father"))
        chips.addView(
            makeChip("Set Mother", "mother"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) }
        )
        root.addView(
            chips,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        micButton = TextView(this).apply {
            text = "\uD83C\uDF99"
            textSize = 32f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0D47A1"))
                setStroke(dp(3), Color.parseColor("#00E5FF"))
            }
            setOnClickListener { startListening() }
        }
        root.addView(micButton, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            topMargin = dp(12)
        })

        return root
    }

    private fun setState(s: JState) {
        orb.state = s
        statusText.text = when (s) {
            JState.IDLE -> "STANDBY"
            JState.LISTENING -> "LISTENING..."
            JState.THINKING -> "THINKING..."
            JState.SPEAKING -> "SPEAKING..."
        }
    }

    private fun showError(msg: String) {
        setState(JState.IDLE)
        replyText.text = msg
    }

    private fun respond(heard: String, reply: String) {
        heardText.text = "You: $heard"
        replyText.text = reply
        historyText.append("You: $heard\nJARVIS: $reply\n\n")
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
        speak(reply)
    }

    private fun say(reply: String) {
        replyText.text = reply
        historyText.append("JARVIS: $reply\n\n")
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
        speak(reply)
    }

    // ---------------------------------------------------------------- Speech output

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            return
        }
        val engine = tts ?: return
        val result = engine.setLanguage(Locale("en", "IN"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { setState(JState.IDLE) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { setState(JState.IDLE) }
            }
        })
        ttsReady = true
    }

    private fun speak(text: String) {
        val engine = tts
        if (ttsReady && engine != null) {
            setState(JState.SPEAKING)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        } else {
            setState(JState.IDLE)
        }
    }

    // ---------------------------------------------------------------- Speech input

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("Speech recognition is not available on this phone. Please make sure the Google app is installed and enabled.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Microphone permission")
                .setMessage("JARVIS needs the microphone only while you tap the mic button and speak a command. Nothing is recorded in the background.")
                .setPositiveButton("Allow") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqMic)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        heardText.text = "..."
        setState(JState.LISTENING)
        recognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqMic) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                showError("Microphone permission was denied, so I cannot hear you. You can allow it in phone Settings > Apps > JARVIS AI > Permissions.")
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!p.isNullOrEmpty()) heardText.text = p[0]
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val heard = list?.firstOrNull()
            if (heard.isNullOrBlank()) {
                showError("I didn't catch that, sir. Tap the mic and try again.")
            } else {
                setState(JState.THINKING)
                respond(heard, route(heard))
            }
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that, sir. Tap the mic and try again."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs an internet connection right now. Please check your network."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Please try again in a moment."
                SpeechRecognizer.ERROR_AUDIO -> "There was an audio problem. Please try again."
                else -> "Speech recognition error (code $error). Please try again."
            }
            showError(msg)
        }
    }

    // ---------------------------------------------------------------- Contacts and calling

    private fun findAlias(text: String): String? {
        val words = text.split(" ").toSet()
        return aliasWords.entries.firstOrNull { e -> e.value.any { it in words } }?.key
    }

    private fun chooseContact(alias: String): String {
        pendingAlias = alias
        return try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                reqPickContact
            )
            "Please choose the contact for ${label(alias)}, sir."
        } catch (e: Exception) {
            "I could not open your contacts, sir."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPickContact) return
        val alias = pendingAlias
        pendingAlias = null
        val uri = data?.data
        if (resultCode != RESULT_OK || alias == null || uri == null) {
            replyText.text = "No contact was selected, sir."
            return
        }
        var name = label(alias)
        var number = ""
        contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                number = c.getString(1) ?: ""
            }
        }
        val cleaned = number.filter { it.isDigit() || it == '+' }
        if (cleaned.isEmpty()) {
            say("I could not read a phone number for that contact, sir.")
            return
        }
        prefs.edit().putString("contact_$alias", "$name|$cleaned").apply()
        say("Saved $name as your ${label(alias)}, sir.")
    }

    private fun confirmCall(alias: String): String {
        val saved = prefs.getString("contact_$alias", null)
            ?: return "No ${label(alias)} contact is saved yet, sir. Tap Set ${label(alias)} below and choose the contact."
        val idx = saved.indexOf('|')
        val name = if (idx > 0) saved.substring(0, idx) else label(alias)
        val number = if (idx > 0) saved.substring(idx + 1) else saved
        AlertDialog.Builder(this)
            .setTitle("Call $name?")
            .setMessage("${label(alias)}: $number")
            .setPositiveButton("Open dialer") { _, _ -> openDialer(number) }
            .setNegativeButton("Cancel", null)
            .show()
        return "Do you want me to call $name, sir? Please confirm on the screen."
    }

    private fun openDialer(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        } catch (e: Exception) {
            replyText.text = "I could not open the dialer, sir."
        }
    }

    // ---------------------------------------------------------------- Local command router

    private fun has(text: String, vararg words: String): Boolean = words.any { text.contains(it) }

    private fun route(raw: String): String {
        val text = raw.lowercase(Locale.ROOT)
            .replace(Regex("[,.!?]"), " ")
            .replace(Regex("\\bjarvis\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val openWord = has(
            text, "open", "launch", "start", "kholo", "khol", "kholiye", "chalu", "chalao",
            "खोलो", "खोल", "चालू", "चलाओ"
        )
        val whatsapp = has(text, "whatsapp", "whats app", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सएप", "व्हाट्सअप")
        val youtube
