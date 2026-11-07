package ca.yorku.eecs3311.detourto.presentation.gui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;

/** Small layout helper so panels share the same spacing. */
final class BorderPaneMargins {

    private BorderPaneMargins() {
    }

    static void top(Node n) {
        BorderPane.setMargin(n, new Insets(8, 0, 0, 0));
    }
}
