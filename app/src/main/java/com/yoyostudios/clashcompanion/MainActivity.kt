fun startSpeechService() {
    if (SpeechService.instance != null) return

    if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    try {
        val intent = Intent(this, SpeechService::class.java)
        startForegroundService(intent)

        speechLoading.value = true

        val pollHandler = android.os.Handler(mainLooper)
        val pollRunnable = object : Runnable {
            override fun run() {
                val svc = SpeechService.instance

                if (svc != null && svc.isReady()) {
                    speechReady.value = true
                    speechLoading.value = false
                } else {
                    pollHandler.postDelayed(this, 500)
                }
            }
        }

        pollHandler.postDelayed(pollRunnable, 500)

    } catch (e: Exception) {
        Log.e(TAG, "STT: Could not start SpeechService", e)
        speechLoading.value = false

        android.widget.Toast.makeText(
            this,
            "Error al iniciar Voice Engine: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
