/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pikatimer.pikareceiver;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 *
 * @author john
 */
public class PikaReceiverPrefs {
    private static final Preferences prefs = Preferences.userRoot().node("PikaReceiver");
    private File outputDir = null;
    private String echoEndpoint = "";
    private Map<String,String> bibChipMap = new HashMap();

    /**
    * SingletonHolder is loaded on the first execution of Singleton.getInstance() 
    * or the first access to SingletonHolder.INSTANCE, not before.
    */
    
    private static class SingletonHolder { 
            private static final PikaReceiverPrefs INSTANCE = new PikaReceiverPrefs();
    }

    public static PikaReceiverPrefs getInstance() {
        
            return SingletonHolder.INSTANCE;
    }
    
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
    
    public Map<String,String> getBibChipMap(){
        return bibChipMap;
    }
    
}
