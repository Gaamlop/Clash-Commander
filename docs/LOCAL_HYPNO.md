# Hypno local 1.5

## Qué cambia

Auto con el perfil exacto Hypno WB Log Bait ejecuta percepción y decisiones en Android. No utiliza Gemini, Worker, tokens de IA ni descargas de modelos durante la partida. Clash Royale sigue necesitando su conexión habitual para jugar. Se conservan Smart, voz, colas, análisis opcional de Gemini y los otros perfiles.

Las ocho cartas base son Skeleton Army, Valkyrie, Goblin Barrel, Wall Breakers, Dart Goblin, Cannon, Princess e Ice Spirit. El usuario confirmó **Valquiria héroe**. Preparar el perfil en Commander no cambia el mazo ni las formas equipadas en el juego.

## Uso

1. Instalar el APK y preparar Hypno en Commander; equipar esas ocho cartas, la Valquiria héroe y las evoluciones correspondientes en Clash Royale.
2. Habilitar overlay, accesibilidad y captura completa. Micrófono/voz y Gemini son opcionales para Auto local.
3. Lanzar Commander, abrir una partida y pulsar Auto. El modelo ejecuta primero una autoprueba nativa con una imagen pública incluida. No se necesita pulsar Img.
4. Parar cancela decisiones y gestos. El usuario puede recuperar el control manual.

## Decisiones y agilidad

Al preparar Hypno se precalienta el detector en segundo plano. Intenta NNAPI con cómputo FP16 y sólo lo conserva si supera la autoprueba; si falla la creación o inferencia, prueba CPU con hasta cuatro hilos. La aceleración efectiva y su rendimiento aún deben medirse en el Nothing.

El escáner obtiene cartas, barra y arena de un mismo fotograma. El controlador revisa observaciones cada 150 ms; la frecuencia efectiva depende de la inferencia en el teléfono. Una observación de más de 1,5 segundos no autoriza acciones. No se anuncia una latencia de dispositivo que todavía no se haya medido.

La política prioriza defensa aérea con unidades que pueden golpear aire, Cannon central frente a condiciones de victoria terrestres, Valkyrie frente a grupos y Skeleton Army frente a objetivos terrestres sin apoyo de área. Evita alimentar Mother Witch o atacar una animación de hechizo como si fuera una tropa. Conserva reserva antes de presionar y no gasta una defensa de tres por un espíritu aislado.

Barrel se dirige a una torre enemiga realmente observada y usa su posición detectada. No repite el objetivo vacío cuando esa torre deja de verse. Tras confirmar Barrel puede lanzar Wall Breakers en el carril opuesto si todavía conserva tres de elixir; una amenaza inmediata cancela esa oportunidad. También puede castigar una inversión pesada al fondo y dividir presión con un ejército superviviente. No son secuencias de toques disparadas sin nuevas observaciones.

Las evoluciones se despliegan mediante la carta cuando el juego las ofrece. No se inventa su carga a partir del nombre del mazo. El reconocimiento adicional cubre ilustraciones normales/evolucionadas y Hero Valkyrie. El ejército superviviente se aprovecha para contrapresión; eso no certifica una lectura universal de todos los efectos evolucionados.

