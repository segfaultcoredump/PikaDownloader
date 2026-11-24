/*
 * Copyright (C) 2026 john garner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.pikatimer.pikadownloader;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PikaRelay server (see ../PikaRelay/docs/API.md) and the client key used
 * to talk to it. Immutable: each RemoteReader keeps the relay it was added
 * with.
 *
 * The blocking calls are for the RemoteReader polling (virtual) threads.
 * Every call throws (or completes exceptionally) with a RuntimeException
 * holding a message for the user when the relay answers with an error.
 * PikaRelay.errorMessage() turns any of them into a message for the user.
 *
 * @param baseURL where the relay is installed, without a trailing slash,
 * e.g. https://relay.example.com or https://example.com/relay/index.php
 * @param key the API key, or "" for a relay in insecure mode
 * @param secret the API secret, or ""
 */
public record PikaRelay(String baseURL, String key, String secret) {

    private static final Logger logger = LoggerFactory.getLogger(PikaRelay.class);

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public PikaRelay {
        baseURL = normalizeURL(baseURL);
        key = key == null ? "" : key.trim();
        secret = secret == null ? "" : secret.trim();
    }

    /**
     * @param url the relay address as typed
     * @return trimmed, without trailing slashes
     */
    static String normalizeURL(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /**
     * @param url the relay address as typed
     * @return why it can't be used, or null if it looks OK
     */
    static String urlProblem(String url) {
        String u = normalizeURL(url);
        if (u.isEmpty()) return "Enter the relay address";
        String lower = u.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "The address must start with https:// (or http://)";
        }
        try {
            URI uri = new URI(u);
            if (uri.getHost() == null || uri.getHost().isBlank()) return "The address has no host name";
            if (uri.getQuery() != null || uri.getFragment() != null) return "Enter just the relay address";
        } catch (Exception ex) {
            return "Not a valid address";
        }
        return null;
    }

    /** True when the address is usable (see urlProblem) */
    static boolean isValidURL(String url) {
        return urlProblem(url) == null;
    }

    /** True for a plain http:// relay (the key and the reads are not encrypted) */
    boolean insecure() {
        return baseURL.toLowerCase().startsWith("http://");
    }

    /** @return the relay's host name, for display */
    String host() {
        try {
            String host = new URI(baseURL).getHost();
            return host == null ? baseURL : host;
        } catch (Exception ex) {
            return baseURL;
        }
    }

    // The same relay server (whatever the key). Used to spot readers that
    // are already added.
    boolean sameRelay(PikaRelay other) {
        return other != null && baseURL.equalsIgnoreCase(other.baseURL);
    }

    @Override
    public String toString() {
        // Never print the secret
        return baseURL + (key.isEmpty() ? "" : " (key " + key + ")");
    }

    /** GET /readerList: {"serverTime":..,"readers":[{id,name,updated,reading,battery,lastRead}]} */
    JSONObject readerList() throws IOException, InterruptedException {
        return get("/readerList");
    }

    /** GET /readerStatus/{id}: {"readerId","serverTime","updated","lastRead","status":{...}} */
    JSONObject readerStatus(String id) throws IOException, InterruptedException {
        return get("/readerStatus/" + segment(id));
    }

    /** GET /getReads/{id}/{since}: reads received after since. {"serverTime","more","reads"} */
    JSONObject readsSince(String id, long since) throws IOException, InterruptedException {
        return get("/getReads/" + segment(id) + "/" + since);
    }

    /** GET /getReads/{id}/{from}/{to}: reads with a read time in [from, to). {"serverTime","more","next","reads"} */
    JSONObject readsBetween(String id, long from, long to) throws IOException, InterruptedException {
        return get("/getReads/" + segment(id) + "/" + from + "/" + to);
    }

    /**
     * Checks the address and key with GET /readerList.
     *
     * @return completes with the reader list, or exceptionally
     */
    CompletableFuture<JSONObject> probe() {
        HttpRequest request;
        try {
            request = request("/readerList").timeout(Duration.ofSeconds(5)).GET().build();
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a valid address"));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(8, TimeUnit.SECONDS) // the request timeout may not cover the connect
                .thenApply(PikaRelay::parse);
    }

    /**
     * POST /commands/{id}, e.g. {"command":"start"}.
     *
     * @return completes with {"id","created","expires"} once the relay has
     * queued the command (the reader picks it up on its next poll)
     */
    CompletableFuture<JSONObject> postCommand(String id, JSONObject command) {
        HttpRequest request = request("/commands/" + segment(id))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
                .build();
        logger.debug("Sending {} to {} via {}", command, id, baseURL);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(PikaRelay::parse);
    }

    private JSONObject get(String path) throws IOException, InterruptedException {
        HttpRequest request = request(path).timeout(REQUEST_TIMEOUT).GET().build();
        logger.trace("GET {}", request.uri());
        return parse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseURL + path))
                .header("Accept", "application/json");
        // Only in secure mode. A relay in insecure mode ignores them anyway.
        if (!key.isEmpty()) {
            builder.header("X-Pika-Key", key);
            builder.header("X-Pika-Secret", secret);
        }
        return builder;
    }

    // Reader ids are [A-Za-z0-9._-], but encode them anyway
    private static String segment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JSONObject parse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            throw new RuntimeException(relayError(response));
        }
        try {
            return new JSONObject(response.body());
        } catch (JSONException ex) {
            throw new RuntimeException("Not a PikaRelay server (unexpected response)");
        }
    }

    // A message for the user from a relay error response
    private static String relayError(HttpResponse<String> response) {
        int status = response.statusCode();
        String error = null;
        try {
            error = new JSONObject(response.body()).optString("error", null);
        } catch (JSONException ex) {
            // not JSON: probably not a PikaRelay at all (or a proxy error page)
        }
        return switch (status) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> error != null ? error : "Not found (check the relay address)";
            case 503 -> "Relay unavailable" + (error != null ? ": " + error : "");
            default -> error != null ? error : "HTTP " + status;
        };
    }

    /**
     * @param ex an exception from a relay call (possibly wrapped)
     * @return a message suitable for showing to the user
     */
    static String errorMessage(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) return "The relay did not respond in time";
        if (cause instanceof ConnectException) {
            if (cause.getCause() instanceof UnresolvedAddressException) return "Unknown host";
            return "Unable to connect to the relay";
        }
        return LocalReader.errorMessage(cause);
    }

    // The messages for a bad key or secret, and for a key without the client
    // role. RemoteReader treats these as errors, not warnings.
    static final String UNAUTHORIZED = "Invalid key or secret";
    static final String FORBIDDEN = "This key is not a client key";

    /** True if the message is about the credentials (fixing it needs the user) */
    static boolean isAuthProblem(String message) {
        return UNAUTHORIZED.equals(message) || FORBIDDEN.equals(message);
    }
}
