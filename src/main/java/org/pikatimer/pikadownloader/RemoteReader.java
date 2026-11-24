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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
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
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PikaReader seen through a PikaRelay server (see PikaRelay and
 * ../PikaRelay/docs/API.md). Works like LocalReader, except that it polls
 * the relay instead of listening on a websocket:
 * - the reader's status every 5 seconds (GET /readerStatus/{id})
 * - new reads every 2 seconds (GET /getReads/{id}/{since}), starting with
 *   the reads the relay received since local midnight
 *
 * Commands (start, rewind, forceSetProfile) are queued on the relay, and the
 * reader picks them up within about 5 seconds. There is no stop.
 *
 * @author john
 */
public class RemoteReader implements Reader {

    static final Logger logger = LoggerFactory.getLogger(RemoteReader.class);

    final Preferences prefs = PikaDownloaderPrefs.INSTANCE.getPreferences();

    private static final BlockingQueue<Read> outputProcessorQueue = OutputProcessor.INSTANCE.getReadQueue();

    private static final long READ_POLL_MS = 2000;
    private static final long STATUS_POLL_MS = 5000;
    private static final long ERROR_BACKOFF_MS = 5000;

    // Reads older than this (by the relay's clock) don't play the read alert:
    // catch-up after an outage, and the reads a rewind from the reader uploads
    private static final long ALERT_MAX_AGE_MS = 60_000;
    private static final long NO_ALERT = Long.MAX_VALUE;

    /** lastSeenProperty before the relay has ever had a status from the reader */
    static final int NEVER_SEEN = Integer.MAX_VALUE;
    /** lastSeenProperty before we have asked the relay (just added) */
    static final int UNKNOWN = -1;

    private final PikaRelay relay;
    private final String readerId;

    // Every read seen this session, used to skip duplicates (the relay's
    // pages may overlap). Each new read also goes to ReadLog.
    final Set<Read> processedReads = ConcurrentHashMap.newKeySet();
    private volatile Read lastRead = null;

    private Pane controlPane = null;

    private final StringProperty readerNameProperty = new SimpleStringProperty();
    private final BooleanProperty readingProperty = new SimpleBooleanProperty(false);
    private final DoubleProperty batteryProperty = new SimpleDoubleProperty(-2);
    private final StringProperty rfidReaderProblemProperty = new SimpleStringProperty("");
    private final StringProperty lastReadProperty = new SimpleStringProperty("<No Reads>");
    private final IntegerProperty readCountProperty = new SimpleIntegerProperty(0);
    private final BooleanProperty enabledProperty = new SimpleBooleanProperty(true);

    // Seconds since the reader's last status reached the relay (by the
    // relay's clock), NEVER_SEEN, or UNKNOWN until the first status poll.
    // Keeps counting while the relay itself can't be reached.
    private final IntegerProperty lastSeenProperty = new SimpleIntegerProperty(UNKNOWN);
    // The last error talking to the relay, "" when the last poll worked
    private final StringProperty relayProblemProperty = new SimpleStringProperty("");

    // serverTime - updated from the last status poll (-1: never seen), and
    // when (System.nanoTime) we got it. Written by the poll thread.
    private volatile long statusAgeMs = -1;
    private volatile long statusAtNanos = 0;
    private volatile boolean statusPolled = false;

    // The reader's timezone (STATUS "timezone"), for rewind ranges
    private volatile ZoneId timezone = ZoneId.systemDefault();

    // Profiles: the current one is in every STATUS, the list in newer
    // PikaReader versions ("profiles"). See LocalReader.
    private final BooleanProperty profileSupportedProperty = new SimpleBooleanProperty(false);
    private final BooleanProperty profileListProperty = new SimpleBooleanProperty(false);
    private final StringProperty profileIDProperty = new SimpleStringProperty("");
    private final StringProperty profileNameProperty = new SimpleStringProperty("");
    private final ObservableList<ReaderProfile> profiles = FXCollections.observableArrayList();

    private final StringProperty outputFileProperty = new SimpleStringProperty();
    private final BooleanProperty outputToFileProperty = new SimpleBooleanProperty(false);

    // Read alert, as in LocalReader
    private final IntegerProperty alertToneProperty = new SimpleIntegerProperty(ReadAlert.DEFAULT_HZ);
    private final BooleanProperty alertMutedProperty = new SimpleBooleanProperty(false);
    private volatile int alertTone = ReadAlert.DEFAULT_HZ;
    private volatile boolean alertMuted = false;

    // The polling thread. A thread stops once this no longer points to it.
    private volatile Thread pollThread = null;
    // The live poll cursor: the relay's receive time (epoch ms)
    private volatile long since;
    // False until the first poll has caught up (no read alerts until then)
    private volatile boolean caughtUp = false;

