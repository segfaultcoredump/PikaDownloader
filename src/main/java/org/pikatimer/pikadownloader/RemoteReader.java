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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.Pane;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @author john
 */
public class RemoteReader implements Reader {
    static final Preferences prefs = PikaReceiverPrefs.INSTANCE.getPreferences();
    static final Map<String,String> bibChipMap = OutputProcessor.INSTANCE.getBibChipMap();

    static final Logger logger = LoggerFactory.getLogger(RemoteReader.class);
    static final Set<String> processedReads = new HashSet();
    
    Semaphore writeLock = new Semaphore(1);
    
    private RemoteReaderCellController cellController = null;
    private Pane controlPane  = null;

    private final String mac;
    private final StringProperty readerIDProperty = new SimpleStringProperty("");
    private final IntegerProperty batteryProperty = new SimpleIntegerProperty(100);
    private final BooleanProperty readingProperty = new SimpleBooleanProperty(false);
    private final StringProperty outputFileProperty = new SimpleStringProperty("");
    private final BooleanProperty outputToFileProperty = new SimpleBooleanProperty(false);
    private final StringProperty readerNameProperty = new SimpleStringProperty("");
    private final StringProperty readerLocationProperty = new SimpleStringProperty("");
    private final IntegerProperty readerUpdatedProperty = new SimpleIntegerProperty(3600);
    private final IntegerProperty readCountProperty = new SimpleIntegerProperty(0);
    private final StringProperty lastReadProperty = new SimpleStringProperty("");
    
    private BufferedWriter outputFileBW = null;
    
    private LocalDateTime lastUpdated = LocalDateTime.MIN;

    public RemoteReader(String m) {
        readerIDProperty.set(m);
        mac = m;
        
        
        outputFileProperty.setValue(prefs.get("OutputFile-" + mac, ""));
        if(!outputFileProperty.isEmpty().get()) outputToFileProperty.set(true);
        
        
        outputFileProperty.addListener((observable, oldValue, newValue)  -> {
            logger.trace("Output File Update for " + mac + " -> " + newValue);

            prefs.put("OutputFile-" + mac, newValue);
        });
        
        outputToFileProperty.addListener((v, o, n) -> {
            if (n == false && outputFileBW != null) try {
                outputFileBW.close();
                
            } catch (IOException ex) {
                // We don't really care
            } finally {
                outputFileBW = null;
            }
        });
        
    }

