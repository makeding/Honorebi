# Komorebi connection setup

Mobile web configuration page served at `/connections/`. It reads the one-time setup token from `?token=…` and never stores it in browser storage.

## Development

```sh
pnpm install
pnpm dev
```

The development server proxies `/api` to `http://127.0.0.1:8081`. Production builds use `pnpm build` and are emitted to `dist/` with the `/connections/` base path.

## API

Every request includes `X-Setup-Token` from the page URL. `GET /api/connections` receives the full connection snapshot. `POST /api/connections` sends all source records and the selected `mainSystem`; the returned snapshot replaces the local form state only after the server's atomic save has completed.

`?source=KONOMITV`, `?source=EDCB`, or `?source=MIRAKURUN_ONLY` shows just that card while retaining the full fetched snapshot for saving.
