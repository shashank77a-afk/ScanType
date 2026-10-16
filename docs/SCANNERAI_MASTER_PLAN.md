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
- [ ] Implement a dedicated document preview/viewer with page thumbnails and Open/Edit/Share/Save actions.

## Phase 2 — Custom CameraX scanner
- [x] Retain the in-app CameraX camera and existing edge-detection path.
- [x] Keep Auto Capture on/off and manual shutter controls.
- [x] Remove the Google ML Kit Document Scanner dependency/fallback entry point.
- [ ] Add lightweight live Enhance/B&W/Lighten preview without slowing camera frames.
- [ ] Improve edge stability, low-light/blur warnings, retake flow and manual corner editing.
- [ ] Test multi-page Add Page/Finish Document, page reorder/delete/rotate and ID front/back end-to-end.
- [ ] Add Book Mode quality improvements and verify curved-page handling.

## Phase 3 — Filter engine and editor
- [x] Add filter choices: Original, Enhance, Lighten, B&W, No Shadow, OmniFix.
- [ ] Implement and visually validate distinct processing for all six filters; OmniFix must be a clearly defined local page-correction mode, not an alias for another filter.
- [ ] Add side-by-side/compare preview, reset/undo, and always render filters from the original image.
- [ ] Build editor tabs: Images, Markup, Page.
- [ ] Add tested Crop, Rotate, Signature, Add Text, Brush/Highlight, Watermark and page management actions.
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
This branch is an incremental implementation, not yet the finished five-phase release. The theme, initial Home/navigation structure, selected filter names, ScannerAi naming/folder behavior and removal of the Google document-scanner dependency have been started. Live-filter rendering, full preview/editor parity, several advanced PDF actions, complete automated checks, and real-device QA remain outstanding.
