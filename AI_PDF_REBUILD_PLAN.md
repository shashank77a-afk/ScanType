# Scanner.ai — AI and PDF rebuild plan

This is a safe, incremental rebuild plan. It deliberately preserves the existing Android app and does not replace production code until a stage is tested.

## Preserve existing working features

Keep the current application package ID, authentication/session code, camera/scanner, crop/filters, saved-document storage, ordinary PDF export, settings, theme, app lock, QR tools, and AdMob integration unless a later audit proves a change is required. Do not change signing or OAuth configuration during the AI/PDF refactor.

The existing Android code already contains separate OCR, backend, letter layout, PDF builder, and export files. The rebuild should improve these boundaries rather than rewrite the whole app.

## Target pipeline

1. **Image intake and preflight**: validate image MIME type and byte size, orientation, dimensions and legibility. Keep the original image unchanged. Prepare a bounded JPEG derivative for the API.
2. **Recognition**: use the secure Cloudflare Worker as the only production route to the Gemini API. API keys must never ship in the Android app or be placed in Git. Return transcription, language, confidence/uncertainty markers when supported, and structured error codes.
3. **Layout extraction**: return a strict, versioned JSON schema containing page aspect ratio and ordered blocks with text, role, alignment, and normalized bounding boxes. Validate every block and coordinate on the server and again in Android. Reject malformed/empty/truncated model output with a useful recoverable error rather than silently generating a broken letter.
4. **Human review**: show the editable transcript and page layout preview before export. Let users correct text and move, resize, reorder, or realign blocks. Preserve original source photo.
5. **PDF rendering**: separate implementations for (a) original scan/image PDF, (b) editable/searchable typed PDF, (c) layout-preserving typed letter PDF, and (d) original scan plus typed transcript. Embed/support Hindi/Devanagari fonts and test Unicode shaping. Avoid near-invisible OCR text layers being misrepresented as true editable typed output.
6. **Export and recovery**: save to Downloads/Files, share, and reopen the PDF. Use temporary files safely, clean up after failures, and never show success unless the PDF exists and can be opened.
7. **Observability and privacy**: log request IDs, model/status/latency, validation stage and error codes only. Do not log images, base64, full transcribed letter contents, session tokens, or API keys.

## Implementation sequence

### Stage 0 — baseline and backup
- Work only on `feature/ai-pdf-rebuild` until review.
- Record current `main` commit, package ID, Gradle configuration, current Worker routes, bindings and secrets metadata (never read or expose secret values).
- Build current `debug` APK and run existing tests before editing.
- Never delete D1 data or overwrite the working Worker as part of the Android refactor.

### Stage 1 — contracts and validation
- Create a documented request/response schema for handwriting OCR and letter layout, including schema version.
- Add server-side bounds/type validation and predictable error envelopes.
- Add unit tests for valid blocks, empty outputs, malformed JSON, markdown fences, missing coordinates, out-of-range coordinates, Unicode, and oversized responses.
- Keep existing endpoint paths backward-compatible while old and new clients coexist.

### Stage 2 — recognition adapter
- Make Android use the backend for production handwriting AI; keep on-device OCR as an explicit fallback with a clear label.
- Make retries bounded; retry only transient upstream errors with backoff and jitter. Do not endlessly retry quota/auth/validation failures.
- Show useful messages for quota exhaustion, rate limiting, network errors, unreadable input, and upstream outages.
- Never put Gemini secrets in source, settings defaults, APKs, or logs.

### Stage 3 — layout-preserving PDF
- Separate layout JSON parsing from PDF rendering.
- Validate and normalize block geometry, order and alignment.
- Handle overlaps, blocks beyond page margins, multi-page overflow, long paragraphs, Devanagari glyphs and mixed Hindi/English.
- Preserve a correction/preview step before export.
- Keep clean-format letter output separate from layout-preserving output.

### Stage 4 — PDF verification
- Verify exported PDF exists, is non-empty, opens through PdfRenderer, and has expected page count.
- Keep output in a temporary location until successful; then atomically finalize and show success.
- Test page size, margins, text readability, clipping, page breaks and sharing.
- Original scan PDF must remain available even if OCR/layout services fail.

### Stage 5 — regression and release
- Run Gradle unit tests and build debug/release variants in CI.
- Test signed-in and signed-out flows, daily limits, account deletion, offline scan/PDF, and existing tools.
- Compare against baseline and review changed files before merging.
- Merge only after user acceptance. Do not deploy a new Worker or publish an APK automatically from this plan.

## Required test set

1. Clear handwritten Hindi letter with date, subject, paragraphs and signature.
2. English handwritten letter.
3. Mixed Hindi/English letter with dates and numbers.
4. Blurry/low-light photo.
5. Rotated image and different aspect ratios.
6. Long multi-page letter with paragraphs crossing page breaks.
7. Invalid/empty/truncated AI response.
8. Gemini 429 quota/rate-limit and 503 transient outage.
9. User cancels mid-operation; no false success and no corrupted output.
10. Original image PDF export still works while AI is unavailable.

## Acceptance criteria

- Existing scanner and core app still builds and works.
- API key remains exclusively server-side.
- A clear Hindi and English letter can be previewed, corrected, exported and reopened.
- Layout/parse failures produce recoverable errors rather than an empty or misleading PDF.
- Original scan export works independently of AI.
- Tests pass and all source changes are reviewed before merge.

## Safety constraints

- This plan does not itself claim the AI/PDF pipeline is fixed.
- Do not change the production Worker, database, secrets, signing key, OAuth client, billing, or App Store/Play Store release settings without a separate explicit and verified step.
