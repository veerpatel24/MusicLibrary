# Music Streaming Library

A **Java** music playlist library for learning and experimentation: playlists are stored as **circular linked lists**, with an **in-memory library** you can change from a **browser UI** or from a **terminal menu** (`Driver`).

**What you can do**

- Load playlists from **CSV files** in the repo (or paste CSV), **insert/remove/find** songs, **merge** two playlists by popularity rules, **reverse** and **deterministically shuffle** (fixed seed).
- Optionally **connect Spotify, YouTube (Google), or SoundCloud**, **import** a remote playlist into the local library, then keep editing it here. Import is **one-way** (this app does not push changes back to those services).

The full **web UI** and **streaming import** live on branch **`Main-V3`**. Clone the repo, then use that branch if you want everything described above.

---

## What you need installed

| Requirement | Notes |
|-------------|--------|
| **JDK 17+** | `java` and `javac` on your `PATH`, or set `JAVA_HOME`. The script uses `/usr/libexec/java_home` on macOS if `JAVA_HOME` is unset. |
| **Bash** | To run `scripts/run-web.sh`. |
| **curl** | Only needed the first time the script downloads Gson into `lib-web/`. |

---

## Run the application locally (web UI)

These steps are for a **new user** on their own machine.

### 1. Get the code and use the right branch

```bash
git clone https://github.com/veerpatel24/MusicStreamingLibrary.git
cd MusicStreamingLibrary
git checkout Main-V3
```

### 2. (Optional) OAuth / streaming import

If you only want CSV + local editing, **skip this**. The UI works without `.env`.

To use **Spotify / YouTube / SoundCloud** import:

1. Copy the template and edit it (never commit `.env`; it is gitignored):

   ```bash
   cp env.example .env
   ```

2. Fill in the variables you need (see comments in `env.example`). **YouTube** needs both `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.

3. In each provider’s developer console, register **redirect URIs** that match how you open the app, for example:

   - `http://localhost:7070/callback/spotify` and `http://127.0.0.1:7070/callback/spotify` (and the same pattern for `/callback/youtube` and `/callback/soundcloud`).

   Register **both** `localhost` and `127.0.0.1` if you might use either in the browser.

### 3. Start the server

From the **repository root**:

```bash
chmod +x scripts/run-web.sh
./scripts/run-web.sh
```

The script **sources `.env`** if it exists, compiles Java into `target/classes`, and starts the HTTP server.

### 4. Open the app in a browser

- Default URL: **[http://localhost:7070/](http://localhost:7070/)**  
- Another port: `PORT=8080 ./scripts/run-web.sh` then open `http://localhost:8080/`.

**Tabs**

- **Library** — refresh the library, add playlists by filename or pasted CSV, load many CSVs at once, add/find/delete songs, reverse, combine, shuffle (seed **2026**).
- **Streaming** — connect services (if `.env` is configured), list remote playlists, import by playlist ID.

Shortcut: [http://localhost:7070/Main-V3.html](http://localhost:7070/Main-V3.html) redirects to `/`.

### 5. Stop the server

In the terminal where it is running, press **Ctrl+C**.

---

## Run the terminal driver (no browser)

Same JDK and repo checkout. From the repo root:

```bash
export JAVA_HOME=…   # if needed
mkdir -p out
javac --release 17 -encoding UTF-8 -cp "lib/cs112.jar" -d out src/music/*.java
java -cp "out:lib/cs112.jar" music.Driver
```

Follow the numbered menu (same operations as the web UI, different interface).

---

## Troubleshooting (web + OAuth)

| Problem | What to try |
|--------|-------------|
| **Connect does nothing / API error** | Open **Streaming**: missing credentials are called out. Add them to `.env` and restart the script. |
| **`bad_state` after OAuth** | Don’t restart the server between clicking **Connect** and finishing login in the browser; click **Connect** again. |
| **`localhost` vs `127.0.0.1`** | Add both redirect URL variants in Spotify / Google / SoundCloud. The app builds the redirect from your browser **Host** unless you set `MUSIC_PUBLIC_ORIGIN` in `.env`. |
| **`.env` errors on Windows** | The script strips CRLF from `.env` before loading; if issues remain, save `.env` with Unix line endings. |
| **OAuth / token errors** | Watch the **terminal** running `run-web.sh` — callback problems are logged to stderr. |

Tokens are stored locally in **`.integration-tokens.json`** (gitignored). Delete that file to force re-login.

---

## Project layout

| Path | Role |
|------|------|
| `src/music/MusicLibrary.java` | Core playlist logic (circular linked lists, merge, shuffle, etc.) |
| `src/music/WebApiServer.java` | HTTP API + static site |
| `src/music/StreamingIntegrations.java` | OAuth + remote playlist → CSV import |
| `src/music/Driver.java` | Interactive terminal UI |
| `scripts/run-web.sh` | Compile + run `WebApiServer` with repo root as data directory |
| `web/public/` | Frontend (`index.html`, `app.js`, `styles.css`) |
| `lib/cs112.jar` | Course utilities (`LLNode`, etc.) |
| `lib-web/gson-*.jar` | JSON (downloaded on first run if missing) |
| `*.csv` | Sample playlists |

---

## Performance note

Static assets use a short browser cache. For the snappiest workflow, use local CSVs and **Refresh**; streaming APIs run only when you connect or import.