    private Timeline timeline;

    /**
     * @param relay the relay (with the key) the reader is on
     * @param readerId the relay's reader id (the PikaReader unitID, case-sensitive)
     * @param name the name to show until the first status arrives
     */
    public RemoteReader(PikaRelay relay, String readerId, String name) {
        this.relay = relay;
        this.readerId = readerId;
        readerNameProperty.set(name == null || name.isBlank() ? readerId : name);

        // Start with the reads the relay received today, so re-adding a
        // reader after a restart picks up what was missed
        since = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        String outputPrefKey = "REMOTE-READER-OUTPUT-" + readerId;
        String alertTonePrefKey = "REMOTE-READER-ALERT-TONE-" + readerId;
        String alertMutePrefKey = "REMOTE-READER-ALERT-MUTE-" + readerId;

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

        outputFileProperty.setValue(prefs.get(outputPrefKey, ""));
        if (!outputFileProperty.isEmpty().get()) outputToFileProperty.set(true);
        outputFileProperty.addListener((observable, oldValue, newValue) -> {
            logger.trace("Output File Update for {} -> {}", readerId, newValue);
            prefs.put(outputPrefKey, newValue == null ? "" : newValue);
            OutputProcessor.INSTANCE.requestCloseAll(); // Close the old file
        });
        outputToFileProperty.addListener((observable, oldValue, newValue) -> {
            if (!newValue) OutputProcessor.INSTANCE.requestCloseAll();
        });

        start();
    }

