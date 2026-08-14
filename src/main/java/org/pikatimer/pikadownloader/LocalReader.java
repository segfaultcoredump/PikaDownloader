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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
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
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
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

/**
 *
 * @author john
 */
public class LocalReader implements Reader {
    final Preferences prefs = PikaReceiverPrefs.INSTANCE.getPreferences();
    final Set<Read> processedReads = new HashSet();
    
    static final Logger logger = LoggerFactory.getLogger(LocalReader.class);
    
    private StringProperty readerIPProperty = new SimpleStringProperty();
    private StringProperty readerPortProperty = new SimpleStringProperty();
    
    private StringProperty readerIDProperty = new SimpleStringProperty();
    private Pane controlPane  = null;
    private LocalReaderCellController cellController = null;
    
    private BooleanProperty readingProperty = new SimpleBooleanProperty(false);
    private BooleanProperty connectedProperty = new SimpleBooleanProperty(false);
    private StringProperty statusLabel = new SimpleStringProperty();
    private DoubleProperty batteryProperty = new SimpleDoubleProperty(0.0);
    
    private StringProperty readerNameProperty = new SimpleStringProperty();
    
    
    private StringProperty lastReadProperty = new SimpleStringProperty("<No Reads>");
    private IntegerProperty readCountProperty = new SimpleIntegerProperty(0);
    
    private IntegerProperty lastUpdatedProperty = new SimpleIntegerProperty(0);
    private LocalDateTime lastUpdated = LocalDateTime.MIN;
    
    private StringProperty outputFileProperty = new SimpleStringProperty();
    private BooleanProperty outputToFileProperty = new SimpleBooleanProperty(false);
    
    private static final BlockingQueue<Read> outputProcessorQueue = OutputProcessor.INSTANCE.getReadQueue();
    
    private Read lastRead = null;
    
    Thread pikaConnectionThread;
    private EventWebSocketClient webSocketClient;
    
