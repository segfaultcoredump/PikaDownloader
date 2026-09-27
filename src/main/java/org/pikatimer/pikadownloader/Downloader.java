package org.pikatimer.pikadownloader;

import java.net.URL;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * JavaFX Downloader
 */
public class Downloader extends Application {
    private static Stage mainStage;
    static final Logger logger = LoggerFactory.getLogger(Downloader.class);


    @Override
    public void start(Stage primaryStage) throws Exception{


        mainStage=primaryStage;
        primaryStage.setTitle("Pika Downloader v1.0");
        
        Parent myPane = FXMLLoader.load(getClass().getResource("FXMLmain.fxml"));
        Scene myScene = new Scene(myPane);
        
        
  
        
 
        // Icons
        String[] sizes = {"256","128","64","48","32"};
        for(String s: sizes){
            logger.debug("Adding icon size {}",s);
            //primaryStage.getIcons().add(new Image("icons/PikaDownloader_"+s+"x"+s+".ico"));
            primaryStage.getIcons().add(new Image("icons/PikaDownloader_"+s+"x"+s+".png"));
        }
        primaryStage.getIcons().add(new Image("icons/PikaDownloader.ico"));
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

    public static void main(String[] args) {
        launch();
    }
    
    
    public Stage getPrimaryStage() {
        return mainStage;
    }

}