Wild Whirlwind se propone por separado: heroína observada, control azul/dorado con marcador de coste, presencia estable en dos fotogramas distintos, grupo terrestre cercano con valor suficiente y al menos cinco de elixir observados para pagar tres conservando dos. No usa la habilidad para responder a aire sin cobertura. El gesto se comprueba con desaparición del botón y gasto de elixir; no se repite mientras sigue pendiente. Referencias mecánicas: [Supercell](https://supercell.com/en/games/clashroyale/blog/news/new-season-k-h-a-o-s/) y [RoyaleAPI](https://royaleapi.com/blog/hero-valkyrie-august-2026?lang=en).

Después de cada gesto se revoca la autorización anterior de mano/elixir y se exige una imagen capturada después de completarlo. La confirmación de una carta requiere rotación del slot y gasto compatible de elixir; completar dos toques no equivale a desplegar una carta. Los resultados vacíos no se rellenan con cartas antiguas para autorizar jugadas. Una pérdida breve de detección enemiga tiene memoria de hasta 900 ms, limitada a la misma captura y partida.

## Evidencia y reproducibilidad

El detector procede de sacha9214/clash-ai `093604e`, convertido a ONNX opset17, 640×640 y 393 clases. Es percepción entrenada por ese proyecto; la política de Hypno, integración Android y pruebas de esta revisión son nuevas. No se atribuye el entrenamiento del detector a Clash Commander. Ver [licencias](LOCAL_ENGINE_LICENSE.md) y [contrato numérico](evidence/local-hypno-model-validation.json).

El exportador verifica el checkpoint por SHA-256, contrato de dimensiones y paridad PyTorch/ONNX sobre dos entradas sintéticas y una imagen real. El máximo error absoluto del tensor fue inferior a 0,002. El APK comprueba el hash del modelo antes de cargarlo.

La prueba JVM de integración usa la API Java y el runtime nativo ONNX 1.17.1 de Linux: imagen real → el mismo preprocesador Kotlin de Android → modelo real → decoder → política. Comprueba rey y Cannon propios y una decisión legal. Otra prueba abre los seis grafos de voz (Whisper, Zipformer y VAD) con ese mismo runtime CPU, para detectar operadores incompatibles; no equivale a probar reconocimiento de audio ni el JNI de Sherpa en Android. El APK repite la parte de percepción como autoprueba en Android. La imagen pública de KataCR puede solaparse con datos de entrenamiento del detector: esta prueba mide integración/compatibilidad, **no precisión independiente**.

Las pruebas de retratos incluyen 12 ilustraciones de las ocho cartas, variantes normales/evo/héroe, redimensionado, atenuación, grises y cartas ajenas. Son regresiones de transformaciones; no acreditan cuatro cartas correctas en cada fotograma real. Los escenarios de combate y el ciclo de tres jugadas con rotación/gasto son simulaciones explícitas, no victorias en una partida.

```sh
# Entorno Python CPU: instalar torch 2.15.0 y torchvision 0.29.2 desde el índice CPU.
pip install -r scripts/local_engine/requirements.txt
python scripts/local_engine/export_arena.py
python scripts/local_engine/verify_bundle.py
./scripts/bootstrap_sherpa_deps.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python scripts/check_apk_native.py app/build/outputs/apk/debug/app-debug.apk
python -m unittest discover -s scripts -p 'test_*.py'
node backend/worker.test.mjs
```

CI reutiliza únicamente el detector convertido con clave por fuente/script; verifica siempre su hash y ejecuta las pruebas nativas. No incluye credenciales. ONNX Runtime Android 1.17.1 aporta el runtime compartido para ambos clientes JNI; el bootstrap elimina la copia duplicada tanto en Linux como en Windows. Tras compilar se comprueban las versiones de símbolos ELF del APK, las bibliotecas ARM64 y los archivos de ambos idiomas. La compatibilidad de la API C no basta: Sherpa exige exactamente `OrtGetApiBase@VERS_1.17.1`. La 1.5.0 usaba el símbolo 1.20.0 y fallaba en el enlazador Android; la prueba JVM de Linux no detectaba ese conflicto. Ver [corrección 1.5.1](evidence/voice-native-1.5.1.json).

## Diagnóstico automático

Auto registra fotograma, antigüedad, cartas observadas, elixir mínimo, detecciones, decisión, latencia y confirmaciones en dos archivos privados de hasta 1 MB cada uno. Un gesto terminado se registra separado de una carta confirmada.

El estado de primer plano se obtiene de eventos de ventana, manteniendo deshabilitada la lectura de contenido de accesibilidad. Solo con Clash Royale como paquete en primer plano y arena neuronal verificada, guarda hasta diez imágenes privadas, como máximo una cada cinco segundos y 200 KB por imagen. Recorta el 18% superior y no recoge pantallas de configuración ni credenciales. Las etiquetas guardadas son predicciones, no verdad supervisada. No sube datos automáticamente. Info exporta un ZIP a Descargas/ClashCommander con el registro y las muestras disponibles para revisar una sesión posteriormente.

## Pendientes para declarar autonomía validada

No se ha ejecutado esta revisión en el Nothing Phone del usuario. Falta medir latencia sostenida, temperatura, lectura de elixir parcial y precisión con cartas/efectos actuales, comprobar el botón real de habilidad y completar una partida con gestos y gasto visualmente confirmados. No hay tasa de victorias, nivel profesional ni resultados de ranked/desafíos acreditados. No automatiza la compra de entradas ni la navegación de los menús del juego.

Vídeos aportados para contraste táctico, pendientes de inspección visual:

- https://youtube.com/shorts/eT38XxE78oE
- https://youtube.com/shorts/OKl1PycTrXc
- https://youtube.com/shorts/yXzTX0m_-OY

El lector web los devolvió como throttled; el navegador falló con ERR_TUNNEL_CONNECTION_FAILED y la búsqueda exacta no tuvo resultados. No se han extraído ni atribuido jugadas a esos clips. Se podrán revisar como archivos de vídeo cuando estén disponibles; esta limitación no afecta al motor local.
