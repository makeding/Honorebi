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

Validation contracts: [settings categories](../docs/validation/settings_categories.md). Run `pnpm test` for the frontend regressions; Android HTTP/persistence and focus tests also cover the packaged assets.
