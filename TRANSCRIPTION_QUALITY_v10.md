# План качества расшифровки и разделения голосов (1.0)

Целевое устройство: Xiaomi 14T / ARM64. Число участников заранее не ограничивать; автоматическая оценка, ручная корректировка, неопределенные голоса. Никаких изменений Чанти Моста. Аудио, исходная расшифровка и производные результаты хранятся отдельно.

## Первый этап: совместимые данные
- Обычный TXT сохраняется без изменения формата.
- Дополнительный part_NNNN.whisper.json содержит schema=1, offsetMs (сумма длительностей завершенных частей), segments со startMs/endMs относительно части и исходным text, untimedText для текста без подтвержденных интервалов.
- JSON включен в ZIP импорт/экспорт; временные файлы не включаются. Старые TXT не перезаписываются автоматически ради получения JSON.
- SpeakerAlignment — предварительное сопоставление интервалов с уже согласованными на всю сессию speaker id. Не запускает модель и не определяет личности; при слабом покрытии или нескольких значимых голосах оставляет unknown/multiple. Пороговые значения пока эвристические, требуют калибровки. Никакого разбиения текста на слова по догадке.

## Следующие этапы
1. Проверить исходники и бинарную совместимость Whisper Android 1.0.0 с large-v3-turbo, прежде чем добавить новую модель. Кандидаты: Q8_0 (~834 MiB) для качества, Q5_0 (~547 MiB) для сравнения, Small как запасной. Хеши SHA-256 и размеры проверять у официального источника; выбор модели и отдельные результаты не должны перезаписывать прежние checkpoints. Сравнивать также с полной моделью при наличии ресурсов. https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md
2. Исследовать Android AAR/JNI sherpa-onnx, лицензии моделей, офлайн segmentation + speaker embedding + clustering. https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html
3. Разметка голосов на всю сессию: глобальная кластеризация/сопоставление эмбеддингов между частями, не переносить локальные номера кластеров вслепую. Проверка перекрытий и смены говорящего внутри фразы. Эталонные голоса и имена только по согласованию и подтверждению участником; ручное переименование кластеров в первую очередь.
4. Словарь Магира: подтвержденный перечень терминов, исходный текст сохранять. Промпт модели только при поддержке API; исправления явно отделять от оригинала.
5. Отдельный человекочитаемый текст с голосами, таймкодами и отмеченными сомнительными репликами; конспект отдельно, без подмены расшифровки.
6. Проверки: реальные игровые фрагменты с разным числом участников, быстрые диалоги, перебивания, повтор одного голоса между частями, тихая речь. Сравнить качество распознавания, ошибки атрибуции, время, память, нагрев, энергопотребление и восстановление на Xiaomi 14T. Не объявлять диаризацию готовой до проверки.

Статус: фундамент временных меток и правила сопоставления, не готовый движок диаризации; Android-сборка и испытания на телефоне еще не подтверждены.

## Sherpa-ONNX Android integration checkpoint

- CI has verified the previous timestamp/alignment groundwork: run 45959542032 completed successfully.
- Pinned official Android AAR: sherpa-onnx v2.0.3, SHA-256 `4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50`. The AAR is fetched at build time; it is not committed into the repository.
- `SherpaSpeakerDiarizer` wraps the official offline speaker diarization API, uses automatic cluster count (`numClusters=-1`), CPU execution, confidence output and sorted millisecond intervals.
- The wrapper deliberately requires a whole-session 16 kHz mono FloatArray. Running it separately on each five-minute part would restart speaker IDs and is not considered a correct session-level solution. Memory-bounded cross-part reconciliation remains to be designed and tested before wiring it into the recording workflow.
- Model candidates: pyannote segmentation 3.0 (MIT, converted ONNX model; original Hugging Face model is gated for direct download but sherpa distributes converted files) and speaker embedding models. Candidate English VoxCeleb CAM++ ONNX is 29.6 MB, SHA-256 `98daf9bcf1c8abf3db374fec4c27a5eda7c58705bb3f180d057b085a72a88bb5`, Apache-2.0 at 3D-Speaker project. Russian-language performance is not established; compare with the 3D-Speaker ERes2Net model and validate on Russian recordings.
- Official Android API references: https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html and https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.3/android/SherpaOnnxSpeakerDiarization
- Official AAR wrapper and pinned model downloader are now implemented in source; CI must confirm compilation. Model download/install has SHA-256 and byte-count verification. The selected CAM++ model is trained on English VoxCeleb; Russian performance is unverified. Memory-bounded session-wide clustering and Xiaomi 14T device tests are still pending. This wrapper is not yet called by the user-facing transcription flow.

## Cross-fragment speaker identity prototype

- Sherpa Android AAR v2.0.3 includes `SpeakerEmbeddingExtractor`, `OnlineStream`, and `SpeakerEmbeddingManager` JNI support, not only offline diarization.
- Added `SherpaSpeakerEmbeddingEngine`: extracts embeddings from clean, sufficiently long, non-overlapping speech turns for each local cluster.
- Added session-scoped `SpeakerIdentityMatcher`: maps local Sherpa cluster IDs to stable session IDs across five-minute parts using normalized speaker embeddings; no fixed speaker count. Empty or ambiguous evidence is left unresolved rather than guessed.
- Matching threshold 0.5 and ambiguity margin 0.06 are provisional engineering defaults, not validated Russian thresholds. They must be calibrated against real Russian recordings. Voice embeddings exist only in memory for a processing pass; no cross-session voice profile database is created.
- The actual diarization service and UI are not wired yet. Need a per-part pipeline: WAV -> local diarization -> clean embeddings -> session matcher -> diarized sidecar; then align Whisper intervals and build a separate speaker-labelled transcript.

## Per-part pipeline

- Added `MagirSpeakerDiarizationPipeline`: reads one converted 16 kHz mono WAV part at a time, runs Sherpa offline diarization, extracts clean embeddings for each local cluster, and resolves them through one session-scoped matcher retained across parts.
- This is the intended memory-bounded route for long sessions. Local speaker numbers are never copied directly into the final transcript; they are reconciled against voice embeddings. A short or ambiguous cluster remains `unknown`.
- The pipeline core is compiled and unit-tested indirectly by the Android build; actual inference, CPU/memory usage, Russian identity accuracy, and threshold calibration still require real-device recordings.
