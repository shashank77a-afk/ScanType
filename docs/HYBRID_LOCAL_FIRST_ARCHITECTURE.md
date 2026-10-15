# Scanner.ai Hybrid Local-First Architecture

## Goal
Document scanning, OCR where supported, text editing, layout composition, and PDF/DOCX export should work locally. Sending a page to Gemini is optional and requires an explicit user action.

## Flow
1. Camera/gallery import and existing crop/enhancement code.
2. On-device ML Kit Devanagari and Latin recognition.
3. Local text/layout blocks using recognized geometry.
4. Review and edit blocks.
5. Generate PDF using Android PdfDocument; DOCX remains in the existing export pipeline.
6. Optional AI fallback only when the user explicitly selects it and AI/login are available.

## Limits
ML Kit text-recognition is not a dedicated Hindi handwriting recognizer. For handwriting, local recognition remains best-effort and must clearly tell users to review/correct output. AI fallback may improve text but may not preserve the original geometry.

## Privacy
Default local workflow must not upload photos. AI fallback button must explain that the selected image is sent over HTTPS through the configured backend. Do not put Gemini API keys in the APK or logs.

## Validation checklist
- Build debug APK.
- Printed Hindi and English samples.
- Handwritten Hindi samples with low/high OCR confidence.
- Empty OCR and AI failure paths.
- Multi-page page indices and block geometry.
- PDF opens and is non-empty.
- Offline test proves default flow does not require sign-in or network.
- Optional AI fallback only runs after explicit user action.
- Compare generated PDF to original; identify spacing/layout limitations.
