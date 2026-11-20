<p align="center">
  <img src="logo.png" alt="Clash Commander" width="150"/>
</p>

# Clash Commander 1.5 · Hypno local experimental

El perfil exacto de Hypno tiene ahora un motor local: captura automática, detector
de unidades incluido en el APK y política táctica sin consultar a Gemini durante
la partida. Incluye presión condicionada a doble línea, Valquiria héroe y
comprobación visual posterior a cada gesto. [Uso, pruebas y límites](docs/LOCAL_HYPNO.md).
La ejecución de partidas completas en el Nothing Phone sigue pendiente de validación.

Asistente Android para jugar Clash Royale mediante voz, reconocimiento local de
cartas y decisiones tácticas de Gemini. Continúa el proyecto original de
[youssofal](https://github.com/youssofal/Clash-Commander), creado para el Supercell
Global AI Game Hack de febrero de 2026.

Este fork conserva los comandos directos, las colas, Smart Path, Autopilot,
captura de pantalla, overlay y análisis previo de mazos. La versión 1.3 incorpora
reconocimiento visual experimental y diagnósticos de captura. La revisión 1.4.1
corrige caducidad de imágenes, cancelación de decisiones y acceso a la renovación
de captura, e incorpora voz española y tres perfiles de mazo. **Compilar la APK
no demuestra que el reconocimiento o Autopilot funcionen correctamente en una
partida real.** No hay una tasa de victorias ni una latencia de juego validada.

<p align="center">
  <img src="app-screenshot.png" alt="Interfaz de Clash Commander" width="300"/>
</p>

## Funciones

| Función | Uso | Conexión |
|---|---|---|
| Fast Path | Juega la carta y zona indicadas por voz | Local |
| Queue Path | Encadena órdenes y espera a que una carta entre en mano | Local |
| Smart Path | Gemini interpreta una intención como defender o atacar | Worker privado + Gemini |
| Autopilot | Gemini propone jugadas durante la partida; puede esperar | Worker privado + Gemini |
| Estrategia del mazo | Genera un plan antes de la partida | Worker privado + Gemini |
| Diagnóstico de captura | Muestra estado de captura y permite guardar una imagen de prueba | Local |

Los modelos de voz se ejecutan en el dispositivo. El selector de idioma ofrece
español con Whisper y mantiene inglés con Zipformer; el parser admite alias de
cartas y zonas. El idioma seleccionado debe coincidir con la voz utilizada.
La precisión y el tiempo de respuesta dependen del teléfono, el ruido, los
modelos y, para las decisiones remotas, la conexión.

## Instalación y configuración

La APK de este fork se obtiene de las ejecuciones correctas de
[GitHub Actions](https://github.com/Gaamlop/Clash-Commander/actions/workflows/android.yml),
artefacto **Clash-Commander-APK**, o compilando este repositorio. Los APK del
proyecto original tienen otra configuración y no equivalen a esta versión.
Consulta [RELEASE_INSTRUCTIONS.md](RELEASE_INSTRUCTIONS.md).

Requisitos: Android 9 o posterior en ARM64, Clash Royale y permisos de accesibilidad,
overlay y captura. Micrófono y motor de voz son opcionales para usar los botones
del panel y Autopilot. El dispositivo objetivo de validación es Nothing Phone (1)
con Android 15. En Android 13 o posterior, una instalación externa
puede requerir habilitar **Permitir ajustes restringidos** en la ficha del sistema
de Clash Commander antes de activar accesibilidad.

1. Abre Clash Commander y comparte tu mazo desde Clash Royale hacia la app.
   Deben cargarse exactamente ocho cartas distintas; una carta desconocida o un
   enlace incompleto produce un error y no instala un mazo parcial.
2. Habilita **Screen Overlay**, **Tap Control** y **Microphone** desde la lista de
   preparación. Selecciona el idioma de voz y carga **Voice Engine**.
3. Autoriza **Screen Capture** y selecciona **Pantalla completa**, para poder
   pasar de la app a Clash Royale durante la captura.
   Puedes volver a autorizar la captura desde la app sin reinstalarla.
4. Para funciones Gemini, configura **GEMINI AUTOPILOT** como se indica abajo.
5. Abre el overlay con **LAUNCH COMMANDER** y entra en una
   partida de prueba. Comprueba que la captura esté reciente y que las cartas
   reconocidas correspondan a las posiciones visibles antes de usar automatización.
   Usa **Defender**, **Atacar I/D**, **Auto** o **Parar**; activa **Listen** si
   quieres controlar por voz. **Parar** cancela también una decisión Smart pendiente.

Seleccionar un mazo en Clash Commander no modifica por sí solo el mazo activo
en Clash Royale: ambos deben coincidir, incluidas las formas de las cartas.

## Gemini sin claves dentro de la APK

El teléfono se comunica por HTTPS con un Cloudflare Worker privado. La clave de
Google se guarda exclusivamente como secreto del Worker. El teléfono usa un
token de acceso diferente, almacenado cifrado mediante Android Keystore.

Sigue la [guía del servidor en español](backend/README.md). En la app se
introducen **la URL HTTPS del Worker y su token de acceso**, nunca la clave de
Google. No hace falta recompilar la APK para cambiar el emparejamiento.

La ruta de salud comprueba el emparejamiento; no llama a Google y por sí sola no
demuestra que el modelo pueda responder. La ruta Gemini realiza las solicitudes
de estrategia o visión. Los fotogramas enviados para decisiones remotas pasan
por tu Worker y llegan a Gemini.

No guardes claves ni tokens en el repositorio, capturas de ajustes, comentarios o
conversaciones. El archivo local de propiedades se usa para la ruta del SDK;
esta compilación deja vacías las claves de proveedor de BuildConfig. Los
clientes Anthropic y Roboflow heredados siguen en el código, pero las funciones
Gemini del fork utilizan el proxy configurado.

## Reconocimiento visual y mazos

El clasificador empaquetado contiene 103 salidas, correspondientes a 102 claves
distintas. La cobertura del catálogo de cartas es mayor que la del modelo.
Además hay huellas experimentales de ocho cartas: Minion Giant, Barbarian
Barrel, Wizard, Goblin Hut, Royal Ghost, Electro Spirit, Skeletons y Lightning, obtenidas de una repetición concreta.
Que una carta aparezca en estas listas no garantiza reconocerla en todos los
teléfonos, resoluciones, animaciones o variantes evolucionadas.

Una predicción débil debe permanecer desconocida. Smart Path y Autopilot exigen
una mano reciente reconocida antes de actuar. Las formas de héroe, las habilidades
y las evoluciones requieren validación propia; no se consideran cubiertas solo
porque exista la carta base en el modelo. La pantalla de mazo muestra las
limitaciones de reconocimiento.

Las recomendaciones de mazos deben indicar composición exacta, evoluciones,
héroes, fuente, fecha de consulta y modo de juego. Las estadísticas de jugadores
de una web no son estadísticas de este Autopilot; una variante con otra forma de
carta tampoco hereda los resultados de la variante original.

La app incluye tres variantes exactas sin héroes consultadas en RoyaleAPI el
19/11/2026, ventana Ranked de siete días. Sus cartas base están en el clasificador;
las formas evolucionadas todavía requieren validación visual:

| Perfil | Evoluciones requeridas | Fuente |
|---|---|---|
| Esqueleto gigante · Ariete | Ariete de batalla, Leñador | [Composición](https://royaleapi.com/decks/stats/arrows,bandit,battle-ram-ev1,electro-wizard,furnace,giant-skeleton,heal-spirit,lumberjack-ev1) |
| Mortero · Carro de cañón | Mortero, Barril de esqueletos | [Composición](https://royaleapi.com/decks/stats/barbarian-barrel,cannon-cart,fireball,goblin-gang,ice-wizard,mortar-ev1,rascals,skeleton-barrel-ev1) |
| Cementerio · Choza | Horno, Caballero | [Composición](https://royaleapi.com/decks/stats/barbarian-barrel,furnace-ev1,goblin-hut,graveyard,ice-wizard,knight-ev1,poison,skeletons) |

Puedes seleccionar Clásico, Gran desafío o Ranked para aportar ese contexto al
plan táctico. Estas composiciones tienen datos de ranked, no evidencia suficiente
para afirmar que sean las mejores de desafíos ni que este Autopilot gane con ellas.
La preparación local no equipa automáticamente cartas o evoluciones dentro del juego.

## Órdenes de voz

En inglés se conservan órdenes como:

~~~text
knight left
fireball top right
giant bottom left
knight left then archers right
defend
push right
follow up
autopilot
stop
~~~

Con español seleccionado, utiliza los equivalentes de cartas y zonas admitidos
por el parser, por ejemplo **caballero izquierda** o **bola de fuego derecha**.
Los comandos para activar y detener Autopilot también están disponibles en el
idioma seleccionado. Verifica la transcripción del overlay durante la prueba.
Ejemplos: **defiende derecha**, **ataca izquierda**, **piloto automático**,
**juega solo**, **detente** y **para, por favor**.

| Zona inglesa | Colocación | Expresiones equivalentes |
|---|---|---|
| left / right | Puente izquierdo / derecho | izquierda / derecha |
| center | Centro de la arena | centro / medio |
| top left / top right | Torre enemiga izquierda / derecha | arriba izquierda / arriba derecha |
| bottom left / bottom right | Detrás de tu torre | abajo izquierda / abajo derecha |
| back | Detrás de tu rey | atrás |
| front left / front right | Posición ofensiva tras el puente | según alias del parser |
| my left / my right | Tu torre de princesas | según alias del parser |
| his king | Rey enemigo | según alias del parser |

La inyección de gestos usa un servicio de accesibilidad con comprobación de
toque y calibración según el tamaño de captura. Una carta seleccionada no
equivale a una colocación confirmada: pueden faltar elixir o condiciones de juego.

## Compilar desde el código

Se necesita JDK 17, Android SDK 35, acceso a las dependencias y un teléfono para
la validación funcional. Usa el wrapper incluido y no agregues secretos al APK.

~~~bash
git clone https://github.com/Gaamlop/Clash-Commander.git
cd Clash-Commander
bash scripts/bootstrap_sherpa_deps.sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
~~~

En Windows, el bootstrap disponible es:

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_sherpa_deps.ps1
~~~

El bootstrap descarga las bibliotecas nativas y modelos de voz que no se
versionan completos en Git. Configura la ruta del SDK mediante las variables
del entorno de Android o un archivo local.properties ignorado por Git.

Verificaciones automáticas:

~~~bash
node backend/worker.test.mjs
./gradlew testDebugUnitTest lintDebug assembleDebug
~~~

Smart y Autopilot envían arena y HUD del mismo fotograma. El elixir que lee Gemini
es una inferencia visual, no un sensor calibrado. Autopilot espera si falta una
cifra legible o el coste de la carta excede lo observado. Las respuestas que tardan
más de seis segundos, cambian de sesión/partida/mazo o se cancelan no ejecutan jugadas.

## Arquitectura

~~~text
Micrófono → Silero VAD → STT español/inglés → parser → CommandRouter
                                                    ├─ Fast Path
                                                    ├─ Queue Path
MediaProjection → detector de mano ──────────────────┼─ Smart Path → Worker → Gemini
                                                    └─ Autopilot → Worker → Gemini
                                                             ↓
                                                AccessibilityService → gestos
~~~

Kotlin y Jetpack Compose proporcionan la interfaz y el overlay; sherpa-onnx
ejecuta voz/VAD; PyTorch Mobile Lite ejecuta el clasificador; OkHttp conecta con
el proxy. La estrategia previa del mazo aporta contexto a las decisiones.

Las piezas principales están en app/src/main/java/com/yoyostudios/clashcompanion/:
MainActivity.kt, capture/, detection/, speech/, command/, accessibility/,
overlay/, deck/, strategy/, api/ y util/. El backend está en backend/.

## Validación pendiente y límites

- Probar captura y permisos en teléfonos reales, incluidos cambios de resolución,
  orientación, captura parcial y reautorización.
- Medir reconocimiento por carta y posición sobre grabaciones distintas de las
  usadas para obtener las huellas; incluir errores, cartas desconocidas y formas.
- Validar voz española e inglesa en dispositivos reales y ambientes con ruido.
- Verificar Smart Path, Autopilot y parada durante solicitudes remotas y cambios
  de mano; comprobar el efecto real de los gestos en la partida.
- Validar elixir, tiempo de partida y condiciones tácticas. La estimación del
  modelo remoto no sustituye una medición confirmada del estado del juego.
- Investigar el bloqueo de gestos documentado en Android 15/16 cuando el usuario
  toca la pantalla durante una orden; evitar asumir que compilar lo resuelve.

La CI histórica de 1.3 ejecuta assembleDebug: demuestra que esa revisión
compila, no que haya pasado una partida completa. La firma debug puede variar
entre ejecuciones; una reinstalación exigida por cambio de firma borra ajustes
locales, incluido el emparejamiento Gemini.

## Licencia

[MIT](LICENSE). Proyecto independiente, sin afiliación con Supercell. Respeta las
condiciones del juego.

## Reglas tácticas y pendientes

Se reconocen perfiles Ice Bow, Miner Control y el Logbait de Hypno compartido por
el usuario. Sus filtros de elixir, amenazas y carriles se prueban con escenarios
explícitos. Importa el mazo real para activar el perfil correspondiente.
Consulta [avance, pruebas y errores pendientes](docs/DEVELOPMENT_STATUS.md).
