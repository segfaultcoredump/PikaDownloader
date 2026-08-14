module org.pikatimer.pikadownloader {
    requires javafx.controls;
    requires java.base;
    requires java.net.http;
    requires javafx.fxml;
    requires org.json;
    requires org.slf4j;
    requires java.prefs;
    requires org.controlsfx.controls; 
    requires org.java_websocket;
    requires org.apache.commons.lang3;
    requires java.desktop;

    opens org.pikatimer.pikadownloader to javafx.fxml, javafx.graphics;
    exports org.pikatimer.pikadownloader;
    
}
