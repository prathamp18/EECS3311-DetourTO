package ca.yorku.eecs3311.detourto.presentation.gui;

import javafx.scene.control.Alert;

/** Pop-up notice for things the rider must not miss (trip at risk, time to leave). */
final class Notifications {

    private Notifications() {
    }

    static void show(String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text);
        a.setHeaderText("DetourTO");
        a.show();
    }
}
