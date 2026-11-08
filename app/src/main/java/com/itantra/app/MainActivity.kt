package com.itantra.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var ip: EditText
    private lateinit var lang: Spinner
    private lateinit var msg: TextView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var myIdView: TextView
    private lateinit var locationView: TextView
    private lateinit var openMapButton: Button
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private lateinit var network: Network
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    private var seq = 0L
    private var speechStart = 0L
    private lateinit var myId: String
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var pendingEmergency = false

    private val languages = linkedMapOf(
        "English" to "en",
        "Hindi" to "hi",
        "Gujarati" to "gu",
        "Marathi" to "mr",
        "Kannada" to "kn",
        "Malayalam" to "ml",
        "Tamil" to "ta",
        "Telugu" to "te",
        "Odia" to "or",
        "Bengali" to "bn"
    )

    private val microphoneRequestCode = 7
    private val locationRequestCode = 20

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)

        ip = findViewById(R.id.ipInput)
        lang = findViewById(R.id.languageSpinner)
        msg = findViewById(R.id.messageView)
        status = findViewById(R.id.statusView)
        metrics = findViewById(R.id.metricsView)
        myIdView = findViewById(R.id.myIdView)
        locationView = findViewById(R.id.locationView)
        openMapButton = findViewById(R.id.openMapButton)

        lang.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.keys.toList()
        )

        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val prefs = getSharedPreferences("itantra", MODE_PRIVATE)
        myId = prefs.getString("myId", null)
            ?: UUID.randomUUID().toString().substring(0, 8).also {
                prefs.edit().putString("myId", it).apply()
            }

        myIdView.text = "Your ID (share this with your friend): $myId"

        network = Network(
            myId,
            { p ->
                runOnUiThread {
                    msg.text = p.message

                    val emergencyText = if (p.priority > 0) "EMERGENCY" else "NORMAL"
                    status.text = "Received: ${p.language}, priority $emergencyText"

                    if (p.latitude != null && p.longitude != null) {
                        lastLatitude = p.latitude
                        lastLongitude = p.longitude
                        locationView.text =
                            "Emergency location received:\n" +
                            "Latitude: ${p.latitude}\n" +
                            "Longitude: ${p.longitude}"
                        openMapButton.isEnabled = true
                    } else {
                        locationView.text = "No location attached to this message."
                        openMapButton.isEnabled = false
                    }

                    speak(p.message, p.language)

                    metrics.text =
                        "Packet received • ${p.message.toByteArray().size} message bytes\n" +
                        "Sequence: ${p.sequence}\n" +
                        "Timestamp: ${p.timestamp}" +
                        if (p.latitude != null && p.longitude != null) "\nLocation attached" else ""
                }
            },
            { s -> runOnUiThread { status.text = "Status: $s" } }
        )

        network.listen()

        findViewById<Button>(R.id.listenButton).setOnClickListener {
            network.listen()
        }

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            status.text = "Ready to send to ${ip.text.toString().trim()}"
        }

        findViewById<Button>(R.id.talkButton).setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startSpeech()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopSpeech()
                    true
                }
                else -> true
            }
        }

        findViewById<Button>(R.id.emergencyButton).setOnClickListener {
            startEmergencyFlow()
        }

        openMapButton.setOnClickListener {
            openLastLocationInMaps()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), microphoneRequestCode)
        }
    }

    private fun startSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), microphoneRequestCode)
            status.text = "Grant microphone permission, then try again"
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition unavailable on this device"
            return
        }

        speechStart = SystemClock.elapsedRealtime()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        recognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle) {
                val a = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!a.isNullOrEmpty()) sendText(a[0], 0)
            }

            override fun onError(e: Int) {
                status.text = "Speech: ${errorText(e)}"
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                languages.values.elementAt(lang.selectedItemPosition)
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer!!.startListening(i)
        status.text = "Listening…"
    }

    private fun errorText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that, try again"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again"
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Error code $e"
    }

    private fun stopSpeech() {
        recognizer?.stopListening()
    }

    private fun sendText(text: String, priority: Int) {
        val l = languages.values.elementAt(lang.selectedItemPosition)
        val p = Packet(
            language = l,
            priority = priority,
            sequence = ++seq,
            timestamp = System.currentTimeMillis(),
            message = text
        )

        msg.text = text

        val bytes = p.encode().size
        val elapsed = SystemClock.elapsedRealtime() - speechStart

        metrics.text =
            "Packet size: $bytes bytes\n" +
            "Speech/processing timing: ${elapsed} ms\n" +
            "Priority: ${if (priority > 0) "HIGH" else "NORMAL"}"

        val dest = ip.text.toString().trim()
        if (dest.isNotEmpty()) {
            network.send(dest, p)
        } else {
            status.text = "Message ready (enter your friend's ID to send)"
        }
    }

    private fun startEmergencyFlow() {
        val dest = ip.text.toString().trim()

        if (dest.isEmpty()) {
            status.text = "Enter your friend's ID first"
            return
        }

        pendingEmergency = true
        status.text = "Getting emergency location…"

        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        fetchLocationAndSendEmergency()
    }

    private fun fetchLocationAndSendEmergency() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        val cancellationToken = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationToken.token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                sendEmergencyWithLocation(location.latitude, location.longitude)
            } else {
                // Fall back to the most recent cached location if available.
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { last: Location? ->
                        if (last != null) {
                            sendEmergencyWithLocation(last.latitude, last.longitude)
                        } else {
                            pendingEmergency = false
                            status.text = "Could not get location. Turn on Location and try again."
                        }
                    }
                    .addOnFailureListener {
                        pendingEmergency = false
                        status.text = "Location unavailable: ${it.message}"
                    }
            }
        }.addOnFailureListener {
            pendingEmergency = false
            status.text = "Location error: ${it.message}"
        }
    }

    private fun sendEmergencyWithLocation(latitude: Double, longitude: Double) {
        pendingEmergency = false
        lastLatitude = latitude
        lastLongitude = longitude

        val l = languages.values.elementAt(lang.selectedItemPosition)
        val p = Packet(
            language = l,
            priority = 1,
            sequence = ++seq,
            timestamp = System.currentTimeMillis(),
            message = "Emergency assistance required.",
            latitude = latitude,
            longitude = longitude
        )

        msg.text = p.message
        locationView.text =
            "Emergency location captured:\n" +
            "Latitude: $latitude\n" +
            "Longitude: $longitude"
        openMapButton.isEnabled = true

        metrics.text =
            "Emergency packet size: ${p.encode().size} bytes\n" +
            "Priority: HIGH\n" +
            "Latitude: $latitude\n" +
            "Longitude: $longitude\n" +
            "Timestamp: ${p.timestamp}"

        val dest = ip.text.toString().trim()
        network.send(dest, p)
        status.text = "Emergency + GPS location sending…"
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            locationRequestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == locationRequestCode) {
            if (hasLocationPermission() && pendingEmergency) {
                fetchLocationAndSendEmergency()
            } else if (!hasLocationPermission()) {
                pendingEmergency = false
                status.text = "Location permission is required for emergency GPS."
            }
        }
    }

    private fun openLastLocationInMaps() {
        val lat = lastLatitude
        val lon = lastLongitude

        if (lat == null || lon == null) {
            status.text = "No location available"
            return
        }

        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Emergency location)")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        try {
            startActivity(intent)
        } catch (_: Exception) {
            status.text = "No map application available"
        }
    }

    private fun speak(text: String, code: String) {
        val loc = Locale.forLanguageTag(code)
        tts.language = loc
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "itantra")
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        network.stop()
        super.onDestroy()
    }
}
