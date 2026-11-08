/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikadownloader;

import java.util.Collections;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
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

    // Holds the reader's pane, stretched to the width of the cell
    private final StackPane paneHolder = new StackPane();

    @Override
    protected void updateItem(Reader r, boolean empty) {
        super.updateItem(r, empty);

        if (empty || r == null || r.getReaderNameProperty().isEmpty().get()) {
            setText(null);
            setGraphic(null);
            paneHolder.getChildren().clear();
        } else {
            setText(null);
            // (moves the pane out of whichever cell had it before)
            paneHolder.getChildren().setAll(r.getControlPane());
            setGraphic(paneHolder);
        }
    }

    // Size the reader panes to the list, not the list to the panes: the cell
    // only asks for the pane's minimum width, so the list sizes it to the
    // list's own width (it only scrolls sideways if the window is narrower
    // than that), and the pane is stretched to fill the cell.
    //
    // Letting the cell ask for the pane's preferred width made the panes
    // creep wider: the list only ever grows its cells to the widest preferred
    // width it has seen, and the reader title bar's width follows the pane's
    // own width (LocalReaderCellController), so any shortfall there grew the
    // cell a little on every update.
    @Override
    protected double computePrefWidth(double height) {
        if (getGraphic() == null) return super.computePrefWidth(height);
        return snappedLeftInset() + paneHolder.minWidth(-1) + snappedRightInset();
    }

    public ReaderListCell() {
        ListCell thisCell = this;

        paneHolder.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, getWidth() - snappedLeftInset() - snappedRightInset()),
                widthProperty(), insetsProperty()));

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
            content.putString(getItem().getReaderNameProperty().getValueSafe());
            
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
                    if (items.get(i).getReaderNameProperty().getValueSafe().equals(db.getString())) draggedIdx = i;
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
