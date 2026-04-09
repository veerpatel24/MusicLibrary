package music;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OAuth (PKCE where supported) and playlist → CSV import for Spotify, YouTube (Data API), and SoundCloud.
 * Tokens persist under the project root; never commit that file.
 */
public final class StreamingIntegrations {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RNG = new SecureRandom();

    private final Path tokenPath;
    private TokenFile tokens;

    public StreamingIntegrations(Path dataRoot) {
        this.tokenPath = dataRoot.resolve(".integration-tokens.json").normalize();
        this.tokens = load();
    }

    private TokenFile load() {
        try {
            if (Files.isRegularFile(tokenPath)) {
                return GSON.fromJson(Files.readString(tokenPath, StandardCharsets.UTF_8), TokenFile.class);
            }
        } catch (Exception ignored) {
        }
        return new TokenFile();
    }

    private synchronized void save() throws IOException {
        Files.createDirectories(tokenPath.getParent());
        Files.writeString(tokenPath, GSON.toJson(tokens), StandardCharsets.UTF_8);
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spotify", tokens.spotify != null && tokens.spotify.accessToken != null);
        m.put("youtube", tokens.youtube != null && tokens.youtube.accessToken != null);
        m.put("soundcloud", tokens.soundcloud != null && tokens.soundcloud.accessToken != null);
        return m;
    }

    public synchronized void disconnect(String provider) throws IOException {
        switch (provider) {
            case "spotify" -> tokens.spotify = null;
            case "youtube" -> tokens.youtube = null;
            case "soundcloud" -> tokens.soundcloud = null;
            default -> throw new IllegalArgumentException("unknown provider");
        }
        save();
    }

    // ——— PKCE ———

    public static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String pkceChallengeS256(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ——— Spotify ———

    public String spotifyAuthorizeUrl(String clientId, String redirectUri, String state, String codeChallenge) {
        String scope = URLEncoder.encode("playlist-read-private playlist-read-collaborative", StandardCharsets.UTF_8);
        return "https://accounts.spotify.com/authorize?"
                + "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&scope=" + scope
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8);
    }

    public synchronized void spotifyFinishCode(String clientId, String code, String redirectUri, String verifier)
            throws IOException, InterruptedException {
        String body = "grant_type=authorization_code"
                + "&code=" + url(code)
                + "&redirect_uri=" + url(redirectUri)
                + "&client_id=" + url(clientId)
                + "&code_verifier=" + url(verifier);
        JsonObject res = httpForm("https://accounts.spotify.com/api/token", body);
        tokens.spotify = new ServiceToken(
                res.get("access_token").getAsString(),
                res.has("refresh_token") && !res.get("refresh_token").isJsonNull() ? res.get("refresh_token").getAsString() : null,
                System.currentTimeMillis() + res.get("expires_in").getAsInt() * 1000L);
        save();
    }

    private synchronized String spotifyAccessToken(String clientId) throws IOException, InterruptedException {
        ServiceToken t = tokens.spotify;
        if (t == null || t.accessToken == null) {
            throw new IllegalStateException("Spotify not connected");
        }
        if (t.expiresAtMs > System.currentTimeMillis() + 30_000L || t.refreshToken == null) {
            return t.accessToken;
        }
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + url(t.refreshToken)
                + "&client_id=" + url(clientId);
        JsonObject res = httpForm("https://accounts.spotify.com/api/token", body);
        t.accessToken = res.get("access_token").getAsString();
        if (res.has("refresh_token") && !res.get("refresh_token").isJsonNull()) {
            t.refreshToken = res.get("refresh_token").getAsString();
        }
        t.expiresAtMs = System.currentTimeMillis() + res.get("expires_in").getAsInt() * 1000L;
        save();
        return t.accessToken;
    }

