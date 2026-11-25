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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.prefs.Preferences;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import org.controlsfx.control.ToggleSwitch;
import org.pikatimer.pikadownloader.util.TemporalField;
import org.pikatimer.pikadownloader.util.TextFieldFormatters;

/**
 * A reader's time skew: a signed duration added to the time of every read it
 * writes out (see Read.adjusted). It is applied only at output, never on
 * ingestion, so de-duplication still works when the skew changes.
 * <p>
 * The duration is saved in the prefs. The on/off switch is session only and
 * always starts off, so a skew from an earlier event can't apply by accident.
 *
 * @author john
 */
public class TimeSkew {

    public static final String PATTERN = "[-]HH:MM:ss[.SSS]";

    private final BooleanProperty enabledProperty = new SimpleBooleanProperty(false);
    private final ObjectProperty<Duration> durationProperty = new SimpleObjectProperty<>(Duration.ZERO);

    // What the output thread reads: the skew in ms, or 0 while off
    private volatile long millis = 0;

    /**
     * @param prefs where the duration is saved
     * @param prefKey the pref key for this reader's duration (in ms)
     */
    public TimeSkew(Preferences prefs, String prefKey) {
        durationProperty.set(Duration.ofMillis(prefs.getLong(prefKey, 0L)));
        durationProperty.addListener((observable, oldValue, newValue) -> {
            prefs.putLong(prefKey, newValue == null ? 0L : newValue.toMillis());
            update();
        });
        enabledProperty.addListener((observable, oldValue, newValue) -> update());
        update();
    }

    private void update() {
        Duration d = durationProperty.get();
        millis = enabledProperty.get() && d != null ? d.toMillis() : 0L;
    }

    /** True while the skew is applied. Not saved. */
    public BooleanProperty enabledProperty() {
        return enabledProperty;
    }

    /** The skew, whole milliseconds. Saved. */
    public ObjectProperty<Duration> durationProperty() {
        return durationProperty;
    }

    /**
     * @return the skew to add to each read in ms, or 0 while it is off. Safe
     * to call from any thread.
     */
    public long getMillis() {
        return millis;
    }

    /**
     * @param d the duration
     * @param plusSign true to put a '+' in front of positive durations
     * @return the duration as [-]HH:MM:ss[.SSS], e.g. "00:00:05" or
     * "-00:00:02.500" (the fraction only when it is not zero)
     */
    public static String format(Duration d, boolean plusSign) {
        long ms = d == null ? 0L : d.toMillis();
        String sign = ms < 0 ? "-" : (plusSign && ms > 0 ? "+" : "");
        ms = Math.abs(ms);
        long hours = ms / 3_600_000L;
        long minutes = ms / 60_000L % 60;
        long seconds = ms / 1000L % 60;
        long fraction = ms % 1000L;
        String s = String.format("%s%02d:%02d:%02d", sign, hours, minutes, seconds);
        return fraction == 0 ? s : s + String.format(".%03d", fraction);
    }

    /**
     * Sets up a reader cell's skew switch and duration field. A partial or
     * invalid entry shows red and keeps the last valid skew.
     */
    public void bindControls(ToggleSwitch toggleSwitch, TextField textField) {
        textField.setText(format(durationProperty.get(), false));
        TemporalField<Duration> field = TextFieldFormatters.durationField(textField, PATTERN);
        field.setRequired(true);
        field.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) durationProperty.set(newValue.truncatedTo(ChronoUnit.MILLIS));
        });
        toggleSwitch.selectedProperty().bindBidirectional(enabledProperty);

        Tooltip tooltip = new Tooltip("Added to the time of every read written out from this reader,\n"
                + "e.g. -00:00:02.500 to make the reads 2.5 seconds earlier.\n"
                + "The switch always starts off.");
        textField.setTooltip(tooltip);
        toggleSwitch.setTooltip(tooltip);
    }
}
