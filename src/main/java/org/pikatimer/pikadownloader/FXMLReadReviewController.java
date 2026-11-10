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

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.util.Duration;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.IndexedCheckModel;
import org.pikatimer.pikadownloader.ReadLog.Entry;
import org.pikatimer.pikadownloader.util.AlphanumericComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Read Review window: every read seen this session (from ReadLog), newest
 * first, with a regex search and a reader filter.
 *
 * Built to stay responsive with ~1M reads, so it does not use a FilteredList
 * and SortedList (see ReadReviewList). Instead:
 * <ul>
 * <li>A search, filter, or sort change rebuilds the rows: a parallel filter
 * plus a parallel sort, with comparators that read the reads directly
 * (the default TableView comparator goes through the cell value factories)</li>
 * <li>Once a second, the new reads are filtered and merged in</li>
 * </ul>
 *
 * @author john
 */
public class FXMLReadReviewController {
    private static final Logger logger = LoggerFactory.getLogger(FXMLReadReviewController.class);

    private static final AlphanumericComparator<String> ALPHANUMERIC = new AlphanumericComparator<>();

    // The order when no column is sorted, and the tie breaker for every sort
    private static final Comparator<Entry> NEWEST_FIRST = (a, b) -> Integer.compare(b.seq(), a.seq());

    // Filter large batches in parallel
    private static final int PARALLEL_THRESHOLD = 10_000;

    @FXML private TextField searchTextField;
    @FXML private CheckComboBox<String> readerCheckComboBox;
    @FXML private Label countLabel;
    @FXML private TableView<Entry> readTableView;
    @FXML private TableColumn<Entry, String> readerColumn;
    @FXML private TableColumn<Entry, String> chipColumn;
    @FXML private TableColumn<Entry, String> bibColumn;
    @FXML private TableColumn<Entry, String> timeColumn;

    private final ReadReviewList rows = new ReadReviewList();

    // How many ReadLog entries have been processed
    private int seen = 0;

    // Every reader with a read in the log (Readers don't override equals)
    private final Set<Reader> knownReaders = Collections.newSetFromMap(new IdentityHashMap<>());

    // State the rows were last built with. If any of it changes, the rows
    // need to be rebuilt rather than just merged.
    private Map<Reader, String> readerNames = null;
    private int bibMapVersion = -1;
    // The timezone override (null when off): times are shown at this offset
    private ZoneOffset tzOverride = null;
    private Filter filter = new Filter(null, Set.of(), Map.of(), false, null);
    private Comparator<Entry> comparator = NEWEST_FIRST;

    // The last valid search (null for none)
    private Pattern searchPattern = null;

    // True while the reader list is being updated (not a user change)
    private boolean updatingReaders = false;
    private boolean started = false;

    private final Timeline refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
    private final Timeline searchDebounce = new Timeline();

