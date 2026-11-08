# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is
PikaDownloader is a JavaFX desktop app that connects to one or more PikaReader RFID timing units on the LAN. It writes chip reads, both live and rewound, to local text files that a timing application imports continuously. It is licensed GPL-3.0. The README documents the user-facing custom output format escape codes (`$C`, `%{DT:pattern}`, and so on).

## Related project: PikaReader
This app is the client for PikaReader. A local copy of the PikaReader source is at `../PikaReader`, and it has its own CLAUDE.md. Every endpoint and message format this app uses is defined there, so check the server side before changing any parsing or requests:
- `../PikaReader/src/main/java/com/pikatimer/pikareader/http/HTTPHandler.java`: REST endpoints `/start`, `/stop`, `/rewind[/{from}[/{to}]]` and `/status`, plus the `/events` websocket that streams `READ`/`STATUS` JSON.
- `../PikaReader/src/main/java/com/pikatimer/pikareader/util/DiscoveryListener.java`: the UDP discovery responder.

## Build / run
Requires JDK 25 with JavaFX 25. `mvn` is not on PATH on this machine; NetBeans uses its bundled Maven.

- Build: `mvn -B package` produces `target/PikaDownloader-1.0-jar-with-dependencies.jar` (assembly plugin) plus a shaded jar.
- Run: `mvn clean javafx:run`. Use `mvn clean javafx:run@debug` to wait for a debugger on localhost:8000.
- NetBeans `nbactions.xml` runs through exec-maven-plugin with `--add-opens javafx.graphics/javafx.scene=ALL-UNNAMED --enable-native-access=javafx.graphics -Xms1g -Xmx1g`. The same flags are in `package.json` → `jdeploy.args`.
- There are no tests and no linter.

## Release
- Publishing a GitHub release triggers `.github/workflows/jdeploy.yml`. It runs `mvn package` on Zulu JDK+FX 25, then jDeploy builds native installers from the jar path in `package.json`.
- A version bump touches `pom.xml` `<version>`, `package.json` `version` **and** `jdeploy.jar` (the jar filename contains the version), and the window title string in `Downloader.java`.

## Architecture
All code lives in `org.pikatimer.pikadownloader` (a JPMS module, see `module-info.java`). New `requires` must be added there.

- **Entry point:** `PikaDownloader.main` → `Downloader` (the `Application`). The indirection is needed so the fat jar launches without the JavaFX launcher check. `Downloader` loads `FXMLmain.fxml` / `FXMLmainController`.
- **Singletons:** these are enum singletons.
  - `PikaDownloaderPrefs.INSTANCE` wraps `java.util.prefs` node `PikaDownloader` and holds the output dir and reader list.
  - `OutputProcessor.INSTANCE` owns the read queue, the bib→chip map and the output format.