    @Override
    public Pane getControlPane() {
        if (controlPane == null) {
            try {
                FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("remoteReaderCell.fxml"));
                controlPane = fxmlLoader.load();
                RemoteReaderCellController cellController = fxmlLoader.getController();
                cellController.setReader(this);
            } catch (IOException ex) {
                logger.error("FXML issue:", ex);
            }
        }
        return controlPane;
    }

    // Must be called on the FX thread
    private void start() {
        if (timeline != null) timeline.stop();
        timeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> refresh()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        refresh();

        Thread thread = Thread.ofVirtual().name("relay-" + readerId).unstarted(this::pollLoop);
        pollThread = thread;
        thread.start();
    }

    // Once a second on the FX thread
    private void refresh() {
        Read r = lastRead;
        if (r != null) {
            lastReadProperty.setValue(r.chip() + " ->\n" + r.dateTime());
            readCountProperty.set(processedReads.size());
        }
        long age = statusAgeMs;
        if (!statusPolled) {
            lastSeenProperty.set(UNKNOWN);
        } else if (age < 0) {
            lastSeenProperty.set(NEVER_SEEN);
        } else {
            long ms = age + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - statusAtNanos);
            lastSeenProperty.set((int) Math.min(ms / 1000, NEVER_SEEN - 1L));
        }
    }

    private void pollLoop() {
        Thread self = Thread.currentThread();
        logger.debug("Polling {} on {}", readerId, relay);
        long nextStatus = 0;
        String lastProblem = "";
        String postedProblem = "";
        while (pollThread == self) {
            long delay = READ_POLL_MS;
            try {
                if (System.nanoTime() - nextStatus >= 0) {
                    pollStatus();
                    nextStatus = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STATUS_POLL_MS);
                }
                pollReads(self);
                if (!lastProblem.isEmpty()) logger.info("Relay connection for {} is OK again", readerId);
                lastProblem = "";
            } catch (InterruptedException ex) {
                break;
            } catch (Exception ex) {
                String problem = PikaRelay.errorMessage(ex);
                if (!problem.equals(lastProblem)) {
                    logger.warn("Unable to poll {} on {}: {}", readerId, relay, problem);
                } else {
                    logger.trace("Unable to poll {} on {}: {}", readerId, relay, problem);
                }
                lastProblem = problem;
                delay = ERROR_BACKOFF_MS;
                // Try the status again on the next round too
                nextStatus = System.nanoTime();
            }
            if (!lastProblem.equals(postedProblem)) {
                String problem = lastProblem;
                postedProblem = problem;
                Platform.runLater(() -> {
                    if (pollThread == self) relayProblemProperty.set(problem);
                });
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                break;
            }
        }
        logger.debug("Stopped polling {}", readerId);
    }

    // {"readerId":"pr4-01","serverTime":..,"updated":..,"lastRead":..,"status":{...PikaReader STATUS...}}
    private void pollStatus() throws IOException, InterruptedException {
        JSONObject response = relay.readerStatus(readerId);
        long serverTime = response.getLong("serverTime");
        if (response.isNull("updated")) {
            statusAgeMs = -1;
        } else {
            statusAgeMs = Math.max(0, serverTime - response.getLong("updated"));
            statusAtNanos = System.nanoTime();
        }
        statusPolled = true;
        JSONObject status = response.optJSONObject("status");
        if (status != null) processStatus(status);
    }

    private void processStatus(JSONObject status) {
        logger.trace("Reader Status: {} -> {}", readerId, status);

        String unitID = status.optString("unitID", "");
        double battery = status.optDouble("battery", -1);
        boolean hasReading = status.has("reading");
        boolean reading = status.optBoolean("reading", false);
        JSONObject profile = status.optJSONObject("profile");
        List<ReaderProfile> profileList = profileList(status.optJSONArray("profiles"));
        String rfidProblem = LocalReader.rfidReaderProblem(status.optJSONArray("readers"));

        String zone = status.optString("timezone", "");
        if (!zone.isBlank()) {
            try {
                timezone = ZoneId.of(zone);
            } catch (DateTimeException ex) {
                logger.debug("Unknown timezone {} from {}", zone, readerId);
            }
        }

        Platform.runLater(() -> {
            if (!unitID.isBlank()) readerNameProperty.setValue(unitID);
            batteryProperty.setValue(battery > 0 ? battery / 100.0 : -1);
            if (hasReading) readingProperty.setValue(reading);
            rfidReaderProblemProperty.setValue(rfidProblem);

            // {"profile":{"id":"finish_mats","name":"Finish Mats"},"profiles":[{"id":..,"name":..},...]}
            if (profile != null) {
                String id = profile.optString("id");
                String name = profile.optString("name", id);
                profileSupportedProperty.setValue(true);
                profileIDProperty.setValue(id);
                profileNameProperty.setValue(name);

                // Older PikaReader versions don't send the list: just the current profile
                profileListProperty.set(profileList != null);
                List<ReaderProfile> next = profileList != null ? new ArrayList<>(profileList) : new ArrayList<>();
                if (!id.isBlank() && next.stream().noneMatch(p -> p.id().equals(id))) {
                    next.add(new ReaderProfile(id, name, ""));
                }
                if (!next.equals(profiles)) profiles.setAll(next);
            } else {
                profileSupportedProperty.setValue(false);
            }
        });
    }

    // [{"id":"finish_mats","name":"Finish Mats"},...] or null if not sent
    private static List<ReaderProfile> profileList(JSONArray list) {
        if (list == null) return null;
        List<ReaderProfile> result = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) continue;
            String id = p.optString("id", "");
            if (id.isBlank()) continue;
            result.add(new ReaderProfile(id, p.optString("name", id), ""));
        }
        return result;
    }

    // {"serverTime":..,"more":false,"reads":[...]}. Send serverTime as the
    // next since, and call again right away while "more" is true.
    private void pollReads(Thread self) throws IOException, InterruptedException {
        boolean more;
        do {
            JSONObject page = relay.readsSince(readerId, since);
            long serverTime = page.getLong("serverTime");
            JSONArray reads = page.optJSONArray("reads");
            more = page.optBoolean("more", false);
            // No alerts while catching up, or for old reads
            long alertCutoff = caughtUp && !more ? serverTime - ALERT_MAX_AGE_MS : NO_ALERT;
            if (reads != null) {
                logger.trace("{} reads from {} (more: {})", reads.length(), readerId, more);
                for (int i = 0; i < reads.length(); i++) {
                    try {
                        JSONObject obj = reads.optJSONObject(i);
                        if (obj != null) processRead(obj, false, alertCutoff);
                    } catch (RuntimeException ex) {
                        logger.debug("Skipping a bad read from {}: {}", readerId, ex.getMessage());
                    }
                }
            }
            // Only move on once the page is done (an interrupted page is
            // fetched again, and the duplicates are dropped)
            if (Thread.interrupted()) throw new InterruptedException();
            since = serverTime;
        } while (more && pollThread == self);
        caughtUp = true;
    }

    /**
     * @param rewind true to queue the read for output even if it was seen before
     * @param alertCutoff play the read alert for a new read at or after this
     * time (epoch ms), NO_ALERT for none
     * @return true if the read was queued for output
     */
    private boolean processRead(JSONObject tagRead, boolean rewind, long alertCutoff) throws InterruptedException {
        Read read = Read.fromJSON(this, tagRead);
        if (read == null) {
            logger.debug("Skipping a read without a chip or epochMilli from {}: {}", readerId, tagRead);
            return false;
        }

        boolean isNew = processedReads.add(read);
        if (!isNew && !rewind) return false;

        // put() waits if the queue is full (e.g. a large rewind) rather than throwing
        try {
            outputProcessorQueue.put(read);
        } catch (InterruptedException ex) {
            if (isNew) processedReads.remove(read); // so it is picked up again
            throw ex;
        }

        // The newest read, by read time
        Read last = lastRead;
        if (last == null || read.epochMilli() >= last.epochMilli()) lastRead = read;

        if (isNew) {
            ReadLog.INSTANCE.add(read);
            if (!alertMuted && read.epochMilli() >= alertCutoff) ReadAlert.INSTANCE.play(alertTone);
        }
        return true;
    }

    public void disable() {
        enabledProperty.set(false);
        Thread thread = pollThread;
        pollThread = null;
        if (thread != null) thread.interrupt();
        if (timeline != null) timeline.stop();
        relayProblemProperty.set("");
    }

    public void enable() {
        if (enabledProperty.get() && pollThread != null) return;
        enabledProperty.set(true);
        start();
    }

    public BooleanProperty isEnabled() {
        return enabledProperty;
    }

    /** The reader is reachable through the relay: its last status is under this many seconds old */
    public boolean seenWithin(int seconds) {
        int seen = lastSeenProperty.get();
        return seen != UNKNOWN && seen <= seconds;
    }

    // Starts the reader. Completes once the relay has queued the command.
    public CompletableFuture<JSONObject> startReader() {
        return relay.postCommand(readerId, new JSONObject().put("command", "start"));
    }

    // Changes the profile, stopping and restarting the reader if it is
    // reading. Completes once the relay has queued the command; the next
    // status shows the new profile.
    public CompletableFuture<JSONObject> changeProfile(String id) {
        return relay.postCommand(readerId, new JSONObject().put("command", "forceSetProfile").put("profile", id));
    }

    // Must be called on the FX thread
    public void rewind() {
        ZoneId zone = timezone;
        String readerName = readerNameProperty.getValueSafe();

        ToggleGroup source = new ToggleGroup();
        RadioButton fromRelay = new RadioButton("From the relay (the reads it already has)");
        RadioButton fromReader = new RadioButton("From the reader (it sends its reads to the relay again)");
        fromRelay.setToggleGroup(source);
        fromReader.setToggleGroup(source);
        fromRelay.setSelected(true);
        Label sourceNote = new Label();
        sourceNote.setWrapText(true);
        sourceNote.setMaxWidth(480);
        sourceNote.setStyle("-fx-font-size: 13px;");
        sourceNote.textProperty().bind(javafx.beans.binding.Bindings.when(fromReader.selectedProperty())
                .then("Use this for reads that never reached the relay. Only the reads the relay is missing come back, over the next few polls.")
                .otherwise("Writes the reads the relay already has to the output file again."));
        Label zoneNote = new Label("Times are in the reader's timezone (" + zone.getId() + ")");
        zoneNote.setStyle("-fx-font-size: 13px;");
        VBox extra = new VBox(4, new Label("Rewind:"), fromRelay, fromReader, sourceNote, zoneNote);
        extra.setPadding(new Insets(8, 0, 0, 0));

        Optional<RewindDialog.RewindRange> result = RewindDialog.show(extra);
        if (result.isEmpty()) return;

        RewindDialog.RewindRange range = result.get();
        long fromMs = range.from().atZone(zone).toInstant().toEpochMilli();
        // The To time is inclusive, the relay's range is not
        long toMs = range.to().atZone(zone).toInstant().toEpochMilli() + 1;

        if (fromReader.isSelected()) {
            rewindFromReader(readerName, fromMs, toMs);
        } else {
            rewindFromRelay(readerName, fromMs, toMs);
        }
    }

    private void rewindFromRelay(String readerName, long fromMs, long toMs) {
        logger.info("Rewind {} from the relay: {} to {}", readerName, Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs));
        Thread.ofVirtual().name("relay-rewind-" + readerId).start(() -> {
            int queued = 0;
            int skipped = 0;
            try {
                long cursor = fromMs;
                while (true) {
                    JSONObject page = relay.readsBetween(readerId, cursor, toMs);
                    JSONArray reads = page.optJSONArray("reads");
                    if (reads != null) {
                        for (int i = 0; i < reads.length(); i++) {
                            try {
                                JSONObject obj = reads.optJSONObject(i);
                                if (obj != null && processRead(obj, true, NO_ALERT)) queued++;
                                else skipped++;
                            } catch (RuntimeException ex) {
                                skipped++;
                                logger.debug("Skipping a bad rewind read from {}: {}", readerName, ex.getMessage());
                            }
                        }
                    }
                    if (!page.optBoolean("more", false) || page.isNull("next")) break;
                    long next = page.getLong("next");
                    if (next <= cursor) break; // never loop on the same page
                    cursor = next;
                }
                logger.info("Rewind {}: {} reads queued, {} skipped", readerName, queued, skipped);
                int q = queued;
                int s = skipped;
                Platform.runLater(() -> {
                    Alert alert = new Alert(AlertType.INFORMATION);
                    alert.setTitle("Rewind");
                    alert.setHeaderText("Rewind of " + readerName + " complete");
                    String msg = "Rewound " + q + " reads from the relay.";
                    if (s > 0) msg += "\n" + s + " reads could not be read and were skipped.";
                    alert.setContentText(msg);
                    alert.show();
                });
            } catch (Exception ex) {
                String msg = PikaRelay.errorMessage(ex);
                logger.error("Rewind of {} failed: {}", readerName, msg);
                int q = queued;
                Platform.runLater(() -> {
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Rewind");
                    alert.setHeaderText("Unable to rewind " + readerName);
                    alert.setContentText(msg + (q > 0 ? "\n" + q + " reads were rewound before the error." : ""));
                    alert.show();
                });
            }
        });
    }

    private void rewindFromReader(String readerName, long fromMs, long toMs) {
        logger.info("Rewind {} from the reader: {} to {}", readerName, Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs));
        boolean offline = !seenWithin(RemoteReaderCellController.WARNING_SECONDS);
        JSONObject command = new JSONObject().put("command", "rewind").put("from", fromMs).put("to", toMs);
        relay.postCommand(readerId, command).whenComplete((response, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                String msg = PikaRelay.errorMessage(ex);
                logger.error("Rewind request for {} failed: {}", readerName, msg);
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Rewind");
                alert.setHeaderText("Unable to rewind " + readerName);
                alert.setContentText(msg);
                alert.show();
                return;
            }
            String expires = "";
            if (response.has("expires") && !response.isNull("expires")) {
                expires = " (the request expires at "
                        + Instant.ofEpochMilli(response.getLong("expires")).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm")) + ")";
            }
            Alert alert = new Alert(offline ? AlertType.WARNING : AlertType.INFORMATION);
            alert.setTitle("Rewind");
            alert.setHeaderText("Rewind of " + readerName + " requested");
            String msg = readerName + " will pick up the request within about 5 seconds" + expires
                    + ".\nAny reads the relay is missing will show up here as they arrive.";
            if (offline) {
                msg += "\n\n" + readerName + " has not checked in with the relay recently. "
                        + "It will only rewind if it checks in before the request expires.";
            }
            alert.setContentText(msg);
            alert.show();
        }));
    }

    public PikaRelay getRelay() {
        return relay;
    }

    public String getReaderId() {
        return readerId;
    }

    @Override
    public StringProperty getReaderNameProperty() {
        return readerNameProperty;
    }

    public BooleanProperty getReadingProperty() {
        return readingProperty;
    }

    public DoubleProperty getBatteryProperty() {
        return batteryProperty;
    }

    public StringProperty getRFIDReaderProblemProperty() {
        return rfidReaderProblemProperty;
    }

    public StringProperty getLastReadProperty() {
        return lastReadProperty;
    }

    public IntegerProperty getReadCountProperty() {
        return readCountProperty;
    }

    public IntegerProperty getLastSeenProperty() {
        return lastSeenProperty;
    }

    public StringProperty getRelayProblemProperty() {
        return relayProblemProperty;
    }

    public BooleanProperty getProfileSupportedProperty() {
        return profileSupportedProperty;
    }

    // True when the reader sends its profile list (newer PikaReader versions)
    public BooleanProperty getProfileListProperty() {
        return profileListProperty;
    }

    public StringProperty getProfileIDProperty() {
        return profileIDProperty;
    }

    public StringProperty getProfileNameProperty() {
        return profileNameProperty;
    }

    public ObservableList<ReaderProfile> getProfiles() {
        return profiles;
    }

    @Override
    public StringProperty getOutputFileProperty() {
        return outputFileProperty;
    }

    @Override
    public BooleanProperty getOutputToFileProperty() {
        return outputToFileProperty;
    }

    public IntegerProperty getAlertToneProperty() {
        return alertToneProperty;
    }

    public BooleanProperty getAlertMutedProperty() {
        return alertMutedProperty;
    }

    /** True if this is the reader with this id on this relay */
    boolean matches(PikaRelay otherRelay, String otherId) {
        return relay.sameRelay(otherRelay) && Objects.equals(readerId, otherId);
    }
}
