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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * HTTP API and static UI for {@link MusicLibrary}. Mirrors actions from {@link Driver}.
 */
public final class WebApiServer {

    private static final int SHUFFLE_SEED = 2026;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MusicLibrary library = new MusicLibrary();
    private final Path dataRoot;
    private final Path publicRoot;

    private WebApiServer(Path dataRoot) {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.publicRoot = this.dataRoot.resolve("web/public").normalize();
    }

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(System.getProperty("music.library.root", ".")).normalize();
        int port = Integer.parseInt(System.getProperty("music.library.port", "7070"));
        new WebApiServer(root).start(port);
    }

    private void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        System.out.println("Music Library UI: http://localhost:" + port + "/");
        System.out.println("Data root: " + dataRoot);
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

    private boolean dispatchApi(HttpExchange ex, String method, String path) throws IOException {
        if ("/api/health".equals(path) && "GET".equals(method)) {
            sendJson(ex, 200, Map.of("ok", true));
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
