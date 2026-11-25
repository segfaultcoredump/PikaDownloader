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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Rewrite Output..." button in a reader's Advanced pane (shared by
 * LocalReaderCellController and RemoteReaderCellController): confirm, then
 * rewrite the reader's output file from the reads in memory
 * (OutputProcessor.rewriteOutputFile).
 *
 * @author john
 */
final class OutputRewrite {

    private static final Logger logger = LoggerFactory.getLogger(OutputRewrite.class);

    private OutputRewrite() {}

    /**
     * Ask the user to confirm, then rewrite the reader's output file on a
     * background thread. Call on the FX thread.
     *
     * @param reader the reader
     * @param button the Rewrite Output... button (the dialogs' owner)
     * @param running set while the rewrite runs (bind the button's disable
     * property to it)
     */
    static void confirmAndRun(Reader reader, Button button, BooleanProperty running) {
        if (running.get()) return;
        String name = reader.getReaderNameProperty().getValueSafe();
        String file = reader.getOutputFileProperty().getValueSafe();
        List<Reader> allReaders = List.copyOf(PikaDownloaderPrefs.INSTANCE.getReaderList());
        List<Reader> sharing = OutputProcessor.INSTANCE.readersSharingFile(reader, allReaders);
        if (sharing.isEmpty()) sharing = List.of(reader);

        int readCount = sharing.stream().mapToInt(r -> r.getProcessedReads().size()).sum();
        String others = sharing.stream()
                .filter(r -> r != reader)
                .map(r -> r.getReaderNameProperty().getValueSafe())
                .collect(Collectors.joining(", "));

        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.initOwner(button.getScene() == null ? null : button.getScene().getWindow());
        alert.setTitle("Rewrite Output File");
        alert.setHeaderText("Rewrite " + file + "?");
        alert.setContentText("This will replace " + file + " with the "
                + readCount + (readCount == 1 ? " read" : " reads")
                + " held in memory for " + name
                + (others.isEmpty() ? "" : " and for " + others + " (which also write to this file)")
                + ", in time order, applying the current time skew, timezone override, "
                + "output format and chip -> bib map.\n\n"
                + "Any reads in the file that are not in memory (for example, from before "
                + "Pika Downloader was started) will be lost. Your timing software may "
                + "need to re-import the file.");

        // Make Cancel the default so an accidental Enter doesn't rewrite
        Button okButton = (Button) alert.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Rewrite");
        okButton.setDefaultButton(false);
        Button cancelButton = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.setDefaultButton(true);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        running.set(true);
        Thread worker = new Thread(() -> {
            String error = null;
            int written = 0;
            try {
                written = OutputProcessor.INSTANCE.rewriteOutputFile(reader, allReaders);
            } catch (Exception ex) {
                logger.warn("Unable to rewrite {} for {}", file, name, ex);
                error = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.toString() : ex.getMessage();
            }
            String errorMessage = error;
            int count = written;
            Platform.runLater(() -> {
                running.set(false);
                Alert done = new Alert(errorMessage == null ? AlertType.INFORMATION : AlertType.ERROR);
                done.initOwner(button.getScene() == null ? null : button.getScene().getWindow());
                done.setTitle("Rewrite Output File");
                if (errorMessage == null) {
                    done.setHeaderText("Rewrote " + file);
                    done.setContentText("Wrote " + count + (count == 1 ? " read" : " reads") + " to " + file + ".");
                } else {
                    done.setHeaderText("Unable to rewrite " + file);
                    done.setContentText(errorMessage);
                }
                done.show();
            });
        }, "rewrite-output");
        worker.setDaemon(true);
        worker.start();
    }
}
