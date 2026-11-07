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
- **`Reader` interface** (`getControlPane`, name, output file, output-to-file toggle). The `ListView<Reader>` uses `ReaderListCell`, which displays the pane each reader builds from its own FXML cell (`localReaderCell.fxml` / `remoteReaderCell.fxml` with matching `*CellController`). `ReaderListCell` also implements drag-to-reorder.
- **`LocalReader`** is the main path. Readers are found by UDP broadcast of `DISCOVER_PIKA_READER_REQUEST` to port 8888; the reply is `PIKA_READER <unitID> <port>` (`FXMLmainController.addLocalDialog`).
  - It connects to `ws://ip:port/events` (Java-WebSocket). Messages are JSON with `type` `READ` or `STATUS`.
  - It controls the unit with HTTP `GET /start`, `/stop`, and `/rewind/<from>/<to>` (ISO local date-times; returns a JSON array of reads).
  - Reader profiles: each `STATUS` carries `profile: {id, name}`. `GET /profiles` lists the available ones, and `PUT /profile/{id}` changes the profile; it returns 409 while reading and can block for up to 30s. When `profile` is missing, the unit runs an older PikaReader, so `profileSupportedProperty` stays false and the cell hides the profile row. `LocalReaderCellController` keeps its own copy of the profile list and applies programmatic updates under a `syncingProfile` guard, so only real user picks trigger the confirmation prompt.
  - Reads become `Read` records, are de-duplicated per reader, and are pushed onto `OutputProcessor`'s `BlockingQueue`. A 1s `Timeline` refreshes UI properties.
  - Per-reader output filename is persisted under pref key `LOCAL-READER-OUTPUT-<unitID>`.
- **`OutputProcessor`** runs one daemon thread. It takes from the queue, waits 500ms to batch, groups reads by reader and writes each group on a virtual thread. It keeps one appending `BufferedWriter` per output filename and writes a header row when the file is empty. Built-in formats come from the `OutputFormat` enum. `CUSTOM` uses `TOKEN_PATTERN`; header and row rendering are separate methods that must be kept in sync when adding tokens.
- **`RemoteReader`** is the legacy/unfinished relay path. It polls an HTTP relay (`status/`, `data/since/`) and writes files itself instead of going through `OutputProcessor`. The relay UI accordion in `FXMLmain.fxml` is hidden (`visible="false"`), and the per-read dispatch in the polling thread is commented out.
- **Bib→chip map:** `FXMLSetupBibMapController` imports a CSV/TXT into `OutputProcessor.getBibChipMap()`. Bib-based formats fall back to the chip number when a chip has no mapping.

## Conventions
- Network and file I/O run on background `Task`/daemon threads. Every JavaFX property or UI mutation from those threads goes through `Platform.runLater`.
- Logging is SLF4J + Logback (`src/main/resources/logback.xml`). The root level is TRACE, written to the console and to a rolling file `${java.io.tmpdir}/PikaReciever-*.log`.
- Planned work is tracked in the root `TODO` file.
