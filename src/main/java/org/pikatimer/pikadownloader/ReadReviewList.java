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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javafx.collections.ObservableListBase;
import org.pikatimer.pikadownloader.ReadLog.Entry;

/**
 * The (filtered and sorted) rows shown in the Read Review table. Read only
 * as far as the table is concerned, FXMLReadReviewController does all of
 * the filtering and sorting.
 *
 * This replaces a FilteredList + SortedList, which get slow with a large
 * number of reads: SortedList walks the whole list for every added row, so
 * a 100k read rewind into a 1M read list would take minutes. Here, a batch
 * of new rows is merged in with one pass over the array and one change event.
 *
 * FX thread only.
 *
 * @author john
 */
public class ReadReviewList extends ObservableListBase<Entry> {

    private Entry[] rows = new Entry[0];
    private int size = 0;

    @Override
    public Entry get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return rows[index];
    }

    @Override
    public int size() {
        return size;
    }

    /**
     * Replace every row.
     *
     * @param newRows the new rows (the list takes ownership of the array)
     */
    public void replaceAll(Entry[] newRows) {
        // The old array is never changed again, so it can back the removed list
        List<Entry> removed = Arrays.asList(rows).subList(0, size);
        rows = newRows;
        size = newRows.length;

        beginChange();
        if (!removed.isEmpty()) nextRemove(0, removed);
        if (size > 0) nextAdd(0, size);
        endChange();
    }

    /**
     * Insert new rows into their sorted positions.
     *
     * @param newRows the rows to add, sorted with comparator
     * @param comparator the comparator this list is already sorted with.
     * Must not consider two different rows equal (e.g. ends with ReadLog's seq).
     * @param index a row index (e.g. the first visible row)
     * @return how many of the new rows were inserted before the row that was
     * at {@code index}
     */
    public int merge(List<Entry> newRows, Comparator<? super Entry> comparator, int index) {
        int k = newRows.size();
        if (k == 0) return 0;

        // Where each new row goes in the current list. The new rows are
        // sorted, so each search starts where the last one ended.
        int[] positions = new int[k];
        int low = 0;
        for (int i = 0; i < k; i++) {
            low = insertionPoint(newRows.get(i), comparator, low);
            positions[i] = low;
        }

        // Build the merged array in one pass
        Entry[] merged = new Entry[size + k];
        int src = 0;
        int insertedBefore = 0;
        for (int i = 0; i < k; i++) {
            int p = positions[i];
            System.arraycopy(rows, src, merged, src + i, p - src);
            src = p;
            merged[p + i] = newRows.get(i);
            if (p <= index) insertedBefore++;
        }
        System.arraycopy(rows, src, merged, src + k, size - src);
        rows = merged;
        size += k;

        // Report the adds (positions in the merged list, ascending),
        // combining the runs of consecutive rows
        beginChange();
        int runStart = positions[0];
        int runEnd = runStart + 1;
        for (int i = 1; i < k; i++) {
            int p = positions[i] + i;
            if (p == runEnd) {
                runEnd++;
            } else {
                nextAdd(runStart, runEnd);
                runStart = p;
                runEnd = p + 1;
            }
        }
        nextAdd(runStart, runEnd);
        endChange();

        return insertedBefore;
    }

    // The index of the first row in [low, size) that sorts after row
    private int insertionPoint(Entry row, Comparator<? super Entry> comparator, int low) {
        int high = size;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (comparator.compare(rows[mid], row) < 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