    public LocalReader(String reader_ip, String port, String reader_id){
        readerIPProperty.setValue(reader_ip); 
        readerIDProperty.setValue(reader_id);
        readerNameProperty.setValue(reader_id);
        readerPortProperty.setValue(port);
        
        //Connect to the reader
        connect();
        
        outputFileProperty.setValue(prefs.get("LOCAL-READER-OUTPUT-" + readerNameProperty.getValue(), ""));
        if(!outputFileProperty.isEmpty().get()) outputToFileProperty.set(true);
        
        
        outputFileProperty.addListener((observable, oldValue, newValue)  -> {
            logger.trace("Output File Update for " + reader_id + " -> " + newValue);
            prefs.put("LOCAL-READER-OUTPUT-" + readerNameProperty.getValue(), newValue);
        });
        
        // lets periodically update stuff
        Timeline timeline = new Timeline(
            new KeyFrame(javafx.util.Duration.seconds(1), event -> {
                // This entire block runs safely on the JavaFX Application Thread
                if (lastRead != null) {
                    lastReadProperty.setValue(lastRead.chip() + " ->\n" + lastRead.dateTime());
                    readCountProperty.set(processedReads.size());
                }
                
                // update the last updated counter
                if (lastUpdated != null) {
                    lastUpdatedProperty.setValue(Duration.between(lastUpdated, LocalDateTime.now()).toSeconds());
                    logger.trace("Reader::setStatus: lastUpdated " + lastUpdatedProperty.getValue().toString() + " ago");
                }            
            })
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    
        
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
    
    private void connect() {
        if (connectedProperty.get()) {
            return; // already connected
        }
        

        LocalReader reader = this;
        Task pikaReaderConnection = new Task<Void>() {

            @Override
            public Void call() {

                try {
                    // Start listening for events....
                    String wsPikaURL = "ws://" + readerIPProperty.getValue() + ":" + readerPortProperty.getValueSafe() + "/events";
                    logger.debug("Connecting to wsPikaURL: " + wsPikaURL);
                    webSocketClient = new EventWebSocketClient(wsPikaURL, reader);
                    webSocketClient.setDaemon(true);
                    webSocketClient.connectBlocking(60, TimeUnit.SECONDS);
                    

                    Platform.runLater(() -> {
                        if (webSocketClient.isOpen()) {
                            statusLabel.setValue("Connected to\n" + wsPikaURL);
                            connectedProperty.setValue(true);
                            
                        }
                    });
                } catch (Exception ex) {
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
        JSONObject message = new JSONObject(line);
        String type = message.optString("type", "UNKNOWN");

        switch (type) {
            case "READ" ->  processRead(message, false);
            case "STATUS" ->  processStatus(message);
            default ->  logger.debug("Unknown: \"" + line.substring(0, 1) + "\" " + line);
        }
    }

    private void processStatus(JSONObject status) {
        
        // Snag battery status       
        Platform.runLater(() -> {
            
            // Name 
            readerNameProperty.setValue(status.getString("unitID"));
            
            // Battery
            if (status.has("battery") && status.getNumber("battery").doubleValue() > 0)
                batteryProperty.setValue(status.getNumber("battery").doubleValue() / 100.0);
            else batteryProperty.setValue(-1);

            // Reading
            readingProperty.setValue(status.getBoolean("reading"));            
        });
        
        // Last Updated TS
        lastUpdated = LocalDateTime.now();
        
    }

    private void processRead(JSONObject tagRead, Boolean rewind) {
        //  {"antenna":3,"chip":"23210006","rssi":-71,"reader":1,"tz":"-07:00","epochMilli":1703796324476,"type":"READ","timestamp":"2023-12-28 13:45:24.476"}
        logger.trace("Chip Read for {}: {}", readerIDProperty.getValueSafe(),tagRead);
        
        

        // Extract the data
        String chip = tagRead.getString("chip");
        Integer reader = tagRead.optInt("reader");
        Integer port = tagRead.optInt("antenna");
        Long epochMilli = tagRead.optLong("epochMilli");
        String dateTime = tagRead.optString("timestamp");
        String tz = tagRead.optString("tz");
        
        // create the read record
        // Reader reader, String chip, Long epochMilli, String dateTime, Integer antenna, Integer rfidReader
        Read read = new Read(this,chip,epochMilli, tz, dateTime, port, reader);
        
        if(! rewind && processedReads.contains(read)){ 
            return;
        } 

        outputProcessorQueue.add(read);
        
        lastRead = read;
        
        
        // If we don't already have it (possible with rewinds), add it
        if (!processedReads.contains(read)) processedReads.add(read);
        
    }

    @Override
    public StringProperty getReaderIDProperty() {
        return readerIDProperty;
    }
    
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
    
    @Override
    public StringProperty getOutputFileProperty(){
        return outputFileProperty;
    }
    
    @Override
    public BooleanProperty getOutputToFileProperty(){
        return outputToFileProperty;
    }
    
    public void clearReadRecords(){
        readCountProperty.set(0);
        lastReadProperty.set("");
    }
    
    public void startReader(){
        HttpClient client = HttpClient.newHttpClient();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + readerIPProperty.getValueSafe() + ":" + readerPortProperty.getValueSafe() + "/start" ))
                .GET()
                .build();
                
        // sendAsync executes non-blocking and 
        // returns a CompletableFuture that we don't care about ;-) 
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
    
    public void stopReader(){
        HttpClient client = HttpClient.newHttpClient();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + readerIPProperty.getValueSafe() + ":" + readerPortProperty.getValueSafe() + "/stop" ))
                .GET()
                .build();
                
        // sendAsync executes non-blocking and returns a CompletableFuture
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
    
    public void rewind() {
        // open a dialog box 
        Dialog<RewindData> dialog = new Dialog();
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

        allOK.bind(Bindings.and(endTimeOK, startTimeOK));

        startTime.textProperty().addListener((observable, oldValue, newValue) -> {
            startTimeOK.setValue(false);
            if (DurationParser.parsable(newValue)) {
                startTimeOK.setValue(Boolean.TRUE);
            }
            if (newValue.isEmpty() || newValue.matches("^[0-9]*(:?([0-5]?([0-9]?(:([0-5]?([0-9]?)?)?)?)?)?)?")) {
                logger.debug("Possiblely good start Time (newValue: " + newValue + ")");
            } else {
                Platform.runLater(() -> {
                    int c = startTime.getCaretPosition();
                    if (oldValue.length() > newValue.length()) {
                        c++;
                    } else {
                        c--;
                    }
                    startTime.setText(oldValue);
                    startTime.positionCaret(c);
                });
                logger.debug("Bad start time (newValue: " + newValue + ")");
            }
        });
        endTime.textProperty().addListener((observable, oldValue, newValue) -> {
            endTimeOK.setValue(false);
            if (DurationParser.parsable(newValue)) {
                endTimeOK.setValue(Boolean.TRUE);
            }
            if (newValue.isEmpty() || newValue.matches("^[0-9]*(:?([0-5]?([0-9]?(:([0-5]?([0-9]?)?)?)?)?)?)?")) {
                logger.debug("Possiblely good start Time (newValue: " + newValue + ")");
            } else {
                Platform.runLater(() -> {
                    int c = endTime.getCaretPosition();
                    if (oldValue.length() > newValue.length()) {
                        c++;
                    } else {
                        c--;
                    }
                    endTime.setText(oldValue);
                    endTime.positionCaret(c);
                });
                logger.debug("Bad end time (newValue: " + newValue + ")");
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
                return result;
            }
            return null;
        });

        Optional<RewindData> result = dialog.showAndWait();

        if (result.isPresent()) {
            RewindData rwd= result.get();

            LocalDateTime from = LocalDateTime.of(rwd.startDate, LocalTime.ofSecondOfDay(rwd.startTime.getSeconds()));
            LocalDateTime to = LocalDateTime.of(rwd.endDate, LocalTime.ofSecondOfDay(rwd.endTime.getSeconds()));
            logger.debug("Rewind from " + from.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " to " + to.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            // issue the rewind command via a background thread
            
            Task pikaCommand = new Task<Void>() {
                @Override public Void call() {
                    if (connectedProperty.get()) {
                        try {
                            URL url = new URL("http://" + readerIPProperty.getValueSafe() + ":" + readerPortProperty.getValueSafe() + "/rewind" + 
                                    "/" + from.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + 
                                    "/" + to.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)); 
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            //conn.setRequestProperty("Accept", "application/json");
                            if (conn.getResponseCode() != 200) {
                                throw new RuntimeException("Failed : HTTP Error code : "
                                        + conn.getResponseCode());
                            }
                            InputStreamReader in = new InputStreamReader(conn.getInputStream(),"UTF-8");
                            BufferedReader br = new BufferedReader(in);
                            String output;
                            String fullResponse="";
                            while ((output = br.readLine()) != null) {
                                logger.trace(output);
                                fullResponse +=output;
                            }
                            conn.disconnect();
                            JSONArray rewinds = new JSONArray(fullResponse);
                            rewinds.forEach(item -> {
                                JSONObject obj = (JSONObject) item;
                                processRead(obj, true);
                            });

                        } catch(Exception ex){
                            logger.debug("Exception in PikaReaderDirect::StartReading() ");
                            ex.printStackTrace(System.out);
                        }
                        
                    }
                    return null;
                }
            };
            new Thread(pikaCommand).start();
         }
    }
    
    private class EventWebSocketClient extends WebSocketClient {

        private Integer messageCounter = 0;
        private String wsURI;
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
            logger.debug("new connection opened");
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            logger.debug("closed with exit code " + code + " additional info: " + reason);

        }

        @Override
        public void onMessage(String message) {
            logger.trace("Received message #" + messageCounter++ + ": " + message + " Thread: " + Thread.currentThread().getName());
            reader.processLine(message);
        }

        @Override
        public void onMessage(ByteBuffer message) {
            logger.debug("received ByteBuffer");
        }

        @Override
        public void onError(Exception ex) {
            System.err.println("an error occurred:" + ex);
        }

    }
    
    private static class RewindData {

        public LocalDate startDate;
        public LocalDate endDate;
        public Duration startTime;
        public Duration endTime;

        public RewindData() {
        }
    }
}
