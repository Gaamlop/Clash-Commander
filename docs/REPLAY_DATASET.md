# Banco de replays para Ice Bow, Miner Control y Hypno WB Bait

Este es el **primer paso para estudiar decisiones de jugadores expertos a partir de videos**, no un entrenamiento del modelo ni una IA competitiva. No se descargan videos automáticamente. Se etiquetan solo grabaciones propias o autorizadas para este uso, y se conserva la evidencia de permiso fuera del repositorio.

## Procedimiento

1. Selecciona un replay de uno de los tres mazos verificados en \`DeckProfiles.kt\`.
2. Pausa el video **antes** de una jugada. Registra la mano visible, elixir propio si se distingue y la situación observable. Si el dato es ilegible, usa \`null\`; no inventes elixir enemigo, ciclo ni cartas.
3. Anota la siguiente acción que realmente hizo el experto como \`expert\`. Si se conoce el resultado, descríbelo de manera separada en notas para análisis. Un experto también puede cometer errores: la etiqueta es **acción observada**, no verdad estratégica infalible.
4. Ejecuta Clash Commander sobre el mismo estado y guarda su elección como \`prediction\`, sin mostrarle de antemano la etiqueta.
5. Corre el evaluador. Compara por mazo las jugadas legales, concordancia de acción/carta y coincidencia exacta de carta+zona. Una discrepancia NO demuestra que el experto o la IA tengan razón.
6. Separa partidos de práctica y de evaluación por **match_id**; nunca entrenes y evalúes sobre momentos del mismo partido.

## Formato JSONL

Un objeto JSON por línea; las coordenadas \`zone\` son etiquetas consistentes elegidas por el anotador. La muestra siguiente es **sintética**, no una jugada extraída de un video real:

\`\`\`json
{"match_id":"synthetic-demo","time_ms":42000,"deck":"ice_bow","hand":["Tesla","Knight","Skeletons","Rocket"],"own_elixir":7,"situation":"Ejemplo ficticio: push terrestre hacia torre izquierda","source_permission":"owner","expert":{"action":"play","card":"Tesla","zone":"center_defense"},"prediction":{"action":"play","card":"Tesla","zone":"center_defense"}}
\`\`\`

\`deck\`: \`ice_bow\`, \`miner_control\` o \`hypno_wb_bait\`. \`hand\`: exactamente cuatro cartas distintas pertenecientes al mazo. \`own_elixir\`: 0..10 o \`null\`. \`source_permission\`: \`owner\` o \`authorized\`. \`expert\`: \`{"action":"wait"}\` o \`{"action":"play","card":"...","zone":"..."}\`. \`prediction\` es opcional; si está ausente, esa fila no cuenta para la exactitud. El archivo no debe incluir tokens, datos privados o imágenes de terceros.

## Comandos

\`\`\`bash
python scripts/replay_eval.py /ruta/labels.jsonl
python scripts/replay_eval.py /ruta/labels.jsonl --json
python -m unittest discover -s scripts -p 'test_*.py' -v
\`\`\`

## Qué NO demuestra

- Que una coincidencia con el video sea la jugada óptima.
- Que Gemini haya aprendido persistentemente.
- Que los gestos Android o la lectura del elixir funcionen.
- Que exista una tasa de victorias competitiva.

### Siguiente etapa

Un anotador puede producir archivos JSONL a partir de replays autorizados; entonces habrá que automatizar extracción de cuadros, doble etiquetado con acuerdos entre anotadores, retención de partidas para evaluación ciega, pruebas de latencia y eventualmente entrenamiento específico cuando haya suficiente diversidad de situaciones. Nunca informar métricas calculadas sobre replays sintéticos como si fueran rendimiento real.
