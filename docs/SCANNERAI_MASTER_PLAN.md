# Scanner.ai Master Implementation Plan

## Guardrails
- Work on `feature/scannerai-viridis-rebuild`; do not commit work directly to `main`.
- Preserve package ID `com.scantype.documentscanner`, backend URL `https://scantype-api.scantype.workers.dev`, Google sign-in configuration, AdMob configuration, and the existing Cloudflare Worker.
- Keep the AI section to exactly two AI-only features: **Handwriting to Text** and **Same-Layout Typed PDF**. Both require Google sign-in and must not silently fall back to local OCR.
- Keep non-AI scanning, QR and PDF utilities usable without Google sign-in.
- Google Drive backup is a later phase. A Cloud icon may be visible now, but must not imply that backup is active.
- Do not claim a feature is complete merely because a card/button exists. Validate the actual workflow and test on an Android device before release.

## Phase 1 — Branding, navigation, local file naming
- [x] Apply Viridis-inspired green/light theme while preserving Light/Dark/System preference handling.
- [x] Add CamScanner-inspired Home shortcuts, Recents heading, Cloud placeholder, and Home/Files/Scan/Tools/Me navigation layout.
- [x] Use `ScannerAi DD-MM-YYYY HH.mm` as the scan-review default filename.
- [x] Export finished scan PDFs to `Download/ScannerAi/` and Gallery images to `Pictures/ScannerAi/`.
- [ ] Review Home, Files, Tools, and Me screens for visual consistency on multiple screen sizes.
- [ ] Add robust collision handling and verify MediaStore/legacy storage behavior on Android 10+ and Android 9.
- [x] Implement an in-app PDF viewer with page navigation/thumbnails and Open/Edit/Share/Save actions (device validation remains in Phase 5).

## Phase 2 — Custom CameraX scanner
- [x] Retain the in-app CameraX camera and existing edge-detection path.
- [x] Keep Auto Capture on/off and manual shutter controls.
- [x] Remove the Google ML Kit Document Scanner dependency/fallback entry point.
- [x] Add live filter selection and lightweight color-matrix preview on Android 12+; on older Android versions the selected filter is applied after capture (device responsiveness/quality checks remain).
- [x] Add throttled in-camera low-light/glare hints; Review quality warning catches blur and CropScreen provides draggable four-corner perspective correction (real-device tuning remains).
- [ ] Improve edge stability and verify the retake/manual-corner workflow under difficult lighting.
- [x] Keep the selected filter consistent through multi-page camera capture into review.
- [ ] Test multi-page Add Page/Finish Document, page reorder/delete/rotate and ID front/back end-to-end.
- [ ] Improve Book Mode quality and verify curved-page handling.

## Phase 3 — Filter engine and editor
- [x] Add filter choices: Original, Enhance, Lighten, B&W, No Shadow, OmniFix.
- [x] Improve local illumination normalization and B&W thresholding; keep OmniFix as a separate processing path.
- [x] Add Original comparison toggle and filter Reset action; resetting a filter preserves the manual crop.
- [x] Release replaced preview and thumbnail bitmaps after Compose stops drawing them.
- [ ] Visually validate distinct results for all six filters on real photographs; OmniFix must be clearly useful, not merely different in code.
- [x] Add per-page Undo/Redo for filter, rotation, crop and markup changes.
- [x] Add editor tabs: Images, Markup, Page.
- [x] Implement Crop, Rotate, signature drawing, Add Text, Brush/Highlight, Watermark and page add/delete/reorder controls; these still need real-device workflow testing.
- [ ] Treat Smart Erase as later/experimental until it can avoid deleting real document content.

## Phase 4 — PDF tools and AI
- [ ] Verify Image-to-PDF, multi-page export, PDF merge/split, rotate/reorder/delete, compression, print/share and save-to-folder.
- [ ] Add/verify password protection, page numbers, annotations and form filling as separate tasks.
- [ ] Keep AI section limited to Handwriting to Text and Same-Layout Typed PDF.
- [ ] Validate Same-Layout flow as photo selection -> AI analysis -> PDF saved/opened, without unnecessary intermediate settings or editing steps.
- [ ] Keep API keys server-side and use the existing Worker/backend; do not change Worker production settings unless a demonstrated backend requirement exists.
- [ ] Add test coverage for login-required and non-AI login-free flows.

## Phase 5 — QA and release gate
- [ ] GitHub Actions APK build succeeds for the exact final commit.
- [ ] Test on a real Android phone: camera permission, edge detection, auto/manual capture, retake, filters, multipage PDF, save folder, open/share, background/rotation and app restart.
- [ ] Test Hindi matras, thin table lines, signatures, shadows, low light and mixed printed/handwritten pages.
- [ ] Verify existing features remain intact (QR, ID card, folders, PDF tools, Google login, AdMob config, backend URL).
- [ ] Confirm backup UI does not claim to sync before Google Drive backup is implemented.
- [ ] Publish a single consolidated APK artifact and record its commit SHA.
- [ ] Only mark Phase 5 complete after device testing; CI success alone is not device verification.

## Current implementation notes
This branch is an incremental implementation, not yet the finished five-phase release. Filter normalization/B&W thresholding, Original comparison/reset, lifecycle-safe bitmap cleanup, Android 12+ live filtered camera preview, camera light/glare hints, consistent multi-page filter selection, per-page Undo/Redo, Images/Markup/Page tabs, and page-aware text/signature/brush/highlight/watermark overlays have been added. Real-device filter/annotation QA, older-device camera behavior, broader edge tuning, several advanced PDF actions, and full regression testing remain outstanding. GitHub Actions builds must pass after the latest commit; CI success is not a substitute for testing on a phone.
