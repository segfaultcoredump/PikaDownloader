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
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;
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
import org.controlsfx.control.ToggleSwitch;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */
public class LocalReaderCellController {
    static final Logger logger = LoggerFactory.getLogger(LocalReaderCellController.class);

    private static final Preferences prefs = PikaDownloaderPrefs.INSTANCE.getPreferences();

    // Path separators and characters that are illegal in a file name on any platform
    private static final Pattern INVALID_FILE_NAME_CHARS = Pattern.compile("[/\\\\:*?\"<>|\\p{Cntrl}]");

    // Error state thresholds: no STATUS for this many seconds, or a battery
    // level (0-1) at or below this
    private static final int OFFLINE_SECONDS = 10;
    private static final double LOW_BATTERY = 0.20;

    // Room for the TitledPane's arrow and title padding, so the title
    // graphic can span the rest of the title bar
    private static final double TITLE_ARROW_ALLOWANCE = 40;

    private static final String errorCSS = """
        /* Custom error style for TitledPane */
            .titled-pane.error-pane > .title {
                -fx-background-color: #ffcccc;      /* Light red background */
                -fx-border-color: #cc0000;          /* Dark red border */
                -fx-border-width: 0 0 1 0;          /* Bottom border only */
            }

            /* Optional: Make the title text dark red for readability */
            .titled-pane.error-pane > .title > .text {
                -fx-fill: #cc0000;
            }

            /* Optional: Make the collapse/expand arrow dark red */
            .titled-pane.error-pane > .title > .arrow-button > .arrow {
                -fx-background-color: #cc0000;
            }    
    """;                                                                      
//            + ".error {"
//            + "    -fx-text-box-border: #FF0000;"
//            + "    -fx-focus-color: #FF0000;"
//            + "    -fx-control-inner-background: #FFF5F5;"
//            + "    -fx-background-color: #ffcccc;" 
//            + "    -fx-border-color: #cc0000;"
//            + "    -fx-fill: #cc0000;"
//            + "}"


    LocalReader reader = null;
    
    @FXML VBox rootVBox;
    
    @FXML TitledPane titledPane;
    
    @FXML Label unitNameLabel;
    
    @FXML HBox battHBox;
    @FXML ProgressBar batteryLevelProgressBar;
    
    @FXML Label onlineStatusLabel;
    @FXML Circle onlineStatusCircle;
    
    @FXML Label readStatusLabel;
    @FXML Circle readStatusCircle;

    @FXML Label rfidProblemLabel;

    @FXML HBox profileHBox;
    @FXML ChoiceBox<ReaderProfile> profileChoiceBox;

    // The ChoiceBox gets its own copy of the reader's profile list so that
    // every programmatic update happens under the syncingProfile guard and
    // never looks like the user picking a new profile
    private final ObservableList<ReaderProfile> profileChoices = FXCollections.observableArrayList();
    private final BooleanProperty profileChangeInProgress = new SimpleBooleanProperty(false);
    private boolean syncingProfile = false;

    @FXML ToggleSwitch outputFileToggleSwitch;
    @FXML TextField outputFileTextField;
    @FXML Button rewindButton;
    @FXML Button startReaderButton;
    @FXML Label readCountLabel;
    @FXML Label lastReadLabel;
    @FXML Label ipLabel;
    
    @FXML Button disconnectButton;
    @FXML Button removeButton;

    // Read alert (in the Advanced pane)
    @FXML Slider alertToneSlider;
    @FXML Label alertToneLabel;
    @FXML CheckBox alertMuteCheckBox;

