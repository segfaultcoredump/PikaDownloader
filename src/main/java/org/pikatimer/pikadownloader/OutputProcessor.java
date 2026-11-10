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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import org.pikatimer.pikadownloader.util.Chips;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the reads from every reader (via the read queue) and writes them out
 * to each reader's output file in the selected output format.
 *
 * Reads are batched (500ms), grouped by output file, and each file is written
 * by a single task so that two readers sharing a file never interleave lines.
 *
 * NOTE: As an enum singleton, the INSTANCE is constructed before the static
 * fields below are initialized, so anything the constructor needs must be an
 * instance field.
 *
 * @author john
 */
public enum OutputProcessor {
    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(OutputProcessor.class);

    private static final Preferences prefs = PikaDownloaderPrefs.INSTANCE.getPreferences();

    // PikaReader's /trigger endpoint sends a read with a chip of "0"
    private static final String TRIGGER_CHIP = "0";

    private final BlockingQueue<Read> readQueue = new ArrayBlockingQueue<>(100000);

    // Open output files, keyed by their absolute path
    private final Map<Path, BufferedWriter> bufferedWriterMap = new ConcurrentHashMap<>();

    // chip -> bib. Always the same instance (other classes hold on to it),
    // updated in place via setBibChipMap(). The chip keys are normalized
    // (no leading zeroes, see Chips.normalize), so look up with bibFor()
    private final Map<String, String> bibChipMap = new ConcurrentHashMap<>();

    // Guards the pending batch and all file writes
    private final ReentrantLock outputLock = new ReentrantLock();
    private final List<Read> pending = new ArrayList<>(); // guarded by outputLock

    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean missingDirWarned = new AtomicBoolean(false);
    private volatile boolean shutdown = false;

    private volatile OutputFormat outputFormat = loadOutputFormat();
    private volatile String customFormat = PikaDownloaderPrefs.INSTANCE.getPreferences().get("CustomOutputFormat", "");
    private volatile CustomFormat compiledFormat;

    // bibMapEnabled mirrors the "Use Chip -> Bib Map" switch (session only,
    // like the map itself). While it is off, bibs fall back to the chip.
    // mappedChipsOnly (saved): only write out reads for chips in the
    // chip -> bib map (and trigger reads) while the map is enabled.
    private volatile boolean mappedChipsOnly = PikaDownloaderPrefs.INSTANCE.getPreferences().getBoolean("MappedChipsOnly", false);
    private volatile boolean bibMapEnabled = false;

    // Bumped whenever the bib map or bibMapEnabled changes (see bibMapVersion())
    private final AtomicInteger bibMapVersion = new AtomicInteger();

    // Timezone override (session only): the fixed UTC offset the reads are
    // written out in, or null to keep each reader's own date/time and tz
    private volatile ZoneOffset timezoneOverride = null;

    private OutputProcessor() {
        compiledFormat = CustomFormat.compile(customFormat, this::bibFor);
        setupReadProcessor();
    }

