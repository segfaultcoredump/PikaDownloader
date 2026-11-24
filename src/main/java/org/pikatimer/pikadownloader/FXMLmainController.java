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

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.controlsfx.control.ToggleSwitch;

/**
 * FXML Controller class
 *
 * @author john
 */
public class FXMLmainController {

    static final Logger logger = LoggerFactory.getLogger(FXMLmainController.class);
    static final PikaDownloaderPrefs pikaPrefs = PikaDownloaderPrefs.INSTANCE;

    @FXML    ListView<Reader> readerListView;
    @FXML    Button outputDirButton;
    @FXML    TextField ouputDirTextField;
    
    @FXML    Button setupChipBibButton;
    @FXML    ToggleSwitch customBibMapToggleSwitch;
    @FXML    ToggleSwitch mappedChipsOnlyToggleSwitch;
    @FXML    Button addLocalButton;
    @FXML    Button addRemoteButton;
    @FXML    Button reviewReadsButton;

    @FXML    ChoiceBox<OutputFormat> outputFormatChoiceBox;
    @FXML    TextField customFormatTextField;
    @FXML    HBox customFormatHBox;

    @FXML    TitledPane outputSettingsTitledPane;
    @FXML    Label bibWarningLabel;

    // Timezone override (session only): -12:00 to +14:00, 15 minute steps
    @FXML    ToggleSwitch tzOverrideToggleSwitch;
    @FXML    Spinner<Integer> tzHourSpinner;
    @FXML    Spinner<Integer> tzMinuteSpinner;
    private static final int TZ_MIN_HOURS = -12;
    private static final int TZ_MAX_HOURS = 14;
    private static final int TZ_MINUTE_STEP = 15;

    // Remote Relay (PikaRelay address and client key, saved in the prefs)
    @FXML    TitledPane remoteRelayTitledPane;
    @FXML    TextField relayURLTextField;
    @FXML    TextField relayKeyTextField;
    @FXML    PasswordField relaySecretField;
    @FXML    Label relayStatusLabel;
    // Extra lines of the Remote Relay title, only shown while collapsed
    private final Label relayHostLabel = new Label();
    private final Label relayTitleStatusLabel = new Label();
    // True while the relay address is usable, which shows Add Remote Reader
    private final BooleanProperty relayURLValid = new SimpleBooleanProperty(false);
    // Bumped on every edit, so the results of older checks are ignored
    private final AtomicInteger relayCheck = new AtomicInteger();
    // Wait for the typing to stop before checking
    private final PauseTransition relayDebounce = new PauseTransition(javafx.util.Duration.millis(500));

    // Read Alerts (volume and mute for every reader)
    @FXML    ToggleButton alertMuteToggleButton;
    @FXML    Slider alertVolumeSlider;
    @FXML    Label alertVolumeLabel;

    // Extra lines of the Output Settings title, only shown while collapsed:
    // the output directory, then the status
    private final Label outputDirLabel = new Label();
    private final Label outputStatusLabel = new Label();
    // Room for the TitledPane's arrow and padding beside the title graphic
    private static final double TITLE_ARROW_ALLOWANCE = 40;

    // The Read Review window, while it is open
    private Stage readReviewStage = null;

    // Why reads can't be saved to the output directory, or null if it is OK
    private final StringProperty outputDirProblem = new SimpleStringProperty();

