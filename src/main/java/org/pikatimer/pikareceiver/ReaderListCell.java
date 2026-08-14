/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikareceiver;

import java.util.Collections;
import javafx.collections.ObservableList;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */

// Simple ListCell<Reader> shell
// Actual UI is baked into the RemoteReader object itself
// This just snags that Pane and displays it
// Also sets up the drag-n-drop for reordering of the cells
public class ReaderListCell  extends ListCell<Reader>  {
    static final Logger logger = LoggerFactory.getLogger(ReaderListCell.class);
    
    @Override
    protected void updateItem(Reader r, boolean empty) {
        super.updateItem(r, empty);

        if (empty || r == null || r.getReaderIDProperty().isEmpty().get()) {
            setText(null);
            setGraphic(null);
        } else {
            setText(null);
            setGraphic(r.getControlPane());
        }
    }
    
    public ReaderListCell() {
        ListCell thisCell = this;
        
        // Drag and Drop of the RemoteReader List 
        // Based on https://stackoverflow.com/questions/20412445/how-to-create-a-reorder-able-tableview-in-javafx
        // But that example performs a swap in setOnDragDropped(), 
        // so we modified it to perorm a more natural shift and move using Collections.rotate
        setOnDragDetected(event -> {
            if (getItem() == null) {
                return;
            }

            Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(getItem().getReaderIDProperty().getValueSafe());
            
            // Snapshot the rVBox node image and 
            // set that as the dragable image
            Image snapshot = getItem().getControlPane().snapshot(new SnapshotParameters(), null);
            dragboard.setDragView(snapshot);             
            
            dragboard.setContent(content);
            logger.trace("ReaderListCell::setOnDragDetected: dragboard content ->  " + content.getString());
            event.consume();
        });

        setOnDragOver(event -> {
            if (event.getGestureSource() != thisCell &&
                   event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        setOnDragEntered(event -> {
            if (event.getGestureSource() != thisCell &&
                    event.getDragboard().hasString()) {
                setOpacity(0.3);
            }
        });

        setOnDragExited(event -> {
            if (event.getGestureSource() != thisCell &&
                    event.getDragboard().hasString()) {
                setOpacity(1);
            }
        });

        setOnDragDropped(event -> {
            if (getItem() == null) {
                return;
            }

            Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasString()) {
                logger.trace("ReaderListCell::setOnDragDropped: dragboard content ->  " + db.getString());
                
                ObservableList<Reader> items = getListView().getItems();
                
                int draggedIdx = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getReaderIDProperty().getValueSafe().equals(db.getString())) draggedIdx = i;
                }
                
                if (draggedIdx > -1) {
                    int thisIdx = items.indexOf(getItem());
                    if (draggedIdx <= thisIdx) {
                        Collections.rotate(items.subList(draggedIdx, thisIdx + 1), -1);
                    } else {
                        Collections.rotate(items.subList(thisIdx, draggedIdx + 1), 1);
                    }
                }
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        setOnDragDone(DragEvent::consume);
    }

}
