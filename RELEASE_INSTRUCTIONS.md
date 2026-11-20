# Obtener e instalar Clash Commander 1.4.1 experimental

Este fork se compila desde Gaamlop/Clash-Commander. Un APK del proyecto original
no incorpora necesariamente los cambios de captura, visión y proxy Gemini.

## APK generado por GitHub Actions

1. Abre [Build Clash Commander APK](https://github.com/Gaamlop/Clash-Commander/actions/workflows/android.yml).
2. Selecciona una ejecución correcta de la rama o pull request que contiene
   1.4.1 experimental (versionCode = 6) y comprueba el SHA de la revisión
   que deseas instalar.
3. Descarga el artefacto **Clash-Commander-APK** y extrae app-debug.apk.
   GitHub puede solicitar iniciar sesión para descargar artefactos.
4. Instálalo en un teléfono ARM64 con Android 9 o posterior y completa la preparación
   descrita en [README.md](README.md).

La base anterior, publicada como 1.3, es
0f197fc5b1ac912d1d07f55c29d67fdbe67641f7 (versionCode = 4). Su
[compilación de referencia](https://github.com/Gaamlop/Clash-Commander/actions/runs/44478804031)
terminó correctamente el 19 de noviembre de 2026. No equivale a una validación
funcional de Autopilot. Los artefactos pueden caducar y nuevas revisiones pueden
contener cambios adicionales sin un nuevo número de versión.

Las compilaciones debug pueden tener firmas distintas entre ejecuciones.
Android puede rechazar la actualización y exigir desinstalar la aplicación
anterior. Eso elimina los ajustes y el token local: no desinstales sin conservar
tu configuración en un lugar privado.

## Compilar en tu equipo

Requisitos: JDK 17, Android SDK 35 y acceso a las dependencias.

~~~bash
git clone https://github.com/Gaamlop/Clash-Commander.git
cd Clash-Commander
bash scripts/bootstrap_sherpa_deps.sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
~~~

En Windows, descarga las dependencias con:

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_sherpa_deps.ps1
~~~

Usa las variables del entorno de Android o configura sdk.dir en un archivo
local.properties ignorado por Git. **No agregues claves API a ese archivo para
empaquetarlas en la APK.** Los modelos de voz y bibliotecas nativas se descargan
con el bootstrap; no basta con copiar únicamente los archivos Kotlin.

## Activar Gemini

Sigue [backend/README.md](backend/README.md). La clave de Google vive en los
secretos del Worker. Configura en la aplicación la URL HTTPS del Worker y un
token de acceso diferente, guardado cifrado en el teléfono. La comprobación de
emparejamiento no confirma por sí sola la disponibilidad real del modelo.

Después de instalar, verifica en una partida de prueba la captura, las cuatro
posiciones de la mano, el idioma de voz, los gestos y la parada de Autopilot.
La versión sigue siendo experimental; no se han demostrado resultados en
desafíos clásicos, grandes desafíos ni ranked.

La revisión 1.4.1 incluye voz offline española (Whisper) e inglesa (Zipformer),
tres composiciones de referencia y controles de partida sin micrófono. La CI
añade pruebas de políticas de visión/decisión/estrategia y del Worker, además de
lint. El Worker debe actualizarse con backend/worker.js para incorporar las
validaciones y el diagnóstico de emparejamiento; la app mantiene el protocolo
de generación compatible con la versión anterior.
