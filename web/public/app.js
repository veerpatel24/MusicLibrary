const api = (path, opts = {}) =>
  fetch(path, {
    headers: { "Content-Type": "application/json", ...opts.headers },
    ...opts,
  }).then(async (r) => {
    const text = await r.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      throw new Error(text || r.statusText);
    }
    if (!r.ok) {
      throw new Error(data.error || r.statusText || "Request failed");
    }
    return data;
  });

function toast(msg) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.remove("show"), 3200);
}

function renderLibrary(playlists) {
  const root = document.getElementById("library-root");
  if (!playlists.length) {
    root.innerHTML = '<div class="playlist-card empty">No playlists yet.</div>';
    return;
  }
  root.innerHTML = playlists
    .map((pl) => {
      const songs =
        pl.songs && pl.songs.length
          ? `<ol class="song-list">${pl.songs
              .map(
                (s) =>
                  `<li><strong>${escapeHtml(s.songName)}</strong> — ${escapeHtml(s.artist)} (${s.year}, pop ${s.popularity})</li>`
              )
              .join("")}</ol>`
          : "<p class=\"hint\">Empty playlist.</p>";
      return `<div class="playlist-card"><h4>Playlist ${pl.index} · ${pl.size} song(s)</h4>${songs}</div>`;
    })
    .join("");
}

function escapeHtml(s) {
  if (!s) return "";
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function refreshLibrary() {
  const data = await api("/api/library");
  renderLibrary(data.playlists || []);
}

document.getElementById("btn-refresh").addEventListener("click", () => {
  refreshLibrary().then(() => toast("Library refreshed")).catch((e) => toast(String(e.message)));
});

document.querySelectorAll(".nav-btn").forEach((b) => {
  b.addEventListener("click", () => {
    const id = b.getAttribute("data-scroll");
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  });
});

document.getElementById("btn-add-playlist").addEventListener("click", async () => {
  const filename = document.getElementById("add-filename").value.trim();
  const index = Number(document.getElementById("add-index").value);
  try {
    await api("/api/playlist", {
      method: "POST",
      body: JSON.stringify({ filename, index }),
    });
    toast("Playlist added");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-add-csv").addEventListener("click", async () => {
  const csv = document.getElementById("add-csv").value;
  const index = Number(document.getElementById("add-csv-index").value);
  try {
    await api("/api/playlist/from-csv", {
      method: "POST",
      body: JSON.stringify({ csv, index }),
    });
    toast("Playlist added from CSV");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-remove").addEventListener("click", async () => {
  const index = Number(document.getElementById("remove-index").value);
  try {
    const data = await api(`/api/playlist/${index}`, { method: "DELETE" });
    toast(data.ok ? "Removed" : "Nothing removed");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-load-all").addEventListener("click", async () => {
  const raw = document.getElementById("load-all-files").value;
  const filenames = raw.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  try {
    await api("/api/playlists/load-all", {
      method: "POST",
      body: JSON.stringify({ filenames }),
    });
    toast("Loaded all playlists");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-add-song").addEventListener("click", async () => {
  const body = {
    position: Number(document.getElementById("song-add-pos").value),
    songName: document.getElementById("song-add-name").value,
    artist: document.getElementById("song-add-artist").value,
    year: Number(document.getElementById("song-add-year").value),
    popularity: Number(document.getElementById("song-add-pop").value),
  };
  const link = document.getElementById("song-add-link").value.trim();
  if (link) body.link = link;
  const pi = Number(document.getElementById("song-add-pi").value);
  try {
    const data = await api(`/api/playlist/${pi}/song`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    toast(data.ok ? "Song inserted" : "Insert failed (check index/position)");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-find").addEventListener("click", async () => {
  const pi = Number(document.getElementById("find-pi").value);
  const songName = document.getElementById("find-name").value;
  const out = document.getElementById("find-result");
  try {
    const res = await fetch(`/api/playlist/${pi}/find`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ songName }),
    });
    const data = await res.json();
    if (!res.ok) {
      out.textContent = data.error || "Not found";
      return;
    }
    out.textContent = data.found ? JSON.stringify(data.song, null, 2) : "Not found";
  } catch (e) {
    out.textContent = String(e.message);
  }
});

document.getElementById("btn-delete-song").addEventListener("click", async () => {
  const body = {
    position: 0,
    songName: document.getElementById("del-name").value,
    artist: document.getElementById("del-artist").value,
    year: Number(document.getElementById("del-year").value),
    popularity: Number(document.getElementById("del-pop").value),
  };
  const pi = Number(document.getElementById("del-pi").value);
  try {
    const data = await api(`/api/playlist/${pi}/delete-song`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    toast(data.ok ? "Song removed" : "Song not found");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-reverse").addEventListener("click", async () => {
  const index = Number(document.getElementById("rev-index").value);
  try {
    await api(`/api/playlist/${index}/reverse`, { method: "POST", body: "{}" });
    toast("Reversed");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-combine").addEventListener("click", async () => {
  const index1 = Number(document.getElementById("comb-a").value);
  const index2 = Number(document.getElementById("comb-b").value);
  try {
    await api("/api/playlists/combine", {
      method: "POST",
      body: JSON.stringify({ index1, index2 }),
    });
    toast("Combined");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

document.getElementById("btn-shuffle").addEventListener("click", async () => {
  const index = Number(document.getElementById("shuf-index").value);
  try {
    await api(`/api/playlist/${index}/shuffle`, { method: "POST", body: "{}" });
    toast("Shuffled (seed 2026)");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

refreshLibrary().catch(() => {
  document.getElementById("library-root").innerHTML =
    '<div class="playlist-card empty">Could not load library. Is the server running?</div>';
});
