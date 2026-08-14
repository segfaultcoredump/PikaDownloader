module org.pikatimer.pikareceiver {
    requires javafx.controls;
    requires java.base;
    requires java.net.http;
    requires javafx.fxml;
    requires org.json;
    requires org.slf4j;
    requires java.prefs;
    requires org.controlsfx.controls; 

    opens org.pikatimer.pikareceiver to javafx.fxml;
    exports org.pikatimer.pikareceiver;
    
}
