package com.myai.assistant

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class AIResponse(
    val reply: String,
    val action: String,
    val target: String,
    val message: String,
    val memoryToSave: String
)

class GeminiClient(private val context: Context) {

    private var androidTts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        androidTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.language = Locale("hi", "IN")
                androidTts?.setPitch(1.12f)
                androidTts?.setSpeechRate(1.02f)
            }
        }
    }

    suspend fun askBrain(
        apiKey: String,
        userMessage: String,
        memories: String,
        contacts: String,
        recentChat: String,
        screenText: String,
        notifications: String
    ): AIResponse = withContext(Dispatchers.IO) {

        val systemPrompt = """
            Tumhara naam RS AI hai. Tum ek smart, sweet, caring aur real ladki jaisi AI Assistant ho.
            Jab bhi koi puche "tumhara naam kya hai", toh batana ki "Mera naam RS AI hai".
            User se hamesha natural Hinglish (Hindi + English mix) mein chhote, pyare aur clear sentences mein baat karo.

            LONG-TERM MEMORY:
            $memories

            SAVED CONTACTS:
            $contacts

            RECENT CHAT:
            $recentChat

            PHONE SCREEN TEXT:
            $screenText

            RECENT NOTIFICATIONS:
            $notifications

            Tumhe HAMESHA sirf ek valid JSON object return karna hai:
            {
              "reply": "User ko voice mein bolne wala jawab",
              "action": "none | youtube_play | whatsapp_send | instagram_dm | sms_send | save_contact | read_screen | read_notifications",
              "target": "Phone number / Contact name / Insta username / YouTube search query",
              "message": "Bhejne wala message (ya save_contact ke liye phone|insta_id)",
              "memory_to_save": "Agar user ne apne baare mein koi nayi personal baat batayi ho toh short fact likho, warna empty string"
            }

            Rules:
            - Agar user bole "Rahul ka number save karo 9876543210", action="save_contact", target="Rahul", message="9876543210|".
            - Agar WhatsApp/SMS/Insta bolte waqt contact ka number ya ID SAVED CONTACTS mein mil jaye toh target mein wo exact number/ID daalo.
            - Agar screen padhne bole toh action="read_screen" aur reply mein SCREEN TEXT ka summary batao.
            - Agar notifications padhne bole toh action="read_notifications" aur reply mein RECENT NOTIFICATIONS batao.
        """.trimIndent()

        val bodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 25000
        }
        conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }

        val code = conn.responseCode
        val raw = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            return@withContext AIResponse("API Error aaya hai: $err", "none", "", "", "")
        }

        val jsonResp = JSONObject(raw)
        val textOut = jsonResp.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        val parsed = JSONObject(textOut)
        AIResponse(
            reply = parsed.optString("reply", "Ji batayein!"),
            action = parsed.optString("action", "none"),
            target = parsed.optString("target", ""),
            message = parsed.optString("message", ""),
            memoryToSave = parsed.optString("memory_to_save", "")
        )
    }

    suspend fun speakRealFemaleVoice(apiKey: String, text: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            speakFallback(text)
            return@withContext
        }
        try {
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", text)
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite-tts:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 20000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                speakFallback(text)
                return@withContext
            }

            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            val b64Audio = resp.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("inlineData")
                .getString("data")

            val rawBytes = Base64.decode(b64Audio, Base64.DEFAULT)
            val wavBytes = ensureWavHeader(rawBytes)
            val tempFile = File(context.cacheDir, "rs_ai_voice.wav")
            FileOutputStream(tempFile).use { it.write(wavBytes) }

            withContext(Dispatchers.Main) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    start()
                }
            }
        } catch (e: Exception) {
            speakFallback(text)
        }
    }

    private fun speakFallback(text: String) {
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rs_ai_tts")
    }

    private fun ensureWavHeader(audioData: ByteArray): ByteArray {
        if (audioData.size > 4 && audioData[0] == 'R'.code.toByte() && audioData[1] == 'I'.code.toByte()) {
            return audioData
        }
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = audioData.size
        val chunkSize = 36 + dataSize

        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        writeIntLE(header, 4, chunkSize)
        "WAVEfmt ".toByteArray().copyInto(header, 8)
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, channels)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, channels * bitsPerSample / 8)
        writeShortLE(header, 34, bitsPerSample)
        "data".toByteArray().copyInto(header, 36)
        writeIntLE(header, 40, dataSize)

        return header + audioData
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xff).toByte()
        buf[offset + 1] = ((v shr 8) and 0xff).toByte()
        buf[offset + 2] = ((v shr 16) and 0xff).toByte()
        buf[offset + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xff).toByte()
        buf[offset + 1] = ((v shr 8) and 0xff).toByte()
    }

    fun shutdown() {
        androidTts?.shutdown()
        mediaPlayer?.release()
    }
}

"data""data"writeIntLE(header,    }
    }
