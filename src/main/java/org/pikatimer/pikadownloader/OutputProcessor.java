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
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */
//todo: make this an enum singleton
public enum OutputProcessor {
    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(OutputProcessor.class);

    private static final Preferences prefs = PikaReceiverPrefs.INSTANCE.getPreferences();
    private static final BlockingQueue<Read> readQueue = new ArrayBlockingQueue(100000);
    private static final Map<String, BufferedWriter> bufferedWriterMap = new ConcurrentHashMap();

    private OutputFormat outputFormat = OutputFormat.valueOf(PikaReceiverPrefs.INSTANCE.getPreferences().get("OutputFormat", OutputFormat.Chip2Time.name()));
    private String customFormat = PikaReceiverPrefs.INSTANCE.getPreferences().get("CustomOutputFormat", "");

    private static final Map<String, String> bibChipMap = new HashMap();

    // Pattern matcher for the custom output format string
    // Group 1: Matches the escape backslash character
    // Group 2: Matches %TOKEN or $TOKEN styles
    // Group 3: Matches ${TOKEN} or %{TOKEN} styles
    // Group 4: Matches the optional :PARAM within ${TOKEN:PARAM}
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(\\\\)?(?:[\\%$]([A-Za-z]+)|[\\%$]\\{([A-Za-z]+)(?::([^}]+))?})",
            Pattern.CASE_INSENSITIVE
    );

    private OutputProcessor() {
        setupReadProcessor();
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
        prefs.put("CustomOutputFormat", format);
    }

    public BlockingQueue<Read> getReadQueue() {
        return readQueue;
    }

    public Map<String, String> getBibChipMap() {
        return bibChipMap;
    }

    private void setupReadProcessor() {
        Task readProcessTask = new Task<Void>() {

            @Override
            public Void call() {

                logger.debug("OutputProcessor: setting up new read processing thread");
                while (true) {
                    try {
                        logger.trace("Waiting for reads...");
                        List<Read> newReads = new ArrayList();
                        newReads.add(readQueue.take());

                        Thread.sleep(500); // reads rarely come in 1 at a time

                        readQueue.drainTo(newReads);
                        logger.trace("Processing {} new reads", newReads.size());

                        // split them into a per-reader map
                        Map<Reader, List<Read>> readMap = new ConcurrentHashMap();
                        newReads.forEach(r -> {
                            if (!readMap.containsKey(r.reader())) {
                                readMap.put(r.reader(), new ArrayList<>());
                            }
                            readMap.get(r.reader()).add(r);
                        });

                        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            readMap.entrySet().forEach(entry -> {
                                executor.submit(() -> processReads(entry.getKey(), entry.getValue()));
                            });
                        }
                    } catch (Exception ex) {
                        logger.debug("Exception in main OutputProcesser Thread", ex);
                    }
                }
            }
        };
        Thread readProcessThread = new Thread(readProcessTask);
        readProcessThread.setName("Thread-readProcessThread");
        readProcessThread.setDaemon(true);
        readProcessThread.setPriority(1);
        readProcessThread.start();
    }

    private void processReads(Reader r, List<Read> reads) {
        logger.trace("Procesing {} reads for {}", reads.size(), r.getReaderNameProperty().getValue());

        // Make sure we are supposed to do something here
        if (r.getOutputToFileProperty().get() && !r.getOutputFileProperty().getValue().isBlank()) {
            String outputFile = r.getOutputFileProperty().getValue();
            try {
                if (!bufferedWriterMap.containsKey(outputFile)) {
                    try {
                        File file = new File(PikaReceiverPrefs.INSTANCE.getOutputDir(), outputFile);
                        FileWriter fw = new FileWriter(file, true);
                        bufferedWriterMap.put(outputFile, new BufferedWriter(fw));
                    } catch (IOException ex) {
                        logger.error(ex.getMessage());
                        return;
                    }
                }

                BufferedWriter outputFileBW = bufferedWriterMap.get(outputFile);

                // avoiding a lambda here so we can cleanly die if we have an exception
                for (Read read : reads) {
                    outputFileBW.write(readToString(read));
                    outputFileBW.newLine();
                }
                outputFileBW.flush();

            } catch (IOException ex) {
                logger.error("Error writing to {} for reader {}", outputFile, r.getReaderNameProperty().getValue(), ex);
                try {
                    bufferedWriterMap.get(outputFile).close();
                } catch (IOException ex1) {
                    logger.error(ex1.getMessage());
                }
                bufferedWriterMap.remove(outputFile);
            }
        }
    }

    private String readToString(Read r) {

        String output = "";
        switch (outputFormat) {
            case RFIDServer_EXT ->
                output = r.antenna().toString() + "," + r.chip() + "," + bibChipMap.getOrDefault(r.chip(), r.chip()) + ",\"" + r.dateTime() + "\"," + r.rfidReader().toString() + "," + r.antenna().toString();
            case RFIDServer -> output = r.antenna().toString() + "," + r.chip() + "," + bibChipMap.getOrDefault(r.chip(), r.chip()) + ",\"" + r.dateTime().substring(r.dateTime().indexOf(" ") + 1) + "\""  ;
            case Chip2Time ->
                output = r.chip() + ",\"" + r.dateTime() + "\"";
            case Bib2Time ->
                output = bibChipMap.getOrDefault(r.chip(), r.chip()) + ",\"" + r.dateTime() + "\"";
            case CUSTOM -> {
                output = processCustomOutputString(r);
            }
        }

        return output;
    }


    private String processCustomOutputString(Read read) {
        logger.debug("Processing read with custom customFormat: {}",customFormat);
        if (customFormat == null) return "";
        else if (customFormat.isBlank()) return "";

        Matcher matcher = TOKEN_PATTERN.matcher(customFormat);
        StringBuilder sb = new StringBuilder(customFormat.length());

        while (matcher.find()) {
            // Check if Group 1 found a backslash
            boolean isEscaped = matcher.group(1) != null;

            if (isEscaped) {
                // Strip the backslash and keep the rest of the match exactly as literal text
                String literalToken = matcher.group().substring(1);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(literalToken));
                continue;
            }

            String tokenName;
            String parameter = null;

            // Determine if it was a %TOKEN or ${TOKEN} style match
            if (matcher.group(2) != null) {
                tokenName = matcher.group(2).toLowerCase();
            } else {
                tokenName = matcher.group(3).toLowerCase();
                parameter = matcher.group(4);
            }

            
            // 2. Perform property replacement mapping
            String replacement = switch (tokenName) {
                case "b", "bib" ->
                    bibChipMap.getOrDefault(read.chip(), read.chip());
                case "c", "chip" ->
                    read.chip();
                case "a", "antenna" ->
                    read.antenna().toString();
                case "r", "reader" ->
                    read.rfidReader().toString();
                //case "dt", "datetime" ->
                //    read.dateTime();
                case "e", "epochmilli" ->
                    read.epochMilli().toString();
                case "z","tz", "timezone" -> 
                    read.tz();
                case "d", "date", "t", "time", "dt", "datetime"-> {
                    
                    if (parameter == null) {
                        // We are going to abuse the fact that
                        // dateTime returns "YYYY-MM-dd HH:MM:ss.SSS"
                        // and just split on the space
                        yield switch (tokenName){
                            case "dt", "datetime" -> read.dateTime();
                            case "d","date" -> read.dateTime().split("\\s+", 2)[0];
                            case "t", "time" -> read.dateTime().split("\\s+", 2)[1];
                            default -> read.dateTime();
                        };
                    } else {
                        LocalDateTime localDateTime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(read.epochMilli()),
                            ZoneId.of(read.tz())
                        );
                        try {
                            yield localDateTime.format(DateTimeFormatter.ofPattern(parameter));
                        } catch (IllegalArgumentException e) {
                            yield "[Invalid DateTimeFormatter Pattern: " + parameter + "]";
                        }
                    }
                }
                // Unrecognized properties return null here to trigger the default fallback logic
                default ->
                    null;
            };

            // 3. Fallback: If property is missing, leave the text completely intact
            if (replacement == null) {
                replacement = matcher.group();
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
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
