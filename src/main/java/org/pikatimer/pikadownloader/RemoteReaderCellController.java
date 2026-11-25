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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.controlsfx.control.ToggleSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The card for a RemoteReader (remoteReaderCell.fxml). Laid out like the
 * local reader card (LocalReaderCellController), with these differences:
 * - health comes from how long ago the reader's last status reached the
 *   relay: a warning (amber title) after WARNING_SECONDS, an error (red
 *   title) after OFFLINE_SECONDS
 * - the reader can be started but not stopped
 * - a profile change stops and restarts a reading reader (forceSetProfile)
 *
 * @author john
 */
public class RemoteReaderCellController {
    static final Logger logger = LoggerFactory.getLogger(RemoteReaderCellController.class);

    // No status for this long: warning, then error
    static final int WARNING_SECONDS = 20;
    static final int OFFLINE_SECONDS = 120;
    private static final double LOW_BATTERY = 0.20;

    // How long to wait for a queued start or profile change to show up in
    // the reader's status before giving up on it
    private static final Duration COMMAND_WAIT = Duration.seconds(60);

    // Room for the TitledPane's arrow and title padding (see LocalReaderCellController)
    private static final double TITLE_ARROW_ALLOWANCE = 40;

    private static final String GREEN = "#00A37A";    // Vibrant Mint Teal
    private static final String YELLOW = "#FFFF33";
    private static final String RED = "#FF3300";
    private static final String GREY = "#949494";
    private static final String CHARCOAL = "#222222";

    RemoteReader reader = null;

    @FXML VBox rootVBox;
    @FXML TitledPane titledPane;
    @FXML Label unitNameLabel;
    @FXML HBox battHBox;
    @FXML ProgressBar batteryLevelProgressBar;
    @FXML Label onlineStatusLabel;
    @FXML Circle onlineStatusCircle;
    @FXML Label readStatusLabel;
    @FXML Circle readStatusCircle;
    @FXML Label relayProblemLabel;
    @FXML Label rfidProblemLabel;
    @FXML HBox profileHBox;
    @FXML ChoiceBox<ReaderProfile> profileChoiceBox;
    @FXML ToggleSwitch outputFileToggleSwitch;
    @FXML TextField outputFileTextField;
    @FXML Button rewindButton;
    @FXML Button startReaderButton;
    @FXML Label readCountLabel;
    @FXML Label lastReadLabel;
    @FXML Label relayLabel;
    @FXML Button disconnectButton;
    @FXML Button removeButton;
    @FXML Button rewriteOutputButton;
    private final BooleanProperty rewriteRunning = new SimpleBooleanProperty(false);
    @FXML Slider alertToneSlider;
    @FXML Label alertToneLabel;
    @FXML CheckBox alertMuteCheckBox;
    @FXML TextField timeSkewTextField;
    @FXML ToggleSwitch timeSkewToggleSwitch;

    // The ChoiceBox's own copy of the profile list (see LocalReaderCellController)
    private final ObservableList<ReaderProfile> profileChoices = FXCollections.observableArrayList();
    private final BooleanProperty profileChangeInProgress = new SimpleBooleanProperty(false);
    private boolean syncingProfile = false;
    // A profile change that the relay queued, until the reader reports it
    private ReaderProfile pendingProfile = null;
    private final PauseTransition profileWait = new PauseTransition(COMMAND_WAIT);

    // A start that the relay queued, until the reader reports it is reading
    private final BooleanProperty startPending = new SimpleBooleanProperty(false);
    private final PauseTransition startWait = new PauseTransition(COMMAND_WAIT);

    private final BooleanProperty errorState = new SimpleBooleanProperty(false);
    private final BooleanProperty warningState = new SimpleBooleanProperty(false);

    public void initialize() {
        // Red or amber title (PikaDownloader.css). Never both: warningState
        // is only true when errorState is not.
        errorState.addListener((observable, oldValue, newValue) -> setStyleClass("error-pane", newValue));
        warningState.addListener((observable, oldValue, newValue) -> setStyleClass("warning-pane", newValue));

        disconnectButton.setOnAction(x -> {
            if (reader == null) return;
            if (!reader.isEnabled().get()) {
                reader.enable();
                disconnectButton.setText("Disconnect");
            } else {
                reader.disable();
                disconnectButton.setText("Reconnect");
            }
        });

        removeButton.setOnAction(x -> {
            if (reader != null) {
                reader.disable();
                PikaDownloaderPrefs.INSTANCE.getReaderList().remove(reader);
            }
        });
    }

