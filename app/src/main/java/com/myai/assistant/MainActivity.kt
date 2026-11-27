package com.myai.assistant
import com.myai.assistant.R
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var memory: MemoryManager
    private lateinit var gemini: GeminiClient
    private lateinit var tvChatLog: TextView
    private lateinit var scrollChat: ScrollView
    private lateinit var etApiKey: EditText
    private lateinit var etCommand: EditText
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        memory = MemoryManager(this)
        gemini = GeminiClient(this)

        tvChatLog = findViewById(R.id.tvChatLog)
        scrollChat = findViewById(R.id.scrollChat)
        etApiKey = findViewById(R.id.etApiKey)
        etCommand = findViewById(R.id.etCommand)

        val prefs = getSharedPreferences("rs_ai_prefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("gemini_key", ""))

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = etApiKey.text.toString().trim()
            prefs.edit().putString("gemini_key", key).apply()
            Toast.makeText(this, "API Key Save ho gayi!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val cmd = etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                etCommand.setText("")
                processUserCommand(cmd)
            }
        }

        findViewById<Button>(R.id.btnMic).setOnClickListener {
            startVoiceListening()
        }

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun startVoiceListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Google Voice Recognition phone mein available nahi hai.", Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) { appendLog("🎙️ Sun rahi hoon...") }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) processUserCommand(spoken)
            }
            override fun onError(err: Int) { appendLog("⚠️ Awaaz samajh nahi aayi, dobara bolein.") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun processUserCommand(userText: String) {
        appendLog("\n👤 Aap: $userText")
        val apiKey = getSharedPreferences("rs_ai_prefs", MODE_PRIVATE).getString("gemini_key", "") ?: ""
        if (apiKey.isBlank()) {
            appendLog("👩 RS AI: Pehle upar apni Gemini API Key paste karke Save karein!")
            return
        }

        lifecycleScope.launch {
            val screenText = AssistantAccessibilityService.instance?.readCurrentScreenText() ?: "Screen access OFF hai"
            val notifs = AssistantNotificationService.getLatestSummary()

            val ai = gemini.askBrain(
                apiKey = apiKey,
                userMessage = userText,
                memories = memory.getAllFacts(),
                contacts = memory.getAllContactsText(),
                recentChat = memory.getRecentChat(),
                screenText = screenText,
                notifications = notifs
            )

            if (ai.memoryToSave.isNotBlank()) memory.saveFact(ai.memoryToSave)
            memory.addChatTurn("User", userText)
            memory.addChatTurn("RS AI", ai.reply)

            appendLog("👩 RS AI: ${ai.reply}")
            gemini.speakRealFemaleVoice(apiKey, ai.reply)
            executeAction(ai)
        }
    }

    private fun executeAction(ai: AIResponse) {
        when (ai.action) {
            "save_contact" -> {
                val parts = ai.message.split("|")
                val phone = parts.getOrNull(0) ?: ""
                val insta = parts.getOrNull(1) ?: ""
                memory.saveContact(ai.target, phone, insta)
            }
            "youtube_play" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(ai.target)}"))
                startActivity(intent)
                AssistantAccessibilityService.instance?.autoTapFirstYouTubeVideo()
            }
            "whatsapp_send" -> {
                var phone = ai.target.replace(" ", "")
                if (!phone.any { it.isDigit() }) {
                    phone = memory.getContact(ai.target).first ?: phone
                }
                if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"
                val url = "https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(ai.message)}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                AssistantAccessibilityService.instance?.autoTapWhatsAppSend()
            }
            "instagram_dm" -> {
                var instaId = ai.target.replace("@", "").trim()
                val savedInsta = memory.getContact(instaId).second
                if (!savedInsta.isNullOrBlank()) instaId = savedInsta
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/$instaId/"))
                startActivity(intent)
                AssistantAccessibilityService.instance?.autoSendInstagramDM(ai.message)
            }
            "sms_send" -> {
                var phone = ai.target.replace(" ", "")
                if (!phone.any { it.isDigit() }) {
                    phone = memory.getContact(ai.target).first ?: phone
                }
                try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(phone, null, ai.message, null, null)
                    appendLog("📩 SMS $phone par send ho gaya!")
                } catch (e: Exception) {
                    appendLog("⚠️ SMS Error: ${e.message}")
                }
            }
        }
    }

    private fun appendLog(msg: String) {
        tvChatLog.append("\n$msg")
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        gemini.shutdown()
    }
}