    // Re-checks the output directory, in case it is deleted or its drive is
    // unplugged after it was picked. Off the FX thread since a dead network
    // share can block.
    private static final ScheduledExecutorService OUTPUT_DIR_CHECK = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("output-dir-check");
        return thread;
    });

    static final ObservableList<Reader> readerList = FXCollections.observableArrayList();

    private static final String errorCSS = ".invalid-field {"
            + "    -fx-text-box-border: #FF0000;"
            + "    -fx-focus-color: #FF0000;"
            + "    -fx-control-inner-background: #FFF5F5;"
            + "}";

    /**
     * Initializes the controller class.
     */
    public void initialize() {
        setupOutputSettingsTitle();
        setupReadAlerts();
        setupRemoteRelay();

        pikaPrefs.setReaderList(readerList);

        addLocalButton.setOnAction(event -> {
            addLocalDialog();

        });

        reviewReadsButton.setOnAction(event -> openReadReview());

        outputDirButton.setOnAction(event -> {
            changeOutputDir();
        });

        readerListView.setItems(readerList);

        Label emptyMessage = new Label("Use the \"Add Local Reader...\" button to add a reader,\n"
                + "or set up the Remote Relay below to add remote readers.");
        emptyMessage.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        readerListView.setPlaceholder(emptyMessage);

        // Don't let folks directly edit the output dir
        // force them through the directory chooser
        ouputDirTextField.setEditable(false);
        ouputDirTextField.setOnMouseClicked(event -> {
            if (event.getButton().equals(MouseButton.PRIMARY)) {
                if (event.getClickCount() == 2) {
                    changeOutputDir();
                }
            }
        });
        // Add in the CSS rules
        ouputDirTextField.getStylesheets().add("data:text/css," + errorCSS.replaceAll(" ", "%20"));

        // Turn the output dir field (and the Output Settings title) red if
        // the output directory is not set or is no longer usable
        outputDirProblem.addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                if (!ouputDirTextField.getStyleClass().contains("invalid-field")) {
                    ouputDirTextField.getStyleClass().add("invalid-field");
                }
            } else {
                ouputDirTextField.getStyleClass().remove("invalid-field");
            }
            updateOutputStatus();
        });


        readerListView.setCellFactory(param -> new ReaderListCell());

        setupChipBibButton.setOnAction(x -> {importBibChipMap();});
        customBibMapToggleSwitch.selectedProperty().addListener(a -> {
            if (customBibMapToggleSwitch.isSelected() && OutputProcessor.INSTANCE.getBibChipMap().isEmpty()) {
                importBibChipMap();
            }
        });
        customBibMapToggleSwitch.selectedProperty().addListener((observable, oldValue, newValue) -> {
            syncBibMapState();
        });

        // Only output reads for mapped chips (and trigger reads).
        // Greyed out unless the chip -> bib map is in use.
        mappedChipsOnlyToggleSwitch.setSelected(OutputProcessor.INSTANCE.isMappedChipsOnly());
        mappedChipsOnlyToggleSwitch.disableProperty().bind(customBibMapToggleSwitch.selectedProperty().not());
        mappedChipsOnlyToggleSwitch.selectedProperty().addListener((observable, oldValue, newValue) -> {
            OutputProcessor.INSTANCE.setMappedChipsOnly(newValue);
            logger.debug("Only output mapped chips: {}", newValue);
            updateOutputStatus();
        });


        outputFormatChoiceBox.setItems(FXCollections.observableArrayList(OutputFormat.values()));

        outputFormatChoiceBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && oldValue != newValue) {
                OutputProcessor.INSTANCE.setOutputFormat(newValue);
                logger.debug("Setting output format to {}", newValue.toString());
                if (newValue != OutputFormat.CUSTOM) {
                    customFormatHBox.setVisible(false);
                    customFormatHBox.setManaged(false);
                } else {
                    customFormatHBox.setVisible(true);
                    customFormatHBox.setManaged(true);
                }
                updateBibWarning();
            }
        });
        outputFormatChoiceBox.setValue(OutputProcessor.INSTANCE.getOutputFormat());

        customFormatTextField.textProperty().set(OutputProcessor.INSTANCE.getCustomFormat());
        customFormatTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            OutputProcessor.INSTANCE.setCustomFormat(newValue);
            logger.debug("CustomOutputFormat Changed: {} -> {}", oldValue, newValue);
            updateBibWarning();
        });
        setupTimezoneOverride();
        syncBibMapState();

        // Restore the output directory from the last run (if it is still usable)
        // so reads from readers that come back with their output turned on are saved
        String savedOutputDir = pikaPrefs.getPreferences().get("outputDir", "");
        if (!savedOutputDir.isBlank()) {
            File dir = new File(savedOutputDir);
            if (dir.isDirectory() && dir.canWrite()) {
                pikaPrefs.setOutputDir(dir);
                ouputDirTextField.setText(dir.getAbsolutePath());
                logger.debug("Restored the output directory: {}", dir.getAbsolutePath());
            } else {
                logger.warn("The saved output directory {} is no longer available", savedOutputDir);
            }
        }

        refreshOutputDirStatus();
        OUTPUT_DIR_CHECK.scheduleWithFixedDelay(this::checkOutputDirInBackground, 5, 5, TimeUnit.SECONDS);
    }

    // Replace the Output Settings title with a graphic: the title, plus the
    // output directory and a status line (bib map / filter state, or an
    // output directory problem) that are only shown while the pane is
    // collapsed
    private void setupOutputSettingsTitle() {
        Label titleLabel = new Label(outputSettingsTitledPane.getText());
        titleLabel.getStyleClass().add("output-title-label");
        titleLabel.fontProperty().bind(outputSettingsTitledPane.fontProperty());

        // The field always holds the current directory, so this follows a new
        // pick even when the old directory was fine too (no status change).
        // Hidden when none is set, the status line says so.
        outputDirLabel.getStyleClass().add("output-dir-label");
        outputDirLabel.setMinWidth(0); // truncate rather than widen the window
        outputDirLabel.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS); // keep the folder name
        outputDirLabel.textProperty().bind(ouputDirTextField.textProperty());
        BooleanBinding showOutputDir = outputSettingsTitledPane.expandedProperty().not()
                .and(ouputDirTextField.textProperty().isNotEmpty());
        outputDirLabel.visibleProperty().bind(showOutputDir);
        outputDirLabel.managedProperty().bind(showOutputDir);

        outputStatusLabel.getStyleClass().add("output-status-label");
        outputStatusLabel.setMinWidth(0); // truncate rather than widen the window
        outputStatusLabel.visibleProperty().bind(outputSettingsTitledPane.expandedProperty().not());
        outputStatusLabel.managedProperty().bind(outputSettingsTitledPane.expandedProperty().not());

        // The TitledPane lays the graphic out at its pref width, and uses the
        // title's pref width as its minimum width, so a long path would run
        // past the title bar and widen the pane. Size the graphic to the pane
        // instead so the lines truncate (as the reader titles do).
        VBox titleVBox = new VBox(titleLabel, outputDirLabel, outputStatusLabel);
        titleVBox.setMinWidth(0);
        titleVBox.prefWidthProperty().bind(outputSettingsTitledPane.widthProperty().subtract(TITLE_ARROW_ALLOWANCE));
        outputSettingsTitledPane.setMinWidth(0);
        outputSettingsTitledPane.setGraphic(titleVBox);
        outputSettingsTitledPane.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    // The Read Alerts volume and mute apply to every reader (each reader has
    // its own tone and mute in its Advanced pane). Both are saved by ReadAlert.
    private void setupReadAlerts() {
        ReadAlert alert = ReadAlert.INSTANCE;

        alertVolumeSlider.setMin(0);
        alertVolumeSlider.setMax(100);
        alertVolumeSlider.setValue(alert.getVolume());
        alertVolumeLabel.setText(alert.getVolume() + "%");
        alertVolumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int volume = (int) Math.round(newValue.doubleValue());
            alert.setVolume(volume);
            alertVolumeLabel.setText(volume + "%");
        });

        // The button names what a click will do
        alertMuteToggleButton.setSelected(alert.isMuted());
        alertMuteToggleButton.setText(alert.isMuted() ? "Unmute" : "Mute");
        alertMuteToggleButton.selectedProperty().addListener((observable, oldValue, newValue) -> {
            alert.setMuted(newValue);
            alertMuteToggleButton.setText(newValue ? "Unmute" : "Mute");
            logger.debug("Read alerts muted: {}", newValue);
        });
        alertVolumeSlider.disableProperty().bind(alertMuteToggleButton.selectedProperty());
        alertVolumeLabel.disableProperty().bind(alertMuteToggleButton.selectedProperty());

        // Play a sample (at the default tone) when they let go of the slider.
        // A filter, since the slider's thumb may consume the release.
        alertVolumeSlider.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) alert.play(ReadAlert.DEFAULT_HZ);
        });
    }

    // Timezone override: write the reads out at a fixed UTC offset (session
    // only). The spinners start at the current system offset and stay
    // enabled while the override is off, so the offset can be picked before
    // it is turned on.
    private void setupTimezoneOverride() {
        int totalMinutes = ZonedDateTime.now().getOffset().getTotalSeconds() / 60;
        int hours = Math.clamp(totalMinutes / 60, TZ_MIN_HOURS, TZ_MAX_HOURS);
        int minutes = isTzHourLimit(hours) ? 0 : Math.abs(totalMinutes % 60) / TZ_MINUTE_STEP * TZ_MINUTE_STEP;

        IntegerSpinnerValueFactory hourFactory = new IntegerSpinnerValueFactory(TZ_MIN_HOURS, TZ_MAX_HOURS, hours, 1);
        hourFactory.setConverter(spinnerConverter("%+03d", hourFactory));
        IntegerSpinnerValueFactory minuteFactory = new IntegerSpinnerValueFactory(0, 60 - TZ_MINUTE_STEP, minutes, TZ_MINUTE_STEP);
        minuteFactory.setConverter(spinnerConverter("%02d", minuteFactory));
        // No wrap around: Spinner's wrap is off with a step, and throws with a max of 0
        tzHourSpinner.setValueFactory(hourFactory);
        tzMinuteSpinner.setValueFactory(minuteFactory);
        tzHourSpinner.setEditable(false);
        tzMinuteSpinner.setEditable(false);
        tzHourSpinner.getEditor().setAlignment(Pos.CENTER_RIGHT);
        tzMinuteSpinner.getEditor().setAlignment(Pos.CENTER_RIGHT);

        // -12:00 and +14:00 are the ends of the range, so no minutes there.
        // Lowering the max clamps the minutes to 00.
        hourFactory.valueProperty().addListener((observable, oldValue, newValue) -> {
            minuteFactory.setMax(newValue != null && isTzHourLimit(newValue) ? 0 : 60 - TZ_MINUTE_STEP);
            applyTimezoneOverride();
        });
        minuteFactory.valueProperty().addListener((observable, oldValue, newValue) -> applyTimezoneOverride());
        tzOverrideToggleSwitch.selectedProperty().addListener((observable, oldValue, newValue) -> applyTimezoneOverride());

        applyTimezoneOverride(); // starts off
    }

    private static boolean isTzHourLimit(int hours) {
        return hours == TZ_MIN_HOURS || hours == TZ_MAX_HOURS;
    }

    // Shows the spinner values with a format. The spinners aren't editable,
    // but parse anything typed in anyway, keeping the value if it is bad.
    private static StringConverter<Integer> spinnerConverter(String format, IntegerSpinnerValueFactory factory) {
        return new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : String.format(format, value);
            }

            @Override
            public Integer fromString(String text) {
                try {
                    return Integer.valueOf(text.trim());
                } catch (NumberFormatException | NullPointerException ex) {
                    return factory.getValue();
                }
            }
        };
    }

    // The offset in the spinners. The minutes take the sign of the hours
    // (-3 and 30 is -03:30).
    private ZoneOffset selectedTimezoneOffset() {
        int hours = tzHourSpinner.getValue();
        int minutes = tzMinuteSpinner.getValue();
        return ZoneOffset.ofHoursMinutes(hours, hours < 0 ? -minutes : minutes);
    }

    // Push the override switch and spinners to the OutputProcessor
    private void applyTimezoneOverride() {
        ZoneOffset offset = tzOverrideToggleSwitch.isSelected() ? selectedTimezoneOffset() : null;
        if (!Objects.equals(offset, OutputProcessor.INSTANCE.getTimezoneOverride())) {
            OutputProcessor.INSTANCE.setTimezoneOverride(offset);
            logger.debug("Timezone override: {}", offset == null ? "Off" : displayOffset(offset));
        }
        updateOutputStatus();
    }

    /**
     * @param offset a UTC offset
     * @return the offset as shown to the user: "-05:00", or "+00:00" for
     * UTC (which ZoneOffset shows as "Z")
     */
    static String displayOffset(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
    }

    // Check the output directory now. Only call this on the FX thread when the
    // directory was just set (it does file I/O)
    private void refreshOutputDirStatus() {
        outputDirProblem.set(PikaDownloaderPrefs.outputDirProblem(pikaPrefs.getOutputDir()));
    }

    // Runs on OUTPUT_DIR_CHECK. Any exception would cancel the schedule.
    private void checkOutputDirInBackground() {
        try {
            File dir = pikaPrefs.getOutputDir();
            String problem = PikaDownloaderPrefs.outputDirProblem(dir);
            Platform.runLater(() -> {
                // Skip a stale result if a new directory was picked meanwhile
                if (dir == pikaPrefs.getOutputDir()) outputDirProblem.set(problem);
            });
        } catch (RuntimeException ex) {
            logger.error("Error checking the output directory", ex);
        }
    }

    // Push the "Use Chip -> Bib Map" switch to the OutputProcessor. Reads the
    // switch itself (not a listener's newValue) since importBibChipMap() can
    // flip it while its own listener is still running.
    private void syncBibMapState() {
        OutputProcessor.INSTANCE.setBibMapEnabled(customBibMapToggleSwitch.isSelected());
        updateBibWarning();
        updateOutputStatus();
    }

    // Show the inline warning when the output includes the bib but no
    // chip -> bib map is in use, so the chip is written instead
    private void updateBibWarning() {
        boolean warn = OutputProcessor.INSTANCE.outputUsesBib() && !isBibMapInUse();
        bibWarningLabel.setVisible(warn);
        bibWarningLabel.setManaged(warn);
    }

    // The map only applies with the switch on and at least one mapping
    private boolean isBibMapInUse() {
        return customBibMapToggleSwitch.isSelected()
                && !OutputProcessor.INSTANCE.getBibChipMap().isEmpty();
    }

    // Update the collapsed status line, and turn the Output Settings title
    // red if reads can't be saved. Shows the effective state, matching what
    // OutputProcessor.writePending() applies.
    private void updateOutputStatus() {
        String problem = outputDirProblem.get();
        if (problem != null) {
            outputStatusLabel.setText(problem);
            if (!outputSettingsTitledPane.getStyleClass().contains("error-pane")) {
                outputSettingsTitledPane.getStyleClass().add("error-pane");
            }
            return;
        }
        outputSettingsTitledPane.getStyleClass().remove("error-pane");

        String status;
        if (isBibMapInUse()) {
            int chips = OutputProcessor.INSTANCE.getBibChipMap().size();
            boolean filtering = mappedChipsOnlyToggleSwitch.isSelected();
            status = "Bib Map: On (" + chips + (chips == 1 ? " chip)" : " chips)")
                    + " · Filter: " + (filtering ? "On" : "Off");
        } else {
            status = "Bib Map: Off · Filter: Off";
        }
        ZoneOffset tzOverride = OutputProcessor.INSTANCE.getTimezoneOverride();
        if (tzOverride != null) status += " · TZ: " + displayOffset(tzOverride);
        outputStatusLabel.setText(status);
    }

    // A reader that is entered by hand in the Add Local Reader dialog.
    // The last one that was added is remembered for the next time.
    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";
    private static final String MANUAL_PROTOCOL_PREF = "ManualReaderProtocol";
    private static final String MANUAL_HOST_PREF = "ManualReaderHost";
    private static final String MANUAL_PORT_PREF = "ManualReaderPort";
    // Just a host name or IP address (no scheme, path, user or spaces)
    private static final Pattern MANUAL_HOST_PATTERN = Pattern.compile("[^\\s/@?#]+");

    private void addLocalDialog() {

        logger.debug("Starting discover...");
        ObservableList<DiscoveredLocalReader> readers = FXCollections.observableArrayList();
        BooleanProperty scanCompleted = new SimpleBooleanProperty(false);
        BooleanProperty dialogClosed = new SimpleBooleanProperty(false);

        // The readers we already have, so discovery doesn't offer them again.
        // Collected here on the FX thread, the scanner thread only reads them.
        // Check the unit ID too: a reader added by hostname has no IP here.
        Set<String> existingIPs = new HashSet<>();
        Set<String> existingNames = new HashSet<>();
        readerList.forEach(x -> {
            if (x instanceof LocalReader r) {
                existingIPs.add(r.getReaderIPProperty().getValueSafe());
                existingNames.add(r.getReaderNameProperty().getValueSafe().toUpperCase());
                logger.debug("Adding existing reader to the exclude list: {} {}", r.getReaderNameProperty().getValueSafe(), r.getReaderIPProperty().getValueSafe());
            }
        });

        // start a discovery task in a background thread
        Task pikaSearch = new Task<Void>() {
            @Override
            public Void call() {

                byte[] packetData = "DISCOVER_PIKA_READER_REQUEST".getBytes();

                // Find the server using UDP broadcast
                // Loop while the dialog box is open
                // UDP Broadcast code borrowed from https://demey.io/network-discovery-using-udp-broadcast/
                // with a few modifications to protect the guilty and to bring it up to date
                // (e.g., try-with-resources 
                while (dialogClosed.not().get()) {
                    try (DatagramSocket broadcastSocket = new DatagramSocket()) {
                        broadcastSocket.setBroadcast(true);
                        // 2 second timeout for responses
                        broadcastSocket.setSoTimeout(2000);

                        // Send a packet to 255.255.255.255 on port 8888
                        DatagramPacket probeDatagramPacket = new DatagramPacket(packetData, packetData.length, InetAddress.getByName("255.255.255.255"), 8888);
                        broadcastSocket.send(probeDatagramPacket);

                        logger.debug("Sent UDP Broadcast to 255.255.255.255");
                        // Broadcast the message over all the network interfaces

                        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces.hasMoreElements()) {
                            NetworkInterface networkInterface = interfaces.nextElement();

                            if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                                continue; // Don't want to broadcast to the loopback interface
                            }

                            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                                InetAddress broadcast = interfaceAddress.getBroadcast();
                                if (broadcast == null) {
                                    continue;
                                }
                                // Send the broadcast package!
                                try {
                                    DatagramPacket sendPacket = new DatagramPacket(packetData, packetData.length, broadcast, 8888);
                                    broadcastSocket.send(sendPacket);
                                    logger.debug("Sent UDP Broadcast to " + broadcast.getHostAddress());
                                } catch (Exception e) {
                                }
                            }
                        }

                        try {

                            while (true) { // the socket timeout should stop this
                                byte[] recvBuf = new byte[1500]; // mass overkill
                                DatagramPacket receivePacket = new DatagramPacket(recvBuf, recvBuf.length);
                                broadcastSocket.receive(receivePacket);

                                String message = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8).trim();

                                logger.debug("PikaReader Discover Response: {} \"{}\"", receivePacket.getAddress().getHostAddress(), message);

                                // PIKA_READER <unitID> <port>
                                String[] msg = message.split("\\s+");
                                if (msg.length < 3) {
                                    logger.debug("Ignoring a malformed discovery response from {}", receivePacket.getAddress().getHostAddress());
                                    continue;
                                }

                                if (msg[0].equalsIgnoreCase("PIKA_READER")) {
                                    DiscoveredLocalReader u = new DiscoveredLocalReader(receivePacket.getAddress().getHostAddress());
                                    u.UnitName.set(msg[1].toUpperCase());
                                    u.PORT.set(msg[2]);
                                    // If we have a new DiscoveredLocalReader, save it. 
                                    if (!readers.contains(u) && !existingIPs.contains(u.IP.getValue())
                                            && !existingNames.contains(u.UnitName.getValue())) {
                                        Platform.runLater(() -> {
                                            if (!readers.contains(u)) {
                                                readers.add(u);
                                            }
                                        });
                                    }
                                }
                            }
                        } catch (Exception ex) {
                        }

                    } catch (IOException ex) {
                        //Logger.getLogger(this.class.getName()).log(Level.SEVERE, null, ex);
                        logger.debug("oops...");
                    }
                }
                logger.debug("Done scanning for PikaReader units.");
                //Platform.runLater(() -> {scanCompleted.set(true);});

                readers.forEach(u -> {
                    logger.debug("Found " + u.IP.getValueSafe());
                });
                return null;
            }
        };
        Thread scanner = new Thread(pikaSearch);
        scanner.setDaemon(true);
        scanner.setName("Pika Scanner");
        scanner.start();

        ProgressBar progress = new ProgressBar();
        progress.progressProperty().bind(pikaSearch.progressProperty());

        ListView<DiscoveredLocalReader> pikaReaderListView = new ListView();
        pikaReaderListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        pikaReaderListView.setItems(readers);

        // open a dialog
        Dialog<List<DiscoveredLocalReader>> dialog = new Dialog();
        dialog.resizableProperty().set(true);
        dialog.getDialogPane().setMaxHeight(Screen.getPrimary().getVisualBounds().getHeight() - 150);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("PikaDownloader.css").toExternalForm());
        dialog.setTitle("Discover...");
        // Keep this short enough for one line: the dialog is sized to the manual entry row
        dialog.setHeaderText("Discover PikaReader units or enter an address");
        ButtonType selectButtonType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectButtonType, ButtonType.CANCEL);

        // Create a scrollPane to put the tables and such in
        VBox mainVBox = new VBox();
        mainVBox.setSpacing(5);
        mainVBox.setStyle("-fx-font-size: 16px;"); // Make the scroll bar a bit larger
        VBox progressVBox = new VBox();
        progressVBox.setAlignment(Pos.CENTER);
        progressVBox.getChildren().add(new Label("Searching for Units..."));
        progressVBox.visibleProperty().bind(scanCompleted.not());
        progressVBox.managedProperty().bind(scanCompleted.not());
        progressVBox.getChildren().add(progress);
        Label foundCount = new Label();
        foundCount.textProperty().bind(Bindings.concat("Found ", Bindings.size(readers).asString()));

        progressVBox.getChildren().add(foundCount);

        progress.setMaxWidth(500);

        progressVBox.setPrefHeight(175);

        VBox ultraListVBox = new VBox();
        ultraListVBox.setStyle("-fx-font-size: 16px;"); // Make everything normal again
        ultraListVBox.fillWidthProperty().set(true);
        ultraListVBox.setAlignment(Pos.CENTER_LEFT);

        Label selectLabel = new Label("Select a PikaReader:");
        selectLabel.visibleProperty().bind(Bindings.size(readers).isNotEqualTo(0));
        selectLabel.managedProperty().bind(Bindings.size(readers).isNotEqualTo(0));
        ultraListVBox.getChildren().add(selectLabel);
        ultraListVBox.getChildren().add(pikaReaderListView);

        Label notFound = new Label("No PikaReader units were found!.\nCheck network settings\nand try again.");
        notFound.visibleProperty().bind(Bindings.size(readers).isEqualTo(0));
        notFound.managedProperty().bind(Bindings.size(readers).isEqualTo(0));
        pikaReaderListView.visibleProperty().bind(Bindings.size(readers).greaterThanOrEqualTo(1));
        pikaReaderListView.managedProperty().bind(Bindings.size(readers).greaterThanOrEqualTo(1));

        ultraListVBox.setPrefHeight(1750);
        // Shrink the list, not the manual entry, when the dialog is short
        VBox.setVgrow(ultraListVBox, Priority.ALWAYS);

        ultraListVBox.getChildren().add(notFound);
        ultraListVBox.visibleProperty().bind(scanCompleted);
        ultraListVBox.managedProperty().bind(scanCompleted);

        // Manual entry: protocol, IP address or hostname, and port (blank for 80 / 443)
        ChoiceBox<String> protocolChoiceBox = new ChoiceBox<>(FXCollections.observableArrayList(HTTP, HTTPS));
        protocolChoiceBox.setValue(HTTP);
        TextField hostTextField = new TextField();
        hostTextField.setPromptText("IP address or hostname");
        // Sizes in em, relative to the dialog's 16px font. The host field
        // still grows if the dialog is made wider; the port only needs 5 digits.
        hostTextField.setStyle("-fx-pref-width: 10.75em;");
        HBox.setHgrow(hostTextField, Priority.ALWAYS);
        TextField portTextField = new TextField();
        portTextField.setStyle("-fx-pref-width: 4.5em;");
        portTextField.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,5}") ? change : null));
        portTextField.promptTextProperty().bind(Bindings.when(protocolChoiceBox.valueProperty().isEqualTo(HTTPS)).then("443").otherwise("80"));
        HBox manualHBox = new HBox(5, protocolChoiceBox, hostTextField, new Label(":"), portTextField);
        manualHBox.setAlignment(Pos.CENTER_LEFT);

        Label manualStatusLabel = new Label();
        manualStatusLabel.setWrapText(true);
        manualStatusLabel.setMinHeight(Region.USE_PREF_SIZE);

        VBox manualVBox = new VBox(5, new Separator(), new Label("Or enter a PikaReader's address:"), manualHBox, manualStatusLabel);
        manualVBox.setMinHeight(Region.USE_PREF_SIZE);

        mainVBox.getChildren().add(progressVBox);
        mainVBox.getChildren().add(ultraListVBox);
        mainVBox.getChildren().add(manualVBox);
        dialog.getDialogPane().setContent(mainVBox);

        scanCompleted.bind(Bindings.size(readers).greaterThanOrEqualTo(1));

        // The manual reader, once GET /status on it works
        BooleanProperty manualOK = new SimpleBooleanProperty(false);
        ObjectProperty<DiscoveredLocalReader> manualReader = new SimpleObjectProperty<>();
        // Bumped on every edit, so the results of older checks are ignored
        AtomicInteger manualCheck = new AtomicInteger();
        // Wait for the typing to stop before checking
        PauseTransition manualDebounce = new PauseTransition(javafx.util.Duration.millis(500));

        InvalidationListener manualEdited = o -> {
            manualCheck.incrementAndGet();
            manualOK.set(false);
            manualReader.set(null);
            manualDebounce.stop();

            String host = hostTextField.getText().trim();
            String port = portTextField.getText();
            if (host.isEmpty()) {
                setStatus(manualStatusLabel, "", null);
            } else if (!MANUAL_HOST_PATTERN.matcher(host).matches()) {
                setStatus(manualStatusLabel, "Enter just the IP address or hostname", "warning-label");
            } else if (!port.isEmpty() && (Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)) {
                setStatus(manualStatusLabel, "The port must be between 1 and 65535", "warning-label");
            } else {
                setStatus(manualStatusLabel, "Checking...", null);
                manualDebounce.playFromStart();
            }
        };
        protocolChoiceBox.valueProperty().addListener(manualEdited);
        hostTextField.textProperty().addListener(manualEdited);
        portTextField.textProperty().addListener(manualEdited);

        manualDebounce.setOnFinished(event -> {
            int check = manualCheck.get();
            boolean secure = HTTPS.equals(protocolChoiceBox.getValue());
            String host = hostTextField.getText().trim();
            int portNumber = portTextField.getText().isEmpty() ? (secure ? 443 : 80) : Integer.parseInt(portTextField.getText());
            String port = String.valueOf(portNumber);

            LocalReader existing = findLocalReader(host, port, null);
            if (existing != null) {
                setStatus(manualStatusLabel, "Already added as " + existing.getReaderNameProperty().getValueSafe(), "warning-label");
                return;
            }

            LocalReader.probeStatus(secure, host, portNumber).whenComplete((unitID, ex) -> Platform.runLater(() -> {
                if (check != manualCheck.get() || dialogClosed.get()) return; // edited since, or closed
                if (ex != null) {
                    logger.debug("No PikaReader at {}{}:{}: {}", secure ? HTTPS : HTTP, host, port, LocalReader.errorMessage(ex));
                    setStatus(manualStatusLabel, LocalReader.errorMessage(ex), "warning-label");
                    return;
                }
                // Upper case, like discovery, so the pref keys for a unit match
                String name = unitID.toUpperCase();
                if (findLocalReader(null, null, name) != null) {
                    setStatus(manualStatusLabel, name + " is already added", "warning-label");
                    return;
                }
                DiscoveredLocalReader r = new DiscoveredLocalReader(host);
                r.UnitName.set(name);
                r.PORT.set(port);
                r.secure = secure;
                manualReader.set(r);
                manualOK.set(true);
                setStatus(manualStatusLabel, "Found " + name, "ok-label");
            }));
        });

        // Pre-fill the last reader that was entered by hand (this checks it too)
        Preferences prefs = pikaPrefs.getPreferences();
        protocolChoiceBox.setValue(HTTPS.equals(prefs.get(MANUAL_PROTOCOL_PREF, HTTP)) ? HTTPS : HTTP);
        portTextField.setText(prefs.get(MANUAL_PORT_PREF, ""));
        hostTextField.setText(prefs.get(MANUAL_HOST_PREF, ""));

        // If they double click on an reader, select it and close the dialog box
        pikaReaderListView.setOnMouseClicked((MouseEvent click) -> {
            if (click.getClickCount() == 2) {
                dialog.setResult(new ArrayList<>(pikaReaderListView.getSelectionModel().getSelectedItems()));
            }
        });

        dialog.getDialogPane().getScene().getWindow().sizeToScene();

        // Select needs a discovered unit or a manual address that works
        Node createButton = dialog.getDialogPane().lookupButton(selectButtonType);
        BooleanBinding isNothingSelected = Bindings.isEmpty(pikaReaderListView.getSelectionModel().getSelectedItems());
        createButton.disableProperty().bind(isNothingSelected.and(manualOK.not()));

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == selectButtonType) {
                List<DiscoveredLocalReader> chosen = new ArrayList<>(pikaReaderListView.getSelectionModel().getSelectedItems());
                DiscoveredLocalReader manual = manualReader.get();
                // Unless the same unit was also picked from the discovered list
                if (manualOK.get() && manual != null && chosen.stream()
                        .noneMatch(d -> d.UnitName.getValueSafe().equalsIgnoreCase(manual.UnitName.getValueSafe()))) {
                    chosen.add(manual);
                }
                return chosen;
            }
            return null;
        });

        Optional<List<DiscoveredLocalReader>> result = dialog.showAndWait();
        dialogClosed.set(true);
        manualDebounce.stop();

        if (result.isPresent()) {
            DiscoveredLocalReader manual = manualReader.get();
            result.get().forEach(newReader -> {
                String ip = newReader.IP.getValueSafe();
                String name = newReader.UnitName.getValueSafe();
                String port = newReader.PORT.getValueSafe();
                LocalReader r = new LocalReader(ip, port, name, newReader.secure);

                Platform.runLater(() -> {
                    readerList.add(r);
                });
                logger.debug("New Reader: {} -> {}", name, r.getBaseURL());

                // Remember the address for the next time
                if (newReader == manual) {
                    prefs.put(MANUAL_PROTOCOL_PREF, protocolChoiceBox.getValue());
                    prefs.put(MANUAL_HOST_PREF, hostTextField.getText().trim());
                    prefs.put(MANUAL_PORT_PREF, portTextField.getText());
                }
            });
        }
    }

    /**
     * Finds a local reader that has already been added.
     *
     * @param host match this IP address or hostname (ignoring case) and port, or null to skip
     * @param port the port to match with the host
     * @param unitID match this unit ID (ignoring case), or null to skip
     * @return the matching reader, or null if there is none
     */
    private static LocalReader findLocalReader(String host, String port, String unitID) {
        for (Reader x : readerList) {
            if (x instanceof LocalReader r) {
                if (host != null && host.equalsIgnoreCase(r.getReaderIPProperty().getValueSafe())
                        && Objects.equals(port, r.getReaderPortProperty().getValueSafe())) return r;
                if (unitID != null && unitID.equalsIgnoreCase(r.getReaderNameProperty().getValueSafe())) return r;
            }
        }
        return null;
    }

    // Sets a status label's text, colored with a style class from
    // PikaDownloader.css (ok-label, warning-label) or plain if null
    private static void setStatus(Label label, String text, String styleClass) {
        label.getStyleClass().removeAll("ok-label", "warning-label");
        if (styleClass != null) label.getStyleClass().add(styleClass);
        label.setText(text);
    }

    // The Remote Relay section: the PikaRelay address and client key/secret
    // (saved in the prefs as they are typed), checked with GET /readerList
    // after a short pause. Add Remote Reader... shows once the address is
    // usable. The title gets the host and the check result while collapsed.
    private void setupRemoteRelay() {
        Label titleLabel = new Label(remoteRelayTitledPane.getText());
        titleLabel.getStyleClass().add("output-title-label");
        titleLabel.fontProperty().bind(remoteRelayTitledPane.fontProperty());

        relayHostLabel.getStyleClass().add("output-dir-label");
        relayHostLabel.setMinWidth(0); // truncate rather than widen the window
        relayHostLabel.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        BooleanBinding showHost = remoteRelayTitledPane.expandedProperty().not()
                .and(relayHostLabel.textProperty().isNotEmpty());
        relayHostLabel.visibleProperty().bind(showHost);
        relayHostLabel.managedProperty().bind(showHost);

        relayTitleStatusLabel.getStyleClass().add("output-status-label");
        relayTitleStatusLabel.setMinWidth(0);
        relayTitleStatusLabel.textProperty().bind(relayStatusLabel.textProperty());
        relayTitleStatusLabel.visibleProperty().bind(remoteRelayTitledPane.expandedProperty().not());
        relayTitleStatusLabel.managedProperty().bind(remoteRelayTitledPane.expandedProperty().not());

        // Sized to the pane, as in setupOutputSettingsTitle()
        VBox titleVBox = new VBox(titleLabel, relayHostLabel, relayTitleStatusLabel);
        titleVBox.setMinWidth(0);
        titleVBox.prefWidthProperty().bind(remoteRelayTitledPane.widthProperty().subtract(TITLE_ARROW_ALLOWANCE));
        remoteRelayTitledPane.setMinWidth(0);
        remoteRelayTitledPane.setGraphic(titleVBox);
        remoteRelayTitledPane.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        PikaRelay saved = pikaPrefs.getRelay();
        relayURLTextField.setText(saved.baseURL());
        relayKeyTextField.setText(saved.key());
        relaySecretField.setText(saved.secret());

        InvalidationListener edited = o -> relayEdited();
        relayURLTextField.textProperty().addListener(edited);
        relayKeyTextField.textProperty().addListener(edited);
        relaySecretField.textProperty().addListener(edited);
        relayDebounce.setOnFinished(event -> checkRelay());

        addRemoteButton.visibleProperty().bind(relayURLValid);
        addRemoteButton.managedProperty().bind(relayURLValid);
        addRemoteButton.setOnAction(event -> addRemoteDialog());

        relayEdited();
    }

    // The relay as entered in the Remote Relay section
    private PikaRelay currentRelay() {
        return new PikaRelay(relayURLTextField.getText(), relayKeyTextField.getText(), relaySecretField.getText());
    }

    private void relayEdited() {
        relayCheck.incrementAndGet();
        relayDebounce.stop();

        PikaRelay relay = currentRelay();
        pikaPrefs.setRelay(relay);

        String problem = PikaRelay.urlProblem(relay.baseURL());
        relayURLValid.set(problem == null);
        relayHostLabel.setText(problem == null ? relay.host() : "");
        if (relay.baseURL().isEmpty()) {
            setRelayStatus("Enter the address of a PikaRelay server to add remote readers", null);
        } else if (problem != null) {
            setRelayStatus(problem, "warning-label");
        } else {
            setRelayStatus("Checking...", null);
            relayDebounce.playFromStart();
        }
    }

    private void checkRelay() {
        int check = relayCheck.get();
        PikaRelay relay = currentRelay();
        relay.probe().whenComplete((list, ex) -> Platform.runLater(() -> {
            if (check != relayCheck.get()) return; // edited since
            if (ex != null) {
                String msg = PikaRelay.errorMessage(ex);
                logger.debug("Relay check for {} failed: {}", relay, msg);
                setRelayStatus(msg, "warning-label");
                return;
            }
            JSONArray readers = list.optJSONArray("readers");
            int n = readers == null ? 0 : readers.length();
            String msg = "Connected · " + n + (n == 1 ? " reader" : " readers");
            if (relay.insecure()) msg += " · Not encrypted (use https://)";
            setRelayStatus(msg, "ok-label");
        }));
    }

    // The status line (and the collapsed title), amber if it is a problem
    private void setRelayStatus(String text, String styleClass) {
        setStatus(relayStatusLabel, text, styleClass);
        boolean warn = "warning-label".equals(styleClass);
        if (warn) {
            if (!remoteRelayTitledPane.getStyleClass().contains("warning-pane")) {
                remoteRelayTitledPane.getStyleClass().add("warning-pane");
            }
        } else {
            remoteRelayTitledPane.getStyleClass().remove("warning-pane");
        }
    }

    // Pick readers to add from the relay's reader list (several at once).
    // The list is refreshed every 5 seconds while the dialog is open.
    private void addRemoteDialog() {
        PikaRelay relay = currentRelay();
        if (!PikaRelay.isValidURL(relay.baseURL())) return;

        ObservableList<RelayReaderEntry> entries = FXCollections.observableArrayList();
        ListView<RelayReaderEntry> listView = new ListView<>(entries);
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listView.setPrefHeight(260);
        Label placeholder = new Label("Loading...");
        placeholder.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        listView.setPlaceholder(placeholder);
        VBox.setVgrow(listView, Priority.ALWAYS);

        Label statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);

        Button refreshButton = new Button("Refresh");
        Pane spring = new Pane();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox statusHBox = new HBox(5, statusLabel, spring, refreshButton);
        statusHBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusLabel, Priority.SOMETIMES);

        VBox mainVBox = new VBox(5, new Label("Select the readers to add:"), listView, statusHBox);
        mainVBox.setStyle("-fx-font-size: 16px;");
        mainVBox.setPrefWidth(520);

        Dialog<List<RelayReaderEntry>> dialog = new Dialog<>();
        dialog.resizableProperty().set(true);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("PikaDownloader.css").toExternalForm());
        dialog.setTitle("Add Remote Reader");
        dialog.setHeaderText("Readers on " + relay.host());
        ButtonType selectButtonType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectButtonType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(mainVBox);

        Node selectButton = dialog.getDialogPane().lookupButton(selectButtonType);
        selectButton.disableProperty().bind(Bindings.isEmpty(listView.getSelectionModel().getSelectedItems()));

        listView.setOnMouseClicked(click -> {
            if (click.getClickCount() == 2 && !listView.getSelectionModel().getSelectedItems().isEmpty()) {
                dialog.setResult(new ArrayList<>(listView.getSelectionModel().getSelectedItems()));
            }
        });

        BooleanProperty dialogClosed = new SimpleBooleanProperty(false);
        AtomicInteger loads = new AtomicInteger();
        Runnable load = () -> {
            int load_ = loads.incrementAndGet();
            relay.probe().whenComplete((list, ex) -> Platform.runLater(() -> {
                if (dialogClosed.get() || load_ != loads.get()) return;
                if (ex != null) {
                    String msg = PikaRelay.errorMessage(ex);
                    setStatus(statusLabel, msg, "warning-label");
                    placeholder.setText("Unable to get the reader list:\n" + msg);
                    return;
                }
                updateRelayEntries(relay, list, entries);
                listView.refresh();
                placeholder.setText("No other readers on this relay.\nA reader shows up here once it sends its status.");
                setStatus(statusLabel, entries.size() + (entries.size() == 1 ? " reader available" : " readers available"), null);
            }));
        };
        refreshButton.setOnAction(event -> load.run());

        Timeline refresh = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), event -> load.run()));
        refresh.setCycleCount(Animation.INDEFINITE);
        refresh.play();
        load.run();

        dialog.setResultConverter(button -> button == selectButtonType
                ? new ArrayList<>(listView.getSelectionModel().getSelectedItems())
                : null);

        Optional<List<RelayReaderEntry>> result = dialog.showAndWait();
        dialogClosed.set(true);
        refresh.stop();

        result.ifPresent(chosen -> chosen.forEach(entry -> {
            if (findRemoteReader(relay, entry.id) != null) return; // added meanwhile
            RemoteReader r = new RemoteReader(relay, entry.id, entry.name);
            readerList.add(r);
            logger.debug("New Remote Reader: {} on {}", entry.id, relay);
        }));
    }

    // Update the dialog's entries in place (so the selection is kept) from
    // {"serverTime":..,"readers":[{"id","name","updated","reading","battery","lastRead"}]},
    // leaving out the readers that are already added
    private static void updateRelayEntries(PikaRelay relay, JSONObject list, ObservableList<RelayReaderEntry> entries) {
        long serverTime = list.optLong("serverTime", System.currentTimeMillis());
        JSONArray readers = list.optJSONArray("readers");
        Map<String, RelayReaderEntry> byID = new HashMap<>();
        entries.forEach(e -> byID.put(e.id, e));
        Set<String> seen = new HashSet<>();
        List<RelayReaderEntry> added = new ArrayList<>();
        for (int i = 0; readers != null && i < readers.length(); i++) {
            JSONObject r = readers.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id", "");
            if (id.isBlank() || findRemoteReader(relay, id) != null) continue;
            seen.add(id);
            RelayReaderEntry entry = byID.get(id);
            if (entry == null) {
                entry = new RelayReaderEntry(id);
                added.add(entry);
            }
            entry.name = r.optString("name", id);
            entry.ageSeconds = r.isNull("updated") ? -1 : Math.max(0, (serverTime - r.optLong("updated", serverTime)) / 1000);
            entry.reading = r.isNull("reading") ? null : r.optBoolean("reading");
            entry.battery = r.isNull("battery") ? -1 : r.optDouble("battery", -1);
        }
        entries.removeIf(e -> !seen.contains(e.id));
        entries.addAll(added);
    }

    /**
     * @return the remote reader with this id on this relay that has already
     * been added, or null
     */
    private static RemoteReader findRemoteReader(PikaRelay relay, String id) {
        for (Reader x : readerList) {
            if (x instanceof RemoteReader r && r.matches(relay, id)) return r;
        }
        return null;
    }

    // A reader in the Add Remote Reader list. Updated in place on refresh.
    private static class RelayReaderEntry {
        final String id;
        String name;
        long ageSeconds = -1;   // -1: the relay has no status from it
        Boolean reading = null; // null: unknown
        double battery = -1;    // -1: unknown

        RelayReaderEntry(String id) {
            this.id = id;
            this.name = id;
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            parts.add(name.equals(id) ? id : name + " (" + id + ")");
            if (ageSeconds < 0) {
                parts.add("never seen");
            } else {
                parts.add(ageSeconds <= RemoteReaderCellController.WARNING_SECONDS ? "online"
                        : "last seen " + RemoteReaderCellController.ago((int) Math.min(ageSeconds, Integer.MAX_VALUE - 1)) + " ago");
            }
            if (reading != null) parts.add(reading ? "reading" : "idle");
            if (battery > 0) parts.add("battery " + Math.round(battery) + "%");
            return String.join(" · ", parts);
        }
    }

    private void changeOutputDir() {
        File selectedDirectory = pikaPrefs.getOutputDir();
        
        // If it has not been set yet, pull from the prefs
        // Default to the user's home directory.
        // The initial directory must exist (Windows throws if it doesn't)
        String initialDir = selectedDirectory != null
                ? selectedDirectory.getAbsolutePath()
                : pikaPrefs.getPreferences().get("outputDir", "");

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setInitialDirectory(PikaDownloaderPrefs.directoryOrHome(initialDir));

        selectedDirectory = directoryChooser.showDialog(outputDirButton.getScene().getWindow());

        if (selectedDirectory != null && selectedDirectory.isDirectory() && selectedDirectory.canWrite()) {
            ouputDirTextField.setText(selectedDirectory.getAbsolutePath());
            logger.debug("Output directory set to {}", selectedDirectory.getAbsolutePath());
            pikaPrefs.setOutputDir(selectedDirectory);
            pikaPrefs.getPreferences().put("outputDir", selectedDirectory.getAbsolutePath());
            refreshOutputDirStatus();
            // Close the files in the old directory, new reads go to the new one
            OutputProcessor.INSTANCE.requestCloseAll();
        }
    }

    // Open the (non-modal) Read Review window, or bring it to the front if
    // it is already open
    private void openReadReview() {
        if (readReviewStage != null) {
            readReviewStage.setIconified(false);
            readReviewStage.toFront();
            return;
        }
        try {
            FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("FXMLReadReview.fxml"));
            Parent root = fxmlLoader.load();
            FXMLReadReviewController controller = fxmlLoader.getController();

            Stage stage = new Stage();
            stage.setTitle("Read Review");
            stage.setScene(new Scene(root));
            if (reviewReadsButton.getScene().getWindow() instanceof Stage mainStage) {
                stage.getIcons().setAll(mainStage.getIcons());
            }
            stage.setOnHidden(event -> {
                controller.stop();
                readReviewStage = null;
            });
            readReviewStage = stage;
            stage.show();
            controller.start();
        } catch (IOException ex) {
            logger.error("Unable to open the Read Review window", ex);
        }
    }

    public void importBibChipMap() {
        
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("FXMLSetupBibMap.fxml"));
        Parent chipMapRoot;
        try {
            
            chipMapRoot = (Parent) fxmlLoader.load();
            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setTitle("Chip to Bib Map Setup");
            stage.setScene(new Scene(chipMapRoot));  
            stage.showAndWait();
        } catch (IOException ex) {
            logger.warn("Error in setupCustomChipMap:",ex);
        }
            
        if (OutputProcessor.INSTANCE.getBibChipMap().isEmpty()) customBibMapToggleSwitch.setSelected(false); 
        else customBibMapToggleSwitch.setSelected(true); 
        syncBibMapState(); // the map may have changed even if the switch did not
        
