package com.yoyostudios.clashcompanion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yoyostudios.clashcompanion.api.AiConnection
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.deck.DeckPresets
import com.yoyostudios.clashcompanion.deck.DeckPreset
import com.yoyostudios.clashcompanion.deck.BattleMode
import com.yoyostudios.clashcompanion.command.CommandRouter
import com.yoyostudios.clashcompanion.detection.CardClassifier
import com.yoyostudios.clashcompanion.detection.CardTemplateMatcher
import com.yoyostudios.clashcompanion.detection.HandDetector
import com.yoyostudios.clashcompanion.overlay.OverlayManager
import com.yoyostudios.clashcompanion.speech.SpeechService
import com.yoyostudios.clashcompanion.speech.SpeechLanguage
import com.yoyostudios.clashcompanion.strategy.OpusCoach
import com.yoyostudios.clashcompanion.ui.screens.MainScreen
import com.yoyostudios.clashcompanion.ui.theme.ClashCompanionTheme
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ClashCompanion"
        private const val REQUEST_MIC_PERMISSION = 100
    }

    // ── Reactive state for Compose ──
    val overlayGranted = mutableStateOf(false)
    val accessibilityEnabled = mutableStateOf(false)
    val micGranted = mutableStateOf(false)
    val captureRunning = mutableStateOf(false)
    val captureHealth = mutableStateOf("Sin captura")
    val speechReady = mutableStateOf(false)
    val speechLoading = mutableStateOf(false)
    val deckCards = mutableStateOf<List<DeckManager.CardInfo>>(emptyList())
    val deckModelWarning = mutableStateOf<String?>(null)
    val deckImportError = mutableStateOf<String?>(null)
    val opusStatus = mutableStateOf("")
    val opusComplete = mutableStateOf(false)
    val aiStatus = mutableStateOf("Sin configurar")
    val speechError = mutableStateOf<String?>(null)
    val voiceLanguage = mutableStateOf("es")
    val battleMode = mutableStateOf(BattleMode.CLASSIC)

    private var overlayManager: OverlayManager? = null
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stateRefresh = object : Runnable {
        override fun run() {
            refreshPermissionStates()
            stateHandler.postDelayed(this, 500L)
        }
    }
    private var analysisJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Coordinates.init(this)
        AiConnection.initialize(this)
        voiceLanguage.value = SpeechLanguage.get(this)
        DeckManager.restoreBattleMode(this)
        battleMode.value = DeckManager.battleMode
        aiStatus.value = if (AiConnection.isConfigured()) "Guardado. Toca para comprobar" else "Sin configurar"
        DeckManager.loadCardDatabase(assets)
        CardClassifier.init(this)

        // Register MediaProjection result handler
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                refreshPermissionStates()
            }
        }

        // Request mic on launch
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION
            )
        }

        // Load saved deck
        if (DeckManager.loadSavedDeck(this)) {
            deckCards.value = DeckManager.currentDeck
            updateDeckModelWarning(DeckManager.currentDeck)
            OpusCoach.loadSavedPlaybook(this)
            if (OpusCoach.cachedPlaybook != null) {
                opusStatus.value = "Playbook loaded"
                opusComplete.value = true
            }
        }

        // A previous build may have silently persisted only seven cards.
        deckImportError.value = DeckManager.lastImportError

        // Handle incoming deck share intent
        handleDeckIntent(intent)

        // Set Compose UI
        setContent {
            ClashCompanionTheme {
                MainScreen(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeckIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        stateHandler.removeCallbacks(stateRefresh)
        stateHandler.post(stateRefresh)
    }

    override fun onPause() {
        stateHandler.removeCallbacks(stateRefresh)
        super.onPause()
    }

    fun refreshPermissionStates() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityEnabled.value = isAccessibilityServiceEnabled()
        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val capture = ScreenCaptureService.getCaptureMetadata()
        captureRunning.value = ScreenCaptureService.currentCaptureSessionId() != null
        captureHealth.value = when {
            !captureRunning.value -> "Autoriza la captura de pantalla completa"
            capture == null -> "Captura autorizada; esperando primera imagen"
            android.os.SystemClock.elapsedRealtime() - capture.capturedAtElapsedRealtime !in 0..2_000L ->
                "Sin imagen reciente; la IA esperara hasta recibir una captura nueva"
            else -> "Imagen reciente: ${capture.width} × ${capture.height}"
        }
        val svc = SpeechService.instance
        speechReady.value = svc?.isReady() == true
        speechError.value = svc?.loadError
        speechLoading.value = svc != null && !speechReady.value && speechError.value == null
    }

    // ── Permission actions (called from Compose) ──

    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    fun requestAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION
            )
        }
    }

    fun startScreenCapture() {
        // Tapping Screen Capture again deliberately renews Android's
        // media-projection consent. Select "Entire screen" rather than
        // "A single app", which can otherwise show only Clash Commander.
        if (ScreenCaptureService.instance != null) {
            CommandRouter.stopAutopilot()
            stopService(Intent(this, ScreenCaptureService::class.java))
            captureRunning.value = false
            scope.launch {
                // Wait for service teardown rather than reusing an authorization session.
                repeat(20) {
                    if (ScreenCaptureService.instance == null) return@repeat
                    kotlinx.coroutines.delay(50)
                }
                if (ScreenCaptureService.instance != null) {
                    Toast.makeText(this@MainActivity, "La captura se esta cerrando. Vuelve a intentarlo", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val pm = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager
                mediaProjectionLauncher.launch(captureConsentIntent(pm))
            }
            return
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(captureConsentIntent(pm))
    }

    private fun captureConsentIntent(manager: MediaProjectionManager): Intent =
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else manager.createScreenCaptureIntent()

    fun startSpeechService() {
        SpeechService.instance?.let { service ->
            if (!service.isReady() && service.loadError != null) service.setLanguage(voiceLanguage.value)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(this, SpeechService::class.java)
        startForegroundService(intent)
        speechLoading.value = true

        // Foreground UI refresh observes readiness and terminal load errors.
    }

    fun selectVoiceLanguage(language: String) {
        if (language == voiceLanguage.value) return
        CommandRouter.stopAutopilot()
        SpeechLanguage.set(this, language)
        voiceLanguage.value = SpeechLanguage.get(this)
        SpeechService.instance?.setLanguage(voiceLanguage.value)
        refreshPermissionStates()
    }

    fun selectBattleMode(mode: BattleMode) {
        DeckManager.setBattleMode(mode, this)
        battleMode.value = mode
    }

    fun preparePreset(preset: DeckPreset) {
        val cards = DeckManager.parseDeckParam(preset.cardIds.joinToString(";")) ?: run {
            deckImportError.value = DeckManager.lastImportError
            return
        }
        installDeck(cards)
        Toast.makeText(this, "Equipa este mismo mazo y sus evoluciones en Clash Royale", Toast.LENGTH_LONG).show()
    }

    fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (!isAccessibilityServiceEnabled()) return
        if (overlayManager == null) {
            overlayManager = OverlayManager(applicationContext)
        }
        overlayManager?.show()
    }

    /**
     * Pair to a private Gemini relay without placing the Gemini key in the APK.
     * Only the user's private access token is entered here.
     */
    fun openAiSettings() {
        val margin = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin / 2, margin, 0)
        }
        val explanation = TextView(this).apply {
            text = "Ingresa la URL de tu servidor y el token privado. Nunca pegues tu clave de Google aqui."
        }
        val urlInput = EditText(this).apply {
            hint = "https://tu-servidor.workers.dev"
            setText(AiConnection.endpointOrEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val tokenInput = EditText(this).apply {
            hint = "Token privado (vacio para conservar el anterior)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        form.addView(explanation)
        form.addView(urlInput)
        form.addView(tokenInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurar Autopilot")
            .setView(form)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    AiConnection.save(urlInput.text.toString(), tokenInput.text.toString())
                    aiStatus.value = "Verificando conexion..."
                    dialog.dismiss()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { AiConnection.testConnection() }
                            aiStatus.value = "Servidor emparejado. Gemini se verifica al analizar"
                            if (deckCards.value.isNotEmpty()) {
                                triggerOpusAnalysis(deckCards.value)
                            }
                        } catch (e: Exception) {
                            aiStatus.value = "No se pudo verificar el servidor. Revisa URL, token y conexion"
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Revisa la URL y el token", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    // ── Deck handling ──

    private fun handleDeckIntent(intent: Intent?) {
        if (intent == null) return
        val cards = when (intent.action) {
            Intent.ACTION_SEND -> {
                // Try EXTRA_TEXT first, then fall back to clipData (CR uses clipData)
                var text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text == null) {
                    val clip = intent.clipData
                    if (clip != null && clip.itemCount > 0) {
                        text = clip.getItemAt(0).text?.toString()
                            ?: clip.getItemAt(0).uri?.toString()
                    }
                }
                if (text != null) {
                    DeckManager.parseDeckFromText(text)
                } else {
                    Log.w(TAG, "DECK: ACTION_SEND but no text found")
                    null
                }
            }
            Intent.ACTION_VIEW -> {
                val url = intent.data?.toString()
                if (url != null) {
                    DeckManager.parseDeckUrl(url)
                } else null
            }
            else -> null
        }
        if (cards != null && cards.size == 8) {
            installDeck(cards)
        } else if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            deckImportError.value = DeckManager.lastImportError
                ?: "No se pudo importar el mazo. Comparte el enlace de nuevo."
        }
    }

    private fun installDeck(cards: List<DeckManager.CardInfo>) {
        analysisJob?.cancel()
        CommandRouter.stopAutopilot()
        // Pause the existing overlay so no slot from the previous deck survives.
        overlayManager?.hide()
        DeckManager.setDeck(cards, this)
        deckImportError.value = null
        deckCards.value = cards
        opusComplete.value = false
        updateDeckModelWarning(cards)
        SpeechService.instance?.reloadHotwords()
        triggerOpusAnalysis(cards)
    }

    private fun updateDeckModelWarning(cards: List<DeckManager.CardInfo>) {
        val unsupported = CardClassifier.unsupportedDeckCards(cards)
        val experimental = cards.filter {
            it.name in CardTemplateMatcher.supportedNames()
        }
        val warnings = mutableListOf<String>()
        if (experimental.isNotEmpty()) {
            warnings.add(
                "Vision 1.3 experimental: reconocimiento de " +
                    experimental.joinToString(", ") { it.name } +
                    " a partir de tu video. Verifica los resultados en una partida de practica."
            )
        }
        if (unsupported.isNotEmpty()) {
            warnings.add(
                "Sin soporte visual: " +
                    unsupported.joinToString(", ") { it.name } +
                    ". No se jugaran automaticamente cuando la IA no las reconozca."
            )
        }
        deckModelWarning.value = warnings.joinToString("\n").ifBlank { null }
        if (warnings.isNotEmpty()) Log.w(TAG, "DECK: ${warnings.joinToString(" | ")}")
    }

    private fun triggerOpusAnalysis(deck: List<DeckManager.CardInfo>) {
        analysisJob?.cancel()
        if (!AiConnection.isConfigured()) {
            opusStatus.value = "Configura Gemini para analizar el mazo"
            return
        }
        opusStatus.value = "Analyzing deck..."
        opusComplete.value = false
        analysisJob = scope.launch {
            val result = OpusCoach.analyzeWithProgress(deck, this@MainActivity) { progress ->
                runOnUiThread {
                    opusStatus.value = progress
                }
            }
            // After coroutine completes, check if playbook was generated
            runOnUiThread {
                opusComplete.value = result != null
                opusStatus.value = if (result != null) "Strategy Ready" else "Strategy unavailable"
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_accessibility_services"
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    override fun onDestroy() {
        stateHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        overlayManager?.hide()
        HandDetector.stopScanning()
        // CardClassifier.destroy() — removed: destroys native mutex while scan may be mid-inference
        scope.cancel()
    }
}