    @Override
    public Pane getControlPane(){
        if (controlPane == null) {
            FXMLLoader fxmlLoader = new FXMLLoader();
            try {
                controlPane = fxmlLoader.load(getClass().getResource("remoteReaderCell.fxml").openStream());
                cellController = (RemoteReaderCellController) fxmlLoader.getController();
                cellController.setReader(this);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return controlPane;
    }
    
    public void setStatus(JSONObject status){
        
        Platform.runLater(() -> {
            
            // Name and Location
            readerLocationProperty.setValue(status.getString("location"));
            readerNameProperty.setValue(status.getString("name"));
            
            // Battery
            batteryProperty.setValue(status.getNumber("battery"));

            // Reading
            readingProperty.setValue(status.getBoolean("reading"));

            // Last Updated
            lastUpdated = LocalDateTime.parse(status.getString("updated").replace(" ", "T"));
            readerUpdatedProperty.setValue(Duration.between(lastUpdated, LocalDateTime.now(ZoneOffset.UTC)).toSeconds());
            logger.trace("Reader::setStatus: lastUpdated " + readerUpdatedProperty.getValue().toString() + " ago");
        });    
    }
    
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 17 * hash + Objects.hashCode(this.readerIDProperty.getValueSafe());
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
        final RemoteReader other = (RemoteReader) obj;
        return Objects.equals(this.readerIDProperty.getValueSafe(), other.readerIDProperty.getValueSafe());
    }

    
    @Override
    public StringProperty getReaderIDProperty() {
        return readerIDProperty;
    }

    public IntegerProperty getBatteryProperty() {
        return batteryProperty;
    }
    
    public IntegerProperty getUpdatedProperty() {
        return readerUpdatedProperty;
    }

    public BooleanProperty getReadingProperty() {
        return readingProperty;
    }
    
    public BooleanProperty getOutputToFileProperty() {
        return outputToFileProperty;
    }

    public StringProperty getOutputFileProperty() {
        return outputFileProperty;
    }

    public StringProperty getReaderNameProperty() {
        return readerNameProperty;
    }

    public StringProperty getReaderLocationProperty() {
        return readerLocationProperty;
    }
    
    public IntegerProperty getReadCountProperty(){
        return readCountProperty;
    }
    
    public StringProperty getLastReadProperty(){
        return lastReadProperty;
    }

    public void processTime(String s){
        try {
            writeLock.acquire();
        } catch (InterruptedException ex) {
            return;
        }
       
        if (outputFileBW == null){
            FileWriter fw = null;
            try {
                File file = new File(PikaReceiverPrefs.INSTANCE.getOutputDir(),outputFileProperty.getValueSafe());
                fw = new FileWriter(file,true);
                outputFileBW = new BufferedWriter(fw);
            } catch (IOException ex) {
                logger.error(ex.getMessage()); 
                writeLock.release();
                return;
            }
        }
        
        
        StringProperty last = new SimpleStringProperty();
        try {
            s.lines().forEach( r -> {
                if (outputFileBW == null) return;
                try {
                    last.setValue(r);
                    if (processedReads.contains(r)) return;
                    processedReads.add(r);
                    
                    if (!bibChipMap.isEmpty()){

                        String[] rfidRead = r.replaceAll("\"", "").split(",");
                        if (bibChipMap.containsKey(rfidRead[1])) {
                            rfidRead[2] = bibChipMap.get(rfidRead[1]);
                            r = String.join(",", rfidRead);
                        }
                    }
                    
                    outputFileBW.write(r);
                    outputFileBW.newLine();
                } catch (IOException ex) {
                    outputFileBW = null;
                }
            });
            
            outputFileBW.flush();
        } catch (IOException ex) {
            logger.error(ex.getMessage()); 
            try {
                outputFileBW.close();
            } catch (IOException ex1) {
                logger.error(ex1.getMessage()); 
            }
            outputFileBW = null;
        }
        
        Platform.runLater(() -> {
            String[] lastRead = last.getValueSafe().replaceAll("\"", "").split(",");
            if (lastRead.length > 5) {
                lastReadProperty.setValue(lastRead[1] + " ->\n" + lastRead[3]);
            }
            readCountProperty.set(processedReads.size());
        });
        writeLock.release();
    }
    
    public void processTime(JSONObject p) {
        // This is try-catch hell
        
        if (!outputToFileProperty.getValue()) return;
        
        // check to see if the chip/time is already in the read map
        if (processedReads.contains(p.getString("rfid"))) return;

        try {
            writeLock.acquire();
        } catch (InterruptedException ex) {
            return;
        }
        processedReads.add(p.getString("rfid"));
        Platform.runLater(() -> {
            String[] lastRead = p.getString("rfid").replaceAll("\"", "").split(",");
            if (lastRead.length > 5) {
                lastReadProperty.setValue(lastRead[1] + " ->\n" + lastRead[3]);
            }
            readCountProperty.set(processedReads.size());
        });

        // if not, write it out
        logger.trace("Reader::processTime " + mac + " -> " + p.getString("rfid"));
        try {    
            if (outputFileBW == null){
                FileWriter fw = null;
                try {
                    File file = new File(PikaReceiverPrefs.INSTANCE.getOutputDir(),outputFileProperty.getValueSafe());
                    fw = new FileWriter(file,true);
                    outputFileBW = new BufferedWriter(fw);
                } catch (IOException ex) {
                    logger.error(ex.getMessage()); 
                    writeLock.release();
                    return;
                }
            }
            String read = p.getString("rfid");
            if (!bibChipMap.isEmpty()){
                
                String[] rfidRead = p.getString("rfid").replaceAll("\"", "").split(",");
                if (bibChipMap.containsKey(rfidRead[1])) {
                    rfidRead[2] = bibChipMap.get(rfidRead[1]);
                    read = String.join(",", rfidRead);
                }
            }
            outputFileBW.write(read);
            outputFileBW.newLine();
            outputFileBW.flush();
        } catch (IOException ex) {
            logger.error(ex.getMessage()); 
            try {
                outputFileBW.close();
            } catch (IOException ex1) {
                logger.error(ex1.getMessage()); 
            }
            outputFileBW = null;
        }
        writeLock.release();
    }


    public void clearReadRecords(){
        processedReads.clear();
    }


    
}
