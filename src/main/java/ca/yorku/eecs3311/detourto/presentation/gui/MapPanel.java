package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.RoutePattern;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;

/**
 * Draws the network, the selected itinerary and current closures on a canvas (offline, no map
 * tiles). Subway lines are thick, streetcars medium, buses thin; the chosen trip is drawn on top
 * in its route colours, walks dashed, closed stops crossed out in red.
 */
public final class MapPanel extends Pane {

    private static final Color[] ROUTE_COLOURS = {Color.web("#f8c300"), Color.web("#00923f"), Color.web("#d62d20"),
        Color.web("#0b6fb3"), Color.web("#8e44ad"), Color.web("#e67e22"), Color.web("#16a085")};

    private final Canvas canvas = new Canvas();
    private final TransitNetwork network;
    private Itinerary itinerary;
    private List<Disruption> disruptions = List.of();

    public MapPanel(TransitNetwork network) {
        this.network = network;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener(o -> render());
        canvas.heightProperty().addListener(o -> render());
        setMinSize(300, 300);
        setStyle("-fx-background-color: white; -fx-border-color: #e4e7eb;");
    }

    public void drawItinerary(Itinerary i) {
        this.itinerary = i;
        render();
    }

    public void highlightDisruptions(List<Disruption> ds) {
        this.disruptions = List.copyOf(ds);
        render();
    }

    public void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (w < 10 || h < 10) {
            return;
        }
        List<Stop> drawn = new ArrayList<>();
        for (Stop s : network.stops()) {
            if (!s.station()) {
                drawn.add(s);
            }
        }
        MapProjection p = new MapProjection(drawn, w, h, 40);
        g.setLineCap(StrokeLineCap.ROUND);

        // network, faded
        for (RoutePattern pat : network.patterns()) {
            Color c = colour(pat.route().id()).deriveColor(0, 0.6, 1.15, 0.45);
            g.setStroke(c);
            g.setLineWidth(width(pat.route().mode()));
            for (int i = 1; i < pat.stopCount(); i++) {
                Stop a = network.stopAt(pat.stopAt(i - 1));
                Stop b = network.stopAt(pat.stopAt(i));
                g.strokeLine(p.x(a.lon()), p.y(a.lat()), p.x(b.lon()), p.y(b.lat()));
            }
        }
        g.setFill(Color.web("#52606d"));
        for (Stop s : drawn) {
            g.fillOval(p.x(s.lon()) - 2, p.y(s.lat()) - 2, 4, 4);
        }

        // the chosen trip
        Set<String> tripStops = new HashSet<>();
        if (itinerary != null) {
            for (Leg leg : itinerary.legs()) {
                if (leg instanceof RideLeg r) {
                    g.setStroke(colour(r.route().id()));
                    g.setLineWidth(7);
                    g.setLineDashes();
                    for (int pos = r.boardPos() + 1; pos <= r.alightPos(); pos++) {
                        Stop a = network.stopAt(r.trip().stopIndexAt(pos - 1));
                        Stop b = network.stopAt(r.trip().stopIndexAt(pos));
                        g.strokeLine(p.x(a.lon()), p.y(a.lat()), p.x(b.lon()), p.y(b.lat()));
                    }
                } else {
                    g.setStroke(Color.web("#334e68"));
                    g.setLineWidth(3);
                    g.setLineDashes(6, 6);
                    g.strokeLine(p.x(leg.from().lon()), p.y(leg.from().lat()), p.x(leg.to().lon()), p.y(leg.to().lat()));
                    g.setLineDashes();
                }
                tripStops.add(leg.from().id());
                tripStops.add(leg.to().id());
            }
            marker(g, p, itinerary.origin(), Color.web("#2f855a"), "Start");
            marker(g, p, itinerary.destination(), Color.web("#2b6cb0"), "End");
        }

        // closures and outages
        for (Disruption d : disruptions) {
            if (!d.affectsRouting()) {
                continue;
            }
            Color c = d.kind() == DisruptionKind.ELEVATOR_OUTAGE ? Color.web("#dd6b20") : Color.web("#c53030");
            for (String id : d.closedStopIds()) {
                network.findStop(id).ifPresent(s -> cross(g, p, s, c));
            }
        }

        // station names: trip stops first, then others where there is room
        g.setFont(Font.font(11));
        List<double[]> placed = new ArrayList<>();
        List<Stop> labelled = new ArrayList<>();
        for (Stop s : network.stops()) {
            boolean onTrip = tripStops.contains(s.id())
                    || network.boardingStopsFor(s).stream().anyMatch(c -> tripStops.contains(c.id()));
            if (onTrip && (s.station() || s.parentStation() == null)) {
                labelled.add(s);
            }
        }
        for (Stop s : network.stops()) {
            if (s.station() && !labelled.contains(s)) {
                labelled.add(s);
            }
        }
        for (Stop s : labelled) {
            String name = s.name().replace(" Station", "");
            double x = p.x(s.lon()) + 6;
            double y = p.y(s.lat()) - 4;
            double[] box = {x - 2, y - 11, x + name.length() * 6.2 + 2, y + 3};
            boolean free = placed.stream().noneMatch(b -> box[0] < b[2] && b[0] < box[2] && box[1] < b[3] && b[1] < box[3]);
            if (free) {
                placed.add(box);
                g.setFill(Color.rgb(255, 255, 255, 0.8));
                g.fillRect(box[0], box[1], box[2] - box[0], box[3] - box[1]);
                g.setFill(Color.web("#243b53"));
                g.fillText(name, x, y);
            }
        }
        legend(g, h);
    }

    private void marker(GraphicsContext g, MapProjection p, Stop s, Color c, String label) {
        double x = p.x(s.lon());
        double y = p.y(s.lat());
        g.setFill(c);
        g.fillOval(x - 8, y - 8, 16, 16);
        g.setFill(Color.WHITE);
        g.fillOval(x - 3, y - 3, 6, 6);
        g.setFill(c);
        g.setFont(Font.font(null, javafx.scene.text.FontWeight.BOLD, 12));
        g.fillText(label, x + 10, y + 16);
    }

    private void cross(GraphicsContext g, MapProjection p, Stop s, Color c) {
        double x = p.x(s.lon());
        double y = p.y(s.lat());
        g.setStroke(c);
        g.setLineWidth(3);
        g.strokeLine(x - 7, y - 7, x + 7, y + 7);
        g.strokeLine(x - 7, y + 7, x + 7, y - 7);
    }

    private void legend(GraphicsContext g, double h) {
        g.setFont(Font.font(11));
        double y = h - 52;
        g.setFill(Color.web("#c53030"));
        g.fillText("X  closed stop", 12, y);
        g.setFill(Color.web("#dd6b20"));
        g.fillText("X  no step-free access", 12, y + 16);
        g.setFill(Color.web("#334e68"));
        g.fillText("- -  walk     thick line: your trip", 12, y + 32);
    }

    private static double width(ca.yorku.eecs3311.detourto.domain.TransitMode m) {
        return switch (m) {
            case SUBWAY -> 4;
            case STREETCAR -> 3;
            default -> 2;
        };
    }

    private static Color colour(String routeId) {
        return switch (routeId) {
            case "1" -> ROUTE_COLOURS[0];
            case "2" -> ROUTE_COLOURS[1];
            default -> ROUTE_COLOURS[2 + Math.floorMod(routeId.hashCode(), ROUTE_COLOURS.length - 2)];
        };
    }
}