    private static OutputFormat loadOutputFormat() {
        try {
            return OutputFormat.valueOf(PikaDownloaderPrefs.INSTANCE.getPreferences().get("OutputFormat", OutputFormat.ChipTime.name()));
        } catch (IllegalArgumentException ex) {
            return OutputFormat.ChipTime; // Unknown value in the prefs
        }
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(OutputFormat of) {
        outputFormat = of;
        prefs.put("OutputFormat", of.name());
    }

    public String getCustomFormat() {
        return customFormat;
    }

    public void setCustomFormat(String format) {
        customFormat = format;
        compiledFormat = CustomFormat.compile(format, this::bibFor);
        prefs.put("CustomOutputFormat", format);
    }

    public boolean isMappedChipsOnly() {
        return mappedChipsOnly;
    }

    /**
     * @param onlyMapped true to only write out reads for chips in the
     * chip -> bib map (plus trigger reads). Only applies while the bib map
     * is enabled and has at least one mapping.
     */
    public void setMappedChipsOnly(boolean onlyMapped) {
        mappedChipsOnly = onlyMapped;
        prefs.putBoolean("MappedChipsOnly", onlyMapped);
    }

    /**
     * @return the fixed UTC offset the reads are written out in, or null if
     * the timezone override is off
     */
    public ZoneOffset getTimezoneOverride() {
        return timezoneOverride;
    }

    /**
     * Write the reads out at a fixed UTC offset: the date/time is recomputed
     * from the epochMilli (which does not change) and the tz is replaced.
     * Applies from the next batch of reads. Not saved.
     *
     * @param offset the offset, or null to turn the override off
     */
    public void setTimezoneOverride(ZoneOffset offset) {
        timezoneOverride = offset;
    }

    /**
     * @param enabled true while the "Use Chip -> Bib Map" switch is on
     */
    public void setBibMapEnabled(boolean enabled) {
        bibMapEnabled = enabled;
        bibMapVersion.incrementAndGet();
    }

    /**
     * @return true if bibs are being looked up: the "Use Chip -> Bib Map"
     * switch is on and the map has at least one mapping
     */
    public boolean isBibMapInUse() {
        return bibMapEnabled && !bibChipMap.isEmpty();
    }

    /**
     * @return a number that changes whenever the chip -> bib map or the
     * "Use Chip -> Bib Map" switch changes, so the bibs shown elsewhere
     * (e.g. the Read Review window) can be refreshed
     */
    public int bibMapVersion() {
        return bibMapVersion.get();
    }

    /**
     * Unlike the output files, there is no fallback to the chip here.
     *
     * @param chip the chip number (leading zeroes are ignored)
     * @return the bib mapped to the chip, or "" if the bib map is not in
     * use or the chip is not in it
     */
    public String mappedBibFor(String chip) {
        if (!bibMapEnabled || chip == null) return "";
        return bibChipMap.getOrDefault(Chips.normalize(chip), "");
    }

    /**
     * @return true if the current output format (or custom format) includes
     * the bib. Without a chip -> bib map in use, the chip is written in its place.
     */
    public boolean outputUsesBib() {
        return switch (outputFormat) {
            case BibTime, BibDateTime, RFIDServer, RFIDServer_EXT -> true;
            case ChipTime, ChipDateTime -> false;
            case CUSTOM -> compiledFormat.usesBib();
        };
    }

    // The bib for a chip, or the chip itself if the map is off or has no mapping for it
    private String bibFor(String chip) {
        if (!bibMapEnabled || chip == null) return chip;
        return bibChipMap.getOrDefault(Chips.normalize(chip), chip);
    }

    public BlockingQueue<Read> getReadQueue() {
        return readQueue;
    }

    public Map<String, String> getBibChipMap() {
        return bibChipMap;
    }

    /**
     * Replace the chip -> bib mappings. The map is never empty part way
     * through, so reads being written at the same time still get their bibs.
     *
     * @param newMap the new chip -> bib mappings. Leading zeroes on the
     * chips are ignored.
     */
    public void setBibChipMap(Map<String, String> newMap) {
        Map<String, String> normalized = new HashMap<>();
        newMap.forEach((chip, bib) -> {
            String key = Chips.normalize(chip);
            if (!key.isEmpty() && bib != null) normalized.put(key, bib);
        });
        bibChipMap.putAll(normalized);
        bibChipMap.keySet().retainAll(normalized.keySet());
        bibMapVersion.incrementAndGet();
    }

    /**
     * Close every open output file. They are re-opened (in append mode) the
     * next time a read is written to them. Called when the output directory,
     * an output file name, or an output toggle changes.
     */
    public void requestCloseAll() {
        closeRequested.set(true);
        missingDirWarned.set(false);
    }

    /**
     * Write out anything still queued and close all of the output files.
     * Called when the application exits.
     */
    public void shutdown() {
        try {
            if (!outputLock.tryLock(5, TimeUnit.SECONDS)) {
                logger.warn("Timed out waiting for the output processor, some reads may not have been saved");
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            shutdown = true;
            readQueue.drainTo(pending);
            writePending();
            closeWriters();
            logger.debug("OutputProcessor shut down");
        } catch (Exception ex) {
            logger.error("Error shutting down the output processor", ex);
        } finally {
            outputLock.unlock();
        }
    }

    private void setupReadProcessor() {
        Runnable readProcessor = () -> {
            logger.debug("OutputProcessor: setting up new read processing thread");
            while (!shutdown) {
                try {
                    Read first = readQueue.poll(1, TimeUnit.SECONDS);
                    if (first != null) {
                        outputLock.lock();
                        try {
                            pending.add(first);
                        } finally {
                            outputLock.unlock();
                        }
                        Thread.sleep(500); // reads rarely come in 1 at a time
                    }

                    outputLock.lock();
                    try {
                        if (shutdown) return;
                        if (closeRequested.getAndSet(false)) closeWriters();
                        readQueue.drainTo(pending);
                        writePending();
                    } finally {
                        outputLock.unlock();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    logger.error("Exception in the OutputProcessor thread", ex);
                }
            }
        };
        Thread readProcessThread = new Thread(readProcessor);
        readProcessThread.setName("Thread-readProcessThread");
        readProcessThread.setDaemon(true);
        readProcessThread.setPriority(1);
        readProcessThread.start();
    }

    // Must hold outputLock
    private void writePending() {
        if (pending.isEmpty()) return;
        List<Read> batch = new ArrayList<>(pending);
        pending.clear();
        logger.trace("Processing {} new reads", batch.size());

        // Group the reads by output file (keeping their order)
        File outputDir = PikaDownloaderPrefs.INSTANCE.getOutputDir();
        boolean dirOK = outputDir != null && outputDir.isDirectory() && outputDir.canWrite();
        Path dirPath = dirOK ? outputDir.toPath().toAbsolutePath().normalize() : null;

        Map<Path, List<Read>> readsByFile = new LinkedHashMap<>();
        Set<String> unsavedReaders = new LinkedHashSet<>();
        int unsavedCount = 0;

        // Never filter against an empty map, that would drop every read
        boolean onlyMapped = mappedChipsOnly && bibMapEnabled && !bibChipMap.isEmpty();
        int unmappedCount = 0;

        for (Read read : batch) {
            Reader r = read.reader();
            // Make sure we are supposed to do something here
            if (!r.getOutputToFileProperty().get()) continue;
            String outputFile = r.getOutputFileProperty().getValueSafe();
            if (outputFile.isBlank()) continue;

            if (onlyMapped) {
                String chip = Chips.normalize(read.chip());
                if (!TRIGGER_CHIP.equals(chip) && !bibChipMap.containsKey(chip)) {
                    unmappedCount++;
                    continue;
                }
            }

            if (!dirOK) {
                unsavedReaders.add(r.getReaderNameProperty().getValueSafe());
                unsavedCount++;
                continue;
            }

            Path path;
            try {
                path = dirPath.resolve(outputFile).normalize();
            } catch (InvalidPathException ex) {
                logger.error("Invalid output file name \"{}\" for reader {}", outputFile, r.getReaderNameProperty().getValueSafe());
                continue;
            }
            // Don't let a file name like ../foo.txt escape the output directory
            if (!dirPath.equals(path.getParent())) {
                logger.error("Output file \"{}\" for reader {} is not in the output directory", outputFile, r.getReaderNameProperty().getValueSafe());
                continue;
            }
            readsByFile.computeIfAbsent(path, p -> new ArrayList<>()).add(read);
        }

        if (unmappedCount > 0) logger.trace("Skipped {} reads for chips not in the chip -> bib map", unmappedCount);
        if (unsavedCount > 0) warnMissingOutputDir(unsavedReaders, unsavedCount);

        // Read once, so the whole batch uses the same offset
        ZoneOffset offset = timezoneOverride;

        if (readsByFile.size() == 1) {
            readsByFile.forEach((path, reads) -> writeReads(path, reads, offset));
        } else if (!readsByFile.isEmpty()) {
            // One task per file, so a file never has two writers
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                readsByFile.forEach((path, reads) -> executor.submit(() -> writeReads(path, reads, offset)));
            }
        }
    }

    // offset: the timezone override, or null for the reader's own date/time.
    // Only applied here (not when the read comes in) so de-duplication and
    // the reader's last read are unaffected.
    private void writeReads(Path path, List<Read> reads, ZoneOffset offset) {
        try {
            logger.trace("Writing {} reads to {}", reads.size(), path);
            BufferedWriter outputFileBW = bufferedWriterMap.get(path);
            if (outputFileBW == null) {
                outputFileBW = Files.newBufferedWriter(path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                bufferedWriterMap.put(path, outputFileBW);

                // If the file is empty, write out the header
                if (Files.size(path) == 0) {
                    outputFileBW.write(getHeader());
                    outputFileBW.newLine();
                }
            }

            for (Read read : reads) {
                String line;
                try {
                    line = readToString(read.atOffset(offset));
                } catch (RuntimeException ex) {
                    // Skip the bad read, but keep going with the rest
                    logger.error("Unable to format read {} from {}: {}", read.chip(), read.reader().getReaderNameProperty().getValueSafe(), ex.toString());
                    continue;
                }
                outputFileBW.write(line);
                outputFileBW.newLine();
            }
            outputFileBW.flush();

        } catch (IOException ex) {
            logger.error("Error writing {} reads to {}", reads.size(), path, ex);
            closeWriter(path);
        } catch (RuntimeException ex) {
            logger.error("Unexpected error writing {} reads to {}", reads.size(), path, ex);
        }
    }

    private void warnMissingOutputDir(Set<String> readers, int count) {
        if (missingDirWarned.compareAndSet(false, true)) {
            logger.error("The output directory is not set or is not writable: {} reads from {} were not saved", count, readers);
            String readerNames = String.join(", ", readers);
            Platform.runLater(() -> {
                Alert alert = new Alert(AlertType.WARNING);
                alert.setTitle("Output Directory Not Set");
                alert.setHeaderText("Reads are not being saved");
                alert.setContentText("The output directory is not set or is not writable, so the reads from "
                        + readerNames + " are not being saved.\n\n"
                        + "Select an output directory, then use Rewind to recover the missed reads.");
                alert.show();
            });
        } else {
            logger.debug("The output directory is not set: {} reads from {} were not saved", count, readers);
        }
    }

    // Must hold outputLock
    private void closeWriters() {
        new ArrayList<>(bufferedWriterMap.keySet()).forEach(this::closeWriter);
    }

    private void closeWriter(Path path) {
        BufferedWriter bw = bufferedWriterMap.remove(path);
        if (bw == null) return;
        try {
            bw.close(); // flushes
            logger.debug("Closed output file {}", path);
        } catch (IOException ex) {
            logger.error("Error closing {}: {}", path, ex.getMessage());
        }
    }

    private String readToString(Read r) {

        // We are going to abuse the fact that
        // dateTime returns "YYYY-MM-dd HH:MM:ss.SSS"
        // and just split on the space
        String dateTime = r.dateTime() == null ? "" : r.dateTime();
        int firstSpace = dateTime.indexOf(' ');
        String time = firstSpace < 0 ? dateTime : dateTime.substring(firstSpace + 1);
        String date = firstSpace < 0 ? "" : dateTime.substring(0, firstSpace);
        String antenna = String.valueOf(r.antenna());

        return switch (outputFormat) {
            case RFIDServer_EXT ->
                antenna + "," + r.chip() + "," + bibFor(r.chip()) + ",\"" + dateTime + "\"," + r.rfidReader() + "," + antenna;
            case RFIDServer -> antenna + "," + r.chip() + "," + bibFor(r.chip()) + ",\"" + time + "\""  ;
            case ChipTime -> r.chip() + "," + time;
            case BibTime -> bibFor(r.chip()) + "," + time;
            case ChipDateTime -> r.chip() + "," + date + "," + time;
            case BibDateTime -> bibFor(r.chip()) + "," +  date + "," + time;
            case CUSTOM -> compiledFormat.render(r);
        };
    }

    private String getHeader(){
        return switch (outputFormat){
            case RFIDServer_EXT -> "Ant,Chip,Bib,DateTime,reader,Ant";
            case RFIDServer -> "Ant,Chip,Bib,Time";
            case ChipTime -> "Chip,Time";
            case BibTime -> "Bib,Time";
            case ChipDateTime -> "Chip,Date,Time";
            case BibDateTime -> "Bib,Date,Time";
            case CUSTOM -> compiledFormat.header();
        };
    }

}

//
// Alternative to the toke Matcher that uses org.apache.commons.text.StringSubstitutor
// 
//
//public record UserRecord(String id, String name, String email) {}
//
//// A mutable wrapper to hold the context of the active iteration item
//class RecordContextLookup implements StringLookup {
//    private UserRecord currentRecord;
//
//    public void setRecord(UserRecord record) { this.currentRecord = record; }
//
//    @Override
//    public String lookup(String key) {
//        if (currentRecord == null) return null;
//        return switch (key) {
//            case "id" -> currentRecord.id();
//            case "name" -> currentRecord.name();
//            case "email" -> currentRecord.email();
//            default -> null;
//        };
//    }
//}
//
//// In your execution logic:
//RecordContextLookup contextLookup = new RecordContextLookup();
//// Instantiate ONCE. Reuses internal TextStringBuilder arrays.
//StringSubstitutor substitutor = new StringSubstitutor(contextLookup); 
//
//for (UserRecord record : massiveRecordList) {
//    contextLookup.setRecord(record); // Zero allocation context switch
//    String result = substitutor.replace(templateString);
//}
//

// Then incorporate the ${DATE:MM-DD-YYYY} setVariableValueDelimiter below


//import org.apache.commons.text.StringSubstitutor;
//import org.apache.commons.text.lookup.StringLookup;
//import java.time.ZoneId;
//import java.time.format.DateTimeFormatter;
//import java.time.temporal.TemporalAccessor;
//import java.util.Map;
//
//public class ModernRecordFormatter {
//    public static void main(String[] args) {
//        // 1. Define modern record data (works seamlessly with Java Records too)
//        Map<String, Object> recordData = Map.of(
//            "user", "Alice Smith",
//            "joinedDate", java.time.LocalDate.of(2024, 5, 12),
//            "loginTime", java.time.Instant.now() // Standard timestamp
//        );
//
//        // 2. Define the template using colon notation
//        String template = "User ${user} joined on ${joinedDate:yyyy-MM-dd}. Last login: ${loginTime:HH:mm:ss z}";
//
//        // 3. Create a lookup that understands colons as format splitters
//        StringLookup recordLookup = variableName -> {
//            if (variableName == null) return null;
//
//            // Split the variable name by the first colon
//            String[] parts = variableName.split(":", 2);
//            String fieldName = parts[0];
//            Object value = recordData.get(fieldName);
//
//            if (value == null) return null;
//
//            // If a format pattern is supplied and the object is a modern Java time type
//            if (parts.length > 1 && value instanceof TemporalAccessor temporal) {
//                String pattern = parts[1];
//                try {
//                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern)
//                            .withZone(ZoneId.systemDefault()); // Required for Instant objects
//                    return formatter.format(temporal);
//                } catch (IllegalArgumentException e) {
//                    return "INVALID_FORMAT[" + pattern + "]";
//                }
//            }
//
//            // Fallback for strings or standard datatypes without explicit patterns
//            return value.toString();
//        };
//
//        // 4. Configure the substitutor with the colon delimiter
//        StringSubstitutor sub = new StringSubstitutor(recordLookup);
//        sub.setVariableValueDelimiter(":"); // Allows ${VARIABLE:FORMAT} syntax
//
//        String result = sub.replace(template);
//        System.out.println(result);
//        // Output: User Alice Smith joined on 2024-05-12. Last login: 19:54:00 MDT
//    }
//}
