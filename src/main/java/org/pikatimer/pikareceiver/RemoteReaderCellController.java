/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikareceiver;

import java.io.IOException;
import static java.lang.Integer.MAX_VALUE;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextFormatter.Change;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.controlsfx.control.ToggleSwitch;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */
public class RemoteReaderCellController {
    static final Logger logger = LoggerFactory.getLogger(RemoteReaderCellController.class);

    private static final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    RemoteReader reader = null;
    @FXML TitledPane titledPane;
    @FXML Label macLabel;
    @FXML Label locationLabel;
    @FXML Label unitNameLabel;
    @FXML ProgressBar batteryLevelProgressBar;
    @FXML Circle onlineSstatusCircle;
    @FXML Circle readStatusCircle;
    @FXML ToggleSwitch outputFileToggleSwitch;
    @FXML TextField outputFileTextField;
    @FXML Button rewindButton;
    @FXML Button startReaderButton;
    @FXML Label readCountLabel;
    @FXML Label lastReadLabel;
    
    UnaryOperator<Change> filter = change -> {
        String text = change.getText();
        if (text.matches("[A-Za-z0-9-.]*")) {
            return change;
        }
        return null;
    };
    TextFormatter<String> textFormatter = new TextFormatter<>(filter);

     
    public void initialize() {
        locationLabel.setOnMouseClicked( event -> {
            if(event.getButton().equals(MouseButton.PRIMARY)){
                if(event.getClickCount() == 2){
                    updateLocationText();
                }
            }
        });
        unitNameLabel.setOnMouseClicked( event -> {
            if(event.getButton().equals(MouseButton.PRIMARY)){
                if(event.getClickCount() == 2){
                    updateUnitNameText();
                }
            }
        });

        outputFileTextField.setTextFormatter(textFormatter);
        
        outputFileToggleSwitch.disableProperty().bind(outputFileTextField.textProperty().isEmpty());
    } 
    
