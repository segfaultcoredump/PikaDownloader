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
    private File outputDir = null;
    private String echoEndpoint = "";
    private Application application;
    ObservableList<Reader> readerList;
           
    public Preferences getPreferences(){
        return prefs;
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