    BooleanProperty errorState = new SimpleBooleanProperty(false);
    
//    UnaryOperator<Change> filter = change -> {
//        String text = change.getText();
//        if (text.matches("[A-Za-z0-9-.]*")) {
//            return change;
//        }
//        return null;
//    };
//    TextFormatter<String> textFormatter = new TextFormatter<>(filter);

     
    public void initialize() {


        rootVBox.getStylesheets().add("data:text/css," + errorCSS.replaceAll(" ", "%20"));
        
        // Turn the TitlePane red if we have an issue
        errorState.addListener((observable, oldValue, newValue) -> {
            logger.debug("New errorState value for {}: {}",unitNameLabel.textProperty().getValue(),newValue);
            logger.trace("Error Stats: Battery: {}  Connection: {}  RFID: {}",reader.getBatteryProperty().getValue(),reader.getLastUpdatedProperty().getValue(),reader.getRFIDReaderProblemProperty().getValue());
            if (newValue) {
                if (!titledPane.getStyleClass().contains("error-pane")) {
                    titledPane.getStyleClass().add("error-pane");
                }
            } else {
                titledPane.getStyleClass().remove("error-pane");
            }
        });
        
        disconnectButton.setOnAction(x -> {
            if (reader != null) {
                if (! reader.isEnabled().get()) {
                    reader.enable();
                    disconnectButton.textProperty().setValue("Disconnect");
                } else {
                    reader.disable();
                    disconnectButton.textProperty().setValue("Reconnect");
                }
            }
        });
        
        removeButton.setOnAction(x -> {
            if (reader != null) {
                reader.disable();
                PikaDownloaderPrefs.INSTANCE.getReaderList().remove(reader);
            }        
        });

    } 
    
    public void setReader(LocalReader r){
        reader = r;
        
        ipLabel.setText(reader.getReaderIPProperty().getValueSafe());
        ipLabel.setOnMouseClicked(event -> {
            logger.debug("ipLabel Click event detected: {} and {}",event.getButton(),event.getClickCount());
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                HostServices hostServices = PikaDownloaderPrefs.INSTANCE.getApplication().getHostServices();
                String url = "http://" + reader.getReaderIPProperty().getValue() + ":" +  reader.getReaderPortProperty().getValue();
                logger.debug("Opening {}",url);
                hostServices.showDocument(url);
            }
        });
        
        // Name and Location
        
