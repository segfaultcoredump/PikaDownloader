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

import java.util.ArrayList;
import java.util.List;

/**
 * Every unique read seen this session, from every reader, in the order they
 * arrived. Append only. Written by the websocket and rewind threads, read by
 * the Read Review window (FXMLReadReviewController).
 *
 * @author john
 */
public enum ReadLog {
    INSTANCE;

    /**
     * @param seq the arrival order (0 for the first read of the session).
     * Unique, so it also breaks ties when the reads are sorted.
     * @param read the read
     */
    public record Entry(int seq, Read read) {}

    private final List<Entry> entries = new ArrayList<>(1 << 16); // guarded by this

    public synchronized void add(Read read) {
        entries.add(new Entry(entries.size(), read));
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * @param from the number of entries already seen
     * @return a copy of the entries added after the first {@code from}
     * (all of them for 0), oldest first
     */
    public synchronized Entry[] since(int from) {
        int size = entries.size();
        if (from >= size) return new Entry[0];
        return entries.subList(Math.max(0, from), size).toArray(Entry[]::new);
    }
}