- **`Reader` interface** (`getControlPane`, name, output file, output-to-file toggle). The `ListView<Reader>` uses `ReaderListCell`, which displays the pane each reader builds from its own FXML cell (`localReaderCell.fxml` / `remoteReaderCell.fxml` with matching `*CellController`). `ReaderListCell` also implements drag-to-reorder. It sizes the panes to the list, not the list to the panes: the cell's `computePrefWidth` asks only for the pane's *minimum* width, and the pane sits in a `StackPane` holder whose pref width is bound to the cell width. A `ListView` only ever grows a cell to the widest pref width it has seen, so a pane whose pref width depends on its own width (like the reader title graphic, bound to `titledPane.width - TITLE_ARROW_ALLOWANCE`) used to make the cells creep wider on every update. For the same reason the reader `TitledPane` has `minWidth(0)`, because a `TitledPane` uses its title's pref width as its minimum width.
- **`LocalReader`** is the main path. Readers are found by UDP broadcast of `DISCOVER_PIKA_READER_REQUEST` to port 8888; the reply is `PIKA_READER <unitID> <port>` (`FXMLmainController.addLocalDialog`).
  - It connects to `ws://ip:port/events` (Java-WebSocket). Messages are JSON with `type` `READ` or `STATUS`.
  - It controls the unit with HTTP `GET /start`, `/stop`, and `/rewind/<from>/<to>` (ISO local date-times; returns a JSON array of reads).
  - Reader profiles: each `STATUS` carries `profile: {id, name}`. `GET /profiles` lists the available ones, and `PUT /profile/{id}` changes the profile; it returns 409 while reading and can block for up to 30s. When `profile` is missing, the unit runs an older PikaReader, so `profileSupportedProperty` stays false and the cell hides the profile row. `LocalReaderCellController` keeps its own copy of the profile list and applies programmatic updates under a `syncingProfile` guard, so only real user picks trigger the confirmation prompt.
  - Error state (red `error-pane` title, `LocalReaderCellController.errorState`): while the reader is enabled, no STATUS for more than `OFFLINE_SECONDS` (10), battery at or below `LOW_BATTERY` (20%), or an RFID reader in STATUS `readers[]` with `connected: false` (`LocalReader.rfidReaderProblemProperty`, `""` when all are fine). The collapsed title has a second line from `titleStatusText()` that gives the reasons and then the battery level (`Disconnected · Low Battery: 15%`). Keep it in sync with the `errorState` binding. While expanded, an RFID problem shows in `rfidProblemLabel`.
  - Reads become `Read` records, are de-duplicated per reader, and are pushed onto `OutputProcessor`'s `BlockingQueue` with `put()` (it blocks when full, so large rewinds don't throw). `processedReads` deliberately keeps every read for the session, for de-duplication. Each read that is new to it is also appended to `ReadLog` (see Read Review). A 1s `Timeline` refreshes UI properties.
  - After a websocket closes, a reconnect is scheduled every 5s on one shared static `RECONNECT` scheduler. The reconnect itself runs on the FX thread and is skipped if the reader was disabled in the meantime.
  - Per-reader output filename is persisted under pref key `LOCAL-READER-OUTPUT-<unitID>`, where the key is captured from the discovery unit ID in the constructor.
  - Start, stop, rewind and profile requests use the shared async `HttpClient` with timeouts. Failures surface to the user as Alerts (`LocalReader.errorMessage` unwraps future exceptions).
