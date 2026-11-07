package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.RoutePattern;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.WalkLeg;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Draws the network, the selected itinerary and current closures (offline, no map tiles).
 *
 * <p>Built to stay readable on the full TTC network: choosing an option zooms to that trip; only
 * the stops that matter (start, end, where to board or change) get labels; bus routes are faint
 * and stop dots appear only when zoomed in. Scroll to zoom, drag to pan, double-click to see the
 * whole map, hover over a stop to see its name.
 */
public final class MapPanel extends StackPane {

    private static final Color[] ROUTE_COLOURS = {Color.web("#f8c300"), Color.web("#00923f"), Color.web("#d62d20"),
        Color.web("#0b6fb3"), Color.web("#8e44ad"), Color.web("#e67e22"), Color.web("#16a085")};
    /** Below this latitude span (about 5 km) individual stops are drawn and named on hover. */
    private static final double SHOW_STOPS_SPAN = 0.05;
    private static final double MARGIN = 30;

    private final Canvas base = new Canvas();
    private final Canvas overlay = new Canvas();
    private final TransitNetwork network;
    private Itinerary itinerary;
    private List<Disruption> disruptions = List.of();
    private MapProjection.Bounds view;
    private MapProjection projection;
    private final List<double[]> visibleStops = new ArrayList<>();
    private final List<Stop> visibleStopObjects = new ArrayList<>();
    private double dragX;
    private double dragY;
    private boolean dragged;

