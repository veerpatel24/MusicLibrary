# Web UI (main-v2)

This folder holds the static frontend served by `music.WebApiServer`.

**Figma reference (layout + chrome):** [Music Library Web App UI](https://www.figma.com/design/36Em0QEmSESwwEyEdyU6CZ) — desktop frame matches this page’s structure (header, sidebar actions, library cards).

## Run

From the repository root:

```bash
chmod +x scripts/run-web.sh
./scripts/run-web.sh
```

Open [http://localhost:7070/](http://localhost:7070/). The server uses the repo root as the data directory for CSV paths (e.g. `2010.csv`).

The original terminal experience is unchanged: run `music.Driver` as before for the MVP.