- **`OutputProcessor`** runs one daemon thread.
  - It polls the queue, waits 500ms to batch, then **groups reads by output file** (not by reader). Each file is written by a single virtual-thread task, so readers sharing a file never interleave lines.
  - It keeps one appending `BufferedWriter` per absolute `Path` and writes a header row when the file is empty. A file name that would resolve outside the output dir is rejected.
  - Batches and writes happen under `outputLock`. `requestCloseAll()` closes every writer before the next batch; call it when the output dir, a file name or an output toggle changes. `shutdown()` (from `Downloader.stop()`) drains, writes and closes everything.
  - If the output dir is missing, it logs an error and shows one warning Alert until a dir is set.
  - Note the enum init order: `INSTANCE` is constructed before the static fields, so state the constructor needs must be instance fields.
  - Built-in formats come from the `OutputFormat` enum. `CUSTOM` is compiled once into a `CustomFormat`, where each token defines both its header name and its renderer, so header and rows stay in sync.
  - Malformed reads are logged and skipped per read.
  - Mapped-chips filter: when `mappedChipsOnly` (saved in prefs as `MappedChipsOnly`) and `bibMapEnabled` are both on, `writePending` skips reads whose chip is not in the bib map. Trigger reads (chip `"0"`, from PikaReader's `/trigger`) always pass, and an empty map never filters.
- **`RemoteReader`** is the legacy/unfinished relay path. It polls an HTTP relay (`status/`, `data/since/`) and writes files itself instead of going through `OutputProcessor`. The relay UI accordion in `FXMLmain.fxml` is hidden (`visible="false"`), and the per-read dispatch in the polling thread is commented out.
- **Read Review** (the "Review Reads..." button opens `FXMLReadReview.fxml` / `FXMLReadReviewController` in a non-modal `Stage`; a second click brings that window to the front):
  - `ReadLog.INSTANCE` is the append-only, synchronized list of every unique read this session, in arrival order. Each `Entry(seq, read)` carries an arrival `seq`, which gives the default newest-first order and breaks ties in every sort. Read it with `since(n)`.
  - The window is sized for about 1M reads, so it deliberately does **not** use `FilteredList`/`SortedList`: `SortedList` does O(n) work for each inserted row. Instead, the table's items are a `ReadReviewList` (an `ObservableListBase`). A custom `sortPolicy`, a search (regex, debounced 250ms) or a reader-filter change runs `rebuild()`: a parallel filter, then `Arrays.parallelSort` with direct-lambda column comparators, never the cell value factories. A 1 Hz `Timeline` filters the new `ReadLog` entries and merges them in with `ReadReviewList.merge()`, which uses binary-search positions and fires one change. When the user has scrolled down, the rows they are looking at stay in place.
  - A rebuild instead of a merge happens when a reader name changes, a new reader appears, or `OutputProcessor.bibMapVersion()` changes, because the current order may be stale.
  - The Bib column is shown only while `OutputProcessor.isBibMapInUse()` is true. It uses `mappedBibFor()`, which is blank for unmapped chips (no fallback to the chip). Chip, bib and reader sort with `util/AlphanumericComparator`, which is allocation-free and stateless, so keep it safe for parallel sorts. Time sorts by `epochMilli`.
  - Timelines run only while the window is open; it rebuilds from `ReadLog` when reopened. `Downloader` calls `Platform.exit()` when the main window is hidden, so an open review window doesn't keep the app running.
- **Bib→chip map:** `FXMLSetupBibMapController` parses CSV/TXT files with `util/BibChipFileParser`, a pure function that handles headers, quotes, a BOM and short lines. It saves through `OutputProcessor.setBibChipMap()`, which updates the same `ConcurrentHashMap` in place and is never empty part way through. Chip keys are normalized with `util/Chips.normalize` (leading zeroes stripped) on import, in `setBibChipMap()` and on every lookup; always look bibs up through `OutputProcessor.bibFor()`, never the raw map. `bibMapEnabled` mirrors the "Use Chip -> Bib Map" switch (session only): while it is off, or a chip has no mapping, bibs fall back to the chip number. `OutputProcessor.outputUsesBib()` (built-in formats, or `CustomFormat.usesBib()`) drives the inline warning label in `FXMLmainController.updateBibWarning()` when the output has a bib but no map is in use (it does not turn the pane red). The label has `prefWidth="0.0"` in the FXML so that, when it is shown at startup, it wraps to the window width instead of making the window wide enough for one line.
- **Output Settings title:** `FXMLmainController.setupOutputSettingsTitle()` replaces the title with a `GRAPHIC_ONLY` two-line graphic. The second line is shown only while the pane is collapsed. `updateOutputStatus()` sets it to the effective map/filter state (`Bib Map: On (N chips) · Filter: On`), or to the output dir problem from `PikaDownloaderPrefs.outputDirProblem()`. The `error-pane` red title is used only for output dir problems. The dir is re-checked every 5s on the `output-dir-check` daemon thread (a dead network share can block), so a dir that disappears later also turns the title and the dir field red.

## Conventions
- Network and file I/O run on background `Task`/daemon threads. Every JavaFX property or UI mutation from those threads goes through `Platform.runLater`.
- Logging is SLF4J + Logback (`src/main/resources/logback.xml`). Root is at INFO and `org.pikatimer` at DEBUG, written to the console and to a rolling file `${java.io.tmpdir}/PikaDownloader-*.log`. Per-message and per-read logging is at TRACE, so keep hot-path logs at `trace` and use `{}` parameters.
- File and directory choosers must use `PikaDownloaderPrefs.directoryOrHome(...)` for the initial directory, because Windows throws when it doesn't exist.
- Planned work is tracked in the root `TODO` file.
