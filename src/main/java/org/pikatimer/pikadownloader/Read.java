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

/**
 *
 * @author john
 */

// Record class to hold the reads

public record Read(Reader reader, String chip, Long epochMilli, String tz, String dateTime, Integer antenna, Integer rfidReader, Double rssi) {

    /**
     * @return the time of day of the read ("HH:mm:ss.SSS"). The dateTime is
     * "yyyy-MM-dd HH:mm:ss.SSS", so this is everything after the space.
     */
    public String timeOfDay() {
        if (dateTime == null) return "";
        int firstSpace = dateTime.indexOf(' ');
        return firstSpace < 0 ? dateTime : dateTime.substring(firstSpace + 1);
    }
}


