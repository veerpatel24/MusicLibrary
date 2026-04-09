async function api(path, opts = {}) {
  const r = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
    ...opts,
  });
  const text = await r.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(text || r.statusText);
  }
  if (!r.ok) throw new Error(data.error || r.statusText || "Request failed");
  return data;
}

function toast(msg) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.remove("show"), 2800);
}

function esc(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function renderLibrary(playlists) {
  const root = document.getElementById("library-root");
  if (!playlists.length) {
    root.innerHTML = '<div class="playlist-card empty">No playlists.</div>';
    return;
  }
  root.innerHTML = playlists
    .map((pl) => {
      const songs =
        pl.songs?.length > 0
          ? `<ol class="song-list">${pl.songs
              .map((s) => `<li><strong>${esc(s.songName)}</strong> — ${esc(s.artist)} (${s.year}, ${s.popularity})</li>`)
              .join("")}</ol>`
          : '<p class="hint">Empty.</p>';
      return `<div class="playlist-card"><h4>#${pl.index} · ${pl.size} songs</h4>${songs}</div>`;
    })
    .join("");
}

async function refreshLibrary() {
  const data = await api("/api/library");
  renderLibrary(data.playlists || []);
}

function bind(id, ev, fn) {
  document.getElementById(id)?.addEventListener(ev, fn);
}

document.querySelectorAll(".tab").forEach((b) => {
  b.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    const on = b.dataset.tab === "lib";
    document.getElementById("tab-lib").classList.toggle("hidden", !on);
    document.getElementById("tab-stream").classList.toggle("hidden", on);
  });
});

bind("btn-refresh", "click", () =>
  refreshLibrary().then(() => toast("OK")).catch((e) => toast(e.message))
);

bind("btn-add-playlist", "click", async () => {
  try {
    await api("/api/playlist", {
      method: "POST",
      body: JSON.stringify({
        filename: document.getElementById("add-filename").value.trim(),
        index: Number(document.getElementById("add-index").value),
      }),
    });
    toast("Added");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-add-csv", "click", async () => {
  try {
    await api("/api/playlist/from-csv", {
      method: "POST",
      body: JSON.stringify({
        csv: document.getElementById("add-csv").value,
        index: Number(document.getElementById("add-csv-index").value),
      }),
    });
    toast("Added");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-load-all", "click", async () => {
  const lines = document
    .getElementById("load-all-files")
    .value.split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  try {
    await api("/api/playlists/load-all", { method: "POST", body: JSON.stringify({ filenames: lines }) });
    toast("Loaded");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-remove", "click", async () => {
  try {
    const data = await api(`/api/playlist/${Number(document.getElementById("remove-index").value)}`, {
      method: "DELETE",
    });
    toast(data.ok ? "Removed" : "No change");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-add-song", "click", async () => {
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
    const data = await api(`/api/playlist/${pi}/song`, { method: "POST", body: JSON.stringify(body) });
    toast(data.ok ? "Inserted" : "Failed");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-find", "click", async () => {
  const out = document.getElementById("find-result");
  const pi = Number(document.getElementById("find-pi").value);
  const songName = document.getElementById("find-name").value;
  try {
    const res = await fetch(`/api/playlist/${pi}/find`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ songName }),
    });
    const data = await res.json();
    out.textContent = res.ok ? (data.found ? JSON.stringify(data.song, null, 2) : "Not found") : data.error || "?";
  } catch (e) {
    out.textContent = e.message;
  }
});

bind("btn-delete-song", "click", async () => {
  const body = {
    position: 0,
    songName: document.getElementById("del-name").value,
    artist: document.getElementById("del-artist").value,
    year: Number(document.getElementById("del-year").value),
    popularity: Number(document.getElementById("del-pop").value),
  };
  const pi = Number(document.getElementById("del-pi").value);
  try {
    const data = await api(`/api/playlist/${pi}/delete-song`, { method: "POST", body: JSON.stringify(body) });
    toast(data.ok ? "Deleted" : "Not found");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-reverse", "click", async () => {
  const i = Number(document.getElementById("rev-index").value);
  try {
    await api(`/api/playlist/${i}/reverse`, { method: "POST", body: "{}" });
    toast("Reversed");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-combine", "click", async () => {
  try {
    await api("/api/playlists/combine", {
      method: "POST",
      body: JSON.stringify({
        index1: Number(document.getElementById("comb-a").value),
        index2: Number(document.getElementById("comb-b").value),
      }),
    });
    toast("Combined");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

bind("btn-shuffle", "click", async () => {
  const i = Number(document.getElementById("shuf-index").value);
  try {
    await api(`/api/playlist/${i}/shuffle`, { method: "POST", body: "{}" });
    toast("Shuffled");
    await refreshLibrary();
  } catch (e) {
    toast(e.message);
  }
});

async function integrationStatus() {
  return api("/api/integrations/status");
}

function setStreamStatus(st) {
  ["spotify", "youtube", "soundcloud"].forEach((k) => {
    const el = document.getElementById(`st-${k}`);
    if (!el) return;
    const on = st[k];
    el.textContent = on ? "Connected" : "Not connected";
    el.classList.toggle("on", on);
  });
}

document.querySelectorAll(".connect-card").forEach((card) => {
  const svc = card.dataset.svc;
  const listEl = card.querySelector(".stream-list");

  card.querySelector(".btn-connect")?.addEventListener("click", async () => {
    try {
      const { url } = await api(`/api/integrations/${svc}/begin`);
      window.location.href = url;
    } catch (e) {
      toast(e.message);
    }
  });

  card.querySelector(".btn-disconnect")?.addEventListener("click", async () => {
    try {
      await api(`/api/integrations/${svc}/disconnect`, { method: "POST", body: "{}" });
      toast("Disconnected");
      setStreamStatus(await integrationStatus());
    } catch (e) {
      toast(e.message);
    }
  });

  card.querySelector(".btn-list")?.addEventListener("click", async () => {
    try {
      const data = await api(`/api/integrations/${svc}/playlists`);
      const rows = data.playlists || [];
      listEl.textContent = rows.length ? JSON.stringify(rows, null, 2) : "(empty)";
    } catch (e) {
      listEl.textContent = e.message;
    }
  });

  card.querySelector(".btn-import")?.addEventListener("click", async () => {
    const playlistId = card.querySelector(".imp-id").value.trim();
    const index = Number(card.querySelector(".imp-idx").value);
    if (!playlistId) {
      toast("Playlist ID required");
      return;
    }
    try {
      await api(`/api/integrations/${svc}/import`, {
        method: "POST",
        body: JSON.stringify({ playlistId, index }),
      });
      toast("Imported");
      await refreshLibrary();
    } catch (e) {
      toast(e.message);
    }
  });
});

const params = new URLSearchParams(location.search);
if (params.get("connect") === "ok") {
  toast(`Connected: ${params.get("provider") || "?"}`);
  history.replaceState({}, "", location.pathname);
} else if (params.get("connect") === "error") {
  toast(`Connect failed: ${params.get("reason") || "?"}`);
  history.replaceState({}, "", location.pathname);
}

document.getElementById("cb-base").textContent = `${location.origin}/callback/{spotify|youtube|soundcloud}`;

integrationStatus()
  .then(setStreamStatus)
  .catch(() => {});

refreshLibrary().catch(() => {
  document.getElementById("library-root").innerHTML =
    '<div class="playlist-card empty">Server offline?</div>';
});
