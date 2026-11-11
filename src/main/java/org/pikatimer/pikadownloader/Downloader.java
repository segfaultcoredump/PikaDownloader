
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class Downloader extends Application {
    private static Stage mainStage;
    private static final Logger logger = LoggerFactory.getLogger(Downloader.class);
    
    private static final PikaDownloaderPrefs prefs = PikaDownloaderPrefs.INSTANCE;

    /**
     * The window title, with the version and build time from
     * pikadownloader-build.properties (filled in by Maven at build time).
     * The build time is stored in UTC and shown in the local time zone.
     * Missing or unfiltered values are left out.
     */
    static String windowTitle() {
        Properties p = new Properties();
        try (InputStream in = Downloader.class.getResourceAsStream("/pikadownloader-build.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ex) {
            logger.debug("Unable to read pikadownloader-build.properties", ex);
        }

        StringBuilder title = new StringBuilder("Pika Downloader");
        String version = buildProperty(p, "version");
        if (version != null) {
            title.append(" v").append(version);
        }
        String built = buildProperty(p, "built");
        if (built != null) {
            try {
                title.append(" (built ")
                        .append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .format(Instant.parse(built).atZone(ZoneId.systemDefault())))
                        .append(')');
            } catch (DateTimeParseException ex) {
                logger.debug("Unable to parse build time {}", built, ex);
            }
        }
        return title.toString();
    }

    // A property that is missing, blank or was never filled in by Maven
    // (still "${...}") is treated as absent.
    private static String buildProperty(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank() || value.startsWith("${")) {
            return null;
        }
        return value.trim();
    }


    @Override
    public void start(Stage primaryStage) throws Exception{

        prefs.setApplication(this);

        mainStage=primaryStage;
        String title = windowTitle();
        // Log which build is running, so a stale install is easy to spot
        logger.info("{}", title);
        primaryStage.setTitle(title);
        
        Parent myPane = FXMLLoader.load(getClass().getResource("FXMLmain.fxml"));
        Scene myScene = new Scene(myPane);
        
 
        // Icons
        String[] sizes = {"256","128","64","48","32"};
        for(String s: sizes){
            logger.debug("Adding icon size {}",s);
            //primaryStage.getIcons().add(new Image("icons/PikaDownloader_"+s+"x"+s+".ico"));
            primaryStage.getIcons().add(new Image("icons/PikaDownloader_"+s+"x"+s+".png"));
        }
        // Note: JavaFX can't decode .ico files, so only the PNGs are used here
        // Total hack and a half to set the dock icon in MacOS
        // From https://runmodule.com/2020/01/05/how-to-set-dock-icon-of-java-application/ 
        if (SystemUtils.IS_OS_MAC) {
            final java.awt.Toolkit defaultToolkit = java.awt.Toolkit.getDefaultToolkit();
            final URL imageResource = getClass().getClassLoader().getResource("icons/PikaDownloader_256x256.png");
            final java.awt.Image image = defaultToolkit.getImage(imageResource);
 
            final java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar();

            try {
                taskbar.setIconImage(image);
            } catch (final UnsupportedOperationException e) {
                logger.debug("The os does not support: 'taskbar.setIconImage'");
            } catch (final SecurityException e) {
                logger.debug("There was a security exception for: 'taskbar.setIconImage'");
            }
        }
        
        //mainStage.setWidth(600);
        //mainStage.setHeight(400);
        
        primaryStage.setScene(myScene);

        // Closing the main window quits, even if other (non-modal) windows
        // like the Read Review are still open. Calls stop() below.
        primaryStage.setOnHidden(event -> Platform.exit());

        Platform.runLater(() -> {
        
            Rectangle2D primaryScreenBounds = Screen.getPrimary().getVisualBounds();  
            //set Stage boundaries so that the window is in the upper right   
            logger.trace("Screen Size: {} x {}",primaryScreenBounds.getWidth(),primaryScreenBounds.getHeight());
            logger.trace("Window Size: {} x {}",primaryStage.getWidth(),primaryStage.getHeight());

            primaryStage.setX((primaryScreenBounds.getWidth() - primaryStage.getWidth()));  
            primaryStage.setY(0);  
            
            // This will center it by default
            //primaryStage.setX((primaryScreenBounds.getWidth() - primaryStage.getWidth())/2);  
            //primaryStage.setY((primaryScreenBounds.getHeight() - primaryStage.getHeight())/2);
        
        });        
        
        primaryStage.show();
        
        
    }

    @Override
    public void stop() {
        // Write out any queued reads and close the output files
        logger.info("Shutting down");
        OutputProcessor.INSTANCE.shutdown();
        ReadAlert.INSTANCE.shutdown();
    }

    public static void main(String[] args) {
        launch();
    }
    
    
    public Stage getPrimaryStage() {
        return mainStage;
    }

}