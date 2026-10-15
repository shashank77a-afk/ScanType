# Handwriting AI / PDF API contract (draft v1)

This document defines the boundary for incremental replacement of AI and PDF code. It does not change the production Cloudflare Worker.

## Principles
- The Android client sends image bytes to the secure Worker; Gemini credentials remain server-side.
- Always preserve the original photo.
- AI output is untrusted input. Validate it at both the Worker and Android boundary.
- A model/API failure must never be treated as a valid empty layout or a successful PDF export.
- Keep existing public routes during migration; version the response payload with `schemaVersion`.

## Layout response
```json
{
  "schemaVersion": 1,
  "language": "hi",
  "pageAspect": 0.707,
  "blocks": [
    {
      "text": "विषय: ...",
      "role": "subject",
      "align": "left",
      "box": [80, 160, 920, 220],
      "bold": false
    }
  ]
}
```

- `language`: `hi`, `en`, or `mixed` (use `unknown` only when unable to determine).
- `pageAspect`: width / height of the source page, finite and positive; UI may use a default when absent.
- `blocks`: non-empty array on successful recognition.
- `text`: trimmed non-empty transcription; preserve language, names, dates, amounts, punctuation and line breaks intentionally written by the author.
- `role`: one of `recipient`, `reference`, `date`, `subject`, `salutation`, `body`, `enclosure_heading`, `enclosure_item`, `closing`, `signature`, `other`.
- `align`: `left`, `center`, `right`, or `justify`.
- `box`: exactly four finite numbers [x0, y0, x1, y1] normalized to 0..1000, origin top-left, with x1 > x0 and y1 > y0. Reject or safely clamp values outside the allowed range; never accept NaN/Infinity.
- `bold`: optional boolean.

## Error envelope
```json
{
  "error": {
    "code": "invalid_layout",
    "message": "The letter layout could not be prepared. Please try a clearer photo."
  },
  "requestId": "opaque-correlation-id"
}
```

Expected stable codes include `bad_json`, `too_large`, `login_required`, `session_expired`, `quota_exhausted`, `rate_limited`, `upstream_unavailable`, `upstream_timeout`, `invalid_model_output`, and `invalid_layout`. Never put credentials or the user's letter text into error logs.

## PDF variants
1. `scan_original`: image-only PDF faithful to the source scan; no AI required.
2. `typed_layout`: real selectable text rendered from validated layout blocks, with preview/edit before save.
3. `typed_clean`: clean conventional letter layout from corrected text.
4. `scan_plus_typed`: original pages and typed transcription, with clear separation.

The UI must label these choices accurately. A nearly invisible OCR layer is not a substitute for a real typed PDF.

## Failure semantics
- Reject empty blocks, invalid coordinates, invalid language or unreasonable block counts.
- Model retries are bounded to at most one strict-format retry for malformed output and bounded transient retries for upstream 429/5xx; respect Retry-After where available.
- Do not retry auth, user quota exhaustion, malformed client requests or validation failures.
- PDF generation is successful only after the output file exists, is non-empty, and can be reopened/rendered.
- On failure, keep the original image and provide retry/edit/manual-text options.

## Required tests
Cover valid Hindi, English and mixed-language output; fenced JSON; truncated JSON; missing/invalid box; empty text; overlapping blocks; long text/multipage overflow; 429/503/timeout; cancelled job; and image-only export while AI is offline.
