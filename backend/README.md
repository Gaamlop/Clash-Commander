# Servidor privado Gemini para Clash Commander

Esta versión funciona así: el teléfono se comunica por HTTPS con tu propio Cloudflare Worker.
Solo el Worker conoce GEMINI_API_KEY. La APK **no contiene** esa clave ni necesita
recompilarse para cambiarla. El teléfono guarda un **token diferente** de acceso
al Worker, cifrado con Android Keystore.

## Configuración desde el celular o una computadora

1. Crea una cuenta en https://dash.cloudflare.com/ si todavía no tienes.
2. Ve a **Workers & Pages** y crea un Worker llamado, por ejemplo, `clash-commander-ai`.
   Puedes empezar desde el ejemplo "Hello World".
3. En **Edit code**, reemplaza el ejemplo con el contenido completo de
   [worker.js](worker.js) y pulsa **Deploy**.
4. Abre **Settings > Variables and Secrets** del Worker. Agrega los siguientes
   elementos con tipo **Secret**, NO con tipo Plaintext:
   - `GEMINI_API_KEY`: pega aquí la clave de Google AI Studio, **solo en Cloudflare**.
   - `APP_ACCESS_TOKEN`: crea una contraseña aleatoria única de al menos
     32 caracteres (idealmente 48 o más), diferente de la clave de Gemini.
     Guárdala en tu gestor de contraseñas, nunca en GitHub o en el chat.
5. Opcional: si tu modelo deja de estar disponible, agrega `GEMINI_MODEL` como
   variable con un identificador compatible con Gemini GenerateContent (por
   defecto es `gemini-3-flash-preview`).
6. Copia solamente la URL pública HTTPS del Worker, algo como
   `https://clash-commander-ai.tu-cuenta.workers.dev`.
7. Abre **Clash Commander**, toca **GEMINI AUTOPILOT**, pega esa URL y
   `APP_ACCESS_TOKEN` (NO la clave de Google) y pulsa **Guardar**.
   La app probará la conexión. Debe decir **Conectado. Gemini disponible**.
8. Comparte el mazo nuevamente si no aparece **Strategy Ready**.
   Luego prueba los comandos de voz "defend" y "autopilot" en una partida
   de entrenamiento, observando que el modo pueda detenerse con "stop".

## Seguridad y costos

- Nunca subas tu clave de Google ni el token del Worker al código público.
  **No son el mismo secreto**.
- Cualquier persona que obtenga tu token del Worker puede consumir tu cuota
  de Gemini. Si sospechas exposición, cámbialo en el panel de Cloudflare y
  actualiza el token dentro de Clash Commander.
- Los fotogramas del juego enviados por el asistente pasan por tu Worker y
  llegan a Gemini para ser analizados.
- Pueden aplicarse límites de uso o cargos de Google y Cloudflare.
  Configura cuotas y alertas en tus cuentas para evitar gastos inesperados.
- El Worker valida un token largo, limita el tamaño de las peticiones y
  no permite enviar solicitudes a otras URL externas.
- **No actives acceso público anónimo** y no escribas credenciales en la URL.
- Un `HTTP 401` indica token incorrecto; `HTTP 503` indica secretos faltantes
  en el Worker; `HTTP 429` suele indicar un límite de cuota del proveedor.

## Detalles técnicos

- `GET /health`: valida el emparejamiento sin llamar a Google.
- `POST /v1/gemini`: reenvía el JSON de generateContent a Gemini.
- El Worker usa el secret `APP_ACCESS_TOKEN` con `Authorization: Bearer ...`.
- Las estrategias previas al juego ahora pueden generarse usando Gemini,
  sin necesidad de una clave Anthropic.
- El motor de voz y los comandos directos siguen funcionando sin esta conexión.
- La firma de compilaciones debug de GitHub Actions puede cambiar entre
  ejecuciones: Android podría pedir desinstalar la anterior antes de instalar
  una nueva. Eso borra los ajustes locales, incluido el emparejamiento.
- Aún falta validar el Autopilot durante una partida real: la compilación
  correcta no demuestra precisión de cartas, elixir ni decisiones tácticas.

La API Key del usuario nunca debe compartirse con el asistente.
