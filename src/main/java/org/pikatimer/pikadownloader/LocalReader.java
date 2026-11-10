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
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javax.net.ssl.SSLException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;

/**
 *
 * @author john
 */
public class LocalReader implements Reader {
    final Preferences prefs = PikaDownloaderPrefs.INSTANCE.getPreferences();

    // Every read seen this session, used to skip duplicates. Each new read
    // is also added to ReadLog (in arrival order) for the Read Review window.
    // Written by the websocket and rewind threads, read by the FX thread.
    final Set<Read> processedReads = ConcurrentHashMap.newKeySet();

    static final Logger logger = LoggerFactory.getLogger(LocalReader.class);

    // One shared thread for all of the reconnect attempts
    private static final ScheduledExecutorService RECONNECT = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("ws-reconnect");
        return thread;
    });
    
    private StringProperty readerIPProperty = new SimpleStringProperty();
    private StringProperty readerPortProperty = new SimpleStringProperty();

    // True for https:// and wss:// (e.g. a unit behind a TLS reverse proxy).
    // Discovered units are always plain http:// and ws://
    private final boolean secure;
    
    //private StringProperty readerIDProperty = new SimpleStringProperty();
    private Pane controlPane  = null;
    private LocalReaderCellController cellController = null;
    
    private BooleanProperty readingProperty = new SimpleBooleanProperty(false);
    private BooleanProperty connectedProperty = new SimpleBooleanProperty(false);
    private StringProperty statusLabel = new SimpleStringProperty();
    private DoubleProperty batteryProperty = new SimpleDoubleProperty(-2);

    // RFID readers on the unit that report they are not connected
    // (e.g. "Reader 1 not connected"), or "" if they are all fine
    private final StringProperty rfidReaderProblemProperty = new SimpleStringProperty("");

    private StringProperty readerNameProperty = new SimpleStringProperty();
    
    
    private StringProperty lastReadProperty = new SimpleStringProperty("<No Reads>");
    private IntegerProperty readCountProperty = new SimpleIntegerProperty(0);
    
    private IntegerProperty lastUpdatedProperty = new SimpleIntegerProperty(0);
    private volatile LocalDateTime lastUpdated = LocalDateTime.MIN;
    
    private StringProperty outputFileProperty = new SimpleStringProperty();
    private BooleanProperty outputToFileProperty = new SimpleBooleanProperty(false);
    
    private static final BlockingQueue<Read> outputProcessorQueue = OutputProcessor.INSTANCE.getReadQueue();
    
    private volatile Read lastRead = null;

    private BooleanProperty enabledProperty = new SimpleBooleanProperty(true);

    // Pref key for the output file name. Captured once (the unit ID from
    // discovery) because the STATUS messages may change the case of the name
    private final String outputPrefKey;

    // Read alert (see ReadAlert): this reader's tone and mute, saved in the
    // prefs under the same unit ID as the output file. The properties are for
    // the UI, the volatile copies are read by the websocket thread.
    private final String alertTonePrefKey;
    private final String alertMutePrefKey;
    private final IntegerProperty alertToneProperty = new SimpleIntegerProperty(ReadAlert.DEFAULT_HZ);
    private final BooleanProperty alertMutedProperty = new SimpleBooleanProperty(false);
    private volatile int alertTone = ReadAlert.DEFAULT_HZ;
    private volatile boolean alertMuted = false;

    // True while a connection attempt is in progress
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // Reader profiles (PikaReader 6684b90 and newer).
    // Older PikaReader versions do not send a profile in their STATUS messages,
    // so profileSupportedProperty stays false and the UI hides the profile.
    private final BooleanProperty profileSupportedProperty = new SimpleBooleanProperty(false);
    private final StringProperty profileIDProperty = new SimpleStringProperty("");
    private final StringProperty profileNameProperty = new SimpleStringProperty("");
    private final ObservableList<ReaderProfile> profiles = FXCollections.observableArrayList();
    private final AtomicBoolean profilesLoading = new AtomicBoolean(false);
    private volatile boolean profilesLoaded = false;

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    Thread pikaConnectionThread;
    private volatile EventWebSocketClient webSocketClient;

    Timeline timeline;

    public LocalReader(String reader_ip, String port, String reader_id){
        this(reader_ip, port, reader_id, false);
    }

    /**
     * @param reader_ip the unit's IP address or hostname
     * @param port the unit's web port
     * @param reader_id the unit ID (from discovery or /status), used for the pref keys
     * @param secure true to use https:// and wss://, false for http:// and ws://
     */
    public LocalReader(String reader_ip, String port, String reader_id, boolean secure){
        this.secure = secure;
        readerIPProperty.setValue(reader_ip);
        //readerIDProperty.setValue(reader_id);
        readerNameProperty.setValue(reader_id);
        readerPortProperty.setValue(port);
        outputPrefKey = "LOCAL-READER-OUTPUT-" + reader_id;

        // Read alert tone and mute (set up before connecting, reads can
        // arrive as soon as we are connected)
        alertTonePrefKey = "LOCAL-READER-ALERT-TONE-" + reader_id;
        alertMutePrefKey = "LOCAL-READER-ALERT-MUTE-" + reader_id;
        alertTone = ReadAlert.clampHz(prefs.getInt(alertTonePrefKey, ReadAlert.DEFAULT_HZ));
        alertMuted = prefs.getBoolean(alertMutePrefKey, false);
        alertToneProperty.set(alertTone);
        alertMutedProperty.set(alertMuted);
        alertToneProperty.addListener((observable, oldValue, newValue) -> {
            alertTone = ReadAlert.clampHz(newValue.intValue());
            prefs.putInt(alertTonePrefKey, alertTone);
        });
        alertMutedProperty.addListener((observable, oldValue, newValue) -> {
            alertMuted = newValue;
            prefs.putBoolean(alertMutePrefKey, newValue);
        });

        //Connect to the reader
        connect();

        outputFileProperty.setValue(prefs.get(outputPrefKey, ""));
        if(!outputFileProperty.isEmpty().get()) outputToFileProperty.set(true);


        outputFileProperty.addListener((observable, oldValue, newValue)  -> {
            logger.trace("Output File Update for {} -> {}", reader_id, newValue);
            prefs.put(outputPrefKey, newValue == null ? "" : newValue);
            // Close the old file
            OutputProcessor.INSTANCE.requestCloseAll();
        });

        // Close the file when the output is turned off
        outputToFileProperty.addListener((observable, oldValue, newValue) -> {
            if (!newValue) OutputProcessor.INSTANCE.requestCloseAll();
        });
    }
    
    @Override
    public Pane getControlPane(){
        if (controlPane == null) {
            
            try {
                
                //controlPane = fxmlLoader.load(getClass().getResource("localReaderCell.fxml").openStream());
                //FXMLLoader fxmlLoader = new FXMLLoader();
                FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("localReaderCell.fxml"));
                controlPane  = fxmlLoader.load();

                cellController = (LocalReaderCellController) fxmlLoader.getController();
                cellController.setReader(this);
            } catch (IOException ex) {
                logger.error("FXML issue:",ex);
            }
        }
        return controlPane;
    }
    
    // Must be called on the FX thread
    private void connect() {
        if (connectedProperty.get()) {
            return; // already connected
        }
        if (!connecting.compareAndSet(false, true)) {
            return; // a connection attempt is already in progress
        }

        if (timeline != null) timeline.stop();

        // lets periodically update stuff
        timeline = new Timeline(
            new KeyFrame(javafx.util.Duration.seconds(1), event -> {
                // This entire block runs safely on the JavaFX Application Thread
                if (lastRead != null) {
                    lastReadProperty.setValue(lastRead.chip() + " ->\n" + lastRead.dateTime());
                    readCountProperty.set(processedReads.size());
                }
                
                // update the last updated counter
                if (lastUpdated != null) {
                    lastUpdatedProperty.setValue(Duration.between(lastUpdated, LocalDateTime.now()).toSeconds());
                    logger.trace("Reader::setStatus: lastUpdated {} ago", lastUpdatedProperty.getValue());
                }
            })
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        LocalReader reader = this;
        Task pikaReaderConnection = new Task<Void>() {

            @Override
            public Void call() {

                try {
                    // Start listening for events....
                    String wsPikaURL = (secure ? "wss://" : "ws://") + uriHost(readerIPProperty.getValueSafe()) + ":" + readerPortProperty.getValueSafe() + "/events";
                    logger.debug("Connecting to wsPikaURL: {}", wsPikaURL);
                    EventWebSocketClient client = new EventWebSocketClient(wsPikaURL, reader);
                    webSocketClient = client;
                    client.setDaemon(true);
                    client.connectBlocking(10, TimeUnit.SECONDS);


                    Platform.runLater(() -> {
                        if (client.isOpen()) {
                            statusLabel.setValue("Connected to\n" + wsPikaURL);
                            connectedProperty.setValue(true);

                        }
                    });
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    logger.error("Reader connection error: {} {}",readerNameProperty.getValueSafe(),ex.getMessage(),ex);
                } finally {
                    connecting.set(false);
                }
                return null;
            }
        };
        pikaConnectionThread = new Thread(pikaReaderConnection);
        pikaConnectionThread.setName("Thread-PikaReader-" + readerIPProperty.getValue() );
        pikaConnectionThread.setDaemon(true);
        pikaConnectionThread.start();
    }
    
    private void processLine(String line) {
        JSONObject message;
        try {
            message = new JSONObject(line);
        } catch (JSONException ex) {
            logger.warn("Ignoring a malformed message from {}: {}", readerNameProperty.getValueSafe(), ex.getMessage());
            return;
        }
        String type = message.optString("type", "UNKNOWN");
        logger.trace("LocalReader::processLine: {}",type);
        try {
            switch (type) {
                case "READ" ->  processRead(message, false);
                case "STATUS" ->  processStatus(message);
                default ->  logger.debug("Unknown message from {}: {}", readerNameProperty.getValueSafe(), line);
            }
        } catch (RuntimeException ex) {
            logger.warn("Unable to process a {} message from {}: {}", type, readerNameProperty.getValueSafe(), line, ex);
        }
    }

    private void processStatus(JSONObject status) {
        logger.trace("Reader Status: {} -> {}",readerNameProperty.getValue(),status);

        // Pull everything out of the message here, only update the properties on the FX thread
        String unitID = status.optString("unitID", "");
        double battery = status.optDouble("battery", -1);
        boolean hasReading = status.has("reading");
        boolean reading = status.optBoolean("reading", false);
        JSONObject profile = status.optJSONObject("profile");
        String rfidProblem = rfidReaderProblem(status.optJSONArray("readers"));

        Platform.runLater(() -> {

            // Name
            if (!unitID.isBlank()) readerNameProperty.setValue(unitID);

            // Battery
            if (battery > 0) {
                logger.trace("Recieved battery level of {}",battery);
                batteryProperty.setValue(battery / 100.0);
            } else batteryProperty.setValue(-1);

            logger.trace("Battery Level: {} set to {}",readerNameProperty.getValue(),batteryProperty.getValue());

            // Reading
            if (hasReading) readingProperty.setValue(reading);

            // RFID Readers
            rfidReaderProblemProperty.setValue(rfidProblem);

            // Reader Profile
            // {"profile":{"id":"finish_mats","name":"Finish Mats"}}
            if (profile != null) {
                String id = profile.optString("id");
                String name = profile.optString("name", id);
                profileSupportedProperty.setValue(true);
                profileIDProperty.setValue(id);
                profileNameProperty.setValue(name);

                // If we don't know about this profile yet (first status or
                // the profile list changed on the unit), add a placeholder
                // so it can be displayed and then go fetch the full list.
                // Keep trying to fetch the list until it loads.
                boolean unknownProfile = !id.isBlank() && profiles.stream().noneMatch(p -> p.id().equals(id));
                if (unknownProfile) profiles.add(new ReaderProfile(id, name, ""));
                if (unknownProfile || !profilesLoaded) loadProfiles();
            } else {
                profileSupportedProperty.setValue(false);
            }
        });
        
        // Last Updated TS
        lastUpdated = LocalDateTime.now();

    }

    // {"readers":[{"id":1,"name":"Reader 1","connected":true,...},...]}
    // Returns "Reader 1 not connected" (or "Reader 1, Reader 2 not connected"),
    // or "" if every RFID reader is connected or the unit didn't list them.
    // A reader without a "connected" flag is assumed to be fine.
    static String rfidReaderProblem(JSONArray readers) {
        if (readers == null) return "";
        List<String> down = new ArrayList<>();
        for (int i = 0; i < readers.length(); i++) {
            JSONObject r = readers.optJSONObject(i);
            if (r == null || r.optBoolean("connected", true)) continue;
            down.add(r.optString("name", "Reader " + r.optString("id", "?")));
        }
        if (down.isEmpty()) return "";
        return String.join(", ", down) + " not connected";
    }

    // Returns true if the read was queued for output
    private boolean processRead(JSONObject tagRead, boolean rewind) {
        //  {"antenna":3,"chip":"23210006","rssi":-71,"reader":1,"tz":"-07:00","epochMilli":1703796324476,"type":"READ","timestamp":"2023-12-28 13:45:24.476"}
        logger.trace("Chip Read for {}: {}", readerNameProperty.getValueSafe(),tagRead);

        // Extract the data
        String chip = tagRead.optString("chip", "");
        Number epochMilliNumber = tagRead.optNumber("epochMilli");
        if (chip.isBlank() || epochMilliNumber == null) {
            logger.debug("Skipping a read without a chip or epochMilli from {}: {}", readerNameProperty.getValueSafe(), tagRead);
            return false;
        }
        Long epochMilli = epochMilliNumber.longValue();
        Integer reader = tagRead.optInt("reader");
        Integer port = tagRead.optInt("antenna");
        String dateTime = tagRead.optString("timestamp", "");
        String tz = tagRead.optString("tz", "");
        Double rssi = tagRead.optDouble("rssi");

        // create the read record
        // Reader reader, String chip, Long epochMilli, String dateTime, Integer antenna, Integer rfidReader
        Read read = new Read(this,chip,epochMilli, tz, dateTime, port, reader, rssi);

        if(! rewind && processedReads.contains(read)){
            return false;
        }

        // put() waits if the queue is full (e.g. a large rewind) rather than throwing
        try {
            outputProcessorQueue.put(read);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while queueing a read from {}", readerNameProperty.getValueSafe());
            return false;
        }

        lastRead = read;

        // If we don't already have it (possible with rewinds), add it.
        // New reads also go to the session read log (Read Review window).
        // New live reads (not rewinds) play the read alert.
        if (processedReads.add(read)) {
            ReadLog.INSTANCE.add(read);
            if (!rewind && !alertMuted) ReadAlert.INSTANCE.play(alertTone);
        }
        return true;
    }

//    @Override
//    public StringProperty getReaderIDProperty() {
//        return readerIDProperty;
//    }
    
    public BooleanProperty getReadingProperty() {
        return readingProperty;
    }
    
    @Override
    public StringProperty getReaderNameProperty(){
        return readerNameProperty;
    }
    
    public DoubleProperty getBatteryProperty(){
        return batteryProperty;
    }

    public StringProperty getRFIDReaderProblemProperty(){
        return rfidReaderProblemProperty;
    }

    public StringProperty getLastReadProperty(){
        return lastReadProperty;
    }
    public IntegerProperty getReadCountProperty(){
        return readCountProperty;
    }
    
    public IntegerProperty getLastUpdatedProperty(){
        return lastUpdatedProperty;
    }
    
    public StringProperty getReaderIPProperty(){
        return readerIPProperty;
    }
    
    public StringProperty getReaderPortProperty(){
        return readerPortProperty;
    }
    
    public void disable(){
        enabledProperty.set(false);
        // The client is created by the connection thread, so it may not exist yet
        EventWebSocketClient client = webSocketClient;
        if (client != null) client.close();
        if (timeline != null) timeline.stop();
        lastUpdatedProperty.set(99);
        connectedProperty.set(false);
    }
    
    public void enable(){
        connect();
        enabledProperty.set(true);
    }
    
    public BooleanProperty isEnabled(){
        return enabledProperty;
    }

    @Override
    public StringProperty getOutputFileProperty(){
        return outputFileProperty;
    }
    
    @Override
    public BooleanProperty getOutputToFileProperty(){
        return outputToFileProperty;
    }
    
    // This reader's read alert tone in Hz (ReadAlert.MIN_HZ - MAX_HZ)
    public IntegerProperty getAlertToneProperty(){
        return alertToneProperty;
    }

    // True to silence the read alert for just this reader
    public BooleanProperty getAlertMutedProperty(){
        return alertMutedProperty;
    }

    public void clearReadRecords(){
        readCountProperty.set(0);
        lastReadProperty.set("");
    }
    
    // Completes exceptionally (with the reader's error message) if the
    // reader can't be reached or rejects the command
    public CompletableFuture<HttpResponse<String>> startReader(){
        return sendCommand("/start");
    }

    public CompletableFuture<HttpResponse<String>> stopReader(){
        return sendCommand("/stop");
    }

    private CompletableFuture<HttpResponse<String>> sendCommand(String command) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseURL() + command))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        logger.debug("Sending {} to {}", command, readerNameProperty.getValueSafe());
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    throw new RuntimeException(responseError(response));
                }
                return response;
            });
    }

    private String baseURL() {
        return baseURL(secure, readerIPProperty.getValueSafe(), readerPortProperty.getValueSafe());
    }

    // The reader's web address, e.g. http://192.168.1.10:8080
    String getBaseURL() {
        return baseURL();
    }

    private static String baseURL(boolean secure, String host, String port) {
        return (secure ? "https://" : "http://") + uriHost(host) + ":" + port;
    }

    // IPv6 literals need [brackets] in a URL
    static String uriHost(String host) {
        if (host.contains(":") && !host.startsWith("[")) return "[" + host + "]";
        return host;
    }

    /**
     * Checks that a PikaReader answers at an address that was entered by hand.
     *
     * @param secure true for https://, false for http://
     * @param host the IP address or hostname
     * @param port the web port
     * @return completes with the unit ID from GET /status, or exceptionally
     * if nothing answers or the response is not from a PikaReader
     */
    static CompletableFuture<String> probeStatus(boolean secure, String host, int port) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseURL(secure, host, String.valueOf(port)) + "/status"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid address"));
        }
        logger.debug("Checking for a PikaReader at {}", request.uri());
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            // The request timeout may not cover the connect, so back it up
            .orTimeout(5, TimeUnit.SECONDS)
            .thenApply(response -> {
                // A PikaReader always answers /status
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    throw new RuntimeException("Not a PikaReader (" + responseError(response) + ")");
                }
                String unitID;
                try {
                    unitID = new JSONObject(response.body()).optString("unitID", "");
                } catch (JSONException ex) {
                    unitID = "";
                }
                if (unitID.isBlank()) throw new RuntimeException("Not a PikaReader (no unitID in /status)");
                return unitID;
            });
    }

    // The "error" from a PikaReader error response, or the HTTP status code
    private static String responseError(HttpResponse<String> response) {
        try {
            return new JSONObject(response.body()).optString("error", "HTTP " + response.statusCode());
        } catch (JSONException ex) {
            return "HTTP " + response.statusCode();
        }
    }

    /**
     * @param ex an exception from a CompletableFuture (possibly wrapped)
     * @return a message suitable for showing to the user
     */
    public static String errorMessage(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException) return "The reader did not respond in time";
        if (cause instanceof ConnectException) {
            if (cause.getCause() instanceof UnresolvedAddressException) return "Unknown host";
            return "Unable to connect to the reader";
        }
        if (cause instanceof TimeoutException) return "The reader did not respond in time";
        if (cause instanceof SSLException) return "Secure connection failed: " + cause.getMessage();
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    public BooleanProperty getProfileSupportedProperty(){
        return profileSupportedProperty;
    }

    public StringProperty getProfileIDProperty(){
        return profileIDProperty;
    }

    public StringProperty getProfileNameProperty(){
        return profileNameProperty;
    }

    public ObservableList<ReaderProfile> getProfiles(){
        return profiles;
    }

    // Fetch the list of available profiles from the reader
    // {"current":"finish_mats","profiles":[{"id":"finish_mats","name":"Finish Mats","description":"...","tagPopulation":32},...]}
    public void loadProfiles(){
        // The STATUS messages arrive once a second, so don't stack up requests
        if (!profilesLoading.compareAndSet(false, true)) return;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseURL() + "/profiles"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    logger.debug("Unable to load the profiles for {}: HTTP {}", readerNameProperty.getValueSafe(), response.statusCode());
                    return;
                }
                JSONArray list = new JSONObject(response.body()).getJSONArray("profiles");
                List<ReaderProfile> newProfiles = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject p = list.getJSONObject(i);
                    String id = p.getString("id");
                    newProfiles.add(new ReaderProfile(id, p.optString("name", id), p.optString("description", "")));
                }
                logger.debug("Loaded {} profiles for {}", newProfiles.size(), readerNameProperty.getValueSafe());
                Platform.runLater(() -> {
                    // Keep the current profile in the list even if the unit didn't return it
                    String currentID = profileIDProperty.getValueSafe();
                    if (!currentID.isBlank() && newProfiles.stream().noneMatch(p -> p.id().equals(currentID))) {
                        newProfiles.add(new ReaderProfile(currentID, profileNameProperty.getValueSafe(), ""));
                    }
                    profiles.setAll(newProfiles);
                });
                profilesLoaded = true;
            })
            .exceptionally(ex -> {
                logger.debug("Unable to load the profiles for {}: {}", readerNameProperty.getValueSafe(), ex.getMessage());
                return null;
            })
            .whenComplete((v, ex) -> profilesLoading.set(false));
    }

    // Change the reader profile. Only allowed by the reader when it is not reading.
    // Completes with the reader's response on success, or exceptionally with
    // the reader's error message (e.g. 409 if the reader is reading).
    public CompletableFuture<JSONObject> changeProfile(String id){
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseURL() + "/profile/"
                        + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")))
                .timeout(Duration.ofSeconds(45)) // The reader can take up to 30 seconds to apply the profile
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();

        logger.debug("Changing the profile for {} to {}", readerNameProperty.getValueSafe(), id);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                JSONObject body;
                try {
                    body = new JSONObject(response.body());
                } catch (Exception ex) {
                    body = new JSONObject();
                }

                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    throw new RuntimeException(body.optString("error", "HTTP " + response.statusCode()));
                }

                String newID = body.optString("id", id);
                String newName = body.optString("name", newID);
                logger.debug("Profile for {} changed to {}", readerNameProperty.getValueSafe(), newName);
                Platform.runLater(() -> {
                    profileIDProperty.setValue(newID);
                    profileNameProperty.setValue(newName);
                });
                return body;
            });
    }
    
    public void rewind() {
        if (!connectedProperty.get()) {
            Alert alert = new Alert(AlertType.INFORMATION);
            alert.setTitle("Rewind");
            alert.setHeaderText("Reader is not connected");
            alert.setContentText(readerNameProperty.getValueSafe() + " is not connected.\nRewind will be available once the reader reconnects.");
            alert.showAndWait();
            return;
        }

        // open a dialog box
        Dialog<RewindRange> dialog = new Dialog<>();
        dialog.setTitle("Rewind");
        dialog.setHeaderText("Rewind timing data...");
        ButtonType rewindButtonType = new ButtonType("Rewind", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(rewindButtonType, ButtonType.CANCEL);

        VBox rewindVBox = new VBox();
        rewindVBox.setStyle("-fx-font-size: 16px;");

        // start date / time
        HBox startHBox = new HBox();
        startHBox.setSpacing(5.0);
        Label startLabel = new Label("From:");
        startLabel.setMinWidth(40);
        DatePicker startDate = new DatePicker();
        TextField startTime = new TextField();
        startHBox.getChildren().addAll(startLabel, startDate, startTime);

        // end date / time
        HBox endHBox = new HBox();
        endHBox.setSpacing(5.0);
        Label endLabel = new Label("To:");
        endLabel.setMinWidth(40);
        DatePicker endDate = new DatePicker();
        TextField endTime = new TextField();
        endHBox.getChildren().addAll(endLabel, endDate, endTime);

        rewindVBox.getChildren().addAll(startHBox, endHBox);
        dialog.getDialogPane().setContent(rewindVBox);

        BooleanProperty startTimeOK = new SimpleBooleanProperty(false);
        BooleanProperty endTimeOK = new SimpleBooleanProperty(false);
        BooleanProperty allOK = new SimpleBooleanProperty(false);

        installTimeFilter(startTime, startTimeOK);
        installTimeFilter(endTime, endTimeOK);

        // Both times valid, both dates set, and from is not after to
        Runnable validate = () -> {
            boolean ok = startTimeOK.get() && endTimeOK.get()
                    && startDate.getValue() != null && endDate.getValue() != null;
            if (ok) {
                LocalDateTime from = toDateTime(startDate.getValue(), startTime.getText());
                LocalDateTime to = toDateTime(endDate.getValue(), endTime.getText());
                ok = !from.isAfter(to);
            }
            allOK.set(ok);
        };
        startTime.textProperty().addListener(o -> validate.run());
        endTime.textProperty().addListener(o -> validate.run());
        startDate.valueProperty().addListener(o -> validate.run());
        endDate.valueProperty().addListener(o -> validate.run());

        //Default to event date / 00:00 for the start time, event date 23:59:00 for the end time
        startDate.setValue(LocalDate.now());
        startTime.setText("00:00:00");
        endDate.setValue(LocalDate.now());
        endTime.setText("23:59:59");

        Node createButton = dialog.getDialogPane().lookupButton(rewindButtonType);
        createButton.disableProperty().bind(allOK.not());

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == rewindButtonType) {
                return new RewindRange(
                        toDateTime(startDate.getValue(), startTime.getText()),
                        toDateTime(endDate.getValue(), endTime.getText()));
            }
            return null;
        });

        Optional<RewindRange> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        RewindRange range = result.get();
        String readerName = readerNameProperty.getValueSafe();
        String from = range.from().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String to = range.to().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        logger.info("Rewind {} from {} to {}", readerName, from, to);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseURL() + "/rewind/" + from + "/" + to))
                .timeout(Duration.ofSeconds(120)) // A full day can be a lot of reads
                .GET()
                .build();

        // Fetch and process the reads in the background, then report back
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    throw new RuntimeException(responseError(response));
                }
                JSONArray rewinds = new JSONArray(response.body());
                int queued = 0;
                int skipped = 0;
                for (int i = 0; i < rewinds.length(); i++) {
                    try {
                        JSONObject obj = rewinds.optJSONObject(i);
                        if (obj != null && processRead(obj, true)) queued++;
                        else skipped++;
                    } catch (RuntimeException ex) {
                        skipped++;
                        logger.debug("Skipping a bad rewind read from {}: {}", readerName, ex.getMessage());
                    }
                }
                logger.info("Rewind {}: {} reads queued, {} skipped", readerName, queued, skipped);
                return new int[]{queued, skipped};
            })
            .whenComplete((counts, ex) -> Platform.runLater(() -> {
                if (ex != null) {
                    logger.error("Rewind of {} failed: {}", readerName, errorMessage(ex));
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Rewind");
                    alert.setHeaderText("Unable to rewind " + readerName);
                    alert.setContentText(errorMessage(ex));
                    alert.show();
                } else {
                    Alert alert = new Alert(AlertType.INFORMATION);
                    alert.setTitle("Rewind");
                    alert.setHeaderText("Rewind of " + readerName + " complete");
                    String msg = "Rewound " + counts[0] + " reads.";
                    if (counts[1] > 0) msg += "\n" + counts[1] + " reads could not be read and were skipped.";
                    alert.setContentText(msg);
                    alert.show();
                }
            }));
    }

    // Date + time of day (a duration from midnight, so 24:00:00 rolls over to the next day)
    private static LocalDateTime toDateTime(LocalDate date, String time) {
        return date.atStartOfDay().plus(DurationParser.parse(time));
    }

    // Only allow time-like input (HH:MM:SS.sss) in the field, and track if it is a valid time
    private static void installTimeFilter(TextField timeField, BooleanProperty timeOK) {
        timeField.textProperty().addListener((observable, oldValue, newValue) -> {
            timeOK.setValue(DurationParser.parsable(newValue));
            if (newValue.isEmpty() || newValue.matches("^[0-9]*(:?([0-5]?([0-9]?(:([0-5]?([0-9]?)?)?)?)?)?)?")) {
                logger.trace("Possiblely good time (newValue: {})", newValue);
            } else {
                Platform.runLater(() -> {
                    int c = timeField.getCaretPosition();
                    if (oldValue.length() > newValue.length()) {
                        c++;
                    } else {
                        c--;
                    }
                    timeField.setText(oldValue);
                    timeField.positionCaret(c);
                });
                logger.trace("Bad time (newValue: {})", newValue);
            }
        });
    }

    private class EventWebSocketClient extends WebSocketClient {
        private Integer messageCounter = 0;
        LocalReader reader;

        public EventWebSocketClient(URI serverURI) {
            super(serverURI);
        }

        EventWebSocketClient(String ws, LocalReader reader) throws URISyntaxException {
            super(new URI(ws));
            this.reader = reader;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            logger.debug("Connection to {} opened", reader.getReaderNameProperty().getValueSafe());
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            logger.warn("Reader {} closed with exit code {}: {}",reader.getReaderNameProperty().getValueSafe(), code, reason);

            if (enabledProperty.get()) {
                // Reconnect in 5 seconds. The shared scheduler just waits,
                // the actual work is done on the FX thread (UI-bound properties).
                RECONNECT.schedule(() -> Platform.runLater(() -> {
                    if (!enabledProperty.get()) return; // Disconnected in the mean time
                    logger.debug("Attempting to reconnect to {}", reader.getReaderNameProperty().getValueSafe());
                    lastUpdatedProperty.set(99);
                    connectedProperty.set(false);
                    reader.connect();
                }), 5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void onMessage(String message) {
            logger.trace("Received message #{}: {} Thread: {}", messageCounter++, message, Thread.currentThread().getName());
            reader.processLine(message);
        }

        @Override
        public void onMessage(ByteBuffer message) {
            logger.debug("received ByteBuffer");
        }

        @Override
        public void onError(Exception ex) {
            logger.warn("Websocket error for {}: {}", reader.getReaderNameProperty().getValueSafe(), ex.toString());
        }

    }

    private record RewindRange(LocalDateTime from, LocalDateTime to) {}
}
