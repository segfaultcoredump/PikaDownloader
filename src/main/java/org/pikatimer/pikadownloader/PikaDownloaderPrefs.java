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
    private String echoEndpoint = "";
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
    
    public String getRelayEndpoint(){
        return echoEndpoint;
    }
    
    public void setRelayEndpoint(String e){
        echoEndpoint = e;
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
