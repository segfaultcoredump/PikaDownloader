/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikareceiver;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.input.MouseButton;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
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
    static final Preferences prefs = PikaReceiverPrefs.getInstance().getPreferences();
    static final Logger logger = LoggerFactory.getLogger(FXMLmainController.class);
    static final PikaReceiverPrefs relayPrefs = PikaReceiverPrefs.getInstance();
    private static final Pattern REGEX_PATTERN = Pattern.compile("^\\p{XDigit}+$");
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    @FXML TextField relayURLTextField;
    @FXML Button connectButton;
    @FXML ListView<Reader> readerListView;
    @FXML Label statusLabel;
    @FXML Button outputDirButton;
    @FXML TextField ouputDirTextField;
    @FXML ToggleSwitch customBibMapToggleSwitch;
    @FXML Button addLocalButton;
    
    
    static final ObservableList<Reader> readerList = FXCollections.observableArrayList();
    static final Map<String,Reader> readerMap = new HashMap();
    static final BooleanProperty connected = new SimpleBooleanProperty(false);
    
    
    
    
    /**
     * Initializes the controller class.
     */
    public void initialize() {
        // Set the default values from previous runs
        String endpoint = prefs.get("Endpoint", "");
        
        addLocalButton.setOnAction(event -> {
            addLocalDialog();
        
        });
        
        relayURLTextField.setText(endpoint);
        statusLabel.setText("Disconnected");
        connectButton.setOnAction(event -> {
            if(!connected.getValue()) connect(); else disconnect();
        });
        outputDirButton.setOnAction(event -> {
            changeOutputDir();
        });
        
        readerListView.setItems(readerList);
        
        // Disable stuff if we are connected
        outputDirButton.disableProperty().bind(connected);
        ouputDirTextField.disableProperty().bind(connected);
        connectButton.disableProperty().bind(relayURLTextField.textProperty().isEmpty());
        
        // Don't let folks directly edit the output dir
        // force them through the directory chooser
        ouputDirTextField.setEditable(false);
        ouputDirTextField.setOnMouseClicked( event -> {
            if(event.getButton().equals(MouseButton.PRIMARY)){
                if(event.getClickCount() == 2){
                    if (!connected.getValue()) changeOutputDir();
                }
            }
        });
        
        readerListView.setCellFactory(param -> new ReaderListCell());
        
        customBibMapToggleSwitch.selectedProperty().addListener(a -> {
            if(customBibMapToggleSwitch.isSelected()) {
                importBibChipMap();
            } else {
                relayPrefs.getBibChipMap().clear();
            }
        });
        
         
    }    
    
    private void addLocalDialog(){
        String ip = "127.0.0.1";
        LocalReader r = new LocalReader(ip);
        
        Platform.runLater(() -> {readerList.add(r);});
        readerMap.put(ip, r);
        logger.debug("New Reader: " +  ip);
                        
    }
    private void connect() {
        
        File outputDir = PikaReceiverPrefs.getInstance().getOutputDir();
            if (outputDir == null || ! outputDir.canWrite()){
                disconnect();
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Output Directory Not Set");
                alert.setHeaderText("The output directory is not set!");
                alert.setContentText("Please specify a valid ouput directory before connecting.");

                alert.showAndWait();
                return;
            }
        if (!relayURLTextField.getText().endsWith("/")) {
            relayURLTextField.setText(relayURLTextField.getText() + "/");
        }
        relayURLTextField.setEditable(false);
        statusLabel.setText("Connecting to " + relayURLTextField.getText());
        String endpoint =  relayURLTextField.getText();
        
        // Try and connect to the endpoint and get the status of the readers
        HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(endpoint + "status/"))
        .setHeader("User-Agent", "Echo Transmitter") // add request header
        .header("Content-Type", "application/json")
        .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // print status code
            logger.trace("FXMLmainController::connect Response Code: " + Integer.toString(response.statusCode()));
            logger.trace("FXMLmainController::connect Response Body: " + response.body());    
            
            // If the response status code is not 200, disconnect and toss a warning
            if (response.statusCode() != 200){
                disconnect();
                Alert alert = new Alert(AlertType.WARNING);
                alert.setTitle("Connection Error");
                alert.setHeaderText("Unable to connect to relay service.");
                alert.setContentText(response.body());

                alert.showAndWait();
                return;
            }
            
            
            // convert the response body into the command array
            JSONArray results = new JSONObject(response.body()).getJSONArray("readers");
            // If successful, create readers and populate reader list
            for (int j = 0; j < results.length(); j++) {
                JSONObject p = results.getJSONObject(j);
                
                if (p.has("mac") && REGEX_PATTERN.matcher(p.getString("mac")).matches()) {
                    if (readerMap.containsKey(p.getString("mac"))) {
                        //readerMap.get(p.getString("mac")).setStatus(p);
                    } else {
                        RemoteReader r = new RemoteReader(p.getString("mac"));
                        r.setStatus(p);
                        Platform.runLater(() -> {readerList.add(r);});
                        readerMap.put(p.getString("mac"), r);
                        logger.debug("New Reader: " +  p.getString("mac"));
                    }
                }
            }
            
            
            connected.set(true);
            // setup thread to poll for timing updates
            startPollingThread();
            // setup thread to continue to poll reader status
            startStatusThread();
            
            
            prefs.put("Endpoint", endpoint);
            PikaReceiverPrefs.getInstance().setEchoEndpoint(endpoint);
            connectButton.setText("Disconnect");
            statusLabel.setText("Connected to " + relayURLTextField.getText());
        } catch (Exception ex) {
            Alert alert = new Alert(AlertType.WARNING);
            alert.setTitle("Connection Error");
            alert.setHeaderText("Unable to connect to relay service.");
            alert.setContentText(ex.getMessage());

            alert.showAndWait();
            disconnect();
        }
    }

    private void disconnect() {
        statusLabel.setText("Disconnected");
        relayURLTextField.setEditable(true);
        
        readerList.removeAll(readerMap.values());
        
        readerMap.clear();
        connectButton.setText("Connect");
        connected.set(false);
        //PikaReceiverPrefs.getInstance().setEchoEndpoint(null);
    }

    private void startStatusThread() {

        logger.trace("FXMLmainController::startStatusThread start...");

        Task statusSyncTask = new Task<Void>() {
            @Override protected Void call() {
                String endpoint =  relayURLTextField.getText();

                while(connected.getValue()) {

                    // Try and connect to the endpoint and get the status of the readers
                    HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "status/"))
                    .setHeader("User-Agent", "Echo Transmitter") // add request header
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(5))
                    .build();

                    
                    try {
                        
                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        // print status code
                        logger.trace("FXMLmainController::startStatusThread Response Code: " + Integer.toString(response.statusCode()));
                        logger.trace("FXMLmainController::startStatusThread Response Body: " + response.body());   
                        if (response.statusCode() <= 299 ){
                        // convert the response body into the command array
                            JSONArray results = new JSONObject(response.body()).getJSONArray("readers");
                            // If successful, create readers and populate reader list
                            for (int j = 0; j < results.length(); j++) {
                                JSONObject p = results.getJSONObject(j);
                                
                                if (p.has("mac") && REGEX_PATTERN.matcher(p.getString("mac")).matches()) {
                                    if (readerMap.containsKey(p.getString("mac"))) {
                                        //readerMap.get(p.getString("mac")).setStatus(p);;
                                    } else {
                                        RemoteReader r = new RemoteReader(p.getString("mac"));
                                        r.setStatus(p);
                                        Platform.runLater(() -> {readerList.add(r);});
                                        readerMap.put(p.getString("mac"), r);
                                        logger.debug("New Reader: " +  p.getString("mac"));
                                    }
                                }
                            }
                        }
                        Thread.sleep(10000);
                    } catch (IOException | InterruptedException ex) {
                        logger.error(ex.getMessage());
                    }
                    
                }
                logger.debug("Reader Statys Thread Exiting");
                return null;
            }
        };
                
        Thread resync = new Thread(statusSyncTask); 
        resync.setName("Status Sync Thread");
        resync.setDaemon(true);
        resync.start();
        
    }

    private void startPollingThread() {

        logger.trace("FXMLmainController::startPollingThread start...");
        Task dataSyncTask = new Task<Void>() {
            @Override protected Void call() {
                
                String endpoint =  relayURLTextField.getText();
                String since = LocalDate.now().toString() + " 00:00:00";
                

                while(connected.getValue()) {
                    logger.trace("FXMLmainController::startPollingThread since: " + since);

                    // Try and connect to the endpoint and get the data since the last read
                    
                    try {
                        logger.trace("FXMLmainController::startPollingThread request: " + endpoint + "data/since/" + since );

                        HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "data/since/" + URLEncoder.encode(since, "UTF-8")))
                        .setHeader("User-Agent", "Echo Transmitter") // add request header
                        .header("Content-Type", "application/json")
                        .build();
                        
                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        // print status code
                        logger.trace("FXMLmainController::startPollingThread Response Code: " + Integer.toString(response.statusCode()));
                        logger.trace("FXMLmainController::startPollingThread Response Body: " + response.body());    
                        // convert the response body into the command array
                        if (response.statusCode()<= 299 ) {
                            JSONArray results = new JSONObject(response.body()).getJSONArray("time_data");
                        
                            // If successful, create readers and populate reader list
                            for (int j = 0; j < results.length(); j++) {
                                JSONObject p = results.getJSONObject(j);

                                if (readerMap.containsKey(p.getString("mac"))) {
                                    //readerMap.get(p.getString("mac")).processTime(p);;
                                }
                                since = p.getString("posttime");
                            }
                        }
                        Thread.sleep(5000);
                    } catch (Exception ex) {
                        logger.error(ex.getMessage());
                    }
                    
                }
                logger.debug("Time Poll Thread Exiting");
                return null;
            }
        };
                
        Thread dataPollThread = new Thread(dataSyncTask); 
        dataPollThread.setName("New Time Poll Thread");
        dataPollThread.setDaemon(true);
        dataPollThread.start();
        logger.trace("FXMLmainController::startPollingThread Thread Started " + dataPollThread.isAlive());

    }

    private void changeOutputDir() {
        File selectedDirectory = PikaReceiverPrefs.getInstance().getOutputDir();
        DirectoryChooser directoryChooser = new DirectoryChooser();
        if ( selectedDirectory != null) directoryChooser.setInitialDirectory(selectedDirectory);
        
        selectedDirectory = directoryChooser.showDialog(outputDirButton.getScene().getWindow());

        if(selectedDirectory != null && selectedDirectory.isDirectory() && selectedDirectory.canWrite()){
            ouputDirTextField.setText(selectedDirectory.getAbsolutePath());
            logger.debug(selectedDirectory.getAbsolutePath());
            PikaReceiverPrefs.getInstance().setOutputDir(selectedDirectory);
        }
    }
    
    public void importBibChipMap(){
        Map chipMap = relayPrefs.getBibChipMap();
        
        FileChooser fileChooser = new FileChooser();
        File sourceFile;
        final BooleanProperty chipFirst = new SimpleBooleanProperty(false);
        
        fileChooser.setTitle("Select Bib -> Chip File");
        
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home"))); 
        
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt","*.csv"),
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"), 
                new FileChooser.ExtensionFilter("All files", "*")
            );
        
        sourceFile = fileChooser.showOpenDialog(customBibMapToggleSwitch.getScene().getWindow());
        if (sourceFile != null) {
            try {            
                    Optional<String> fs = Files.lines(sourceFile.toPath()).findFirst();
                    String[] t = fs.get().split(",", -1);
                    if (t.length != 2) return; 
                    
                    if(t[0].toLowerCase().contains("chip")) {
                        chipFirst.set(true);
                        logger.debug("Found a chip -> bib file");
                    } else if (t[0].toLowerCase().contains("bib")) {
                        chipFirst.set(false);
                        logger.debug("Found a bib -> chip file");
                    } else {
                        chipMap.put(t[1],t[0]);
                        chipFirst.set(false);
                        logger.debug("No header in file. Assuming bib -> chip.");
                        logger.trace("Mapped chip " + t[1] + " to " + t[0]);
                    }
                    Files.lines(sourceFile.toPath())
                        .map(s -> s.trim())
                        .filter(s -> !s.isEmpty())
                        .skip(1)
                        .forEach(s -> {
                            //System.out.println("readOnce read " + s); 
                            String[] tokens = s.split(",", -1);
                            if (tokens.length != 2) return; 
                            if(chipFirst.get()) {
                                chipMap.put(tokens[0],tokens[1]);
                                logger.trace("Mapped chip " + tokens[0] + " to " + tokens[1]);
                            } else {
                                chipMap.put(tokens[1],tokens[0]);
                                logger.trace("Mapped chip " + tokens[1] + " to " + tokens[0]);
                            }
                        });
                    logger.debug("Found a total of " + chipMap.size() + " mappings");
                    
                } catch (IOException ex) {
                    logger.warn(ex.getMessage());
                }
        }
    }
}