    private void setStyleClass(String styleClass, boolean on) {
        if (on) {
            if (!titledPane.getStyleClass().contains(styleClass)) titledPane.getStyleClass().add(styleClass);
        } else {
            titledPane.getStyleClass().remove(styleClass);
        }
    }

    public void setReader(RemoteReader r) {
        reader = r;

        // Relay (double click opens it in a browser)
        relayLabel.setText(reader.getRelay().host());
        Tooltip.install(relayLabel, new Tooltip(reader.getRelay().baseURL() + "\nReader ID: " + reader.getReaderId()));
        relayLabel.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                HostServices hostServices = PikaDownloaderPrefs.INSTANCE.getApplication().getHostServices();
                hostServices.showDocument(reader.getRelay().baseURL());
            }
        });

        unitNameLabel.textProperty().bind(reader.getReaderNameProperty());

        setupTitle();

        // Relay problems (can't reach it, bad key, ...)
        BooleanBinding showRelayProblem = reader.isEnabled().and(reader.getRelayProblemProperty().isNotEmpty());
        relayProblemLabel.textProperty().bind(Bindings.concat("Relay: ", reader.getRelayProblemProperty()));
        relayProblemLabel.visibleProperty().bind(showRelayProblem);
        relayProblemLabel.managedProperty().bind(showRelayProblem);

        // RFID reader problems (only while the status is current)
        BooleanBinding online = reader.isEnabled()
                .and(reader.getLastSeenProperty().greaterThanOrEqualTo(0))
                .and(reader.getLastSeenProperty().lessThanOrEqualTo(OFFLINE_SECONDS));
        BooleanBinding showRFIDProblem = online.and(reader.getRFIDReaderProblemProperty().isNotEmpty());
        rfidProblemLabel.textProperty().bind(reader.getRFIDReaderProblemProperty());
        rfidProblemLabel.visibleProperty().bind(showRFIDProblem);
        rfidProblemLabel.managedProperty().bind(showRFIDProblem);

        // Battery level (same colors as the local readers)
        batteryLevelProgressBar.progressProperty().addListener((observable, oldValue, newValue) -> {
            double progress = newValue.doubleValue();
            battHBox.setVisible(progress >= 0);
            if (progress < 0.20) batteryLevelProgressBar.setStyle("-fx-accent: #FF3300;");
            else if (progress > 0.50) batteryLevelProgressBar.setStyle("-fx-accent: #00A37A;");
            else batteryLevelProgressBar.setStyle("-fx-accent: #dede17;");
        });
        batteryLevelProgressBar.progressProperty().bind(reader.getBatteryProperty());
        battHBox.setVisible(reader.getBatteryProperty().get() >= 0);

        // Reading status
        updateReading(reader.getReadingProperty().get());
        reader.getReadingProperty().addListener((ov, o, n) -> {
            updateReading(n);
            if (n) {
                startPending.set(false);
                startWait.stop();
            }
        });

        // Last seen
        updateLastSeen(reader.getLastSeenProperty().get());
        reader.getLastSeenProperty().addListener((ov, o, n) -> updateLastSeen(n.intValue()));

        // Read count
        readCountLabel.textProperty().bind(reader.getReadCountProperty().asString());
        lastReadLabel.textProperty().bind(reader.getLastReadProperty());

        setupProfile();
        setupOutputFile();

        // Start only: the relay has no stop command
        startReaderButton.textProperty().bind(
            Bindings.when(reader.getReadingProperty()).then("Reading")
                .otherwise(Bindings.when(startPending).then("Starting...").otherwise("Start Reader")));
        startReaderButton.disableProperty().bind(
            reader.getReadingProperty().or(startPending).or(reader.isEnabled().not()));
        Tooltip startTooltip = new Tooltip();
        startTooltip.textProperty().bind(
            Bindings.when(reader.getReadingProperty())
                .then("Remote readers can't be stopped")
                .otherwise("Start the reader (sent through the relay)"));
        // Tooltips are not shown on disabled controls, so put it on the HBox
        if (startReaderButton.getParent() instanceof Node parent) Tooltip.install(parent, startTooltip);
        startReaderButton.setOnAction(event -> startReading());
        startWait.setOnFinished(event -> {
            if (!startPending.get()) return;
            startPending.set(false);
            String name = reader.getReaderNameProperty().getValueSafe();
            logger.warn("{} did not start within {}s of the start request", name, (int) COMMAND_WAIT.toSeconds());
            Alert alert = new Alert(AlertType.WARNING);
            alert.setTitle("Start Reader");
            alert.setHeaderText(name + " has not started yet");
            alert.setContentText("The start request is still queued on the relay. " + name
                    + " will start if it checks in with the relay within 10 minutes of the request.");
            alert.show();
        });

        rewindButton.setOnAction(event -> reader.rewind());

        removeButton.visibleProperty().bind(reader.isEnabled().not());

        // Read alert tone (rounded to 10 Hz) and mute for this reader
        alertToneSlider.setMin(ReadAlert.MIN_HZ);
        alertToneSlider.setMax(ReadAlert.MAX_HZ);
        alertToneSlider.setValue(reader.getAlertToneProperty().get());
        alertToneLabel.textProperty().bind(reader.getAlertToneProperty().asString("%d Hz"));
        alertToneSlider.valueProperty().addListener((ov, o, n) ->
            reader.getAlertToneProperty().set((int) Math.round(n.doubleValue() / 10.0) * 10));
        alertMuteCheckBox.selectedProperty().bindBidirectional(reader.getAlertMutedProperty());
        alertToneSlider.disableProperty().bind(reader.getAlertMutedProperty());
        alertToneLabel.disableProperty().bind(reader.getAlertMutedProperty());
        // Play a sample on release (a filter, since the thumb may consume it)
        alertToneSlider.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && !reader.getAlertMutedProperty().get()) {
                ReadAlert.INSTANCE.play(reader.getAlertToneProperty().get());
            }
        });
        // Never start a drag-to-reorder from the slider (see LocalReaderCellController)
        alertToneSlider.setOnDragDetected(Event::consume);

        // Time skew for this reader's reads (see TimeSkew). The switch always
        // starts off. The field consumes its own drags like the slider.
        reader.getTimeSkew().bindControls(timeSkewToggleSwitch, timeSkewTextField);
        timeSkewTextField.setOnDragDetected(Event::consume);

        // Error: not heard from in OFFLINE_SECONDS (or never), low battery,
        // an RFID reader problem, or a bad key. Warning: not heard from in
        // WARNING_SECONDS, or the relay can't be reached right now.
        // Only while enabled. Keep in sync with titleStatusText().
        BooleanBinding authProblem = Bindings.createBooleanBinding(
                () -> PikaRelay.isAuthProblem(reader.getRelayProblemProperty().get()),
                reader.getRelayProblemProperty());
        BooleanBinding error = reader.isEnabled().and(
            reader.getLastSeenProperty().greaterThan(OFFLINE_SECONDS)
            .or(reader.getBatteryProperty().greaterThanOrEqualTo(0.0)
                .and(reader.getBatteryProperty().lessThanOrEqualTo(LOW_BATTERY)))
            .or(showRFIDProblem)
            .or(authProblem));
        errorState.bind(error);
        warningState.bind(reader.isEnabled().and(error.not()).and(
            reader.getLastSeenProperty().greaterThan(WARNING_SECONDS)
            .or(reader.getRelayProblemProperty().isNotEmpty())));
    }

    // Title line 1: name, a "Remote" tag, and the reading status.
    // Line 2 (only while collapsed): titleStatusText().
    private void setupTitle() {
        Label titledPaneLabel = new Label();
        titledPaneLabel.textProperty().bind(reader.getReaderNameProperty());

        Label remoteTag = new Label("Remote");
        remoteTag.getStyleClass().add("remote-tag-label");
        remoteTag.setMinWidth(Label.USE_PREF_SIZE);

        Label titledPaneReadingStatusLabel = new Label();
        titledPaneReadingStatusLabel.textProperty().bind(readStatusLabel.textProperty());

        Pane titledPaneSpring = new Pane();
        HBox.setHgrow(titledPaneSpring, Priority.ALWAYS);
        titledPaneSpring.setMaxWidth(Double.MAX_VALUE);

        HBox titledPaneContent = new HBox(6, titledPaneLabel, remoteTag, titledPaneSpring, titledPaneReadingStatusLabel);
        titledPaneContent.setAlignment(Pos.CENTER_LEFT);

        StringBinding titleStatus = Bindings.createStringBinding(this::titleStatusText,
                reader.isEnabled(),
                reader.getLastSeenProperty(),
                reader.getRelayProblemProperty(),
                reader.getBatteryProperty(),
                reader.getRFIDReaderProblemProperty(),
                reader.getTimeSkew().enabledProperty(),
                reader.getTimeSkew().durationProperty());
        Label titleStatusLabel = new Label();
        titleStatusLabel.getStyleClass().add("reader-status-label");
        titleStatusLabel.setMinWidth(0); // truncate rather than widen the window
        titleStatusLabel.textProperty().bind(titleStatus);
        BooleanBinding showTitleStatus = titledPane.expandedProperty().not().and(titleStatus.isNotEmpty());
        titleStatusLabel.visibleProperty().bind(showTitleStatus);
        titleStatusLabel.managedProperty().bind(showTitleStatus);

        // Same sizing as the local reader title (see CLAUDE.md, ReaderListCell)
        VBox titleVBox = new VBox(titledPaneContent, titleStatusLabel);
        titleVBox.setMinWidth(0);
        titleVBox.prefWidthProperty().bind(titledPane.widthProperty().subtract(TITLE_ARROW_ALLOWANCE));
        titledPane.setMinWidth(0);
        titledPane.setGraphic(titleVBox);
        titledPane.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    // The collapsed title's second line: why the reader is in a warning or
    // error state, then the battery level, e.g.
    // "Offline (last seen 3m ago) · Low Battery: 15%". "" hides the line.
    private String titleStatusText() {
        if (!reader.isEnabled().get()) return "Manually disconnected";

        List<String> parts = new ArrayList<>();
        String relayProblem = reader.getRelayProblemProperty().getValueSafe();
        if (!relayProblem.isBlank()) parts.add("Relay: " + relayProblem);

        int seen = reader.getLastSeenProperty().get();
        if (seen == RemoteReader.UNKNOWN) {
            if (relayProblem.isBlank()) parts.add("Checking the relay...");
        } else if (seen == RemoteReader.NEVER_SEEN) {
            parts.add("Never seen on the relay");
        } else if (seen > OFFLINE_SECONDS) {
            parts.add("Offline (last seen " + ago(seen) + " ago)");
        } else {
            if (seen > WARNING_SECONDS) parts.add("Last seen " + ago(seen) + " ago");
            String rfidProblem = reader.getRFIDReaderProblemProperty().getValueSafe();
            if (!rfidProblem.isBlank()) parts.add(rfidProblem);
        }

        // The last known level is kept while offline, as a dead battery is
        // the usual reason
        double battery = reader.getBatteryProperty().get();
        if (battery >= 0) {
            parts.add((battery <= LOW_BATTERY ? "Low Battery: " : "Battery: ") + Math.round(battery * 100) + "%");
        }

        // An active skew, so it is visible while collapsed
        java.time.Duration skew = reader.getTimeSkew().durationProperty().get();
        if (reader.getTimeSkew().enabledProperty().get() && skew != null && !skew.isZero()) {
            parts.add("Skew: " + TimeSkew.format(skew, true));
        }
        return String.join(" · ", parts);
    }

    // 45s, 3m, 2h 5m, 3d
    static String ago(int seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) {
            int m = seconds / 60 % 60;
            return (seconds / 3600) + "h" + (m > 0 ? " " + m + "m" : "");
        }
        return (seconds / 86400) + "d";
    }

    private void updateReading(boolean reading) {
        readStatusCircle.setFill(Color.web(reading ? GREEN : CHARCOAL));
        readStatusLabel.setText(reading ? "Reader: Reading" : "Reader: Idle");
    }

    private void updateLastSeen(int seen) {
        if (seen == RemoteReader.UNKNOWN) {
            onlineStatusCircle.setFill(Color.web(GREY));
            onlineStatusLabel.setText("Checking:");
        } else if (seen == RemoteReader.NEVER_SEEN) {
            onlineStatusCircle.setFill(Color.web(RED));
            onlineStatusLabel.setText("Never Seen:");
        } else if (seen <= WARNING_SECONDS) {
            onlineStatusCircle.setFill(Color.web(GREEN));
            onlineStatusLabel.setText("Online:");
        } else if (seen <= OFFLINE_SECONDS) {
            onlineStatusCircle.setFill(Color.web(YELLOW));
            onlineStatusLabel.setText("Last Seen: " + ago(seen));
        } else {
            onlineStatusCircle.setFill(Color.web(RED));
            onlineStatusLabel.setText("Offline: " + ago(seen));
        }
    }

    private void startReading() {
        String name = reader.getReaderNameProperty().getValueSafe();
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("Start Reader");
        alert.setHeaderText("Start Reader");
        String content = "This will start " + name + ".\n"
                + "The request goes through the relay, and the reader starts within about 5 seconds.\n"
                + "Remote readers can't be stopped.";
        if (!reader.seenWithin(WARNING_SECONDS)) {
            content += "\n\n" + name + " has not checked in with the relay recently. "
                    + "It will only start if it checks in within 10 minutes.";
        }
        content += "\n\nAre you ok with this?";
        alert.setContentText(content);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        startPending.set(true);
        reader.startReader().whenComplete((response, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                startPending.set(false);
                String msg = PikaRelay.errorMessage(ex);
                logger.warn("Unable to start {}: {}", name, msg);
                Alert error = new Alert(AlertType.ERROR);
                error.setTitle("Start Reader");
                error.setHeaderText("Unable to start " + name);
                error.setContentText(msg);
                error.showAndWait();
                return;
            }
            // Queued: wait for the status to say it is reading
            if (reader.getReadingProperty().get()) startPending.set(false);
            else startWait.playFromStart();
        }));
    }

    private void setupProfile() {
        // Hidden if the reader is running an older PikaReader without profiles
        profileHBox.visibleProperty().bind(reader.getProfileSupportedProperty());
        profileHBox.managedProperty().bind(reader.getProfileSupportedProperty());

        profileChoiceBox.setItems(profileChoices);

        // Changeable while reading (forceSetProfile restarts the reader), but
        // not without a list of profiles to pick from
        profileChoiceBox.disableProperty().bind(
            profileChangeInProgress
            .or(reader.isEnabled().not())
            .or(Bindings.size(profileChoices).lessThan(2)));

        Tooltip profileTooltip = new Tooltip();
        profileTooltip.textProperty().bind(
            Bindings.when(reader.getProfileListProperty().not())
                .then("Update PikaReader on this unit to change its profile remotely")
                .otherwise(Bindings.when(profileChangeInProgress)
                    .then("Waiting for the reader to change the profile")
                    .otherwise(Bindings.when(reader.getReadingProperty())
                        .then("Changing the profile stops and restarts the reader")
                        .otherwise("Select a reader profile"))));
        Tooltip.install(profileHBox, profileTooltip);

        syncProfileList();
        reader.getProfiles().addListener((ListChangeListener.Change<? extends ReaderProfile> c) -> syncProfileList());
        reader.getProfileIDProperty().addListener((ov, o, n) -> {
            if (pendingProfile != null && pendingProfile.id().equals(n)) {
                // The reader made the change
                pendingProfile = null;
                profileWait.stop();
                profileChangeInProgress.set(false);
            }
            syncProfileSelection();
        });

        profileChoiceBox.valueProperty().addListener((ov, o, n) -> {
            if (syncingProfile || n == null) return;
            if (n.id().equals(reader.getProfileIDProperty().getValueSafe())) return;
            Platform.runLater(() -> promptProfileChange(n));
        });

        profileWait.setOnFinished(event -> {
            ReaderProfile target = pendingProfile;
            if (target == null) return;
            pendingProfile = null;
            profileChangeInProgress.set(false);
            syncProfileSelection();
            String name = reader.getReaderNameProperty().getValueSafe();
            logger.warn("{} did not change to profile {} within {}s", name, target.id(), (int) COMMAND_WAIT.toSeconds());
            Alert alert = new Alert(AlertType.WARNING);
            alert.setTitle("Change Reader Profile");
            alert.setHeaderText(name + " has not changed its profile yet");
            alert.setContentText("The change to " + target.name() + " is still queued on the relay. " + name
                    + " will make the change if it checks in with the relay within 10 minutes of the request.");
            alert.show();
        });
    }

    private void syncProfileList() {
        syncingProfile = true;
        try {
            profileChoices.setAll(reader.getProfiles());
        } finally {
            syncingProfile = false;
        }
        syncProfileSelection();
    }

    // Select the profile the reader is using (or the one we asked for)
    private void syncProfileSelection() {
        syncingProfile = true;
        try {
            String id = pendingProfile != null ? pendingProfile.id() : reader.getProfileIDProperty().getValueSafe();
            ReaderProfile current = profileChoices.stream()
                    .filter(p -> p.id().equals(id))
                    .findFirst()
                    .orElse(null);
            profileChoiceBox.setValue(current);
        } finally {
            syncingProfile = false;
        }
    }

    private void promptProfileChange(ReaderProfile target) {
        String name = reader.getReaderNameProperty().getValueSafe();
        String currentName = reader.getProfileNameProperty().getValueSafe();

        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("Change Reader Profile");
        alert.setHeaderText("Change Reader Profile");
        String content = "Change the reader profile for " + name
                + "\nfrom " + currentName + " to " + target.name() + "?";
        if (reader.getReadingProperty().get()) {
            content += "\n\n" + name + " is reading. It will stop, change to " + target.name()
                    + ", and start reading again.";
        }
        content += "\n\nThe change goes through the relay and takes effect within about 5 seconds.";
        if (!reader.seenWithin(WARNING_SECONDS)) {
            content += "\n" + name + " has not checked in with the relay recently.";
        }
        alert.setContentText(content);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            syncProfileSelection(); // revert the ChoiceBox
            return;
        }

        profileChangeInProgress.set(true);
        pendingProfile = target;
        syncProfileSelection();
        reader.changeProfile(target.id()).whenComplete((response, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                pendingProfile = null;
                profileChangeInProgress.set(false);
                syncProfileSelection();
                String msg = PikaRelay.errorMessage(ex);
                logger.warn("Unable to change the profile for {} to {}: {}", name, target.id(), msg);
                Alert error = new Alert(AlertType.ERROR);
                error.setTitle("Change Reader Profile");
                error.setHeaderText("Unable to change the reader profile");
                error.setContentText(msg);
                error.showAndWait();
                return;
            }
            // Queued: wait for the status to show the new profile
            if (pendingProfile != null && pendingProfile.id().equals(reader.getProfileIDProperty().getValueSafe())) {
                pendingProfile = null;
                profileChangeInProgress.set(false);
                syncProfileSelection();
            } else {
                profileWait.playFromStart();
            }
        }));
    }

    // Output file name and switch (as in LocalReaderCellController)
    private void setupOutputFile() {
        outputFileToggleSwitch.setSelected(reader.getOutputToFileProperty().getValue());
        outputFileToggleSwitch.selectedProperty().addListener(a ->
            reader.getOutputToFileProperty().set(outputFileToggleSwitch.isSelected()));

        outputFileTextField.setText(reader.getOutputFileProperty().getValueSafe());
        outputFileTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!oldValue.equals(newValue)) {
                outputFileToggleSwitch.setSelected(false);
                reader.getOutputFileProperty().setValue(newValue);
            }
        });

        BooleanBinding validFileName = Bindings.createBooleanBinding(
                () -> LocalReaderCellController.isValidFileName(outputFileTextField.getText()),
                outputFileTextField.textProperty());
        outputFileToggleSwitch.disableProperty().bind(validFileName.not());
        validFileName.addListener((ov, o, valid) -> updateOutputFileState(valid));
        updateOutputFileState(validFileName.get());

        // Rewrite Output... (Advanced pane), only while the output is on
        rewriteOutputButton.disableProperty().bind(reader.getOutputToFileProperty().not().or(rewriteRunning));
        rewriteOutputButton.setOnAction(e -> OutputRewrite.confirmAndRun(reader, rewriteOutputButton, rewriteRunning));
    }

    private void updateOutputFileState(boolean valid) {
        String name = outputFileTextField.getText();
        boolean flag = !valid && name != null && !name.isBlank();
        if (flag) {
            if (!outputFileTextField.getStyleClass().contains("invalid-field")) {
                outputFileTextField.getStyleClass().add("invalid-field");
            }
        } else {
            outputFileTextField.getStyleClass().remove("invalid-field");
        }
        if (!valid && outputFileToggleSwitch.isSelected()) {
            outputFileToggleSwitch.setSelected(false);
        }
    }
}
