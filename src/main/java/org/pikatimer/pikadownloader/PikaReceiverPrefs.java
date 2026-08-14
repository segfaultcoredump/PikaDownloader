/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikadownloader;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 *
 * @author john
 */
public enum PikaReceiverPrefs {
    INSTANCE;
    
    private static final Preferences prefs = Preferences.userRoot().node("PikaReceiver");
    private File outputDir = null;
    private String echoEndpoint = "";
    
    // TODO: Move this to the OutputProcessor
    

       
    public Preferences getPreferences(){
        return prefs;
    }
    
    public File getOutputDir(){
        return outputDir;
    }
    
    public void setOutputDir(File d){
        outputDir = d;
    }
    
    public String getEchoEndpoint(){
        return echoEndpoint;
    }
    
    public void setEchoEndpoint(String e){
        echoEndpoint = e;
    }
    
    
    
}
