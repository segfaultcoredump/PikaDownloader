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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a Bib -> Chip (or Chip -> Bib) mapping file.
 *
 * The first non-blank line decides the column order:
 * - a header whose first column contains "chip": chip,bib
 * - a header whose first column contains "bib": bib,chip
 * - anything else: no header, the file is bib,chip
 *
 * Values are trimmed and surrounding quotes are removed. Lines with fewer
 * than two values (or an empty bib or chip) are skipped and reported. Extra
 * columns are ignored. If a chip is listed more than once, the last one wins.
 *
 * @author john
 */
public final class BibChipFileParser {

    /**
     * @param chipToBib the chip -> bib mappings, in file order
     * @param skipped the lines that were skipped ("Line N: ...")
     * @param error why the file could not be used at all, or null
     */
    public record Result(Map<String, String> chipToBib, List<String> skipped, String error) {}

    private BibChipFileParser() {
    }

    public static Result parse(List<String> lines) {
        Map<String, String> chipToBib = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        boolean chipFirst = false;
        boolean firstLine = true;
        int lineNumber = 0;

        for (String raw : lines) {
            lineNumber++;
            String line = raw == null ? "" : raw;
            if (lineNumber == 1) line = stripBOM(line);
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] tokens = line.split(",", -1);

            if (firstLine) {
                firstLine = false;
                String first = clean(tokens[0]).toLowerCase(Locale.ROOT);
                if (first.contains("chip")) {
                    chipFirst = true;
                    continue; // header line
                } else if (first.contains("bib")) {
                    chipFirst = false;
                    continue; // header line
                }
                // No header, assume bib -> chip and fall through to process the line
            }

            if (tokens.length < 2) {
                skipped.add("Line " + lineNumber + ": " + line);
                continue;
            }
            String a = clean(tokens[0]);
            String b = clean(tokens[1]);
            String chip = chipFirst ? a : b;
            String bib = chipFirst ? b : a;
            if (chip.isEmpty() || bib.isEmpty()) {
                skipped.add("Line " + lineNumber + ": " + line);
                continue;
            }
            chipToBib.put(chip, bib);
        }

        if (firstLine) {
            return new Result(chipToBib, skipped, "The file is empty");
        }
        return new Result(Collections.unmodifiableMap(chipToBib), List.copyOf(skipped), null);
    }

    // Trim and remove surrounding quotes
    private static String clean(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    // Excel likes to start CSV files with a UTF-8 byte order mark
    private static String stripBOM(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}
