# Music Streaming Library

Java playlist engine (circular linked lists) with a **local web UI** and optional **Spotify**, **YouTube**, and **SoundCloud** import.

Active UI and streaming work live on branch **`Main-V3`**.

## Quick start (web)

1. Copy `env.example` to `.env` only if you will use streaming import (see below).
2. From the repo root:

```bash
chmod +x scripts/run-web.sh
./scripts/run-web.sh
```

3. Open [http://localhost:7070/](http://localhost:7070/) (or set `PORT=8080 ./scripts/run-web.sh`).

- **Library** — add/remove playlists (CSV files or paste), edit songs, combine, reverse, shuffle (seed **2026**).
- **Streaming** — connect an account, list playlists, **import** one into the local library at a chosen index. Imports become normal playlists you can edit here. **Pushing** edits back to Spotify/YouTube/SoundCloud is not implemented (import-only).

Shortcut URL: [http://localhost:7070/Main-V3.html](http://localhost:7070/Main-V3.html) redirects to `/`.

## Terminal driver (unchanged)

```bash
export JAVA_HOME=…   # JDK 17+
javac -cp "lib/cs112.jar" -d out src/music/*.java
java -cp "out:lib/cs112.jar" music.Driver
```

## Connecting Spotify, YouTube, or SoundCloud

1. Create developer apps with each provider and add the **exact** redirect URI:

   - Spotify: `{MUSIC_PUBLIC_ORIGIN}/callback/spotify`  
   - Google (YouTube Data API): `{MUSIC_PUBLIC_ORIGIN}/callback/youtube`  
   - SoundCloud: `{MUSIC_PUBLIC_ORIGIN}/callback/soundcloud`  

   Default `MUSIC_PUBLIC_ORIGIN` is `http://localhost:7070` (override in `.env` if you change host/port or use a tunnel).

2. Put credentials in `.env` (see `env.example`). Restart `./scripts/run-web.sh`.

3. In the UI → **Streaming** → **Connect**, approve access, then **List playlists** and **Import** using the playlist ID (Spotify/YouTube IDs appear in URLs; SoundCloud IDs come from the list).

**Notes**

- **YouTube** uses the official **YouTube Data API** (Google account). It reads **YouTube playlists** (the same account often mirrors YouTube Music library playlists you have saved as YouTube lists).
- **SoundCloud** API access depends on your app’s approval tier; errors in the list/import panel usually mean the token or API permissions need to be fixed in the SoundCloud developer dashboard.
- OAuth tokens are stored only on your machine in **`.integration-tokens.json`** at the project root (gitignored). Do not share that file.

### Connect button does nothing or “bad_state”

1. Open the **Streaming** tab: red boxes list missing env vars. Create **`.env`** from **`env.example`** and restart `./scripts/run-web.sh`.
2. **`localhost` vs `127.0.0.1`**: The app now builds the OAuth redirect from your browser’s **Host** header. In Spotify / Google / SoundCloud consoles, register redirect URLs for **both** `http://localhost:7070/callback/...` and `http://127.0.0.1:7070/callback/...` if you switch between them.
3. **`bad_state`**: The server forgot the login attempt (usually **restarted Java** between clicking Connect and approving in the browser). Click Connect again without restarting the server.
4. **Token exchange errors**: Check the **terminal** where `run-web.sh` is running; OAuth failures are printed to stderr. You can also pass client IDs without `.env`:  
   `java -Dspotify.client.id=… -cp … music.WebApiServer` (see `env.example` for variable names).

## Project layout

| Path | Role |
|------|------|
| `src/music/MusicLibrary.java` | Core linked-list logic |
| `src/music/WebApiServer.java` | HTTP API + static files |
| `src/music/StreamingIntegrations.java` | OAuth + playlist → CSV import |
| `web/public/` | UI (`index.html`, `app.js`, `styles.css`, `Main-V3.html`) |
| `*.csv` | Sample playlist data |

## Performance

Static `.js` / `.css` responses use a short **Cache-Control** max-age. Keep using local CSVs and the **Refresh** button for the fastest loop; streaming calls only run when you connect or import.
