/*
 * Copyright (C) 2026 john
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
package org.pikatimer.pikareceiver;

import java.io.IOException;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.FloatProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleFloatProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.Pane;

/**
 *
 * @author john
 */
public class LocalReader implements Reader {
    
    private StringProperty readerIPProperty = new SimpleStringProperty();
    
    private StringProperty readerIDProperty = new SimpleStringProperty();
    private Pane controlPane  = null;
    private LocalReaderCellController cellController = null;
    
    private BooleanProperty readingProperty = new SimpleBooleanProperty(false);
    private BooleanProperty connectedProperty = new SimpleBooleanProperty(false);
    private FloatProperty batteryProperty = new SimpleFloatProperty(0);
    
    private StringProperty readerNameProperty = new SimpleStringProperty();
    
    private StringProperty lastReadProperty = new SimpleStringProperty();
    private IntegerProperty lastReadCountProperty = new SimpleIntegerProperty(0);
    
    private IntegerProperty lastUpdatedProperty = new SimpleIntegerProperty();
    
    private StringProperty outputFileProperty = new SimpleStringProperty();
    private BooleanProperty outputToFileProperty = new SimpleBooleanProperty(false);
    
    public LocalReader(String reader_ip){
        readerIPProperty.setValue(reader_ip); 
        readerIDProperty.setValue(reader_ip);
        
        //Connect to the reader
        connect();
        
        
    }
    
    @Override
    public Pane getControlPane(){
        if (controlPane == null) {
            FXMLLoader fxmlLoader = new FXMLLoader();
            try {
                controlPane = fxmlLoader.load(getClass().getResource("localReaderCell.fxml").openStream());
                cellController = (LocalReaderCellController) fxmlLoader.getController();
                cellController.setReader(this);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return controlPane;
    }
    
    private void connect() {
        
    }

    @Override
    public StringProperty getReaderIDProperty() {
        return readerIDProperty;
    }
    
    public BooleanProperty getReadingProperty() {
        return readingProperty;
    }
    
    public StringProperty getReaderNameProperty(){
        return readerNameProperty;
    }
    
    public FloatProperty getBatteryProperty(){
        return batteryProperty;
    }
    
    public StringProperty getLastReadProperty(){
        return lastReadProperty;
    }
    public IntegerProperty getReadCountProperty(){
        return lastReadCountProperty;
    }
    
    public IntegerProperty getLastUpdatedProperty(){
        return lastUpdatedProperty;
    }
    
    public StringProperty getReaderIPProperty(){
        return readerIPProperty;
    }
    
    public StringProperty getOutputFileProperty(){
        return outputFileProperty;
    }
    
    public BooleanProperty getOutputToFileProperty(){
        return outputToFileProperty;
    }
    
    public void clearReadRecords(){
        lastReadCountProperty.set(0);
        lastReadProperty.set("");
    }
    
    public void startReader(){
        
    }
    
    public void stopReader(){
        
    }
}
