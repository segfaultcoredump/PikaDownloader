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

import java.util.Optional;
import java.util.prefs.Preferences;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.controlsfx.control.ToggleSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */
public class LocalReaderCellController {
    static final Logger logger = LoggerFactory.getLogger(LocalReaderCellController.class);

    private static final Preferences prefs = PikaReceiverPrefs.INSTANCE.getPreferences();
    
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
    
    @FXML ToggleSwitch outputFileToggleSwitch;
    @FXML TextField outputFileTextField;
    @FXML Button rewindButton;
    @FXML Button startReaderButton;
    @FXML Label readCountLabel;
    @FXML Label lastReadLabel;
    @FXML Label ipLabel;
    
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
            if (newValue) {
                if (!titledPane.getStyleClass().contains("error-pane")) {
                    titledPane.getStyleClass().add("error-pane");
                }
            } else {
                titledPane.getStyleClass().remove("error-pane");
            }
        });
        
        

    } 
    
    public void setReader(LocalReader r){
        reader = r;
        
        ipLabel.setText(reader.getReaderIPProperty().getValueSafe());
        ipLabel.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                //HostServices hostServices = getHostServices();
                //hostServices.showDocument("http://" + reader.reader_ip + ":8080");
            }
        });
        
        // Name and Location
        
        unitNameLabel.textProperty().bind(reader.getReaderNameProperty());
        
        // Set the titledPane display
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
        titledPane.setGraphic(titledPaneContent);
        
        // Colors chosen to work with colorblind (red/green and full monochrome) viewers
        
        // Battery Level
        
        batteryLevelProgressBar.progressProperty().addListener((observable, oldValue, newValue) -> {
            double progress = newValue.doubleValue();
            logger.debug("Battery level at {}",progress);
            if (progress < 0) battHBox.setVisible(false);
            else battHBox.setVisible(true);
            
            if (progress < 0.25) {
                // Under 10%: Charcoal Grey
                batteryLevelProgressBar.setStyle("-fx-accent: #222222;");
            } else if (progress > 0.50) {
                // Over 50%: Deep Blue
                batteryLevelProgressBar.setStyle("-fx-accent: #00A37A;");
            } else {
                // Between 25% and 50%: Dark Orange
                batteryLevelProgressBar.setStyle("-fx-accent: #D55E00;");
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
            logger.debug("Reader updated Property listener fired " + o + " -> " + u);
            if (u.intValue() < 5) {
                onlineStatusCircle.setFill(Color.web("#00A37A")); // Vibrant Mint Teal
                onlineStatusLabel.textProperty().setValue("Connected:");
            } else if (u.intValue() < 15) {
                onlineStatusCircle.setFill(Color.web("#D55E00")); // Dark Orange
                onlineStatusLabel.textProperty().setValue("Last Seen: " + u);
            } else {
                onlineStatusCircle.setFill(Color.web("#222222")); // Charcoal Grey
                onlineStatusLabel.textProperty().setValue("Disconnected:");
            }
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
        
        startReaderButton.setOnAction(event -> {
            toggleReading();
        });
        
        rewindButton.setOnAction(event -> {
            rewind();
        });
        
        // enter an error state if the battery is below 25 or 
        // we have not heard from the unit in 15 seconds
        errorState.bind(
            reader.getLastUpdatedProperty().greaterThan(15)
            .or(
                reader.getBatteryProperty().greaterThanOrEqualTo(0)
                .and(reader.getBatteryProperty().lessThanOrEqualTo(25))
            )
        );
        
    }

    private void toggleReading() {
        Boolean currentReadingStatus = reader.getReadingProperty().getValue();
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
        if (result.get() == ButtonType.OK) {
            // send the stop or start command...
            if (currentReadingStatus) reader.stopReader();
            else reader.startReader();
        }
    }

    private void rewind(){
         reader.rewind();
    }
    
}