    public List<Map<String, Object>> spotifyListPlaylists(String clientId) throws IOException, InterruptedException {
        String token = spotifyAccessToken(clientId);
        List<Map<String, Object>> out = new ArrayList<>();
        String url = "https://api.spotify.com/v1/me/playlists?limit=50";
        while (url != null) {
            JsonObject page = httpGetJson(url, token);
            JsonArray items = page.getAsJsonArray("items");
            for (JsonElement el : items) {
                JsonObject p = el.getAsJsonObject();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.get("id").getAsString());
                row.put("name", p.get("name").getAsString());
                row.put("tracks", p.getAsJsonObject("tracks").get("total").getAsInt());
                out.add(row);
            }
            url = nextUrl(page);
        }
        return out;
    }

    public String spotifyPlaylistToCsv(String clientId, String playlistId) throws IOException, InterruptedException {
        String token = spotifyAccessToken(clientId);
        StringJoiner lines = new StringJoiner("\n");
        String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=50";
        while (url != null) {
            JsonObject page = httpGetJson(url, token);
            for (JsonElement el : page.getAsJsonArray("items")) {
                JsonObject wrap = el.getAsJsonObject();
                if (!wrap.has("track") || wrap.get("track").isJsonNull()) {
                    continue;
                }
                JsonObject tr = wrap.getAsJsonObject("track");
                if (!tr.has("id") || tr.get("id").isJsonNull()) {
                    continue;
                }
                String name = tr.get("name").getAsString();
                String artist = "";
                if (tr.has("artists") && tr.get("artists").isJsonArray() && tr.getAsJsonArray("artists").size() > 0) {
                    artist = tr.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
                }
                int year = 0;
                if (tr.has("album") && !tr.get("album").isJsonNull()) {
                    JsonObject al = tr.getAsJsonObject("album");
                    if (al.has("release_date")) {
                        String rd = al.get("release_date").getAsString();
                        year = parseYear(rd);
                    }
                }
                int pop = tr.has("popularity") && !tr.get("popularity").isJsonNull()
                        ? Math.min(99, tr.get("popularity").getAsInt())
                        : 50;
                String tid = tr.get("id").getAsString();
                String link = "spotify_track_" + tid + ".wav";
                lines.add(csvLine(name, artist, year, pop, link));
            }
            url = nextUrl(page);
        }
        return lines.toString();
    }

    // ——— YouTube (Google OAuth) ———

    public String youtubeAuthorizeUrl(String clientId, String redirectUri, String state) {
        String scope = URLEncoder.encode("https://www.googleapis.com/auth/youtube.readonly", StandardCharsets.UTF_8);
        return "https://accounts.google.com/o/oauth2/v2/auth?"
                + "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=" + scope
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    public synchronized void youtubeFinishCode(String clientId, String clientSecret, String code, String redirectUri)
            throws IOException, InterruptedException {
        String body = "grant_type=authorization_code"
                + "&code=" + url(code)
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret)
                + "&redirect_uri=" + url(redirectUri);
        JsonObject res = httpForm("https://oauth2.googleapis.com/token", body);
        tokens.youtube = new ServiceToken(
                res.get("access_token").getAsString(),
                res.has("refresh_token") && !res.get("refresh_token").isJsonNull() ? res.get("refresh_token").getAsString() : null,
                System.currentTimeMillis() + res.get("expires_in").getAsInt() * 1000L);
        save();
    }

    private synchronized String youtubeAccess(String clientId, String clientSecret) throws IOException, InterruptedException {
        ServiceToken t = tokens.youtube;
        if (t == null || t.accessToken == null) {
            throw new IllegalStateException("YouTube not connected");
        }
        if (t.expiresAtMs > System.currentTimeMillis() + 30_000L || t.refreshToken == null) {
            return t.accessToken;
        }
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + url(t.refreshToken)
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret);
        JsonObject res = httpForm("https://oauth2.googleapis.com/token", body);
        t.accessToken = res.get("access_token").getAsString();
        t.expiresAtMs = System.currentTimeMillis() + res.get("expires_in").getAsInt() * 1000L;
        save();
        return t.accessToken;
    }

    public List<Map<String, Object>> youtubeListPlaylists(String clientId, String clientSecret)
            throws IOException, InterruptedException {
        String token = youtubeAccess(clientId, clientSecret);
        List<Map<String, Object>> out = new ArrayList<>();
        String pageToken = null;
        do {
            String u = "https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=50";
            if (pageToken != null) {
                u += "&pageToken=" + url(pageToken);
            }
            JsonObject page = httpGetJson(u, token);
            if (page.has("items")) {
                for (JsonElement el : page.getAsJsonArray("items")) {
                    JsonObject p = el.getAsJsonObject();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", p.get("id").getAsString());
                    row.put("name", p.getAsJsonObject("snippet").get("title").getAsString());
                    out.add(row);
                }
            }
            pageToken = page.has("nextPageToken") && !page.get("nextPageToken").isJsonNull()
                    ? page.get("nextPageToken").getAsString()
                    : null;
        } while (pageToken != null);
        return out;
    }

    public String youtubePlaylistToCsv(String clientId, String clientSecret, String playlistId)
            throws IOException, InterruptedException {
        String token = youtubeAccess(clientId, clientSecret);
        StringJoiner lines = new StringJoiner("\n");
        String pageToken = null;
        do {
            String u = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId="
                    + url(playlistId);
            if (pageToken != null) {
                u += "&pageToken=" + url(pageToken);
            }
            JsonObject page = httpGetJson(u, token);
            if (page.has("items")) {
                for (JsonElement el : page.getAsJsonArray("items")) {
                    JsonObject it = el.getAsJsonObject();
                    JsonObject sn = it.getAsJsonObject("snippet");
                    String title = sn.get("title").getAsString();
                    String channel = sn.get("videoOwnerChannelTitle") != null && !sn.get("videoOwnerChannelTitle").isJsonNull()
                            ? sn.get("videoOwnerChannelTitle").getAsString()
                            : (sn.has("channelTitle") ? sn.get("channelTitle").getAsString() : "");
                    int year = 0;
                    if (sn.has("publishedAt")) {
                        year = parseYear(sn.get("publishedAt").getAsString().substring(0, 10));
                    }
                    String vid = it.getAsJsonObject("contentDetails").get("videoId").getAsString();
                    lines.add(csvLine(title, channel, year, 50, "youtube_" + vid + ".wav"));
                }
            }
            pageToken = page.has("nextPageToken") && !page.get("nextPageToken").isJsonNull()
                    ? page.get("nextPageToken").getAsString()
                    : null;
        } while (pageToken != null);
        return lines.toString();
    }

    // ——— SoundCloud ———

    public String soundcloudAuthorizeUrl(String clientId, String redirectUri, String state, String codeChallenge) {
        return "https://secure.soundcloud.com/authorize?"
                + "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8)
                + "&code_challenge_method=S256"
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    public synchronized void soundcloudFinishCode(String clientId, String clientSecret, String code, String redirectUri,
            String verifier) throws IOException, InterruptedException {
        String body = "grant_type=authorization_code"
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret)
                + "&redirect_uri=" + url(redirectUri)
                + "&code=" + url(code)
                + "&code_verifier=" + url(verifier);
        JsonObject res = httpForm("https://secure.soundcloud.com/oauth/token", body);
        tokens.soundcloud = new ServiceToken(
                res.get("access_token").getAsString(),
                res.has("refresh_token") && !res.get("refresh_token").isJsonNull() ? res.get("refresh_token").getAsString() : null,
                System.currentTimeMillis() + res.get("expires_in").getAsInt() * 1000L);
        save();
    }

    private synchronized String soundcloudAccess() throws IOException, InterruptedException {
        ServiceToken t = tokens.soundcloud;
        if (t == null || t.accessToken == null) {
            throw new IllegalStateException("SoundCloud not connected");
        }
        if (t.expiresAtMs > System.currentTimeMillis() + 30_000L) {
            return t.accessToken;
        }
        throw new IllegalStateException("SoundCloud token expired; reconnect");
    }

    public List<Map<String, Object>> soundcloudPlaylists() throws IOException, InterruptedException {
        String token = soundcloudAccess();
        String auth = "OAuth " + token;
        List<Map<String, Object>> out = new ArrayList<>();
        JsonElement root = httpGetRawAuth("https://api.soundcloud.com/me/playlists?linked_partitioning=1&page_size=50", auth);
        JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("collection");
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject p = el.getAsJsonObject();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(p.get("id").getAsLong()));
                row.put("name", p.get("title").getAsString());
                row.put("tracks", p.has("track_count") ? p.get("track_count").getAsInt() : 0);
                out.add(row);
            }
        }
        return out;
    }

    public String soundcloudPlaylistToCsv(String playlistId) throws IOException, InterruptedException {
        String token = soundcloudAccess();
        String auth = "OAuth " + token;
        StringJoiner lines = new StringJoiner("\n");
        String pageUrl = "https://api.soundcloud.com/playlists/" + playlistId
                + "/tracks?linked_partitioning=1&page_size=50";
        while (pageUrl != null) {
            JsonObject page = httpGetJsonAuth(pageUrl, auth);
            JsonArray coll = page.getAsJsonArray("collection");
            if (coll != null) {
                for (JsonElement el : coll) {
                    JsonObject tr = el.getAsJsonObject();
                    if (!tr.has("title")) {
                        continue;
                    }
                    String title = tr.get("title").getAsString();
                    String artist = "";
                    if (tr.has("user") && !tr.get("user").isJsonNull()) {
                        artist = tr.getAsJsonObject("user").get("username").getAsString();
                    }
                    int year = 0;
                    if (tr.has("release") && !tr.get("release").isJsonNull()) {
                        String r = tr.get("release").getAsString();
                        if (r.length() >= 4) {
                            year = parseYear(r.substring(0, 4));
                        }
                    }
                    int pop = tr.has("playback_count")
                            ? Math.min(99, (int) (Math.log10(tr.get("playback_count").getAsInt() + 1) * 20))
                            : 50;
                    String id = String.valueOf(tr.get("id").getAsLong());
                    lines.add(csvLine(title, artist, year, pop, "sc_" + id + ".wav"));
                }
            }
            pageUrl = null;
            if (page.has("next_href") && !page.get("next_href").isJsonNull()) {
                String next = page.get("next_href").getAsString();
                if (next.startsWith("http://")) {
                    next = "https://" + next.substring(7);
                }
                pageUrl = next;
            }
        }
        return lines.toString();
    }

    // ——— HTTP helpers ———

    private static JsonObject httpForm(String uri, String formBody) throws IOException, InterruptedException {
        java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        java.net.http.HttpResponse<String> res = c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static JsonObject httpGetJson(String uri, String bearer) throws IOException, InterruptedException {
        return httpGetJsonAuth(uri, "Bearer " + bearer);
    }

    private static JsonObject httpGetJsonAuth(String uri, String authorizationHeader) throws IOException, InterruptedException {
        java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", authorizationHeader)
                .GET()
                .build();
        java.net.http.HttpResponse<String> res = c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(Objects.requireNonNullElse(res.body(), "{}")).getAsJsonObject();
    }

    private static JsonElement httpGetRawAuth(String uri, String authorizationHeader) throws IOException, InterruptedException {
        java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", authorizationHeader)
                .GET()
                .build();
        java.net.http.HttpResponse<String> res = c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(Objects.requireNonNullElse(res.body(), "[]"));
    }

    private static String nextUrl(JsonObject page) {
        if (page.has("next") && !page.get("next").isJsonNull()) {
            return page.get("next").getAsString();
        }
        return null;
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int parseYear(String y) {
        try {
            if (y != null && y.length() >= 4) {
                return Integer.parseInt(y.substring(0, 4));
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private static String csvLine(String name, String artist, int year, int pop, String link) {
        return escapeCsv(name) + "," + escapeCsv(artist) + "," + year + "," + pop + "," + escapeCsv(link);
    }

    private static String escapeCsv(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r", " ").replace("\n", " ").replace("\"", "'");
        if (t.contains(",") || t.contains("\"")) {
            return "\"" + t.replace("\"", "\"\"") + "\"";
        }
        return t;
    }

    @SuppressWarnings("unused")
    private static final class TokenFile {
        ServiceToken spotify;
        ServiceToken youtube;
        ServiceToken soundcloud;
    }

    @SuppressWarnings("unused")
    private static final class ServiceToken {
        String accessToken;
        String refreshToken;
        long expiresAtMs;

        ServiceToken() {
        }

        ServiceToken(String accessToken, String refreshToken, long expiresAtMs) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