    public void initialize() {
        readTableView.setItems(rows);
        readTableView.setPlaceholder(new Label("No reads have been received yet"));

        // Live reader name (the unit can change it)
        readerColumn.setCellValueFactory(c -> c.getValue().read().reader().getReaderNameProperty());
        chipColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().read().chip()));
        bibColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(OutputProcessor.INSTANCE.mappedBibFor(c.getValue().read().chip())));
        // At the timezone override's offset (if on), to match the output files
        timeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().read().timeOfDay(tzOverride)));

        // Not used by the sort policy below (see columnComparator()), but
        // keeps the table's own comparator in line with it
        readerColumn.setComparator(ALPHANUMERIC);
        chipColumn.setComparator(ALPHANUMERIC);
        bibColumn.setComparator(ALPHANUMERIC);
        timeColumn.setComparator(ALPHANUMERIC);

        bibColumn.setVisible(OutputProcessor.INSTANCE.isBibMapInUse());

        // We do the sorting (and filtering) ourselves
        readTableView.setSortPolicy(table -> {
            if (started) rebuild(true);
            return true;
        });

        // Search: wait for a pause in the typing
        searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.stop();
            searchDebounce.getKeyFrames().setAll(new KeyFrame(Duration.millis(250), event -> applySearch()));
            searchDebounce.play();
        });

        // Reader filter: nothing checked shows every reader
        updateReaderTitle();
        readerCheckComboBox.getCheckModel().getCheckedItems().addListener((ListChangeListener<String>) change -> {
            updateReaderTitle();
            if (!updatingReaders && started) rebuild(true);
        });

        refreshTimeline.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * Load the reads and start watching for new ones. Call once the window is showing.
     */
    public void start() {
        started = true;
        refresh(); // readerNames is null, so this does the first rebuild
        refreshTimeline.play();
    }

    /**
     * Stop watching for new reads. Call when the window is closed.
     */
    public void stop() {
        started = false;
        refreshTimeline.stop();
        searchDebounce.stop();
    }

    // Once a second: pick up the new reads
    private void refresh() {
        if (!started) return;
        try {
            Entry[] fresh = ReadLog.INSTANCE.since(seen);
            seen += fresh.length;
            for (Entry e : fresh) knownReaders.add(e.read().reader());

            Map<Reader, String> names = currentReaderNames();
            updateReaderItems(names);

            boolean bibInUse = OutputProcessor.INSTANCE.isBibMapInUse();
            if (bibColumn.isVisible() != bibInUse) bibColumn.setVisible(bibInUse);

            if (!names.equals(readerNames) || OutputProcessor.INSTANCE.bibMapVersion() != bibMapVersion
                    || !Objects.equals(OutputProcessor.INSTANCE.getTimezoneOverride(), tzOverride)) {
                // A reader was added or renamed, or the bibs or the timezone
                // override changed, so the current order (and filter) may be
                // out of date
                rebuild(false);
            } else if (fresh.length > 0) {
                addRows(fresh);
                updateCount();
            }
        } catch (RuntimeException ex) {
            logger.error("Unable to refresh the read review", ex);
        }
    }

    // Filter, sort, and show every read
    private void rebuild(boolean scrollToTop) {
        long start = System.nanoTime();

        Entry[] all = ReadLog.INSTANCE.since(0);
        // Readers of reads that came in since the last refresh()
        for (int i = seen; i < all.length; i++) knownReaders.add(all[i].read().reader());
        seen = all.length;

        readerNames = currentReaderNames();
        bibMapVersion = OutputProcessor.INSTANCE.bibMapVersion();
        tzOverride = OutputProcessor.INSTANCE.getTimezoneOverride();
        timeColumn.setText(tzOverride == null ? "Time" : "Time (" + FXMLmainController.displayOffset(tzOverride) + ")");
        filter = new Filter(searchPattern, Set.copyOf(readerCheckComboBox.getCheckModel().getCheckedItems()),
                readerNames, bibColumn.isVisible(), tzOverride);
        comparator = currentComparator(readerNames);

        Entry[] shown = filter.matchesAll() ? all : Arrays.stream(all).parallel().filter(filter).toArray(Entry[]::new);
        long filtered = System.nanoTime();
        if (comparator == NEWEST_FIRST) {
            reverse(shown); // already oldest first
        } else {
            Arrays.parallelSort(shown, comparator);
        }
        long sorted = System.nanoTime();

        rows.replaceAll(shown);
        readTableView.refresh(); // the bibs or reader names may have changed
        if (scrollToTop) readTableView.scrollTo(0);
        updateCount();

        logger.debug("Read review: showing {} of {} reads (filter {} ms, sort {} ms, table {} ms)", shown.length, all.length,
                (filtered - start) / 1_000_000, (sorted - filtered) / 1_000_000, (System.nanoTime() - sorted) / 1_000_000);
    }

    // Merge new reads into the current rows
    private void addRows(Entry[] fresh) {
        boolean large = fresh.length >= PARALLEL_THRESHOLD;
        Entry[] matching;
        if (filter.matchesAll()) {
            matching = fresh;
        } else if (large) {
            matching = Arrays.stream(fresh).parallel().filter(filter).toArray(Entry[]::new);
        } else {
            matching = Arrays.stream(fresh).filter(filter).toArray(Entry[]::new);
        }
        if (matching.length == 0) return;
        if (large) Arrays.parallelSort(matching, comparator);
        else Arrays.sort(matching, comparator);

        // If they have scrolled down, keep the rows they are looking at in
        // place. At the top, the new reads just show up there.
        int firstVisible = firstVisibleIndex();
        int insertedAbove = rows.merge(Arrays.asList(matching), comparator, firstVisible);
        if (firstVisible > 0 && insertedAbove > 0) readTableView.scrollTo(firstVisible + insertedAbove);
    }

    private void applySearch() {
        String text = searchTextField.getText() == null ? "" : searchTextField.getText();
        if (text.isEmpty()) {
            searchPattern = null;
        } else {
            try {
                searchPattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException ex) {
                // Not a valid regex (yet): flag it and keep the last search
                if (!searchTextField.getStyleClass().contains("invalid-field")) {
                    searchTextField.getStyleClass().add("invalid-field");
                }
                return;
            }
        }
        searchTextField.getStyleClass().remove("invalid-field");
        if (started) rebuild(true);
    }

    // Builds the comparator from the table's sort order. Reads the values
    // directly rather than through the cell value factories, with each
    // column as a single lambda: with ~1M reads this sorts noticeably faster
    // than Comparator.comparing(...).reversed().thenComparing(...)
    private Comparator<Entry> currentComparator(Map<Reader, String> names) {
        Comparator<Entry> result = NEWEST_FIRST;
        List<TableColumn<Entry, ?>> sortOrder = readTableView.getSortOrder();
        // Build from the last sort column to the first, so the first one is checked first
        for (int i = sortOrder.size() - 1; i >= 0; i--) {
            TableColumn<Entry, ?> column = sortOrder.get(i);
            if (!column.isVisible()) continue;
            Comparator<Entry> c = columnComparator(column, names, column.getSortType() == TableColumn.SortType.DESCENDING);
            if (c == null) continue;
            Comparator<Entry> next = result;
            result = (a, b) -> {
                int r = c.compare(a, b);
                return r != 0 ? r : next.compare(a, b);
            };
        }
        return result;
    }

    // Safe to use from a parallel sort: names is never changed once built.
    // The comparators only return small values, so negating them is safe.
    private Comparator<Entry> columnComparator(TableColumn<Entry, ?> column, Map<Reader, String> names, boolean descending) {
        int dir = descending ? -1 : 1;
        if (column == readerColumn) return (a, b) -> dir * ALPHANUMERIC.compare(
                names.getOrDefault(a.read().reader(), ""), names.getOrDefault(b.read().reader(), ""));
        if (column == chipColumn) return (a, b) -> dir * ALPHANUMERIC.compare(a.read().chip(), b.read().chip());
        if (column == bibColumn) return (a, b) -> dir * ALPHANUMERIC.compare(
                OutputProcessor.INSTANCE.mappedBibFor(a.read().chip()), OutputProcessor.INSTANCE.mappedBibFor(b.read().chip()));
        // By the actual time, so reads after midnight sort after the ones before it
        if (column == timeColumn) return (a, b) -> dir * Long.compare(a.read().epochMilli(), b.read().epochMilli());
        return null;
    }

    // Reader -> name, for every reader in the log
    private Map<Reader, String> currentReaderNames() {
        Map<Reader, String> names = new HashMap<>();
        knownReaders.forEach(r -> names.put(r, r.getReaderNameProperty().getValueSafe()));
        return names;
    }

    // The filter lists every reader with reads, plus the current readers
    private void updateReaderItems(Map<Reader, String> names) {
        TreeSet<String> sorted = new TreeSet<>(ALPHANUMERIC);
        sorted.addAll(names.values());
        ObservableList<Reader> current = PikaDownloaderPrefs.INSTANCE.getReaderList();
        if (current != null) current.forEach(r -> sorted.add(r.getReaderNameProperty().getValueSafe()));
        sorted.remove("");

        List<String> items = new ArrayList<>(sorted);
        if (items.equals(readerCheckComboBox.getItems())) return;

        IndexedCheckModel<String> checkModel = readerCheckComboBox.getCheckModel();
        List<String> checked = new ArrayList<>(checkModel.getCheckedItems());
        updatingReaders = true;
        try {
            readerCheckComboBox.getItems().setAll(items);
            checkModel.clearChecks();
            checked.stream().filter(items::contains).forEach(checkModel::check);
        } finally {
            updatingReaders = false;
        }
        // If a checked reader went away, the filter changed
        if (started && checkModel.getCheckedItems().size() != checked.size()) rebuild(false);
    }

    private void updateReaderTitle() {
        readerCheckComboBox.setTitle(readerCheckComboBox.getCheckModel().getCheckedItems().isEmpty() ? "All Readers" : null);
    }

    private void updateCount() {
        countLabel.setText(String.format("%,d / %,d", rows.size(), seen));
        if (seen > 0 && readTableView.getPlaceholder() instanceof Label placeholder) {
            placeholder.setText("No reads match the search");
        }
    }

    // The index of the top row in view (0 if the table isn't showing yet)
    private int firstVisibleIndex() {
        if (readTableView.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
            IndexedCell<?> cell = flow.getFirstVisibleCell();
            if (cell != null) return Math.max(0, cell.getIndex());
        }
        return 0;
    }

    private static void reverse(Entry[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            Entry t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    // Immutable, so it can be used from a parallel stream
    // offset: the timezone override (null when off), for the time search
    private record Filter(Pattern pattern, Set<String> readers, Map<Reader, String> names, boolean bibVisible,
            ZoneOffset offset) implements Predicate<Entry> {

        boolean matchesAll() {
            return pattern == null && readers.isEmpty();
        }

        @Override
        public boolean test(Entry entry) {
            Read read = entry.read();
            String name = names.getOrDefault(read.reader(), "");
            if (!readers.isEmpty() && !readers.contains(name)) return false;
            if (pattern == null) return true;
            return pattern.matcher(read.chip()).find()
                    || pattern.matcher(name).find()
                    || (bibVisible && pattern.matcher(OutputProcessor.INSTANCE.mappedBibFor(read.chip())).find())
                    || pattern.matcher(read.timeOfDay(offset)).find();
        }
    }
}
