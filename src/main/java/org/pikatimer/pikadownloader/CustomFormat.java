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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiled "Custom" output format (see the README for the escape codes).
 *
 * The format string is parsed once into a list of segments (literal text or
 * a token) so that each read only needs a single pass over the segments
 * instead of re-running the regex matcher and re-building date formatters.
 *
 * @author john
 */
public final class CustomFormat {

    // Pattern matcher for the custom output format string
    // Group 1: Matches the escape backslash character
    // Group 2: Matches %TOKEN or $TOKEN styles
    // Group 3: Matches ${TOKEN} or %{TOKEN} styles
    // Group 4: Matches the optional :PARAM within ${TOKEN:PARAM}
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(\\\\)?(?:[\\%$]([A-Za-z]+)|[\\%$]\\{([A-Za-z]+)(?::([^}]+))?})",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // ZoneId.of() re-parses the offset every time, so cache them
    private static final Map<String, ZoneId> ZONE_CACHE = new ConcurrentHashMap<>();

    private record Segment(String header, Function<Read, String> renderer) {}

    private final List<Segment> segments;
    private final String header;

    private CustomFormat(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        StringBuilder sb = new StringBuilder();
        this.segments.forEach(s -> sb.append(s.header()));
        this.header = sb.toString();
    }

    /**
     * Compile a custom format string.
     *
     * @param format the custom format (may be null or blank)
     * @param bibChipMap the chip -> bib map used by the Bib token. The map is
     * read when each read is rendered, so later changes to it are picked up.
     * @return the compiled format
     */
    public static CustomFormat compile(String format, Map<String, String> bibChipMap) {
        List<Segment> segments = new ArrayList<>();
        if (format == null || format.isBlank()) {
            return new CustomFormat(segments);
        }

        Matcher matcher = TOKEN_PATTERN.matcher(format);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                segments.add(literal(format.substring(last, matcher.start())));
            }
            last = matcher.end();

            // Escaped: strip the backslash and keep the rest as literal text
            if (matcher.group(1) != null) {
                segments.add(literal(matcher.group().substring(1)));
                continue;
            }

            // %TOKEN / $TOKEN or ${TOKEN[:PARAM]} / %{TOKEN[:PARAM]}
            String tokenName;
            String parameter = null;
            if (matcher.group(2) != null) {
                tokenName = matcher.group(2).toLowerCase(Locale.ROOT);
            } else {
                tokenName = matcher.group(3).toLowerCase(Locale.ROOT);
                parameter = matcher.group(4);
            }
            segments.add(token(tokenName, parameter, matcher.group(), bibChipMap));
        }
        if (last < format.length()) {
            segments.add(literal(format.substring(last)));
        }
        return new CustomFormat(segments);
    }

    /**
     * @return the header line for this format (token names in place of the tokens)
     */
    public String header() {
        return header;
    }

    /**
     * @param read the read to format
     * @return the read formatted as a single output line
     */
    public String render(Read read) {
        StringBuilder sb = new StringBuilder(64);
        for (Segment s : segments) {
            sb.append(s.renderer().apply(read));
        }
        return sb.toString();
    }

    private static Segment literal(String text) {
        return new Segment(text, r -> text);
    }

    private static Segment token(String tokenName, String parameter, String original, Map<String, String> bibChipMap) {
        return switch (tokenName) {
            case "b", "bib" ->
                new Segment("Bib", r -> bibChipMap.getOrDefault(r.chip(), r.chip()));
            case "c", "chip" ->
                new Segment("Chip", Read::chip);
            case "a", "antenna" ->
                new Segment("Ant", r -> String.valueOf(r.antenna()));
            case "r", "reader" ->
                new Segment("Reader", r -> String.valueOf(r.rfidReader()));
            case "s", "rssi" ->
                new Segment("RSSI", r -> String.valueOf(r.rssi()));
            case "e", "epochmilli" ->
                new Segment("EpochMilli", r -> String.valueOf(r.epochMilli()));
            case "z", "tz", "timezone" ->
                new Segment("Timezone", Read::tz);
            case "d", "date" ->
                dateTimeToken("Date", tokenName, parameter);
            case "t", "time" ->
                dateTimeToken("Time", tokenName, parameter);
            case "dt", "datetime" ->
                dateTimeToken("DateTime", tokenName, parameter);
            // Unrecognized tokens are left completely intact
            default ->
                new Segment(original, r -> original);
        };
    }

    private static Segment dateTimeToken(String header, String tokenName, String parameter) {
        if (parameter == null) {
            // We are going to abuse the fact that
            // dateTime returns "YYYY-MM-dd HH:MM:ss.SSS"
            // and just split on the whitespace
            return switch (tokenName) {
                case "d", "date" -> new Segment(header, r -> WHITESPACE.split(nullToEmpty(r.dateTime()), 2)[0]);
                case "t", "time" -> new Segment(header, r -> {
                    String[] parts = WHITESPACE.split(nullToEmpty(r.dateTime()), 2);
                    return parts.length > 1 ? parts[1] : parts[0];
                });
                default -> new Segment(header, r -> nullToEmpty(r.dateTime()));
            };
        }

        String invalid = "[Invalid DateTimeFormatter Pattern: " + parameter + "]";
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(parameter);
        } catch (IllegalArgumentException e) {
            return new Segment(header, r -> invalid);
        }
        return new Segment(header, r -> {
            if (r.epochMilli() == null) return "";
            try {
                return Instant.ofEpochMilli(r.epochMilli()).atZone(zoneFor(r.tz())).format(formatter);
            } catch (DateTimeException e) {
                return invalid;
            }
        });
    }

    // The reader's timezone offset (e.g. -07:00), or the local zone if it is missing or invalid
    private static ZoneId zoneFor(String tz) {
        if (tz == null || tz.isBlank()) return ZoneId.systemDefault();
        return ZONE_CACHE.computeIfAbsent(tz, z -> {
            try {
                return ZoneId.of(z);
            } catch (DateTimeException e) {
                return ZoneId.systemDefault();
            }
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
