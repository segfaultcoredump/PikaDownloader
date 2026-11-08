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
package org.pikatimer.pikadownloader.util;

/**
 * Chip number helpers.
 *
 * @author john
 */
public final class Chips {

    private Chips() {
    }

    /**
     * The form of a chip number used as a chip -> bib map key: surrounding
     * whitespace and leading zeroes removed, so "000123" and "123" match.
     * A chip of all zeroes becomes "0" (the trigger chip).
     *
     * @param chip the chip number (may be null)
     * @return the normalized chip number, or "" if it is null or blank
     */
    public static String normalize(String chip) {
        if (chip == null) return "";
        String c = chip.strip();
        int i = 0;
        while (i < c.length() - 1 && c.charAt(i) == '0') i++;
        return c.substring(i);
    }
}