//        
//        Map chipMap = OutputProcessor.INSTANCE.getBibChipMap();
//
//        FileChooser fileChooser = new FileChooser();
//        File sourceFile;
//        final BooleanProperty chipFirst = new SimpleBooleanProperty(false);
//
//        fileChooser.setTitle("Select Bib -> Chip File");
//
//        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
//
//        fileChooser.getExtensionFilters().addAll(
//                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.csv"),
//                new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
//                new FileChooser.ExtensionFilter("All files", "*")
//        );
//
//        sourceFile = fileChooser.showOpenDialog(customBibMapToggleSwitch.getScene().getWindow());
//        if (sourceFile != null) {
//            try {
//                Optional<String> fs = Files.lines(sourceFile.toPath()).findFirst();
//                String[] t = fs.get().split(",", -1);
//                if (t.length != 2) {
//                    return;
//                }
//
//                if (t[0].toLowerCase().contains("chip")) {
//                    chipFirst.set(true);
//                    logger.debug("Found a chip -> bib file");
//                } else if (t[0].toLowerCase().contains("bib")) {
//                    chipFirst.set(false);
//                    logger.debug("Found a bib -> chip file");
//                } else {
//                    chipMap.put(t[1], t[0]);
//                    chipFirst.set(false);
//                    logger.debug("No header in file. Assuming bib -> chip.");
//                    logger.trace("Mapped chip " + t[1] + " to " + t[0]);
//                }
//                Files.lines(sourceFile.toPath())
//                        .map(s -> s.trim())
//                        .filter(s -> !s.isEmpty())
//                        .skip(1)
//                        .forEach(s -> {
//                            //System.out.println("readOnce read " + s); 
//                            String[] tokens = s.split(",", -1);
//                            if (tokens.length != 2) {
//                                return;
//                            }
//                            if (chipFirst.get()) {
//                                chipMap.put(tokens[0], tokens[1]);
//                                logger.trace("Mapped chip " + tokens[0] + " to " + tokens[1]);
//                            } else {
//                                chipMap.put(tokens[1], tokens[0]);
//                                logger.trace("Mapped chip " + tokens[1] + " to " + tokens[0]);
//                            }
//                        });
//                logger.debug("Found a total of " + chipMap.size() + " mappings");
//
//            } catch (IOException ex) {
//                logger.warn(ex.getMessage());
//            }
//        }
    }

    // TODO: Change this to a record
    private static class DiscoveredLocalReader {

        @Override
        public int hashCode() {
            int hash = 7 + IP.hashCode() + UnitName.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final DiscoveredLocalReader other = (DiscoveredLocalReader) obj;
            if (!this.IP.getValueSafe().equals(other.IP.getValueSafe())) {
                return false;
            }
            return this.UnitName.getValueSafe().equals(other.UnitName.getValueSafe());
        }
        public StringProperty IP = new SimpleStringProperty();
        public StringProperty UnitName = new SimpleStringProperty();
        public StringProperty PORT = new SimpleStringProperty();
        // https:// and wss:// (only for a reader entered by hand)
        public boolean secure = false;

        public DiscoveredLocalReader() {

        }

        public DiscoveredLocalReader(String host) {
            IP.set(host);
        }

        public StringProperty ipProperty() {
            return IP;
        }

        public StringProperty macProperty() {
            return UnitName;
        }

        public StringProperty portProperty() {
            return PORT;
        }

        @Override
        public String toString() {
            return UnitName.getValueSafe() + " (" + IP.getValueSafe() + ")";
        }
    }
}
