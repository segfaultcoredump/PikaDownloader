/*
 * Copyright (C) 2026 John Garner <segfaultcoredump@gmail.com>
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

import org.pikatimer.pikadownloader.util.AlphanumericComparator;
import org.pikatimer.pikadownloader.util.BibChipFileParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FXML Controller class
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class FXMLSetupBibMapController  {
    private static final Logger logger = LoggerFactory.getLogger(FXMLSetupBibMapController.class);
    private static final Preferences pikaPrefs = PikaDownloaderPrefs.INSTANCE.getPreferences();


    @FXML private TableView<ChipMap> bibMappingTableView;
    @FXML private TableColumn<ChipMap,String> chipTableColumn;
    @FXML private TableColumn<ChipMap,String> bibTableColumn;
    @FXML private Button deleteButton;
    @FXML private Button importButton;
    
    @FXML private Button addButton;
    @FXML private Button saveButton;
    @FXML private Button clearAllButton;
    @FXML private Button addRepeatButton;
    
    @FXML private TextField searchTextField;
    @FXML private Label mapCountLabel;
    
    @FXML private TextField bibTextField;
    @FXML private TextField chipTextField;
    
    @FXML private TextField startBibTextField;
    @FXML private TextField endBibTextField;
    @FXML private TextField chipOffsetTextField;


        
    // Sanity limit for the "add a range of bibs" button
    private static final int MAX_REPEATING_MAPPINGS = 100000;

    private final ObservableList<ChipMap> chipMapList = FXCollections.observableArrayList();
    FilteredList<ChipMap> filteredchipMapList;
    SortedList<ChipMap> sortedTimeList;
            
    BooleanProperty mapModified = new SimpleBooleanProperty(false);
    
    UnaryOperator<TextFormatter.Change> integerFilter = change -> {
        String newText = change.getControlNewText();

        // Allows empty string (clearing the field) or a valid integer pattern
        if (newText.isEmpty() || newText.matches("[1-9]\\d*")) { return change; }
        return null; // Reject change
    };
    
    
    public void initialize() {
        
        // Pull in any existing mappings into an observable list
        Map<String,String> chipMap = OutputProcessor.INSTANCE.getBibChipMap();
        
        // create the chip to bib list
        chipMap.keySet().forEach(k -> {
            chipMapList.add(new ChipMap(k,chipMap.get(k)));
        });
        
        
        filteredchipMapList = new FilteredList<>(chipMapList, p -> true);

        // 2. Set the filter Predicate whenever the filter changes.
        searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            updateFilterPredicate();
        });
        
        // 3. Wrap the FilteredList in a SortedList. 
        sortedTimeList = new SortedList<>(filteredchipMapList);

        // 4. Bind the SortedList comparator to the TableView comparator.
        sortedTimeList.comparatorProperty().bind(bibMappingTableView.comparatorProperty());
        
        // 5. Set the cell factories and stort routines... 
        bibTableColumn.setCellValueFactory(b -> new ReadOnlyStringWrapper(b.getValue().bib()));
        bibTableColumn.setComparator(new AlphanumericComparator());
        
        chipTableColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().chip()));
        chipTableColumn.setComparator(new AlphanumericComparator());

        bibMappingTableView.setItems(sortedTimeList);
        bibMappingTableView.setPlaceholder(new Label("No chip mappings have been entered yet"));
        
        mapCountLabel.textProperty().bind(Bindings.concat(Bindings.size(sortedTimeList).asString(),"/",Bindings.size(chipMapList).asString()));
        
        bibMappingTableView.getSortOrder().add(bibTableColumn);
        
        //disable buttons 
        saveButton.disableProperty().bind(mapModified.not());
        clearAllButton.disableProperty().bind(Bindings.size(chipMapList).isEqualTo(0));
        deleteButton.disableProperty().bind(Bindings.size(bibMappingTableView.getSelectionModel().getSelectedItems()).isEqualTo(0));
        //addButton.disableProperty().bind(); // only if there is text in the chip and bib text fields
        
        // We use an arbitrary node to get the window we are in to set the exit handler
        // Wrap this in a runLater to avoid a NPE since the window does not yet exist
        Platform.runLater(()-> {
            bibMappingTableView.getScene().getWindow().setOnCloseRequest( event -> {
                logger.debug("SetOnCloseRequest called...");
                if (mapModified.getValue()){
                    Alert closeConfirmation = new Alert(Alert.AlertType.CONFIRMATION);
                    closeConfirmation.setContentText("There are unsaved changes to the Chip -> Bib mappings.");
                    Button closeButton = (Button) closeConfirmation.getDialogPane().lookupButton(
                            ButtonType.OK
                    );
                    closeButton.setText("Close Anyway");
                    closeConfirmation.setHeaderText("Unsaved Changes...");
                    Optional<ButtonType> closeResponse = closeConfirmation.showAndWait();
                    if (!ButtonType.OK.equals(closeResponse.get())) {
                        event.consume();
                    }
                }
            });
        });  
        
        // Integers only... 
        startBibTextField.setTextFormatter(new TextFormatter<>(integerFilter));
        endBibTextField.setTextFormatter(new TextFormatter<>(integerFilter));
        chipOffsetTextField.setTextFormatter(new TextFormatter<>(integerFilter));
//        startBibTextField.textProperty().addListener((observable, oldValue, newValue) -> {
//            logger.debug("startChipTextField Text Changed (newValue: " + newValue + ")");
//            if (!newValue.isEmpty() && ! newValue.matches("^\\d+$")) {
//                    Platform.runLater(() -> { 
//                    int c = startBibTextField.getCaretPosition();
//                    startBibTextField.setText(oldValue);
//                    startBibTextField.positionCaret(c);
//                });
//            }
//        });
//        endBibTextField.textProperty().addListener((observable, oldValue, newValue) -> {
//            logger.debug("startChipTextField Text Changed (newValue: " + newValue + ")");
//            if (!newValue.isEmpty() && ! newValue.matches("^\\d+$")) {
//                    Platform.runLater(() -> { 
//                    int c = endBibTextField.getCaretPosition();
//                    endBibTextField.setText(oldValue);
//                    endBibTextField.positionCaret(c);
//                });
//            }
//        });
//        chipOffsetTextField.textProperty().addListener((observable, oldValue, newValue) -> {
//            logger.debug("startChipTextField Text Changed (newValue: " + newValue + ")");
//            if (!newValue.isEmpty() && ! newValue.matches("^\\d+$")) {
//                    Platform.runLater(() -> { 
//                    int c = chipOffsetTextField.getCaretPosition();
//                    chipOffsetTextField.setText(oldValue);
//                    chipOffsetTextField.positionCaret(c);
//                });
//            }
//        });
        
        // clean up the bib and chip text fields
        // no leading spaces, no leading zeroes
        // we remove trailing spaces on focus lost
        chipTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            logger.trace("TextField Text Changed (newValue: {})", newValue);
            if (!newValue.isEmpty()) {
                

                Platform.runLater(() -> { 
                    int c = chipTextField.getCaretPosition();
                    chipTextField.setText(newValue.replaceFirst("^[ 0]*", ""));
                    chipTextField.positionCaret(c);
                });
                 
            }
        });
        
        chipTextField.focusedProperty().addListener((ObservableValue<? extends Boolean> arg0, Boolean oldPropertyValue, Boolean newPropertyValue) -> {
            if (!newPropertyValue) {
                chipTextField.setText(chipTextField.getText().replaceFirst("^[ 0]*", "").replaceFirst(" *$", ""));
            }
        });
        
        bibTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            logger.trace("TextField Text Changed (newValue: {})", newValue);
            if (!newValue.isEmpty()) {
                

                Platform.runLater(() -> { 
                    int c = bibTextField.getCaretPosition();
                    bibTextField.setText(newValue.replaceFirst("^[ 0]*", ""));
                    bibTextField.positionCaret(c);
                });
                 
            }
        });
        
        bibTextField.focusedProperty().addListener((ObservableValue<? extends Boolean> arg0, Boolean oldPropertyValue, Boolean newPropertyValue) -> {
            if (!newPropertyValue) {
                bibTextField.setText(bibTextField.getText().replaceFirst("^[ 0]*", "").replaceFirst(" *$", ""));
            }
        });
        
        // This is stupid
        deleteButton.defaultButtonProperty().bind(deleteButton.focusedProperty());
        importButton.defaultButtonProperty().bind(importButton.focusedProperty());
        addButton.defaultButtonProperty().bind(addButton.focusedProperty());
        saveButton.defaultButtonProperty().bind(saveButton.focusedProperty());
        clearAllButton.defaultButtonProperty().bind(clearAllButton.focusedProperty());
        addRepeatButton.defaultButtonProperty().bind(addRepeatButton.focusedProperty());
        
    }
    
    public void deleteAction(ActionEvent fxevent){
        chipMapList.removeAll(bibMappingTableView.getSelectionModel().getSelectedItems());
        mapModified.setValue(Boolean.TRUE);
    }
    
    public void importAction(ActionEvent fxevent){
        FileChooser fileChooser = new FileChooser();

        fileChooser.setTitle("Select Bib -> Chip File");

        // The initial directory must exist (Windows throws if it doesn't)
        fileChooser.setInitialDirectory(PikaDownloaderPrefs.directoryOrHome(pikaPrefs.get("BibChipFileDir", "")));

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt","*.csv"),
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
                new FileChooser.ExtensionFilter("All files", "*")
            );

        File sourceFile = fileChooser.showOpenDialog(importButton.getScene().getWindow());
        if (sourceFile == null) return;
        if (sourceFile.getParentFile() != null) {
            pikaPrefs.put("BibChipFileDir", sourceFile.getParentFile().getAbsolutePath());
        }

        List<String> lines;
        try {
            lines = readLines(sourceFile.toPath());
        } catch (IOException ex) {
            logger.warn("Error Reading file {}",sourceFile.getAbsolutePath(),ex);
            showAlert(Alert.AlertType.ERROR, "Unable to read " + sourceFile.getName(), ex.getMessage());
            return;
        }

        BibChipFileParser.Result result = BibChipFileParser.parse(lines);
        if (result.error() != null) {
            showAlert(Alert.AlertType.WARNING, "Unable to import " + sourceFile.getName(), result.error());
            return;
        }

        mergeMappings(result.chipToBib());
        logger.debug("Imported {} mappings from {}, {} lines skipped. {} total mappings",
                result.chipToBib().size(), sourceFile.getAbsolutePath(), result.skipped().size(), chipMapList.size());

        if (!result.skipped().isEmpty()) {
            List<String> skipped = result.skipped();
            StringBuilder msg = new StringBuilder();
            msg.append("Imported ").append(result.chipToBib().size()).append(" mappings.\n\n")
               .append(skipped.size()).append(" lines did not have both a bib and a chip and were skipped:\n");
            skipped.stream().limit(10).forEach(s -> msg.append(s).append("\n"));
            if (skipped.size() > 10) msg.append("...and ").append(skipped.size() - 10).append(" more");
            showAlert(Alert.AlertType.WARNING, "Some lines were skipped", msg.toString());
        }
    }

    // Read the file as UTF-8, falling back to ISO-8859-1 for files saved
    // with a legacy (e.g. Windows) encoding
    private static List<String> readLines(Path path) throws IOException {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            return Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        }
    }

    // Add (or replace) mappings in one pass, then update the table once
    private void mergeMappings(Map<String, String> chipToBib) {
        if (chipToBib.isEmpty()) return;
        Map<String, ChipMap> merged = new LinkedHashMap<>();
        chipMapList.forEach(m -> merged.put(m.chip(), m));
        chipToBib.forEach((chip, bib) -> merged.put(chip, new ChipMap(chip, bib)));
        chipMapList.setAll(merged.values());
        mapModified.setValue(true);
    }

    private void showAlert(Alert.AlertType type, String header, String content) {
        Alert alert = new Alert(type);
        alert.setTitle("Chip to Bib Map Setup");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    public void addRepeatingMappingButtonAction(ActionEvent fxevent){

        if (startBibTextField.getText().isEmpty() || endBibTextField.getText().isEmpty() || chipOffsetTextField.getText().isEmpty()) return;

        try {
            long start = Long.parseLong(startBibTextField.getText());
            long end = Long.parseLong(endBibTextField.getText());
            long offset = Long.parseLong(chipOffsetTextField.getText());
            if (start == 0) start = 1;
            if (end < start ) {
                long tmp = start;
                start = end;
                end = tmp;
            }
            if (end - start + 1 > MAX_REPEATING_MAPPINGS) {
                showAlert(Alert.AlertType.WARNING, "Too many mappings",
                        "A range can add at most " + MAX_REPEATING_MAPPINGS + " mappings at a time.");
                return;
            }
            Map<String, String> chipToBib = new LinkedHashMap<>();
            for(long bib = start; bib <= end; bib++) {
                chipToBib.put(Long.toString(bib + offset), Long.toString(bib));
            }
            mergeMappings(chipToBib);

        } catch (NumberFormatException ex){
            logger.debug("Invalid repeating mapping: {}", ex.getMessage());
            showAlert(Alert.AlertType.WARNING, "Invalid range", "The start bib, end bib, and chip offset must be whole numbers.");
            return;
        }
        startBibTextField.setText("");
        endBibTextField.setText("");
        chipOffsetTextField.setText("");
    }
    public void addMappingButtonAction(ActionEvent fxevent){
        String chip = chipTextField.getText() == null ? "" : chipTextField.getText().trim();
        String bib = bibTextField.getText() == null ? "" : bibTextField.getText().trim();
        if (chip.isEmpty() || bib.isEmpty()) return; // Need both a chip and a bib

        ChipMap newMapping = new ChipMap(chip,bib);
        if (chipMapList.contains(newMapping)) chipMapList.remove(newMapping);

        chipMapList.add(newMapping);

        mapModified.setValue(true);

        chipTextField.setText("");
        bibTextField.setText("");
        chipTextField.requestFocus();
    }

    public void clearAllAction(ActionEvent fxevent){
        chipMapList.clear();
        mapModified.setValue(true);
    }

    @FXML
    public void saveButtonAction(ActionEvent fxevent){
        logger.debug("BibChipMapController: Save button pushed...");
        try {
        Map<String,String> bibMap = new HashMap<>();

        chipMapList.forEach(m -> bibMap.put(m.chip(), m.bib()));

        // Swap the mappings in without the map ever being empty part way through
        OutputProcessor.INSTANCE.setBibChipMap(bibMap);


        mapModified.setValue(false);


        Stage stage = (Stage) ((Node) fxevent.getSource()).getScene().getWindow();
            if (stage != null) {
                logger.debug("Calling stage.close()");
                stage.close();
            }
        } catch (Exception e) {
        logger.error("Failed to save and close window", e);
    }
    }

    public void cancelButtonAction(ActionEvent fxevent){
        // We will just kick off the onClose action below
        ((Node) fxevent.getSource()).getScene().getWindow().fireEvent(
            new WindowEvent((
                    (Node) fxevent.getSource()).getScene().getWindow(),
                    WindowEvent.WINDOW_CLOSE_REQUEST
            )
        );
    }

    // Compile the search pattern once per change, not once per row
    private void updateFilterPredicate(){
        String filter = searchTextField.textProperty().getValueSafe();
        if (filter.isEmpty()) {
            filteredchipMapList.setPredicate(chipMap -> true);
            return;
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            // Not a valid regex (yet), show everything
            filteredchipMapList.setPredicate(chipMap -> true);
            return;
        }
        filteredchipMapList.setPredicate(chipMap ->
                pattern.matcher(chipMap.chip()).find() || pattern.matcher(chipMap.bib()).find());
    }


    private record ChipMap (String chip, String bib){
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChipMap user = (ChipMap) o;
            // Strictly check the 'id' field
            return Objects.equals(chip, user.chip);
        }

        @Override
        public int hashCode() {
            // Compute the hash using only the 'id' field
            return Objects.hash(chip);
        }
    }
    
}