        unitNameLabel.textProperty().bind(reader.getReaderNameProperty());
        unitNameLabel.setOnMouseClicked(event -> {
            logger.debug("unitNameLabel Click event detected: {} and {}",event.getButton(),event.getClickCount());
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                HostServices hostServices = PikaDownloaderPrefs.INSTANCE.getApplication().getHostServices();
                String url = "http://" + reader.getReaderNameProperty().getValue() + ".local:" +  reader.getReaderPortProperty().getValue();
                logger.debug("Opening {}",url);
                hostServices.showDocument(url);
            }
        });
        
        // Set the titledPane display
        // Line 1: name and reading status
        // Line 2 (only while collapsed): why the reader is in an error state,
        // and the battery level
        HBox titledPaneContent = new HBox();
        Label titledPaneLabel = new Label();
        titledPaneLabel.textProperty().bind(reader.getReaderNameProperty());

        Label titledPaneReadingStatusLabel = new Label();
        titledPaneReadingStatusLabel.textProperty().bind(readStatusLabel.textProperty());

        Pane titledPaneSpring = new Pane();
        HBox.setHgrow(titledPaneSpring, Priority.ALWAYS);
        titledPaneSpring.setMaxWidth(Double.MAX_VALUE);

        titledPaneContent.getChildren().addAll(titledPaneLabel,titledPaneSpring,titledPaneReadingStatusLabel);
        titledPaneContent.setAlignment(Pos.CENTER_LEFT);

        StringBinding titleStatus = Bindings.createStringBinding(this::titleStatusText,
                reader.isEnabled(),
                reader.getLastUpdatedProperty(),
                reader.getBatteryProperty(),
                reader.getRFIDReaderProblemProperty());
        Label titleStatusLabel = new Label();
        titleStatusLabel.getStyleClass().add("reader-status-label");
        titleStatusLabel.setMinWidth(0); // truncate rather than widen the window
        titleStatusLabel.textProperty().bind(titleStatus);
        BooleanBinding showTitleStatus = titledPane.expandedProperty().not().and(titleStatus.isNotEmpty());
        titleStatusLabel.visibleProperty().bind(showTitleStatus);
        titleStatusLabel.managedProperty().bind(showTitleStatus);

        // The TitledPane lays the graphic out at its pref width, so stretch
        // it across the title bar to let the spring push the reading status
        // to the right
        VBox titleVBox = new VBox(titledPaneContent, titleStatusLabel);
        titleVBox.setMinWidth(0);
        titleVBox.prefWidthProperty().bind(titledPane.widthProperty().subtract(TITLE_ARROW_ALLOWANCE));
        // A TitledPane uses its title's preferred width as its minimum width.
        // The title now follows the pane's own width, so without this the
        // pane's minimum (and the list cell, see ReaderListCell) would creep
        // wider on every update whenever the allowance is a bit short (the
        // arrow and padding scale with the font size)
        titledPane.setMinWidth(0);
        titledPane.setGraphic(titleVBox);
        titledPane.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        // RFID reader problems (only shown while we are hearing from the unit,
        // otherwise they are stale)
        BooleanBinding online = reader.isEnabled().and(reader.getLastUpdatedProperty().lessThanOrEqualTo(OFFLINE_SECONDS));
        BooleanBinding showRFIDProblem = online.and(reader.getRFIDReaderProblemProperty().isNotEmpty());
        rfidProblemLabel.textProperty().bind(reader.getRFIDReaderProblemProperty());
        rfidProblemLabel.visibleProperty().bind(showRFIDProblem);
        rfidProblemLabel.managedProperty().bind(showRFIDProblem);

        // Battery Level
        batteryLevelProgressBar.progressProperty().addListener((observable, oldValue, newValue) -> {
            double progress = newValue.doubleValue();
            logger.trace("Battery level at {}",progress);
            if (progress < 0) battHBox.setVisible(false);
            else battHBox.setVisible(true);
            
            if (progress < 0.20) {
                // Under 20%: Red
                batteryLevelProgressBar.setStyle("-fx-accent: #FF3300;");
            } else if (progress > 0.50) {
                // Over 50%: // Vibrant Mint Teal
                batteryLevelProgressBar.setStyle("-fx-accent: #00A37A;");
            } else {
                // Between 25% and 50%: Yellow
                batteryLevelProgressBar.setStyle("-fx-accent: #dede17;");
            }
        });
        batteryLevelProgressBar.progressProperty().bind(reader.getBatteryProperty());
        
        // Reading status
        if (reader.getReadingProperty().getValue()) {
            readStatusCircle.setFill(Color.web("#00A37A")); // Vibrant Mint Teal
            startReaderButton.setText("Stop Reader");
            readStatusLabel.textProperty().setValue("Reader: Reading");
        } else {
            readStatusCircle.setFill(Color.web("#222222")); // Charcoal Grey
            startReaderButton.setText("Start Reader");
            readStatusLabel.textProperty().setValue("Reader: Idle");
        }
        reader.getReadingProperty().addListener((ov,o,n) -> {
            // Set the indicator circle color
            
            if (n) {
                readStatusCircle.setFill(Color.web("#00A37A")); // Vibrant Mint Teal
                startReaderButton.setText("Stop Reader");
                readStatusLabel.textProperty().setValue("Reader: Reading");
            } else {
                readStatusCircle.setFill(Color.web("#222222")); // Charcoal Grey
                startReaderButton.setText("Start Reader");
                readStatusLabel.textProperty().setValue("Reader: Idle");
            } 

        });
        
        // Read Count
        readCountLabel.textProperty().bind(reader.getReadCountProperty().asString());
        lastReadLabel.textProperty().bind(reader.getLastReadProperty());
        
        // Last Updated
        onlineStatusCircle.setFill(Color.web("#00A37A")); // Vibrant Mint Teal

        reader.getLastUpdatedProperty().addListener((ov, o, u) -> {
            logger.trace("Reader updated Property listener fired {} -> {}", o, u);
            if (u.intValue() < 2) {
                onlineStatusCircle.setFill(Color.web("#00A37A")); // Vibrant Mint Teal
                onlineStatusLabel.textProperty().setValue("Connected:");
            } else if (u.intValue() < 10) {
                onlineStatusCircle.setFill(Color.web("#FFFF33")); // Yellow
                onlineStatusLabel.textProperty().setValue("Last Seen: " + u);
            } else {
                onlineStatusCircle.setFill(Color.web("#FF3300")); // Red
                onlineStatusLabel.textProperty().setValue("Disconnected:");
            }
        });
        
        // Reader Profile
        // Hidden if the reader is running an older PikaReader without profiles
        profileHBox.visibleProperty().bind(reader.getProfileSupportedProperty());
        profileHBox.managedProperty().bind(reader.getProfileSupportedProperty());

        profileChoiceBox.setItems(profileChoices);

        // Visible but not changeable while reading
        profileChoiceBox.disableProperty().bind(
            reader.getReadingProperty()
            .or(profileChangeInProgress)
            .or(reader.isEnabled().not())
            .or(Bindings.isEmpty(profileChoices))
        );

        // Tooltips are not shown on disabled controls, so put it on the HBox
        Tooltip profileTooltip = new Tooltip();
        profileTooltip.textProperty().bind(
            Bindings.when(reader.getReadingProperty())
                .then("Stop the reader to change the profile")
                .otherwise("Select a reader profile")
        );
        Tooltip.install(profileHBox, profileTooltip);

        // Keep the display in sync with the reader, including changes made
        // directly on the unit (via the STATUS messages)
        syncProfileList();
        reader.getProfiles().addListener((ListChangeListener.Change<? extends ReaderProfile> c) -> syncProfileList());
        reader.getProfileIDProperty().addListener((ov, o, n) -> syncProfileSelection());

        // The user picked a profile
        profileChoiceBox.valueProperty().addListener((ov, o, n) -> {
            if (syncingProfile || n == null) return;
            if (n.id().equals(reader.getProfileIDProperty().getValueSafe())) return;
            // Let the ChoiceBox popup close before we prompt
            Platform.runLater(() -> promptProfileChange(n));
        });

        // Not quite as clean as simply binding the values, but it helps
        outputFileToggleSwitch.setSelected(reader.getOutputToFileProperty().getValue());
        outputFileToggleSwitch.selectedProperty().addListener(a -> {
            reader.getOutputToFileProperty().set(outputFileToggleSwitch.isSelected());
        });
              
        outputFileTextField.textProperty().setValue(r.getOutputFileProperty().getValueSafe());
        
        logger.debug("Setting default output file for local reader {} to {}",reader.getReaderNameProperty().getValueSafe(), r.getOutputFileProperty().getValueSafe());
        // Liten for changes and update the prefs as it changes
        outputFileTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            logger.trace("Output File Update for {} -> {}", reader.getReaderNameProperty().getValueSafe(), newValue);
            if (!oldValue.equals(newValue)) {
                outputFileToggleSwitch.setSelected(false);
                reader.getOutputFileProperty().setValue(newValue);
            }
        });

        // Flag bad file names (path separators, illegal characters) and
        // don't allow the output to be turned on until it is fixed
        BooleanBinding validFileName = Bindings.createBooleanBinding(
                () -> isValidFileName(outputFileTextField.getText()),
                outputFileTextField.textProperty());
        outputFileToggleSwitch.disableProperty().bind(validFileName.not());
        validFileName.addListener((ov, o, valid) -> updateOutputFileState(valid));
        updateOutputFileState(validFileName.get());

        startReaderButton.setOnAction(event -> {
            toggleReading();
        });
        
        rewindButton.setOnAction(event -> {
            rewind();
        });
        
        
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

        // Play a sample when they let go of the slider. A filter, since the
        // slider's thumb may consume the release.
        alertToneSlider.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && !reader.getAlertMutedProperty().get()) {
                ReadAlert.INSTANCE.play(reader.getAlertToneProperty().get());
            }
        });

        // Dragging the slider must never start a drag-to-reorder of the reader
        // list (ReaderListCell), which would cut the slider drag short. Today
        // the reader TitledPane's skin already consumes the mouse events that
        // bubble up to it, so this is a backstop in case that changes.
        alertToneSlider.setOnDragDetected(Event::consume);

        // enter an error state if the battery is at or below 20%,
        // we have not heard from the unit in 10 seconds, or one of its
        // RFID readers is not connected,
        // unless the unit is disabled, then we don't care
        // (keep in sync with titleStatusText())
        errorState.bind(
            reader.isEnabled().and(
            reader.getLastUpdatedProperty().greaterThan(OFFLINE_SECONDS)
            .or(
                reader.getBatteryProperty().greaterThanOrEqualTo(0.0)
                .and(reader.getBatteryProperty().lessThanOrEqualTo(LOW_BATTERY))
            )
            .or(reader.getRFIDReaderProblemProperty().isNotEmpty()))
        );

    }

    // The collapsed title's second line: the reasons for an error state
    // first, then the battery level, e.g. "Disconnected · Low Battery: 15%".
    // "" hides the line.
    private String titleStatusText() {
        // The user disconnected it, not an error
        if (!reader.isEnabled().get()) return "Manually disconnected";

        List<String> parts = new ArrayList<>();
        if (reader.getLastUpdatedProperty().get() > OFFLINE_SECONDS) {
            parts.add("Disconnected");
        } else {
            // Only current while we are hearing from the unit
            String rfidProblem = reader.getRFIDReaderProblemProperty().getValueSafe();
            if (!rfidProblem.isBlank()) parts.add(rfidProblem);
        }

        // Below 0: the unit does not report a battery level (or no STATUS yet).
        // The last known level is kept while disconnected, as a dead
        // battery is the usual reason.
        double battery = reader.getBatteryProperty().get();
        if (battery >= 0) {
            parts.add((battery <= LOW_BATTERY ? "Low Battery: " : "Battery: ") + Math.round(battery * 100) + "%");
        }
        return String.join(" · ", parts);
    }

    // Valid: not blank, not . or .., and no path separators or characters
    // that are illegal in a file name (on any platform)
    static boolean isValidFileName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.equals(".") || name.equals("..")) return false;
        return !INVALID_FILE_NAME_CHARS.matcher(name).find();
    }

    private void updateOutputFileState(boolean valid) {
        String name = outputFileTextField.getText();
        // Blank is not an error (just no output file yet), so only flag real problems
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

    private void toggleReading() {
        boolean currentReadingStatus = reader.getReadingProperty().getValue();
        Alert alert = new Alert(AlertType.CONFIRMATION);
        
        if (currentReadingStatus == true) {
            alert.setTitle("Stop Reader");
            alert.setHeaderText("Stop Reader");
            alert.setContentText("This will stop the reader.\nAre you ok with this?");
        } else {
            alert.setTitle("Start Reader");
            alert.setHeaderText("Start Reader");
            alert.setContentText("This will start the reader.\nAre you ok with this?");
        }
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        // send the stop or start command...
        String action = currentReadingStatus ? "stop" : "start";
        startReaderButton.setDisable(true);
        CompletableFuture<?> request = currentReadingStatus ? reader.stopReader() : reader.startReader();
        request.whenComplete((response, ex) -> Platform.runLater(() -> {
            startReaderButton.setDisable(false);
            if (ex != null) {
                String msg = LocalReader.errorMessage(ex);
                logger.warn("Unable to {} {}: {}", action, reader.getReaderNameProperty().getValueSafe(), msg);
                Alert error = new Alert(AlertType.ERROR);
                error.setTitle(currentReadingStatus ? "Stop Reader" : "Start Reader");
                error.setHeaderText("Unable to " + action + " " + reader.getReaderNameProperty().getValueSafe());
                error.setContentText(msg);
                error.showAndWait();
            }
        }));
    }

    private void rewind(){
         reader.rewind();
    }

    // Copy the reader's profile list into the ChoiceBox and select the current one
    private void syncProfileList() {
        syncingProfile = true;
        try {
            profileChoices.setAll(reader.getProfiles());
        } finally {
            syncingProfile = false;
        }
        syncProfileSelection();
    }

    // Select the profile the reader is currently using
    private void syncProfileSelection() {
        syncingProfile = true;
        try {
            String currentID = reader.getProfileIDProperty().getValueSafe();
            ReaderProfile current = profileChoices.stream()
                    .filter(p -> p.id().equals(currentID))
                    .findFirst()
                    .orElse(null);
            profileChoiceBox.setValue(current);
        } finally {
            syncingProfile = false;
        }
    }

    private void promptProfileChange(ReaderProfile target) {
        String currentName = reader.getProfileNameProperty().getValueSafe();

        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("Change Reader Profile");
        alert.setHeaderText("Change Reader Profile");
        String content = "Change the reader profile for " + reader.getReaderNameProperty().getValueSafe()
                + "\nfrom " + currentName + " to " + target.name() + "?";
        if (target.description() != null && !target.description().isBlank()) {
            content += "\n\n" + target.name() + ": " + target.description();
        }
        alert.setContentText(content);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            syncProfileSelection(); // revert the ChoiceBox
            return;
        }

        profileChangeInProgress.set(true);
        reader.changeProfile(target.id()).whenComplete((response, ex) -> {
            Platform.runLater(() -> {
                profileChangeInProgress.set(false);
                syncProfileSelection();

                if (ex != null) {
                    String msg = LocalReader.errorMessage(ex);
                    logger.warn("Unable to change the profile for {} to {}: {}", reader.getReaderNameProperty().getValueSafe(), target.id(), msg);

                    Alert error = new Alert(AlertType.ERROR);
                    error.setTitle("Change Reader Profile");
                    error.setHeaderText("Unable to change the reader profile");
                    error.setContentText(msg);
                    error.showAndWait();
                    return;
                }

                // Show any warnings (e.g. a reader model not suited to this profile)
                List<String> warnings = profileWarnings(response);
                if (!warnings.isEmpty()) {
                    Alert warning = new Alert(AlertType.WARNING);
                    warning.setTitle("Change Reader Profile");
                    warning.setHeaderText("Profile changed to " + reader.getProfileNameProperty().getValueSafe());
                    warning.setContentText(String.join("\n", warnings));
                    warning.showAndWait();
                }
            });
        });
    }

    // {"readers":[{"id":1,"settings":{"warnings":["..."]}},...]}
    private List<String> profileWarnings(JSONObject response) {
        List<String> warnings = new ArrayList<>();
        if (response == null) return warnings;
        JSONArray readers = response.optJSONArray("readers");
        if (readers == null) return warnings;
        for (int i = 0; i < readers.length(); i++) {
            JSONObject r = readers.optJSONObject(i);
            if (r == null) continue;
            JSONObject settings = r.optJSONObject("settings");
            if (settings == null) continue;
            JSONArray w = settings.optJSONArray("warnings");
            if (w == null) continue;
            for (int j = 0; j < w.length(); j++) {
                String s = w.optString(j, "");
                if (!s.isBlank()) warnings.add(s);
            }
        }
        return warnings;
    }
    
}
