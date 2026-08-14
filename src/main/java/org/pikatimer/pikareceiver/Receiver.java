package org.pikatimer.pikareceiver;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;



/**
 * JavaFX Receiver
 */
public class Receiver extends Application {
    private static Stage mainStage;


    @Override
    public void start(Stage primaryStage) throws Exception{


        mainStage=primaryStage;
        primaryStage.setTitle("Relay Receiver");
        
        Pane myPane = (Pane)FXMLLoader.load(getClass().getResource("FXMLmain.fxml"));
        Scene myScene = new Scene(myPane);
        
        Rectangle2D primaryScreenBounds = Screen.getPrimary().getVisualBounds();  
  
        //set Stage boundaries so that the main screen is centered.                
        primaryStage.setX((primaryScreenBounds.getWidth() - primaryStage.getWidth())/2);  
        primaryStage.setY((primaryScreenBounds.getHeight() - primaryStage.getHeight())/2);  
 
        
        primaryStage.setScene(myScene);
        primaryStage.show();
        
        
    }

    public static void main(String[] args) {
        launch();
    }
    
    
    public Stage getPrimaryStage() {
        return mainStage;
    }

}