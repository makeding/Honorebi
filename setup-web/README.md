# Komorebi connection setup

Mobile web configuration page served at `/connections/`. It reads a per-window secret from the URL fragment (`#…`) and never stores that authorization secret in browser storage. The link can be used repeatedly until the TV's QR dialog closes; reopening that dialog generates a new secret.

## Development

```sh
pnpm install
pnpm dev
```

The development server proxies `/api` to `http://127.0.0.1:8081`. Production builds use `pnpm build` and are emitted to `dist/` with the `/connections/` base path.

## API

Every API request includes `X-Setup-Token` from the fragment. Query-string tokens are not accepted. `GET /api/connections` receives the full connection snapshot. `POST /api/connections` sends all three source records, `mainSystem`, live-source priority, EDCB playback method, Cloudflare Access and SMB settings. Source labels and password-presence flags are read-only; existing secrets are never returned. Omitted secrets preserve their saved values and explicit empty strings clear them. The returned snapshot replaces the form only after the atomic save completes.

`?source=KONOMITV`, `?source=EDCB`, or `?source=MIRAKURUN_ONLY` initially expands that card. All connection settings remain visible and editable. With one configured source the main-system selector is disabled and that source is selected automatically.

Unsaved edits use origin-scoped `sessionStorage` drafts. Restoration merges changed fields into freshly fetched settings; successful saves clear the draft. Account/device tokens and setup secrets never enter drafts.

Account endpoints under `/api/connections/account` require the same open QR session. Clicking account linking first applies any edited KonomiTV connection/Access fields, retaining unrelated unsaved edits as a draft; no separate save is required. Pairing starts on the TV; the phone navigates directly to the provider's verification page while the TV continues polling. Closing the QR dialog cancels polling. Logout requires explicit confirmation.

## Screensaver image management

The same bundle also serves the mobile screensaver image uploader at `/screensaver/` (or `?mode=screensaver`), authenticated via the same per-window `X-Setup-Token` fragment secret:

- `GET /api/screensaver/images` — returns the saved image inventory (`id`, `index`, `byteSize`, `owned`), `maxImageBytes`, and `maxFilesPerUpload`.
- `GET /api/screensaver/images/{id}/content?thumbnail=1` — returns a downsampled JPEG thumbnail (or original image bytes when `thumbnail` is omitted).
- `POST /api/screensaver/images` — accepts `multipart/form-data` (`images` field, up to 20 files per request, 64 MiB per file), validates image bounds, saves into app-private storage, and returns the updated inventory.
- `DELETE /api/screensaver/images/{id}` and `DELETE /api/screensaver/images` — delete one or all saved screensaver images and return the updated inventory.

Validation contracts: [settings categories](../docs/validation/settings_categories.md). Run `pnpm test` for the frontend regressions; Android HTTP/persistence and focus tests also cover the packaged assets.
