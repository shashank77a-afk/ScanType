# Scanner.ai AI/PDF implementation checklist

Use this checklist before changing production code.

## Baseline
- [ ] Capture current main commit SHA and existing Worker version metadata.
- [ ] Run current Gradle build; record baseline results.
- [ ] Record package ID, OAuth client configuration, AdMob configuration, and signing setup without exposing credentials.
- [ ] Verify existing scan, crop, multi-page save, document history, account/session, app-lock, and ordinary PDF workflows.
- [ ] Record real current failures and reproducible sample test cases.

## AI / Worker
- [ ] Extract current Worker source to version control and document all current routes and bindings.
- [ ] Add typed, versioned request/response contracts without changing public routes.
- [ ] Keep all secrets as Worker secrets; do not commit .dev.vars, tokens, or secrets.
- [ ] Validate encoded image size before expensive work.
- [ ] Apply bounded retries with exponential backoff/jitter for transient upstream failures only.
- [ ] Parse JSON robustly, validate schema and coordinates, reject empty/malformed output.
- [ ] Return stable machine-readable error codes and a request ID.
- [ ] Logs contain no base64, tokens, API key, or letter transcription.
- [ ] Add tests for malformed JSON, fence-wrapped JSON, truncated output, 429, 503 and timeout.

## Android
- [ ] Add repository/interface boundary for OCR and layout analysis.
- [ ] Do not use device-stored Gemini key in production path.
- [ ] Add explicit states: preparing, recognizing, review required, export in progress, completed, recoverable failure.
- [ ] Preserve image URI/file through every failed operation.
- [ ] Show AI-vs-offline mode accurately.
- [ ] Validate JSON and geometry before creating blocks.
- [ ] Allow editing transcription and block attributes before final PDF.
- [ ] Build typed letter and clean-letter as separate products.

## PDF
- [ ] Add real PDF output verification: file exists, file length > 0, PdfRenderer opens it.
- [ ] Add Devanagari Unicode/font regression tests.
- [ ] Test long lines, paragraphs, page breaks, overlap handling and source image aspect ratios.
- [ ] Ensure save/export success is reported only after verification.
- [ ] Original scan PDF remains available independently of AI.

## Release gate
- [ ] Run tests and assembleDebug successfully.
- [ ] Run regression suite for existing app capabilities.
- [ ] Review git diff to confirm production configuration was not changed accidentally.
- [ ] Test debug APK on a device with Hindi and English sample letters.
- [ ] User approves the result.
- [ ] Only then open a non-draft PR/merge; deploy Worker separately after explicit release review.
