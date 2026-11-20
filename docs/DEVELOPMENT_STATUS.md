# Clash Commander: avance y validación

## Alcance de esta revisión

Base inicial: 0f197fc (1.3). Se incorporaron también los cambios posteriores de
main hasta 36ba3ad (1.4, evaluación offline, cobertura y elixir local): tres huellas adicionales, umbral de comparación 0,18
y mínimo de dos cartas verificadas. Versión de integración: 1.4.4-experimental,
versionCode 9. Ningún resultado de compilación se considera prueba de juego real.

## Objetivos verificables

- Captura: identidad de fotograma/sesión y antigüedad monotónica; copia y reciclado
  sincronizados. Una imagen congelada no renueva la evidencia de la mano.
- Calibración: selección en el centro del mismo recorte utilizado para clasificar,
  con pruebas a 1080×2400 (Nothing Phone 1), referencia y resoluciones menores.
  Esto verifica geometría, no que el recorte coincida con la pantalla real.
- Decisiones: cancelación de HTTP/gestos/colas/reglas; caducidad; nombres y zonas
  validados; estrategia guardada solamente para el mazo correspondiente.
- Estado: arena y HUD del mismo fotograma; elixir y amenazas comunicados como
  observaciones visuales inciertas. Valores ausentes permanecen desconocidos.
- Ice Bow: coincidencia exacta con el perfil de ocho cartas de DeckProfiles, reserva de
  nueve de elixir para X-Bow ofensivo/Rocket y prioridad defensiva.
- Miner Control: coincidencia exacta con Miner Poison Wall Breakers, reserva defensiva para Miner y
  rechazo de Poison a torre sin defensores observados.
- Logbait de Hypno: coincidencia exacta con Barril de Duendes, Valquiria,
  Ejército de Esqueletos, Rompemuros, Princesa, Cañón, Duende Lanzadardos y
  Espíritu de Hielo. Prioridad defensiva y reserva antes de presionar.
  La composición fue proporcionada por el usuario; no incluye Rocket ni The Log.

Estas reglas son filtros conservadores sobre propuestas de Gemini. No demuestran
que Gemini perciba correctamente una amenaza ni que la jugada aceptada sea óptima.
Las pruebas usan escenarios explícitos, sin llamadas a Gemini ni consumo de cuota.

## Pendientes y criterios de cierre

1. **P1: percepción real.** Medir precisión por carta/slot sobre capturas independientes
   del Nothing Phone 1 (Android 15), incluyendo evoluciones y cartas atenuadas.
   No se localizó la repetición mencionada en el Drive conectado.
2. **P1: coordenadas de arena.** Anclas contrastadas geométricamente con capturas
   reales en 1.4.3; falta confirmar selección y despliegue efectivos en el teléfono.
3. **P1: lectura de elixir/amenazas.** Comparar las inferencias de Gemini con
   anotaciones humanas; cuantificar falsos positivos, lecturas ausentes y latencia.
   La lectura local de barra incorporada desde main es experimental; los valores
   parciales 1..9 requieren etiquetas independientes. No hay ciclo rival validado.
4. **P1: resultado de jugada.** Dos gestos completados no acreditan despliegue ni
   gasto de elixir. Falta confirmar transiciones visuales y detectar fallos del juego.
5. **P1: partida supervisada completa.** Validar parada, recuperación de captura,
   decisiones defensivas y ofensivas y fin de partida antes de afirmar autonomía.
6. **P2: rendimiento.** Medir CPU/temperatura/latencia de Whisper, clasificador y
   Gemini en el teléfono. No hay tasa de victorias ni nivel competitivo demostrado.
7. **P2: estrategia.** Ampliar escenarios tácticos anotados y verificar variantes
   exactas de Ice Bow/Miner Control; no agregar más reglas sin casos de prueba.

## Verificación reproducible

```sh
node backend/worker.test.mjs
./gradlew testDebugUnitTest lintDebug assembleDebug
```

La suite cubre geometría, antigüedad, cambios de mano, cancelación HTTP, contratos
JSON, caché por mazo, alias españoles, catálogo de presets y restricciones tácticas.
Los informes quedan en app/build/reports; el APK en app/build/outputs/apk/debug.
La instalación y las pruebas de partida en el teléfono siguen pendientes.

La integración conserva DeckProfiles como única definición de los tres mazos,
los helpers de autorización de main y su evaluador offline de repeticiones.
Las pruebas del evaluador no constituyen una evaluación sobre un vídeo real.

## Incidente observado: una sola jugada y respuestas lentas

El usuario confirmó que Auto mostró respuesta demasiado lenta/decisión descartada
tras un Barril de Duendes. Img no forma parte del ciclo: es diagnóstico opcional.
Se mantiene la caducidad de seis segundos; ampliar la validez de una captura
antigua no sería una corrección de percepción. La revisión 1.4.2 limita el
playbook enviado en cada decisión a un resumen de cartas en mano, condiciones
de victoria y restricciones. El análisis completo sigue guardado localmente.
Pruebas de contexto grande verifican la reducción y que JSON malformado no
interrumpa el ciclo. Una prueba del ciclo simula jugada, error de observación,
espera y segunda jugada sin intervención; cancelar sí termina el ciclo.
Se registran automáticamente latencias y timeouts sin capturas ni credenciales.
Pendiente: medir si esta reducción baja la latencia real de Gemini en el teléfono;
no se afirma resuelto el incidente sin esa medida.

