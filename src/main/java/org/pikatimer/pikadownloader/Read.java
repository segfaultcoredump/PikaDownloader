package org.pikatimer.pikadownloader;

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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 *
 * @author john
 */

// Record class to hold the reads

public record Read(Reader reader, String chip, Long epochMilli, String tz, String dateTime, Integer antenna, Integer rfidReader, Double rssi) {

    // Same as the "timestamp" PikaReader sends (see its TagRead)
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * @return the time of day of the read ("HH:mm:ss.SSS"). The dateTime is
     * "yyyy-MM-dd HH:mm:ss.SSS", so this is everything after the space.
     */
    public String timeOfDay() {
        if (dateTime == null) return "";
        int firstSpace = dateTime.indexOf(' ');
        return firstSpace < 0 ? dateTime : dateTime.substring(firstSpace + 1);
    }

    /**
     * The read moved to a fixed UTC offset (the timezone override). The
     * dateTime is recomputed from the epochMilli, which does not change.
     *
     * @param offset the offset, or null to keep the reader's own
     * @return a copy with the dateTime ("yyyy-MM-dd HH:mm:ss.SSS") and tz
     * (e.g. "-05:00", or "Z" for UTC like PikaReader) at the offset, or this
     * read if the offset or epochMilli is null
     */
    public Read atOffset(ZoneOffset offset) {
        if (offset == null || epochMilli == null) return this;
        String shifted = Instant.ofEpochMilli(epochMilli).atOffset(offset).format(DATE_TIME);
        return new Read(reader, chip, epochMilli, offset.getId(), shifted, antenna, rfidReader, rssi);
    }

    /**
     * Same as atOffset(offset).timeOfDay(), without a formatter, since the
     * Read Review search runs this over every read.
     *
     * @param offset the offset, or null for the reader's own time
     * @return the time of day ("HH:mm:ss.SSS") at the offset
     */
    public String timeOfDay(ZoneOffset offset) {
        if (offset == null || epochMilli == null) return timeOfDay();
        long ms = Math.floorMod(epochMilli + offset.getTotalSeconds() * 1000L, MILLIS_PER_DAY);
        int millis = (int) (ms % 1000);
        int seconds = (int) (ms / 1000 % 60);
        int minutes = (int) (ms / 60_000 % 60);
        int hours = (int) (ms / 3_600_000);
        char[] c = new char[12];
        twoDigits(c, 0, hours);
        c[2] = ':';
        twoDigits(c, 3, minutes);
        c[5] = ':';
        twoDigits(c, 6, seconds);
        c[8] = '.';
        c[9] = (char) ('0' + millis / 100);
        c[10] = (char) ('0' + millis / 10 % 10);
        c[11] = (char) ('0' + millis % 10);
        return new String(c);
    }

    private static void twoDigits(char[] c, int at, int value) {
        c[at] = (char) ('0' + value / 10);
        c[at + 1] = (char) ('0' + value % 10);
    }
}