    public MapPanel(TransitNetwork network) {
        this.network = network;
        javafx.scene.layout.Pane canvases = new javafx.scene.layout.Pane(base, overlay);
        base.widthProperty().bind(canvases.widthProperty());
        base.heightProperty().bind(canvases.heightProperty());
        overlay.widthProperty().bind(canvases.widthProperty());
        overlay.heightProperty().bind(canvases.heightProperty());
        base.widthProperty().addListener(o -> render());
        base.heightProperty().addListener(o -> render());

        Button fit = new Button("Fit trip");
        fit.setOnAction(e -> fitTrip());
        Button whole = new Button("Whole map");
        whole.setOnAction(e -> {
            view = null;
            render();
        });
        Button in = new Button("+");
        in.setOnAction(e -> zoom(base.getWidth() / 2, base.getHeight() / 2, 0.6));
        Button out = new Button("-");
        out.setOnAction(e -> zoom(base.getWidth() / 2, base.getHeight() / 2, 1 / 0.6));
        HBox controls = new HBox(4, in, out, fit, whole);
        controls.setPickOnBounds(false);
        controls.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        StackPane.setAlignment(controls, Pos.TOP_RIGHT);
        controls.setStyle("-fx-padding: 8;");
        getChildren().addAll(canvases, controls);

        overlay.setOnScroll(e -> zoom(e.getX(), e.getY(), e.getDeltaY() > 0 ? 0.8 : 1.25));
        overlay.setOnMousePressed(e -> {
            dragX = e.getX();
            dragY = e.getY();
            dragged = false;
        });
        overlay.setOnMouseDragged(e -> {
            pan(e.getX() - dragX, e.getY() - dragY);
            dragX = e.getX();
            dragY = e.getY();
            dragged = true;
        });
        overlay.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !dragged) {
                view = null;
                render();
            }
        });
        overlay.setOnMouseMoved(e -> hover(e.getX(), e.getY()));
        overlay.setOnMouseExited(e -> clearOverlay());
        setMinSize(300, 300);
        setStyle("-fx-background-color: #fbfcfd; -fx-border-color: #e4e7eb;");
    }

    /** Shows a trip and zooms to it. */
    public void drawItinerary(Itinerary i) {
        this.itinerary = i;
        if (i != null) {
            fitTrip();
        } else {
            render();
        }
    }

    public void highlightDisruptions(List<Disruption> ds) {
        this.disruptions = List.copyOf(ds);
        render();
    }

    private void fitTrip() {
        if (itinerary == null) {
            return;
        }
        List<Stop> pts = new ArrayList<>();
        for (Leg leg : itinerary.legs()) {
            if (leg instanceof RideLeg r) {
                for (int pos = r.boardPos(); pos <= r.alightPos(); pos++) {
                    pts.add(network.stopAt(r.trip().stopIndexAt(pos)));
                }
            } else {
                pts.add(leg.from());
                pts.add(leg.to());
            }
        }
        view = MapProjection.Bounds.of(pts).padded(0.12, 0.012);
        render();
    }

    private void zoom(double px, double py, double factor) {
        if (projection == null) {
            return;
        }
        MapProjection.Bounds v = projection.visible(base.getWidth(), base.getHeight());
        double lat = projection.lat(py);
        double lon = projection.lon(px);
        view = new MapProjection.Bounds(lat - (lat - v.minLat()) * factor, lat + (v.maxLat() - lat) * factor,
                lon - (lon - v.minLon()) * factor, lon + (v.maxLon() - lon) * factor);
        render();
    }

    private void pan(double dx, double dy) {
        if (projection == null) {
            return;
        }
        MapProjection.Bounds v = projection.visible(base.getWidth(), base.getHeight());
        double dLon = projection.lon(0) - projection.lon(dx);
        double dLat = projection.lat(0) - projection.lat(dy);
        view = new MapProjection.Bounds(v.minLat() + dLat, v.maxLat() + dLat, v.minLon() + dLon, v.maxLon() + dLon);
        render();
    }

    // ------------------------------------------------------------------ drawing

    public void render() {
        double w = base.getWidth();
        double h = base.getHeight();
        GraphicsContext g = base.getGraphicsContext2D();
        g.setFill(Color.web("#fbfcfd"));
        g.fillRect(0, 0, w, h);
        clearOverlay();
        if (w < 10 || h < 10) {
            return;
        }
        List<Stop> all = new ArrayList<>();
        for (Stop s : network.stops()) {
            if (!s.station()) {
                all.add(s);
            }
        }
        MapProjection p = view == null ? new MapProjection(all, w, h, MARGIN) : new MapProjection(view, w, h, 0);
        projection = p;
        MapProjection.Bounds visible = p.visible(w, h);
        boolean zoomedIn = visible.latSpan() < SHOW_STOPS_SPAN;
        g.setLineCap(StrokeLineCap.ROUND);

        // background network: buses faint, rail stronger; skip segments off screen
        for (int pass = 0; pass < 2; pass++) {
            for (RoutePattern pat : network.patterns()) {
                boolean rail = pat.route().mode() != TransitMode.BUS;
                if ((pass == 0) == rail) {
                    continue;
                }
                Color c = rail ? colour(pat.route().id()).deriveColor(0, 0.7, 1.1, 0.55) : Color.rgb(120, 130, 145, 0.18);
                g.setStroke(c);
                g.setLineWidth(rail ? (pat.route().mode() == TransitMode.SUBWAY ? 4 : 2.5) : 1);
                for (int i = 1; i < pat.stopCount(); i++) {
                    Stop a = network.stopAt(pat.stopAt(i - 1));
                    Stop b = network.stopAt(pat.stopAt(i));
                    double x1 = p.x(a.lon());
                    double y1 = p.y(a.lat());
                    double x2 = p.x(b.lon());
                    double y2 = p.y(b.lat());
                    if (Math.max(x1, x2) < 0 || Math.min(x1, x2) > w || Math.max(y1, y2) < 0 || Math.min(y1, y2) > h) {
                        continue;
                    }
                    g.strokeLine(x1, y1, x2, y2);
                }
            }
        }

        // stops: dots only when zoomed in; remembered for hover names
        visibleStops.clear();
        visibleStopObjects.clear();
        for (Stop s : all) {
            double x = p.x(s.lon());
            double y = p.y(s.lat());
            if (x < 0 || x > w || y < 0 || y > h) {
                continue;
            }
            visibleStops.add(new double[] {x, y});
            visibleStopObjects.add(s);
            if (zoomedIn) {
                g.setFill(Color.web("#7b8794"));
                g.fillOval(x - 1.5, y - 1.5, 3, 3);
            }
        }

        // the chosen trip, on top
        List<double[]> placed = new ArrayList<>();
        placed.add(new double[] {0, h - 52, 340, h});
        placed.add(new double[] {w - 240, 0, w, 44});
        Set<String> closed = closedStopIds();
        if (itinerary != null) {
            for (Leg leg : itinerary.legs()) {
                if (leg instanceof RideLeg r) {
                    g.setStroke(Color.WHITE);
                    g.setLineWidth(10);
                    strokeRide(g, p, r);
                    g.setStroke(colour(r.route().id()).darker());
                    g.setLineWidth(6);
                    strokeRide(g, p, r);
                    for (int pos = r.boardPos() + 1; pos < r.alightPos(); pos++) {
                        Stop s = network.stopAt(r.trip().stopIndexAt(pos));
                        g.setFill(Color.WHITE);
                        g.fillOval(p.x(s.lon()) - 2, p.y(s.lat()) - 2, 4, 4);
                    }
                } else {
                    g.setStroke(Color.web("#334e68"));
                    g.setLineWidth(3);
                    g.setLineDashes(6, 6);
                    g.strokeLine(p.x(leg.from().lon()), p.y(leg.from().lat()), p.x(leg.to().lon()), p.y(leg.to().lat()));
                    g.setLineDashes();
                }
            }
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

        // labels: trip tags first, then station names where there is room
        if (itinerary != null) {
            List<RideLeg> rides = itinerary.rideLegs();
            List<Stop> marks = new ArrayList<>(List.of(itinerary.origin(), itinerary.destination()));
            for (int i = 1; i < rides.size(); i++) {
                marks.add(rides.get(i).from());
            }
            for (Stop m : marks) {
                double mx = p.x(m.lon());
                double my = p.y(m.lat());
                placed.add(new double[] {mx - 8, my - 8, mx + 8, my + 8});
            }
            tag(g, p, itinerary.origin(), "Start" + (rides.isEmpty() ? "" : " · " + rides.get(0).route().label()),
                    Color.web("#2f855a"), placed, true);
            for (int i = 1; i < rides.size(); i++) {
                tag(g, p, rides.get(i).from(), "Change to " + rides.get(i).route().label(), Color.web("#334e68"), placed, true);
            }
            tag(g, p, itinerary.destination(), "End", Color.web("#2b6cb0"), placed, true);
        }
        g.setFont(Font.font(11));
        // station names only: subway/rail stations, and on a small network every station
        boolean small = network.patterns().size() <= 60;
        for (Stop s : network.stops()) {
            if (!s.station() || (!small && !isRailStation(s))) {
                continue;
            }
            label(g, p, s, closed.contains(s.id()), placed, w, h);
        }
        legend(g, h);
    }

    private void strokeRide(GraphicsContext g, MapProjection p, RideLeg r) {
        for (int pos = r.boardPos() + 1; pos <= r.alightPos(); pos++) {
            Stop a = network.stopAt(r.trip().stopIndexAt(pos - 1));
            Stop b = network.stopAt(r.trip().stopIndexAt(pos));
            g.strokeLine(p.x(a.lon()), p.y(a.lat()), p.x(b.lon()), p.y(b.lat()));
        }
    }

    /** A rounded label box with a pointer dot, for the stops that matter on the trip. */
    private void tag(GraphicsContext g, MapProjection p, Stop s, String text, Color c, List<double[]> placed, boolean force) {
        double x = p.x(s.lon());
        double y = p.y(s.lat());
        String name = shortName(s);
        g.setFill(c);
        g.fillOval(x - 7, y - 7, 14, 14);
        g.setFill(Color.WHITE);
        g.fillOval(x - 3, y - 3, 6, 6);
        g.setFont(Font.font(null, FontWeight.BOLD, 12));
        String line1 = text;
        double width = Math.max(textWidth(line1, Font.font(null, FontWeight.BOLD, 12)), textWidth(name, Font.font(11))) + 16;
        double[][] spots = {{x + 12, y - 36}, {x + 12, y + 8}, {x - 12 - width, y - 36}, {x - 12 - width, y + 8}};
        double[] box = null;
        for (double[] sp : spots) {
            double[] b = {sp[0], sp[1], sp[0] + width, sp[1] + 32};
            if (placed.stream().noneMatch(o -> overlaps(o, b)) && b[0] >= 0 && b[1] >= 0
                    && b[2] <= base.getWidth() && b[3] <= base.getHeight()) {
                box = b;
                break;
            }
        }
        if (box == null) {
            box = new double[] {spots[0][0], spots[0][1], spots[0][0] + width, spots[0][1] + 32};
        }
        placed.add(box);
        g.setFill(Color.rgb(255, 255, 255, 0.95));
        g.fillRoundRect(box[0], box[1], width, 32, 8, 8);
        g.setStroke(c);
        g.setLineWidth(1.5);
        g.strokeRoundRect(box[0], box[1], width, 32, 8, 8);
        g.setFill(c);
        g.fillText(line1, box[0] + 7, box[1] + 13);
        g.setFont(Font.font(11));
        g.setFill(Color.web("#243b53"));
        g.fillText(name, box[0] + 7, box[1] + 27);
    }

    private void label(GraphicsContext g, MapProjection p, Stop s, boolean closed, List<double[]> placed, double w, double h) {
        String name = shortName(s).replace(" Station", "");
        double x = p.x(s.lon()) + 6;
        double y = p.y(s.lat()) - 4;
        double[] box = {x - 2, y - 11, x + textWidth(name, Font.font(11)) + 2, y + 3};
        if (box[0] < 0 || box[2] > w || box[1] < 0 || box[3] > h || placed.stream().anyMatch(b -> overlaps(b, box))) {
            return;
        }
        placed.add(box);
        g.setFill(Color.rgb(251, 252, 253, 0.85));
        g.fillRect(box[0], box[1], box[2] - box[0], box[3] - box[1]);
        g.setFill(closed ? Color.web("#c53030") : Color.web("#486581"));
        g.fillText(name, x, y);
    }

    private void hover(double mx, double my) {
        clearOverlay();
        double best = 100;
        int bestIdx = -1;
        for (int i = 0; i < visibleStops.size(); i++) {
            double dx = visibleStops.get(i)[0] - mx;
            double dy = visibleStops.get(i)[1] - my;
            double d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            return;
        }
        Stop s = visibleStopObjects.get(bestIdx);
        double x = visibleStops.get(bestIdx)[0];
        double y = visibleStops.get(bestIdx)[1];
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.setStroke(Color.web("#102a43"));
        g.setLineWidth(2);
        g.strokeOval(x - 6, y - 6, 12, 12);
        g.setFont(Font.font(12));
        String text = s.name();
        double width = textWidth(text, Font.font(12)) + 12;
        double bx = Math.min(x + 10, overlay.getWidth() - width - 4);
        double by = Math.max(y - 30, 4);
        g.setFill(Color.web("#102a43", 0.92));
        g.fillRoundRect(bx, by, width, 22, 6, 6);
        g.setFill(Color.WHITE);
        g.fillText(text, bx + 6, by + 15);
    }

    private void clearOverlay() {
        overlay.getGraphicsContext2D().clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
    }

    private void cross(GraphicsContext g, MapProjection p, Stop s, Color c) {
        double x = p.x(s.lon());
        double y = p.y(s.lat());
        g.setStroke(Color.WHITE);
        g.setLineWidth(6);
        g.strokeLine(x - 7, y - 7, x + 7, y + 7);
        g.strokeLine(x - 7, y + 7, x + 7, y - 7);
        g.setStroke(c);
        g.setLineWidth(3);
        g.strokeLine(x - 7, y - 7, x + 7, y + 7);
        g.strokeLine(x - 7, y + 7, x + 7, y - 7);
    }

    private void legend(GraphicsContext g, double h) {
        g.setFont(Font.font(11));
        double y = h - 32;
        g.setFill(Color.rgb(255, 255, 255, 0.9));
        g.fillRoundRect(6, y - 14, 330, 40, 8, 8);
        g.setFill(Color.web("#c53030"));
        g.fillText("X closed", 14, y);
        g.setFill(Color.web("#dd6b20"));
        g.fillText("X no step-free", 74, y);
        g.setFill(Color.web("#334e68"));
        g.fillText("- - walk     thick line: your trip", 162, y);
        g.setFill(Color.web("#829ab1"));
        g.fillText("Scroll to zoom, drag to move, hover for names", 14, y + 16);
    }

    private static double textWidth(String text, Font font) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(text);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    private Set<String> closedStopIds() {
        Set<String> out = new HashSet<>();
        for (Disruption d : disruptions) {
            if (d.affectsRouting() && d.kind() != DisruptionKind.ELEVATOR_OUTAGE) {
                out.addAll(d.closedStopIds());
            }
        }
        return out;
    }

    private boolean isRailStation(Stop s) {
        for (Stop c : network.boardingStopsFor(s)) {
            for (var ps : network.patternsAt(network.indexOf(c))) {
                if (network.patterns().get(ps.pattern()).route().mode() != TransitMode.BUS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean overlaps(double[] a, double[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    private static String shortName(Stop s) {
        int dash = s.name().indexOf(" - ");
        return dash > 0 ? s.name().substring(0, dash) : s.name();
    }

    private static Color colour(String routeId) {
        return switch (routeId) {
            case "1" -> ROUTE_COLOURS[0];
            case "2" -> ROUTE_COLOURS[1];
            default -> ROUTE_COLOURS[2 + Math.floorMod(routeId.hashCode(), ROUTE_COLOURS.length - 2)];
        };
    }

    /** Used by tests: the labels a walk leg would not get. */
    static boolean isWalk(Leg l) {
        return l instanceof WalkLeg;
    }
}
