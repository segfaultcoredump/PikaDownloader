/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikadownloader;

import java.io.File;
import java.util.prefs.Preferences;
import javafx.application.Application;
import javafx.collections.ObservableList;

/**
 *
 * @author john
 */
public enum PikaDownloaderPrefs {
    INSTANCE;
    
    private static final Preferences prefs = Preferences.userRoot().node("PikaDownloader");
    private volatile File outputDir = null;
    private Application application;
    ObservableList<Reader> readerList;
           
    public Preferences getPreferences(){
        return prefs;
    }

    /**
     * @param path a directory path (may be null)
     * @return the path if it is an existing directory, otherwise the user's
     * home directory. Use this for FileChooser / DirectoryChooser initial 
     * directories: Windows throws if the initial directory doesn't exist.
     */
    public static File directoryOrHome(String path) {
        if (path != null && !path.isBlank()) {
            File dir = new File(path);
            if (dir.isDirectory()) return dir;
        }
        return new File(System.getProperty("user.home"));
    }

    /**
     * Uses the same checks as OutputProcessor before it writes any reads.
     * This does file I/O, which can block on a dead network share, so call
     * it off the FX thread unless the directory was just picked.
     *
     * @param dir the output directory (may be null)
     * @return why reads can't be saved to the directory, or null if it is OK
     */
    public static String outputDirProblem(File dir) {
        if (dir == null) return "No output directory set";
        if (!dir.isDirectory()) return "Output directory not found";
        if (!dir.canWrite()) return "Output directory is not writable";
        return null;
    }

    public File getOutputDir(){
        return outputDir;
    }
    
    public void setOutputDir(File d){
        outputDir = d;
    }
    
    // The PikaRelay settings from the Remote Relay section. The secret is kept
    // in plain text in java.util.prefs (the registry on Windows, a plist on
    // macOS), like the rest of the settings.
    private static final String RELAY_URL_PREF = "RelayURL";
    private static final String RELAY_KEY_PREF = "RelayKey";
    private static final String RELAY_SECRET_PREF = "RelaySecret";

    /**
     * @return the relay from the Remote Relay section (the address may be
     * blank or not valid yet, see PikaRelay.urlProblem)
     */
    public PikaRelay getRelay(){
        return new PikaRelay(prefs.get(RELAY_URL_PREF, ""), prefs.get(RELAY_KEY_PREF, ""), prefs.get(RELAY_SECRET_PREF, ""));
    }

    public void setRelay(PikaRelay relay){
        prefs.put(RELAY_URL_PREF, relay.baseURL());
        prefs.put(RELAY_KEY_PREF, relay.key());
        prefs.put(RELAY_SECRET_PREF, relay.secret());
    }
    
    public void setApplication(Application app){
        application = app;
    }
    public Application getApplication(){
        return application;
    }
    
    public void setReaderList(ObservableList<Reader> readers) {
        readerList = readers;
    }
    
    public ObservableList<Reader> getReaderList(){
        return readerList;
    }
            
    
}
