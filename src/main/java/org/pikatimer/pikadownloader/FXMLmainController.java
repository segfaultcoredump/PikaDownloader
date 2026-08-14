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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
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

    static final Preferences prefs = PikaReceiverPrefs.INSTANCE.getPreferences();
    static final Logger logger = LoggerFactory.getLogger(FXMLmainController.class);
    static final PikaReceiverPrefs relayPrefs = PikaReceiverPrefs.INSTANCE;
    private static final Pattern REGEX_PATTERN = Pattern.compile("^\\p{XDigit}+$");

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @FXML    TextField relayURLTextField;
    @FXML    Button connectButton;
    @FXML    ListView<Reader> readerListView;
    @FXML    Label statusLabel;
    @FXML    Button outputDirButton;
    @FXML    TextField ouputDirTextField;
    @FXML    ToggleSwitch customBibMapToggleSwitch;
    @FXML    Button addLocalButton;

    @FXML    ChoiceBox<OutputFormat> outputFormatChoiceBox;
    @FXML    TextField customFormatTextField;
    @FXML    HBox customFormatHBox;

    static final ObservableList<Reader> readerList = FXCollections.observableArrayList();
    static final Map<String, Reader> readerMap = new HashMap();
    static final BooleanProperty connected = new SimpleBooleanProperty(false);

    private static final String errorCSS = ".invalid-field {"
            + "    -fx-text-box-border: #FF0000;"
            + "    -fx-focus-color: #FF0000;"
            + "    -fx-control-inner-background: #FFF5F5;"
            + "}";

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
            if (!connected.getValue()) {
                connect();
            } else {
                disconnect();
            }
        });
        outputDirButton.setOnAction(event -> {
            changeOutputDir();
        });

        readerListView.setItems(readerList);

        Label emptyMessage = new Label("Use the \"Local Reader...\" button to add a reader.");
        readerListView.setPlaceholder(emptyMessage);

        // Disable stuff if we are connected
        outputDirButton.disableProperty().bind(connected);
        ouputDirTextField.disableProperty().bind(connected);
        connectButton.disableProperty().bind(relayURLTextField.textProperty().isEmpty());

        // Don't let folks directly edit the output dir
        // force them through the directory chooser
        ouputDirTextField.setEditable(false);
        ouputDirTextField.setOnMouseClicked(event -> {
            if (event.getButton().equals(MouseButton.PRIMARY)) {
                if (event.getClickCount() == 2) {
                    if (!connected.getValue()) {
                        changeOutputDir();
                    }
                }
            }
        });
        // Add in the CSS rules
        ouputDirTextField.getStylesheets().add("data:text/css," + errorCSS.replaceAll(" ", "%20"));

        // Turn the textfield red if it has an invalid time
        ouputDirTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue.isBlank()) {
                if (!ouputDirTextField.getStyleClass().contains("invalid-field")) {
                    ouputDirTextField.getStyleClass().add("invalid-field");
                }
            } else {
                Path path = Paths.get(newValue);
                if (Files.isDirectory(path) && Files.isWritable(path)) {
                    ouputDirTextField.getStyleClass().remove("invalid-field");
                } else {
                    if (!ouputDirTextField.getStyleClass().contains("invalid-field")) {
                        ouputDirTextField.getStyleClass().add("invalid-field");
                    }
                }
            }
        });
        
        // We are blank by default, so flag it
        ouputDirTextField.getStyleClass().add("invalid-field");
        

        readerListView.setCellFactory(param -> new ReaderListCell());

        customBibMapToggleSwitch.selectedProperty().addListener(a -> {
            if (customBibMapToggleSwitch.isSelected()) {
                importBibChipMap();
            }
        });

        // TODO: flip these to get / set the output formatter and custom format via OutputProcessor
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
            }
        });
        outputFormatChoiceBox.setValue(OutputProcessor.INSTANCE.getOutputFormat());

        customFormatTextField.textProperty().set(OutputProcessor.INSTANCE.getCustomFormat());
        customFormatTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            OutputProcessor.INSTANCE.setCustomFormat(newValue);
            logger.debug("CustomOutputFormat Changed: {} -> {}", oldValue, newValue);
        });

    }

    private void addLocalDialog() {

        logger.debug("Starting discover...");
        ObservableList<DiscoveredLocalReader> readers = FXCollections.observableArrayList();
        BooleanProperty scanCompleted = new SimpleBooleanProperty(false);
        BooleanProperty dialogClosed = new SimpleBooleanProperty(false);
        // start a discovery task in a background thread
        Task pikaSearch = new Task<Void>() {
            @Override
            public Void call() {

                // This is ugly but it works
                byte one = Integer.valueOf(1).byteValue();
                byte zero = Integer.valueOf(0).byteValue();
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

                                String message = new String(receivePacket.getData()).trim();

                                logger.debug("PikaReader Discover Response: " + receivePacket.getAddress().getHostAddress() + " \"" + message + "\"");

                                String[] msg = message.split(" ");

                                if (msg[0].equalsIgnoreCase("PIKA_READER")) {
                                    DiscoveredLocalReader u = new DiscoveredLocalReader(receivePacket.getAddress().getHostAddress());
                                    u.UnitName.set(msg[1].toUpperCase());
                                    u.PORT.set(msg[2]);
                                    // If we have a new DiscoveredLocalReader, save it. 
                                    if (!readers.contains(u) && !readerMap.containsKey(u.IP.getValue())) {
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
        dialog.setTitle("Discover...");
        dialog.setHeaderText("Discover Local PikaReader units");
        ButtonType selectButtonType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectButtonType, ButtonType.CANCEL);

        // Create a scrollPane to put the tables and such in
        VBox mainVBox = new VBox();
        mainVBox.setPrefWidth(250);
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

        ultraListVBox.getChildren().add(notFound);
        ultraListVBox.visibleProperty().bind(scanCompleted);
        ultraListVBox.managedProperty().bind(scanCompleted);
        mainVBox.getChildren().add(progressVBox);
        mainVBox.getChildren().add(ultraListVBox);
        dialog.getDialogPane().setContent(mainVBox);

        scanCompleted.bind(Bindings.size(readers).greaterThanOrEqualTo(1));

        // If they double click on an reader, select it and close the dialog box
        pikaReaderListView.setOnMouseClicked((MouseEvent click) -> {
            if (click.getClickCount() == 2) {
                dialog.setResult(pikaReaderListView.getSelectionModel().getSelectedItems());
            }
        });

        dialog.getDialogPane().getScene().getWindow().sizeToScene();

        Node createButton = dialog.getDialogPane().lookupButton(selectButtonType);
        BooleanBinding isNothingSelected = Bindings.isEmpty(pikaReaderListView.getSelectionModel().getSelectedItems());
        createButton.disableProperty().bind(isNothingSelected);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == selectButtonType) {
                return pikaReaderListView.getSelectionModel().getSelectedItems();
            }
            return null;
        });

        Optional<List<DiscoveredLocalReader>> result = dialog.showAndWait();
        dialogClosed.set(true);

        if (result.isPresent()) {
            result.get().forEach(newReader -> {
                String ip = newReader.IP.getValueSafe();
                String name = newReader.UnitName.getValueSafe();
                String port = newReader.PORT.getValueSafe();
                LocalReader r = new LocalReader(ip, port, name);

                Platform.runLater(() -> {
                    readerList.add(r);
                });
                readerMap.put(ip, r);
                logger.debug("New Reader: {} -> {}", name, ip);
            });
        }
    }

    private void connect() {

        File outputDir = PikaReceiverPrefs.INSTANCE.getOutputDir();
        if (outputDir == null || !outputDir.canWrite()) {
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
        String endpoint = relayURLTextField.getText();

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
            if (response.statusCode() != 200) {
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
                        Platform.runLater(() -> {
                            readerList.add(r);
                        });
                        readerMap.put(p.getString("mac"), r);
                        logger.debug("New Reader: " + p.getString("mac"));
                    }
                }
            }

            connected.set(true);
            // setup thread to poll for timing updates
            startPollingThread();
            // setup thread to continue to poll reader status
            startStatusThread();

            prefs.put("Endpoint", endpoint);
            PikaReceiverPrefs.INSTANCE.setEchoEndpoint(endpoint);
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
            @Override
            protected Void call() {
                String endpoint = relayURLTextField.getText();

                while (connected.getValue()) {

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
                        if (response.statusCode() <= 299) {
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
                                        Platform.runLater(() -> {
                                            readerList.add(r);
                                        });
                                        readerMap.put(p.getString("mac"), r);
                                        logger.debug("New Reader: " + p.getString("mac"));
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
            @Override
            protected Void call() {

                String endpoint = relayURLTextField.getText();
                String since = LocalDate.now().toString() + " 00:00:00";

                while (connected.getValue()) {
                    logger.trace("FXMLmainController::startPollingThread since: " + since);

                    // Try and connect to the endpoint and get the data since the last read
                    try {
                        logger.trace("FXMLmainController::startPollingThread request: " + endpoint + "data/since/" + since);

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
                        if (response.statusCode() <= 299) {
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
        File selectedDirectory = PikaReceiverPrefs.INSTANCE.getOutputDir();
        DirectoryChooser directoryChooser = new DirectoryChooser();
        if (selectedDirectory != null) {
            directoryChooser.setInitialDirectory(selectedDirectory);
        }

        selectedDirectory = directoryChooser.showDialog(outputDirButton.getScene().getWindow());

        if (selectedDirectory != null && selectedDirectory.isDirectory() && selectedDirectory.canWrite()) {
            ouputDirTextField.setText(selectedDirectory.getAbsolutePath());
            logger.debug(selectedDirectory.getAbsolutePath());
            PikaReceiverPrefs.INSTANCE.setOutputDir(selectedDirectory);
        }
    }

    public void importBibChipMap() {
        Map chipMap = OutputProcessor.INSTANCE.getBibChipMap();

        FileChooser fileChooser = new FileChooser();
        File sourceFile;
        final BooleanProperty chipFirst = new SimpleBooleanProperty(false);

        fileChooser.setTitle("Select Bib -> Chip File");

        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.csv"),
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
                new FileChooser.ExtensionFilter("All files", "*")
        );

        sourceFile = fileChooser.showOpenDialog(customBibMapToggleSwitch.getScene().getWindow());
        if (sourceFile != null) {
            try {
                Optional<String> fs = Files.lines(sourceFile.toPath()).findFirst();
                String[] t = fs.get().split(",", -1);
                if (t.length != 2) {
                    return;
                }

                if (t[0].toLowerCase().contains("chip")) {
                    chipFirst.set(true);
                    logger.debug("Found a chip -> bib file");
                } else if (t[0].toLowerCase().contains("bib")) {
                    chipFirst.set(false);
                    logger.debug("Found a bib -> chip file");
                } else {
                    chipMap.put(t[1], t[0]);
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
                            if (tokens.length != 2) {
                                return;
                            }
                            if (chipFirst.get()) {
                                chipMap.put(tokens[0], tokens[1]);
                                logger.trace("Mapped chip " + tokens[0] + " to " + tokens[1]);
                            } else {
                                chipMap.put(tokens[1], tokens[0]);
                                logger.trace("Mapped chip " + tokens[1] + " to " + tokens[0]);
                            }
                        });
                logger.debug("Found a total of " + chipMap.size() + " mappings");

            } catch (IOException ex) {
                logger.warn(ex.getMessage());
            }
        }
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
