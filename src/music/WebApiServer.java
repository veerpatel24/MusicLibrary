package music;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import edu.rutgers.cs112.node.LLNode;

/**
 * HTTP API and static UI for {@link MusicLibrary}. Mirrors {@link Driver}; optional Spotify / YouTube / SoundCloud import.
 */
public final class WebApiServer {

    private static final int SHUFFLE_SEED = 2026;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long OAUTH_TTL_MS = 900_000L;

    private final MusicLibrary library = new MusicLibrary();
    private final Path dataRoot;
    private final Path publicRoot;
    private final StreamingIntegrations streaming;
    private final ConcurrentHashMap<String, PendingOAuth> oauthPending = new ConcurrentHashMap<>();

    private int listenPort = 7070;

    private WebApiServer(Path dataRoot) {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.publicRoot = this.dataRoot.resolve("web/public").normalize();
        this.streaming = new StreamingIntegrations(this.dataRoot);
    }

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(System.getProperty("music.library.root", ".")).normalize();
        int port = Integer.parseInt(System.getProperty("music.library.port", "7070"));
        new WebApiServer(root).start(port);
    }

    private void start(int port) throws IOException {
        this.listenPort = port;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/callback/", this::handleOAuthCallback);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        String po = env("MUSIC_PUBLIC_ORIGIN");
        String hint = po != null ? po.replaceAll("/+$", "") : "http://127.0.0.1:" + port;
        System.out.println("Music Library UI: " + hint + "/  (OAuth redirect URIs must use the same host you open in the browser, or set MUSIC_PUBLIC_ORIGIN)");
        System.out.println("Data root: " + dataRoot);
    }

    /**
     * OAuth redirect_uri must match the authorize request exactly. Prefer deriving from the incoming Host
     * so http://127.0.0.1:7070 and http://localhost:7070 both work when registered with the provider.
     */
    private String resolvePublicOrigin(HttpExchange ex) {
        String po = env("MUSIC_PUBLIC_ORIGIN");
        if (po != null) {
            return po.replaceAll("/+$", "");
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            return "http://127.0.0.1:" + listenPort;
        }
        host = host.trim();
        String xf = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String scheme = "https".equalsIgnoreCase(xf) ? "https" : "http";
        if (!host.contains(":") && listenPort != 80 && listenPort != 443) {
            host = host + ":" + listenPort;
        }
        return scheme + "://" + host;
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        v = v.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v.isBlank() ? null : v;
    }

    /** Env first, then {@code -D} system property (e.g. {@code -Dspotify.client.id=...}). */
    private static String cfg(String envKey, String systemPropertyKey) {
        String v = env(envKey);
        if (v != null) {
            return v;
        }
        if (systemPropertyKey != null) {
            v = System.getProperty(systemPropertyKey);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private void handleOAuthCallback(HttpExchange ex) throws IOException {
        addCors(ex);
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        try {
            URI uri = ex.getRequestURI();
            String q = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            Map<String, String> qp = parseQuery(q);
            String code = qp.get("code");
            String state = qp.get("state");
            String err = qp.get("error");
            String origin = resolvePublicOrigin(ex);
            if (err != null) {
                redirectBrowser(ex, origin + "/?connect=error&reason=" + urlEnc(err));
                return;
            }
            if (code == null || state == null) {
                redirectBrowser(ex, origin + "/?connect=error&reason=missing_params");
                return;
            }
            PendingOAuth p = oauthPending.remove(state);
            if (p == null || System.currentTimeMillis() > p.expiresAt) {
                redirectBrowser(ex, origin + "/?connect=error&reason=bad_state");
                return;
            }
            String redir = origin + "/callback/" + p.provider;
            switch (p.provider) {
                case "spotify" -> {
                    String cid = cfg("SPOTIFY_CLIENT_ID", "spotify.client.id");
                    if (cid == null) {
                        throw new IllegalStateException("SPOTIFY_CLIENT_ID not set");
                    }
                    streaming.spotifyFinishCode(cid, code, redir, p.verifier);
                }
                case "youtube" -> {
                    String cid = cfg("GOOGLE_CLIENT_ID", "google.client.id");
                    String sec = cfg("GOOGLE_CLIENT_SECRET", "google.client.secret");
                    if (cid == null || sec == null) {
                        throw new IllegalStateException("GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET not set");
                    }
                    streaming.youtubeFinishCode(cid, sec, code, redir);
                }
                case "soundcloud" -> {
                    String cid = cfg("SOUNDCLOUD_CLIENT_ID", "soundcloud.client.id");
                    String sec = cfg("SOUNDCLOUD_CLIENT_SECRET", "soundcloud.client.secret");
                    if (cid == null || sec == null) {
                        throw new IllegalStateException("SOUNDCLOUD_CLIENT_ID / SOUNDCLOUD_CLIENT_SECRET not set");
                    }
                    streaming.soundcloudFinishCode(cid, sec, code, redir, p.verifier);
                }
                default -> throw new IllegalStateException("unknown provider");
            }
            redirectBrowser(ex, origin + "/?connect=ok&provider=" + urlEnc(p.provider));
        } catch (Exception e) {
            String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("[oauth callback] " + m);
            e.printStackTrace(System.err);
            String origin = resolvePublicOrigin(ex);
            redirectBrowser(ex, origin + "/?connect=error&reason=" + urlEnc(m));
        }
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) {
                String k = java.net.URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8);
                String v = java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
                m.put(k, v);
            }
        }
        return m;
    }

    private static void redirectBrowser(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void handleApi(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        try {
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            String method = ex.getRequestMethod();
            if (dispatchApi(ex, method, path)) {
                return;
            }
            sendJson(ex, 404, Map.of("error", "not found"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ex, 503, Map.of("error", "interrupted"));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sendJson(ex, 400, Map.of("error", msg));
        }
    }

    private static final Pattern P_PLAYLIST_INDEX = Pattern.compile("^/api/playlist/(\\d+)$");
    private static final Pattern P_SONG = Pattern.compile("^/api/playlist/(\\d+)/song$");
    private static final Pattern P_FIND = Pattern.compile("^/api/playlist/(\\d+)/find$");
    private static final Pattern P_DEL = Pattern.compile("^/api/playlist/(\\d+)/delete-song$");
    private static final Pattern P_REV = Pattern.compile("^/api/playlist/(\\d+)/reverse$");
    private static final Pattern P_SHUF = Pattern.compile("^/api/playlist/(\\d+)/shuffle$");

    private boolean dispatchApi(HttpExchange ex, String method, String path) throws IOException, InterruptedException {
        String origin = resolvePublicOrigin(ex);
        if ("/api/health".equals(path) && "GET".equals(method)) {
            sendJson(ex, 200, Map.of("ok", true));
            return true;
        }
        if ("/api/integrations/config".equals(path) && "GET".equals(method)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("publicOrigin", origin);
            m.put("spotifyConfigured", cfg("SPOTIFY_CLIENT_ID", "spotify.client.id") != null);
            m.put("youtubeConfigured", cfg("GOOGLE_CLIENT_ID", "google.client.id") != null
                    && cfg("GOOGLE_CLIENT_SECRET", "google.client.secret") != null);
            m.put("soundcloudConfigured", cfg("SOUNDCLOUD_CLIENT_ID", "soundcloud.client.id") != null
                    && cfg("SOUNDCLOUD_CLIENT_SECRET", "soundcloud.client.secret") != null);
            m.put("usingEnvOverride", env("MUSIC_PUBLIC_ORIGIN") != null);
            sendJson(ex, 200, m);
            return true;
        }
        if ("/api/integrations/status".equals(path) && "GET".equals(method)) {
            sendJson(ex, 200, streaming.status());
            return true;
        }
        if ("/api/integrations/spotify/begin".equals(path) && "GET".equals(method)) {
            String cid = cfg("SPOTIFY_CLIENT_ID", "spotify.client.id");
            if (cid == null) {
                sendJson(ex, 400, Map.of("error",
                        "Set SPOTIFY_CLIENT_ID in .env or -Dspotify.client.id=… Register redirect URI exactly: "
                                + origin + "/callback/spotify"));
                return true;
            }
            String state = StreamingIntegrations.randomUrlSafe(16);
            String verifier = StreamingIntegrations.randomUrlSafe(48);
            String challenge = StreamingIntegrations.pkceChallengeS256(verifier);
            oauthPending.put(state, new PendingOAuth("spotify", verifier, System.currentTimeMillis() + OAUTH_TTL_MS));
            String url = streaming.spotifyAuthorizeUrl(cid, origin + "/callback/spotify", state, challenge);
            sendJson(ex, 200, Map.of("url", url));
            return true;
        }
        if ("/api/integrations/youtube/begin".equals(path) && "GET".equals(method)) {
            String cid = cfg("GOOGLE_CLIENT_ID", "google.client.id");
            String gsec = cfg("GOOGLE_CLIENT_SECRET", "google.client.secret");
            if (cid == null || gsec == null) {
                sendJson(ex, 400, Map.of("error",
                        "Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env (or -Dgoogle.client.id / -Dgoogle.client.secret). Redirect: "
                                + origin + "/callback/youtube"));
                return true;
            }
            String state = StreamingIntegrations.randomUrlSafe(16);
            oauthPending.put(state, new PendingOAuth("youtube", null, System.currentTimeMillis() + OAUTH_TTL_MS));
            String url = streaming.youtubeAuthorizeUrl(cid, origin + "/callback/youtube", state);
            sendJson(ex, 200, Map.of("url", url));
            return true;
        }
        if ("/api/integrations/soundcloud/begin".equals(path) && "GET".equals(method)) {
            String cid = cfg("SOUNDCLOUD_CLIENT_ID", "soundcloud.client.id");
            String scsec = cfg("SOUNDCLOUD_CLIENT_SECRET", "soundcloud.client.secret");
            if (cid == null || scsec == null) {
                sendJson(ex, 400, Map.of("error",
                        "Set SOUNDCLOUD_CLIENT_ID and SOUNDCLOUD_CLIENT_SECRET. Redirect: "
                                + origin + "/callback/soundcloud"));
                return true;
            }
            String state = StreamingIntegrations.randomUrlSafe(16);
            String verifier = StreamingIntegrations.randomUrlSafe(48);
            String challenge = StreamingIntegrations.pkceChallengeS256(verifier);
            oauthPending.put(state, new PendingOAuth("soundcloud", verifier, System.currentTimeMillis() + OAUTH_TTL_MS));
            String url = streaming.soundcloudAuthorizeUrl(cid, origin + "/callback/soundcloud", state, challenge);
            sendJson(ex, 200, Map.of("url", url));
            return true;
        }
        if ("/api/integrations/spotify/disconnect".equals(path) && "POST".equals(method)) {
            streaming.disconnect("spotify");
            sendJson(ex, 200, Map.of("ok", true));
            return true;
        }
        if ("/api/integrations/youtube/disconnect".equals(path) && "POST".equals(method)) {
            streaming.disconnect("youtube");
            sendJson(ex, 200, Map.of("ok", true));
            return true;
        }
        if ("/api/integrations/soundcloud/disconnect".equals(path) && "POST".equals(method)) {
            streaming.disconnect("soundcloud");
            sendJson(ex, 200, Map.of("ok", true));
            return true;
        }
        if ("/api/integrations/spotify/playlists".equals(path) && "GET".equals(method)) {
            String cid = cfg("SPOTIFY_CLIENT_ID", "spotify.client.id");
            if (cid == null) {
                sendJson(ex, 400, Map.of("error", "SPOTIFY_CLIENT_ID not set"));
                return true;
            }
            sendJson(ex, 200, Map.of("playlists", streaming.spotifyListPlaylists(cid)));
            return true;
        }
        if ("/api/integrations/youtube/playlists".equals(path) && "GET".equals(method)) {
            String cid = cfg("GOOGLE_CLIENT_ID", "google.client.id");
            String sec = cfg("GOOGLE_CLIENT_SECRET", "google.client.secret");
            if (cid == null || sec == null) {
                sendJson(ex, 400, Map.of("error", "Google OAuth env not set"));
                return true;
            }
            sendJson(ex, 200, Map.of("playlists", streaming.youtubeListPlaylists(cid, sec)));
            return true;
        }
        if ("/api/integrations/soundcloud/playlists".equals(path) && "GET".equals(method)) {
            sendJson(ex, 200, Map.of("playlists", streaming.soundcloudPlaylists()));
            return true;
        }
        if ("/api/integrations/spotify/import".equals(path) && "POST".equals(method)) {
            JsonObject o = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            String pid = o.get("playlistId").getAsString();
            int index = o.get("index").getAsInt();
            String cid = cfg("SPOTIFY_CLIENT_ID", "spotify.client.id");
            if (cid == null) {
                sendJson(ex, 400, Map.of("error", "SPOTIFY_CLIENT_ID not set"));
                return true;
            }
            String csv = streaming.spotifyPlaylistToCsv(cid, pid);
            Path dest = writeImportCsv("spotify", csv);
            synchronized (library) {
                library.addPlaylist(dest.toString(), index);
                sendJson(ex, 200, Map.of("ok", true, "savedAs", dest.getFileName().toString(), "playlists", buildLibraryPayload()));
            }
            return true;
        }
        if ("/api/integrations/youtube/import".equals(path) && "POST".equals(method)) {
            JsonObject o = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            String pid = o.get("playlistId").getAsString();
            int index = o.get("index").getAsInt();
            String cid = cfg("GOOGLE_CLIENT_ID", "google.client.id");
            String sec = cfg("GOOGLE_CLIENT_SECRET", "google.client.secret");
            if (cid == null || sec == null) {
                sendJson(ex, 400, Map.of("error", "Google OAuth env not set"));
                return true;
            }
            String csv = streaming.youtubePlaylistToCsv(cid, sec, pid);
            Path dest = writeImportCsv("youtube", csv);
            synchronized (library) {
                library.addPlaylist(dest.toString(), index);
                sendJson(ex, 200, Map.of("ok", true, "savedAs", dest.getFileName().toString(), "playlists", buildLibraryPayload()));
            }
            return true;
        }
        if ("/api/integrations/soundcloud/import".equals(path) && "POST".equals(method)) {
            JsonObject o = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            String pid = o.get("playlistId").getAsString();
            int index = o.get("index").getAsInt();
            String csv = streaming.soundcloudPlaylistToCsv(pid);
            Path dest = writeImportCsv("soundcloud", csv);
            synchronized (library) {
                library.addPlaylist(dest.toString(), index);
                sendJson(ex, 200, Map.of("ok", true, "savedAs", dest.getFileName().toString(), "playlists", buildLibraryPayload()));
            }
            return true;
        }
        if ("/api/library".equals(path) && "GET".equals(method)) {
            synchronized (library) {
                sendJson(ex, 200, Map.of("playlists", buildLibraryPayload()));
            }
            return true;
        }
        if ("/api/playlist".equals(path) && "POST".equals(method)) {
            AddPlaylistBody body = GSON.fromJson(readBody(ex), AddPlaylistBody.class);
            Path file = resolveDataFile(body.filename);
            synchronized (library) {
                library.addPlaylist(file.toString(), body.index);
                sendJson(ex, 200, Map.of("ok", true, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        if ("/api/playlist/from-csv".equals(path) && "POST".equals(method)) {
            JsonObject o = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            int index = o.get("index").getAsInt();
            String csv = o.get("csv").getAsString();
            Path uploads = dataRoot.resolve("web/uploads");
            Files.createDirectories(uploads);
            Path dest = uploads.resolve("paste_" + System.currentTimeMillis() + ".csv");
            Files.writeString(dest, csv, StandardCharsets.UTF_8);
            synchronized (library) {
                library.addPlaylist(dest.toString(), index);
                sendJson(ex, 200, Map.of("ok", true, "playlists", buildLibraryPayload(), "savedAs", dest.getFileName().toString()));
            }
            return true;
        }
        if ("/api/playlists/load-all".equals(path) && "POST".equals(method)) {
            LoadAllBody body = GSON.fromJson(readBody(ex), LoadAllBody.class);
            List<String> names = body.filenames == null ? List.of() : body.filenames;
            String[] strPaths = new String[names.size()];
            for (int i = 0; i < names.size(); i++) {
                strPaths[i] = resolveDataFile(names.get(i).trim()).toString();
            }
            synchronized (library) {
                library.addAllPlaylists(strPaths);
                sendJson(ex, 200, Map.of("ok", true, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        if ("/api/playlists/combine".equals(path) && "POST".equals(method)) {
            CombineBody body = GSON.fromJson(readBody(ex), CombineBody.class);
            synchronized (library) {
                library.combinePlaylists(body.index1, body.index2);
                sendJson(ex, 200, Map.of("ok", true, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        Matcher m;
        m = P_PLAYLIST_INDEX.matcher(path);
        if (m.matches() && "DELETE".equals(method)) {
            int playlistIndex = Integer.parseInt(m.group(1));
            synchronized (library) {
                boolean ok = library.removePlaylist(playlistIndex);
                sendJson(ex, 200, Map.of("ok", ok, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        m = P_SONG.matcher(path);
        if (m.matches() && "POST".equals(method)) {
            int playlistIndex = Integer.parseInt(m.group(1));
            SongBody body = GSON.fromJson(readBody(ex), SongBody.class);
            Song song = body.toSong();
            synchronized (library) {
                boolean ok = library.addSong(playlistIndex, body.position, song);
                sendJson(ex, 200, Map.of("ok", ok, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        m = P_FIND.matcher(path);
        if (m.matches() && "POST".equals(method)) {
            int playlistIndex = Integer.parseInt(m.group(1));
            FindBody body = GSON.fromJson(readBody(ex), FindBody.class);
            synchronized (library) {
                Song found = library.findSong(playlistIndex, body.songName);
                if (found == null) {
                    sendJson(ex, 404, Map.of("found", false));
                } else {
                    sendJson(ex, 200, Map.of("found", true, "song", songToMap(found)));
                }
            }
            return true;
        }
        m = P_DEL.matcher(path);
        if (m.matches() && "POST".equals(method)) {
            int playlistIndex = Integer.parseInt(m.group(1));
            SongBody body = GSON.fromJson(readBody(ex), SongBody.class);
            Song song = body.toSong();
            synchronized (library) {
                boolean ok = library.deleteSong(playlistIndex, song);
                sendJson(ex, 200, Map.of("ok", ok, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        m = P_REV.matcher(path);
        if (m.matches() && "POST".equals(method)) {
            int playlistIndex = Integer.parseInt(m.group(1));
            synchronized (library) {
                library.reversePlaylist(playlistIndex);
                sendJson(ex, 200, Map.of("ok", true, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        m = P_SHUF.matcher(path);
        if (m.matches() && "POST".equals(method)) {
            int playlistIndex = Integer.parseInt(m.group(1));
            synchronized (library) {
                StdRandom.setSeed(SHUFFLE_SEED);
                library.shufflePlaylist(playlistIndex);
                sendJson(ex, 200, Map.of("ok", true, "seed", SHUFFLE_SEED, "playlists", buildLibraryPayload()));
            }
            return true;
        }
        return false;
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String raw = ex.getRequestURI().getPath();
        if (raw == null || raw.isEmpty() || "/".equals(raw)) {
            raw = "/index.html";
        }
        Path file = publicRoot.resolve(raw.substring(1)).normalize();
        if (!file.startsWith(publicRoot) || !Files.isRegularFile(file)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        String ct = guessContentType(file.toString());
        ex.getResponseHeaders().set("Content-Type", ct);
        String fn = file.toString();
        if (fn.endsWith(".css") || fn.endsWith(".js")) {
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        }
        ex.sendResponseHeaders(200, bytes.length);
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            ex.close();
        }
    }

    private static String guessContentType(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        byte[] all = is.readAllBytes();
        return new String(all, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] json = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, json.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(json);
        }
    }

    private Path writeImportCsv(String prefix, String csv) throws IOException {
        Path uploads = dataRoot.resolve("web/uploads");
        Files.createDirectories(uploads);
        Path dest = uploads.resolve("import_" + prefix + "_" + System.currentTimeMillis() + ".csv");
        Files.writeString(dest, csv, StandardCharsets.UTF_8);
        return dest;
    }

    private Path resolveDataFile(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename required");
        }
        Path p = dataRoot.resolve(filename).normalize();
        if (!p.startsWith(dataRoot)) {
            throw new IllegalArgumentException("invalid path");
        }
        return p;
    }

    private static final class PendingOAuth {
        final String provider;
        final String verifier;
        final long expiresAt;

        PendingOAuth(String provider, String verifier, long expiresAt) {
            this.provider = provider;
            this.verifier = verifier;
            this.expiresAt = expiresAt;
        }
    }

    private List<Map<String, Object>> buildLibraryPayload() {
        ArrayList<Playlist> lists = library.getPlaylists();
        List<Map<String, Object>> out = new ArrayList<>();
        if (lists == null) {
            return out;
        }
        for (int i = 0; i < lists.size(); i++) {
            out.add(playlistToMap(i, lists.get(i)));
        }
        return out;
    }

    private Map<String, Object> playlistToMap(int index, Playlist p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("size", p.getSize());
        m.put("songs", songsInOrder(p));
        return m;
    }

    private List<Map<String, Object>> songsInOrder(Playlist p) {
        List<Map<String, Object>> songs = new ArrayList<>();
        LLNode<Song> last = p.getLast();
        if (last == null) {
            return songs;
        }
        LLNode<Song> ptr = last.getNext();
        LLNode<Song> start = ptr;
        do {
            songs.add(songToMap(ptr.getData()));
            ptr = ptr.getNext();
        } while (ptr != start);
        return songs;
    }

    private Map<String, Object> songToMap(Song s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songName", s.getSongName());
        m.put("artist", s.getArtist());
        m.put("year", s.getYear());
        m.put("popularity", s.getPopularity());
        m.put("link", s.getLink());
        return m;
    }

    @SuppressWarnings("unused")
    private static final class AddPlaylistBody {
        String filename;
        int index;
    }

    @SuppressWarnings("unused")
    private static final class LoadAllBody {
        List<String> filenames;
    }

    @SuppressWarnings("unused")
    private static final class SongBody {
        int position;
        String songName;
        String artist;
        int year;
        int popularity;
        String link;

        Song toSong() {
            Song s = new Song(songName, artist, year, popularity);
            if (link != null && !link.isBlank()) {
                s.setLink(link);
            }
            return s;
        }
    }

    @SuppressWarnings("unused")
    private static final class FindBody {
        String songName;
    }

    @SuppressWarnings("unused")
    private static final class CombineBody {
        int index1;
        int index2;
    }
}
