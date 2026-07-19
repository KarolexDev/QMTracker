package red.jackf.chesttracker.impl.qmsync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP layer for QMSync. See doc/qmsync-api.md for the contract the website implements.
 */
public class QMSyncHttp {
    public static final int PROTOCOL_VERSION = 1;
    private static final Gson GSON = new Gson();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public enum Result {
        SYNCED,
        ACCESS_DENIED,
        URL_NOT_FOUND,
        CONNECTION_FAILED
    }

    public record Identity(UUID playerUuid, String playerName, String serverId, String serverName) {
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("protocolVersion", PROTOCOL_VERSION);
            json.addProperty("playerUuid", playerUuid.toString());
            json.addProperty("playerName", playerName);
            json.addProperty("serverId", serverId);
            json.addProperty("serverName", serverName);
            return json;
        }
    }

    private QMSyncHttp() {}

    /**
     * Validates and normalizes a user-entered base URL; returns null if unusable.
     */
    public static URI parseBaseUrl(String raw) {
        try {
            URI uri = URI.create(raw.strip());
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")))
                return null;
            if (uri.getHost() == null) return null;
            return uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static CompletableFuture<Result> handshake(String baseUrl, Identity identity) {
        return post(baseUrl, "/api/handshake", identity.toJson());
    }

    public static CompletableFuture<Result> sync(String baseUrl, Identity identity, JsonElement data) {
        JsonObject payload = identity.toJson();
        payload.add("data", data);
        return post(baseUrl, "/api/sync", payload);
    }

    private static CompletableFuture<Result> post(String baseUrl, String endpoint, JsonObject body) {
        URI uri;
        try {
            String base = baseUrl.strip();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            uri = URI.create(base + endpoint);
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(Result.URL_NOT_FOUND);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(QMSyncHttp::classify)
                .exceptionally(QMSyncHttp::classifyError);
    }

    private static Result classify(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code == 404) return Result.URL_NOT_FOUND;
        if (code == 401 || code == 403) return Result.ACCESS_DENIED;
        if (code < 200 || code >= 300) return Result.CONNECTION_FAILED;

        try {
            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            if (json != null && json.has("status")) {
                String status = json.get("status").getAsString().replace(' ', '_').toUpperCase();
                if (status.equals("SYNCED")) return Result.SYNCED;
                if (status.equals("ACCESS_DENIED")) return Result.ACCESS_DENIED;
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException ignored) {
        }
        // 2xx without a recognisable body isn't a QMSync endpoint
        return Result.URL_NOT_FOUND;
    }

    private static Result classifyError(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) return Result.URL_NOT_FOUND;
            if (cause instanceof ConnectException || cause instanceof HttpTimeoutException)
                return Result.CONNECTION_FAILED;
        }
        return Result.CONNECTION_FAILED;
    }
}