    public void setReader(RemoteReader r){
        reader = r;
        // MAC address
        String mac = reader.getReaderIDProperty().getValueSafe();
        macLabel.textProperty().set(mac.substring(0, 2) + ":" + mac.substring(2, 4)+ ":" + mac.substring(4, 6));
        
        // Name and Location
        locationLabel.textProperty().bind(reader.getReaderLocationProperty());
        unitNameLabel.textProperty().bind(reader.getReaderNameProperty());
        titledPane.textProperty().bind(Bindings.concat(reader.getReaderNameProperty(),": ",reader.getReaderLocationProperty()));
        
        // Battery Level
        batteryLevelProgressBar.progressProperty().bind(reader.getBatteryProperty().divide(100.0f));
        
        // Reading status
        if (reader.getReadingProperty().getValue()) {
            readStatusCircle.setFill(Color.LIGHTGREEN);
            startReaderButton.setText("Stop Reader");
        }
        else {
            readStatusCircle.setFill(Color.RED);
            startReaderButton.setText("Start Reader");
        }
        reader.getReadingProperty().addListener((ov,o,n) -> {
            // Set the indicator circle color
            if (n == true) readStatusCircle.setFill(Color.LIGHTGREEN);
            else readStatusCircle.setFill(Color.RED);
            
            // Set the test on the 
            if (n == true) startReaderButton.setText("Stop Reader");
            else startReaderButton.setText("Start Reader");
        });
        
        // Read Count
        readCountLabel.textProperty().bind(reader.getReadCountProperty().asString());
        lastReadLabel.textProperty().bind(reader.getLastReadProperty());
        
        // Last Updated
        Integer u = reader.getUpdatedProperty().getValue();
        if (u < 20) {
            onlineSstatusCircle.setFill(Color.LIGHTGREEN);
        } else if (u > 120) {
            onlineSstatusCircle.setFill(Color.RED);
        } else {
            onlineSstatusCircle.setFill(Color.YELLOW);
        }
        reader.getUpdatedProperty().addListener((ov, o, n) -> {
            logger.trace("Reader updated Property listener fired " + o + " -> " + n);
            if (n.intValue() < 20) {
                onlineSstatusCircle.setFill(Color.LIGHTGREEN);
            } else if (n.intValue() > 120) {
                onlineSstatusCircle.setFill(Color.RED);
            } else {
                onlineSstatusCircle.setFill(Color.YELLOW);
            }
        });
        
        // Not quite as clean as simply binding the values, but it helps 
        outputFileToggleSwitch.setSelected(reader.getOutputToFileProperty().getValue());
        outputFileToggleSwitch.selectedProperty().addListener(a -> {
            reader.getOutputToFileProperty().set(outputFileToggleSwitch.isSelected());
        });
        
        outputFileTextField.textProperty().setValue(r.getOutputFileProperty().getValueSafe());
        outputFileTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            logger.trace("Output File Update for " + mac + " -> " + newValue);
            if (!oldValue.equals(newValue)) outputFileToggleSwitch.setSelected(false);
            reader.getOutputFileProperty().setValue(newValue);
        });
        
        startReaderButton.setOnAction(event -> {
            toggleReading();}
        );
        
        rewindButton.setOnAction(event -> {
            rewind();}
        );
        
    }

    private void updateLocationText() {
        TextInputDialog dialog = new TextInputDialog(reader.getReaderLocationProperty().getValueSafe());
        dialog.setTitle("Change Reader Location");
        dialog.setHeaderText("Update Location for " + reader.getReaderNameProperty().getValueSafe());
        dialog.setContentText("New Location:");

        // Traditional way to get the response value.
        Optional<String> result = dialog.showAndWait();
        if (result.isPresent()){
            logger.debug("Changing reader " + reader.getReaderIDProperty().getValueSafe() + " location string to " + result.get());
            
             JSONObject command = new JSONObject();
            command.put("mac", reader.getReaderIDProperty().getValueSafe());
            command.put("rename_location", result.get());
            command.put("location", result.get());
        
            String endpoint = PikaReceiverPrefs.getInstance().getEchoEndpoint();
            
            logger.debug("Posting to " + endpoint + "commands/ : \n " + command.toString(4));

            HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
                .uri(URI.create(endpoint + "command/"))
                .setHeader("User-Agent", "Echo Transmitter") // add request header
                .header("Content-Type", "application/json")
                .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                // print status code
                logger.trace("remoteReaderCellController::updateLocationText Response Code: " + Integer.toString(response.statusCode()));
                logger.trace("remoteReaderCellController::updateLocationText Response Body: " + response.body());   
                Alert confAlert = new Alert(AlertType.INFORMATION);
                confAlert.setTitle("Request Sent");
                confAlert.setContentText("Rename Request Sent");
                confAlert.setHeaderText(null);

                confAlert.showAndWait();
            } catch (IOException | InterruptedException ex) {
                logger.error(ex.getMessage());
            }
        }
    }

    private void updateUnitNameText() {
        TextInputDialog dialog = new TextInputDialog(reader.getReaderNameProperty().getValueSafe());
        dialog.setTitle("Change Reader Name");
        dialog.setHeaderText("Update Name for "+ reader.getReaderIDProperty().getValueSafe());
        dialog.setContentText("New Name:");

        // Traditional way to get the response value.
        Optional<String> result = dialog.showAndWait();
        if (result.isPresent()){
            logger.debug("Changing reader " + reader.getReaderIDProperty().getValueSafe() + " location string to " + result.get());
            
             JSONObject command = new JSONObject();
            command.put("mac", reader.getReaderIDProperty().getValueSafe());
            command.put("rename_reader", result.get());
            command.put("location", result.get());
        
            String endpoint = PikaReceiverPrefs.getInstance().getEchoEndpoint();
            
            logger.debug("Posting to " + endpoint + "commands/ : \n " + command.toString(4));

            HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
                .uri(URI.create(endpoint + "command/"))
                .setHeader("User-Agent", "Echo Transmitter") // add request header
                .header("Content-Type", "application/json")
                .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                // print status code
                logger.trace("remoteReaderCellController::updateLocationText Response Code: " + Integer.toString(response.statusCode()));
                logger.trace("remoteReaderCellController::updateLocationText Response Body: " + response.body());   
                Alert confAlert = new Alert(AlertType.INFORMATION);
                confAlert.setTitle("Request Sent");
                confAlert.setContentText("Rename Request Sent");
                confAlert.setHeaderText(null);

                confAlert.showAndWait();
            } catch (IOException | InterruptedException ex) {
                logger.error(ex.getMessage());
            }
        }
    }

    private void toggleReading() {
        Boolean currentStatus = reader.getReadingProperty().getValue();
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("Start/Stop Reader");
        
        JSONObject command = new JSONObject();
        command.put("mac", reader.getReaderIDProperty().getValueSafe());
        if (currentStatus == true) {
            alert.setHeaderText("Stop Remote Reader");
            alert.setContentText("This will stop the remote reader.\nAre you ok with this?");
            command.put("command", "STOP");
        } else {
            alert.setHeaderText("Start Remote Reader");
            alert.setContentText("This will start the remote reader.\nAre you ok with this?");
            command.put("command", "START");
        }
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.get() == ButtonType.OK){
            // send the stop or start command...
            String endpoint = PikaReceiverPrefs.getInstance().getEchoEndpoint();
            
            logger.trace("Posting to " + endpoint + "commands/ : \n " + command.toString(4));

            HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
                .uri(URI.create(endpoint + "command/"))
                .setHeader("User-Agent", "Echo Transmitter") // add request header
                .header("Content-Type", "application/json")
                .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                // print status code
                logger.trace("remoteReaderCellController::toggleReadingProperty Response Code: " + Integer.toString(response.statusCode()));
                logger.trace("remoteReaderCellController::toggleReadingProperty Response Body: " + response.body());   
                Alert confAlert = new Alert(AlertType.INFORMATION);
                confAlert.setTitle("Request Sent");
                confAlert.setContentText("Request Sent");
                confAlert.setHeaderText(null);

                confAlert.showAndWait();
            } catch (IOException | InterruptedException ex) {
                logger.error(ex.getMessage());
            }
        }
    }

    private void rewind(){
        // open a dialog box 
        Dialog<RewindData> dialog = new Dialog();
        dialog.setTitle("Rewind");
        dialog.setHeaderText("Rewind timing data...");
        ButtonType rewindButtonType = new ButtonType("Rewind", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(rewindButtonType, ButtonType.CANCEL);
        
        VBox rewindVBox = new VBox();
        rewindVBox.setStyle("-fx-font-size: 16px;");
        rewindVBox.setSpacing(3);
        
        // Note, due to https://bugs.openjdk.java.net/browse/JDK-8191995
        // We need to disable the DatePicker editor
        // since otherwise users can type in a new date that is
        // promptly ignored. :-/ 
        
        // start date / time
        HBox startHBox = new HBox();
        startHBox.setSpacing(5.0);
        Label startLabel = new Label("From:");
        startLabel.setMinWidth(40);
        DatePicker startDate = new DatePicker();
        TextField startTime = new TextField();
        startDate.getEditor().setDisable(true);
        startDate.getEditor().setOpacity(1);
        startHBox.getChildren().addAll(startLabel,startDate,startTime);
        
        // end date / time
        HBox endHBox = new HBox();
        endHBox.setSpacing(5.0);
        Label endLabel = new Label("To:");
        endLabel.setMinWidth(40);
        DatePicker endDate = new DatePicker();
        TextField endTime = new TextField();
        endDate.getEditor().setDisable(true);
        endDate.getEditor().setOpacity(1);
        endHBox.getChildren().addAll(endLabel,endDate,endTime);
        
        // Server vs
        HBox optionsHBox = new HBox();
        optionsHBox.setSpacing(5.0);
        optionsHBox.setAlignment(Pos.CENTER_LEFT);
        Label sourceLabel = new Label("Rewind From: ");
        Pane spring = new Pane();
        spring.setMaxWidth(MAX_VALUE);
        HBox.setHgrow(spring, Priority.ALWAYS);
        ChoiceBox<String> sourceChoiceBox = new ChoiceBox(FXCollections.observableArrayList("Reader", "Server"));
        sourceChoiceBox.getSelectionModel().selectFirst();
        ToggleSwitch clearToggleSwitch = new ToggleSwitch("Clear Read Count");
        
        optionsHBox.getChildren().addAll(sourceLabel,sourceChoiceBox,spring,clearToggleSwitch);
        
        rewindVBox.getChildren().addAll(startHBox,endHBox,optionsHBox);
        dialog.getDialogPane().setContent(rewindVBox);

        BooleanProperty startTimeOK = new SimpleBooleanProperty(false);
        BooleanProperty endTimeOK = new SimpleBooleanProperty(false);
        BooleanProperty allOK = new SimpleBooleanProperty(false);
       
        allOK.bind(Bindings.and(endTimeOK, startTimeOK));
        
        startTime.textProperty().addListener((observable, oldValue, newValue) -> {
            startTimeOK.setValue(false);
            if (DurationParser.parsable(newValue)) startTimeOK.setValue(Boolean.TRUE);
            if ( newValue.isEmpty() || newValue.matches("^[0-9]*(:?([0-5]?([0-9]?(:([0-5]?([0-9]?)?)?)?)?)?)?") ){
                logger.trace("Rewind Dialog: Possiblely good start Time (newValue: " + newValue + ")");
            } else {
                Platform.runLater(() -> {
                    int c = startTime.getCaretPosition();
                    if (oldValue.length() > newValue.length()) c++;
                    else c--;
                    startTime.setText(oldValue);
                    startTime.positionCaret(c);
                });
                logger.trace("Rewind Dialog: Bad start time (newValue: " + newValue + ")");
            }
        });
        endTime.textProperty().addListener((observable, oldValue, newValue) -> {
            endTimeOK.setValue(false);
            if (DurationParser.parsable(newValue)) endTimeOK.setValue(Boolean.TRUE);
            if ( newValue.isEmpty() || newValue.matches("^[0-9]*(:?([0-5]?([0-9]?(:([0-5]?([0-9]?)?)?)?)?)?)?") ){
                logger.trace("Rewind Dialog: Possiblely good start Time (newValue: " + newValue + ")");
            } else {
                Platform.runLater(() -> {
                    int c = endTime.getCaretPosition();
                    if (oldValue.length() > newValue.length()) c++;
                    else c--;
                    endTime.setText(oldValue);
                    endTime.positionCaret(c);
                });
                logger.trace("Redind Dialog: Bad end time (newValue: " + newValue + ")");
            }
        });
        
        //Default to event date / 00:00 for the start time, event date 23:59:00 for the end time
        startDate.setValue(LocalDate.now());
        startTime.setText("00:00:00");
        endDate.setValue(LocalDate.now());
        endTime.setText("23:59:59");
        
        Node createButton = dialog.getDialogPane().lookupButton(rewindButtonType);
        createButton.disableProperty().bind(allOK.not());
        
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == rewindButtonType) {
                RewindData result = new RewindData();
                result.startDate = startDate.getValue();
                result.startTime = DurationParser.parse(startTime.getText());
                result.endDate = endDate.getValue();
                result.endTime = DurationParser.parse(endTime.getText());
                result.source = sourceChoiceBox.getSelectionModel().getSelectedItem();
                result.clearCount = clearToggleSwitch.isSelected();
                return result;
            }
            return null;
        });

        Optional<RewindData> result = dialog.showAndWait();

        if (result.isPresent()) {
            RewindData rwd= result.get();
            
            if (rwd.clearCount) reader.clearReadRecords();
            
            if (rwd.source.equals("Server")) {
                // This can take a bit of work, so we will spawn a background thread
                Task dataSyncTask = new Task<Void>() {
                    @Override protected Void call() {

                        String endpoint =  PikaReceiverPrefs.getInstance().getEchoEndpoint();
                        String mac = reader.getReaderIDProperty().getValueSafe();

                        logger.trace("remoteReaderCellController::Rewind Thread Starting ");

                        // Try and connect to the endpoint and get the data since the last read

                        try {
                            
                            String from = LocalDateTime.of(rwd.startDate, LocalTime.ofSecondOfDay(rwd.startTime.getSeconds())).toString().replace("T", " ");
                            String to = LocalDateTime.of(rwd.endDate, LocalTime.ofSecondOfDay(rwd.endTime.getSeconds())).toString().replace("T", " ");
                            logger.debug("Rewinding from Server: " + mac + " from " + from + " -> " + to);
                            logger.trace("remoteReaderCellController::Rewind request: " + endpoint + "data/since/" +  mac + "/" + URLEncoder.encode(from, "UTF-8") + "/" + URLEncoder.encode(to, "UTF-8") );

                            HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint + "data/" + mac + "/" + URLEncoder.encode(from, "UTF-8") + "/" + URLEncoder.encode(to, "UTF-8") ))
                            .setHeader("User-Agent", "Echo Transmitter") // add request header
                            .header("Content-Type", "application/json")
                            .build();

                            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                            // print status code
                            logger.trace("FXMLmainController::startPollingThread Response Code: " + Integer.toString(response.statusCode()));
                            logger.trace("FXMLmainController::startPollingThread Response Body: " + response.body());    
                            // convert the response body into the command array
                            if (response.statusCode()<= 299 ) {
                                reader.processTime(response.body());
                            }
                        } catch (Exception ex) {
                            logger.error(ex.getMessage());
                        }

                        logger.trace("Rewind Thread Exiting");
                        return null;
                    }
                };

                Thread dataPollThread = new Thread(dataSyncTask); 
                dataPollThread.setName("New Time Poll Thread");
                dataPollThread.setDaemon(true);
                dataPollThread.start();
            } else {
            
                // convert the date/time to seconds since 1/1/1980
                LocalDateTime EPOC = LocalDateTime.of(LocalDate.parse("1980-01-01",DateTimeFormatter.ISO_LOCAL_DATE),LocalTime.MIDNIGHT);
                Long startTimestamp = Duration.between(EPOC, LocalDateTime.of(rwd.startDate, LocalTime.ofSecondOfDay(rwd.startTime.getSeconds()))).getSeconds();
                Long endTimestamp = Duration.between(EPOC, LocalDateTime.of(rwd.endDate, LocalTime.ofSecondOfDay(rwd.endTime.getSeconds()))).getSeconds();
                
                if (logger.isDebugEnabled()) {
                    String from = LocalDateTime.of(rwd.startDate, LocalTime.ofSecondOfDay(rwd.startTime.getSeconds())).toString().replace("T", " ");
                    String to = LocalDateTime.of(rwd.endDate, LocalTime.ofSecondOfDay(rwd.endTime.getSeconds())).toString().replace("T", " ");
                    String mac = reader.getReaderIDProperty().getValueSafe();
                    logger.debug("Rewinding from Reader: " + mac + " from " + from + " -> " + to);
                }

                logger.debug("Reader " + reader.getReaderIDProperty().getValueSafe() + " Rewind from " + startTimestamp + " to " + endTimestamp);
                JSONObject command = new JSONObject();
                command.put("mac", reader.getReaderIDProperty().getValueSafe());
                command.put("command", "REWIND " + startTimestamp.toString() + " " + endTimestamp.toString());

                // send the rewind command...
                String endpoint = PikaReceiverPrefs.getInstance().getEchoEndpoint();

                logger.trace("Posting to " + endpoint + "commands/ : \n " + command.toString(4));

                HttpRequest request = HttpRequest.newBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
                    .uri(URI.create(endpoint + "command/"))
                    .setHeader("User-Agent", "Echo Transmitter") // add request header
                    .header("Content-Type", "application/json")
                    .build();
                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    // print status code
                    logger.trace("remoteReaderCellController::rewind Response Code: " + Integer.toString(response.statusCode()));
                    logger.trace("remoteReaderCellController::rewind Response Body: " + response.body());   
                    Alert confAlert = new Alert(AlertType.INFORMATION);
                    confAlert.setTitle("Request Sent");
                    confAlert.setContentText("Rewind Request Sent");
                    confAlert.setHeaderText(null);
                    confAlert.showAndWait();
                } catch (IOException | InterruptedException ex) {
                    logger.error(ex.getMessage());
                }
            }
        }
    }
    
    private static class RewindData {
        public LocalDate startDate;
        public LocalDate endDate;
        public Duration startTime;
        public Duration endTime;
        public String source;
        public Boolean clearCount;

        public RewindData() {
        }
    }
}