## Referencia externa solicitada: Clasher

Fuentes consultadas: https://github.com/vegetableleaf/ClashAI/blob/main/README.md
y https://github.com/vegetableleaf/ClashAI/blob/main/RESULTS.md . El README
describe imitación de repeticiones y aprendizaje por refuerzo mediante autojuego
en simulador; especifica que checkpoints y datasets no están incluidos. Los
resultados publicados no acreditan por sí solos que sea el jugador top10 citado
por el usuario. Está pendiente confirmar perfil/vídeo. No se ha copiado código
ni integrado un modelo de ese repositorio. La inferencia rápida de una política
entrenada requiere un objetivo independiente: contrato de observaciones, modelo
disponible y evaluación reproducible. La corrección actual reduce contexto
remoto y verifica reintentos; no convierte Gemini en una política entrenada.

## Evidencia de capturas y calibración 1.4.3

El usuario aportó capturas con mano Princess/Ice Spirit/Goblin Barrel, siguiente
Dart Goblin y10elixir. Valkyrie aparece como desconocida; no se fuerza su identidad.
En esa fase el mensaje es SMART con fallo de decisión, no prueba de Auto activo.
La captura de tres coronas ya no contiene mano: el aviso de compartir pantalla
completa era engañoso en resultados y se sustituyó por espera de partida.
Las zonas heredadas front_left/front_right quedaban sobre la mitad enemiga; center
cerca del río. Se anotaron aproximadamente río, torres y límites del área propia
sobre las imágenes576×1280. Las posiciones ahora escalan esas anclas, sin modificar
los recortes de cartas. Las pruebas comprueban que las zonas de tropas quedan
bajo el río y los objetivos de torre dentro de cajas anotadas. Esto es calibración
geométrica por imagen, aún no confirmación de despliegue exitoso en el teléfono.
Los fallos HTTP muestran únicamente el código, sin cuerpos ni credenciales.

Se localizó además el vídeo de Surgical Goblin “AI IS TAKING OVER CLASH ROYALE… IT ALREADY HIT #1” (https://www.youtube.com/watch?v=X_leWfBQ8-w). La transcripción indexada describe reacciones rápidas pero no publica el sistema; no prueba que el repositorio ClashAI corresponda a esa cuenta.


## Incidente confirmado y corrección 1.4.4

Las nuevas capturas sí muestran Auto activo, error Gemini HTTP 429 y una espera
por mínimo dos cartas mientras el panel informa cuatro. El escáner vaciaba la
mano autorizada antes de cada inferencia. Ahora HandObservationStore publica
observaciones completas atómicamente: comenzar otra inferencia conserva la
última observación hasta su caducidad original de dos segundos; un resultado
vacío la sustituye inmediatamente. Invalidar captura/partida/escáner impide que
una inferencia anterior vuelva a publicar cartas. No se autorizan cartas retenidas
solo por suavizado visual. Las pruebas cubren estas transiciones y caducidad.

El cliente comparte una pausa de cuota entre playbook y decisiones. Tras HTTP
429 respeta Retry-After y aplica al menos 30, 60 y después 120 segundos si se
repite, sin llamadas durante la pausa. Una respuesta simultánea anterior exitosa
no borra esa pausa. Auto informa que durante ella no hay jugadas IA; no debe
presentarse como defensa operativa. La caducidad táctica sigue siendo seis segundos.
El Worker conserva solo el retraso saneado, sin propagar cuerpos/headers privados.
El Worker remoto del usuario no se ha redesplegado: el APK utiliza la pausa
predeterminada aun con el Worker previo; respetar su Retry-After requiere actualizarlo.
Esta corrección no aumenta cuota ni soluciona la dependencia táctica de Gemini.

Se integraron 360bc6b y 36ba3ad sin recuperar su revocación durante inferencia.
La lectura local de elixir comparte fotograma, sesión y caducidad con la mano;
se usa como veto adicional de coste y contexto experimental, no sustituye todavía
la verificación visual de Gemini. Cobertura y mano completa se registran sin
imágenes ni identidades; cobertura no equivale a precisión de reconocimiento.

Pendientes concretos: Valkyrie/variantes visuales desconocidas; precisión de
amenazas; validación de elixir parcial; confirmación visual de gasto/despliegue;
latencia medida y partida completa. No hay victorias autónomas demostradas.


Verificación local 1.4.4: 95 pruebas JVM, 13 pruebas del Worker y 6 del evaluador
aprobadas; lint sin errores (75 avisos heredados); assembleDebug correcto.
No se hicieron llamadas a Gemini ni partidas reales para estas pruebas.
