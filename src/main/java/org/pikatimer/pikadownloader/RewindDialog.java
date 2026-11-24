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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Rewind dialog (a From and To date and time) used by the local and
 * remote readers.
 */
final class RewindDialog {

    private static final Logger logger = LoggerFactory.getLogger(RewindDialog.class);

    /** A rewind range. Both ends are inclusive. */
    record RewindRange(LocalDateTime from, LocalDateTime to) {}

    private RewindDialog() {}

    /**
     * Shows the dialog and waits for it. Must be called on the FX thread.
     *
     * @param extra more options to show below the times (e.g. where a remote
     * reader rewinds from), or null
     * @return the range, or empty if they cancelled
     */
    static Optional<RewindRange> show(Node extra) {
        Dialog<RewindRange> dialog = new Dialog<>();
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
        if (extra != null) {
            rewindVBox.setSpacing(2.0);
            rewindVBox.getChildren().add(extra);
        }
        dialog.getDialogPane().setContent(rewindVBox);

        BooleanProperty startTimeOK = new SimpleBooleanProperty(false);
        BooleanProperty endTimeOK = new SimpleBooleanProperty(false);
        BooleanProperty allOK = new SimpleBooleanProperty(false);

        installTimeFilter(startTime, startTimeOK);
        installTimeFilter(endTime, endTimeOK);

        // Both times valid, both dates set, and from is not after to
        Runnable validate = () -> {
            boolean ok = startTimeOK.get() && endTimeOK.get()
                    && startDate.getValue() != null && endDate.getValue() != null;
            if (ok) {
                LocalDateTime from = toDateTime(startDate.getValue(), startTime.getText());
                LocalDateTime to = toDateTime(endDate.getValue(), endTime.getText());
                ok = !from.isAfter(to);
            }
            allOK.set(ok);
        };
        startTime.textProperty().addListener(o -> validate.run());
        endTime.textProperty().addListener(o -> validate.run());
        startDate.valueProperty().addListener(o -> validate.run());
        endDate.valueProperty().addListener(o -> validate.run());

        //Default to event date / 00:00 for the start time, event date 23:59:00 for the end time
        startDate.setValue(LocalDate.now());
        startTime.setText("00:00:00");
        endDate.setValue(LocalDate.now());
        endTime.setText("23:59:59");

        Node createButton = dialog.getDialogPane().lookupButton(rewindButtonType);
        createButton.disableProperty().bind(allOK.not());

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == rewindButtonType) {
                return new RewindRange(
                        toDateTime(startDate.getValue(), startTime.getText()),
                        toDateTime(endDate.getValue(), endTime.getText()));
            }
            return null;
        });

        return dialog.showAndWait();
    }

    // Date + time of day (a duration from midnight, so 24:00:00 rolls over to the next day)
    private static LocalDateTime toDateTime(LocalDate date, String time) {
        return date.atStartOfDay().plus(DurationParser.parse(time));
    }

    // Only allow time-like input (HH:MM:SS.sss) in the field, and track if it is a valid time
    private static void installTimeFilter(TextField timeField, BooleanProperty timeOK) {
        timeField.textProperty().addListener((observable, oldValue, newValue) -> {
            timeOK.setValue(DurationParser.parsable(newValue));
            if (newValue.isEmpty() || newValue.matches("^[0-9]*(:?([0-5]?([0-9]?(:([0-5]?([0-9]?)?)?)?)?)?)?")) {
                logger.trace("Possiblely good time (newValue: {})", newValue);
            } else {
                Platform.runLater(() -> {
                    int c = timeField.getCaretPosition();
                    if (oldValue.length() > newValue.length()) {
                        c++;
                    } else {
                        c--;
                    }
                    timeField.setText(oldValue);
                    timeField.positionCaret(c);
                });
                logger.trace("Bad time (newValue: {})", newValue);
            }
        });
    }
}
