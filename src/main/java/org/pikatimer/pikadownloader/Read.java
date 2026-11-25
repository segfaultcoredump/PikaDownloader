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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 *
 * @author john
 */

// Record class to hold the reads

public record Read(Reader reader, String chip, Long epochMilli, String tz, String dateTime, Integer antenna, Integer rfidReader, Double rssi) {

    // Same as the "timestamp" PikaReader sends (see its TagRead)
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private static final long MILLIS_PER_DAY = 86_400_000L;

    // tz -> its offset, or null if it isn't one (see ownOffset())
    private static final Map<String, ZoneOffset> OFFSET_CACHE = new ConcurrentHashMap<>();

    /**
     * A PikaReader READ object, from its websocket, a rewind, or PikaRelay
     * (which stores and returns the same JSON):
     * {"antenna":3,"chip":"23210006","rssi":-71,"reader":1,"tz":"-07:00","epochMilli":1703796324476,"timestamp":"2023-12-28 13:45:24.476"}
     *
     * @param reader the reader it came from
     * @param tagRead the JSON
     * @return the read, or null if it has no chip or epochMilli
     */
    public static Read fromJSON(Reader reader, JSONObject tagRead) {
        String chip = tagRead.optString("chip", "");
        Number epochMilli = tagRead.optNumber("epochMilli");
        if (chip.isBlank() || epochMilli == null) return null;
        return new Read(reader, chip, epochMilli.longValue(),
                tagRead.optString("tz", ""),
                tagRead.optString("timestamp", ""),
                tagRead.optInt("antenna"),
                tagRead.optInt("reader"),
                tagRead.optDouble("rssi"));
    }

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
     * The read as it is written out: moved by the reader's time skew, then to
     * the timezone override (if any). The epochMilli is moved by the skew too.
     * Only used at output, so de-duplication (Read equality) is unaffected.
     *
     * @param override the timezone override, or null to keep the reader's own
     * offset (and tz)
     * @param skewMillis the time skew in ms (0 for none)
     * @return the adjusted copy, or this read if there is nothing to change
     */
    public Read adjusted(ZoneOffset override, long skewMillis) {
        if (skewMillis == 0 || epochMilli == null) return atOffset(override);
        long shiftedMilli = epochMilli + skewMillis;
        ZoneOffset offset = override != null ? override : ownOffset(shiftedMilli);
        String shifted = Instant.ofEpochMilli(shiftedMilli).atOffset(offset).format(DATE_TIME);
        return new Read(reader, chip, shiftedMilli, override != null ? override.getId() : tz, shifted, antenna, rfidReader, rssi);
    }

    // The offset of the reader's tz (e.g. "-07:00" or "Z"), or the local
    // zone's offset at that time if the tz is missing or not an offset (the
    // same fallback as CustomFormat)
    private ZoneOffset ownOffset(long atMilli) {
        ZoneOffset offset = null;
        if (tz != null && !tz.isBlank()) {
            offset = OFFSET_CACHE.computeIfAbsent(tz, z -> {
                try {
                    ZoneId zone = ZoneId.of(z);
                    return zone instanceof ZoneOffset o ? o : null;
                } catch (DateTimeException e) {
                    return null;
                }
            });
        }
        if (offset != null) return offset;
        return ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(atMilli));
    }

    /**
     * Same as atOffset(offset).timeOfDay(), without a formatter, since the
     * Read Review search runs this over every read.
     *
     * @param offset the offset, or null for the reader's own time
     * @return the time of day ("HH:mm:ss.SSS") at the offset
     */
    public String timeOfDay(ZoneOffset offset) {
        return timeOfDay(offset, 0L);
    }

    /**
     * Same as adjusted(offset, skewMillis).timeOfDay(), without a formatter.
     *
     * @param offset the timezone override, or null for the reader's own offset
     * @param skewMillis the time skew in ms (0 for none)
     * @return the time of day ("HH:mm:ss.SSS") as it is written out
     */
    public String timeOfDay(ZoneOffset offset, long skewMillis) {
        if (epochMilli == null || (offset == null && skewMillis == 0)) return timeOfDay();
        long shiftedMilli = epochMilli + skewMillis;
        if (offset == null) offset = ownOffset(shiftedMilli);
        long ms = Math.floorMod(shiftedMilli + offset.getTotalSeconds() * 1000L, MILLIS_PER_DAY);
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


