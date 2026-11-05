package ca.yorku.eecs3311.detourto.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class PlacesCommandTest {

    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original;
    TripController controller;

    @BeforeEach
    void setUp() throws IOException {
        original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        TransitNetwork net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net));
    }

    @AfterEach
    void restore() {
        System.setOut(original);
    }

    int places(String... args) {
        PlacesCommand cmd = new PlacesCommand();
        cmd.controller = controller;
        CommandLine cl = new CommandLine(cmd);
        cl.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
        return cl.execute(args);
    }

    String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void addListRemoveAndPlanWithASavedPlace() {
        assertEquals(0, places("add", "school", "York", "University"));
        assertEquals(0, places("add", "home", "Keele"));
        assertEquals(0, places("list"));
        assertTrue(out().contains("school     York University Station"), out());

        PlanCommand plan = new PlanCommand();
        plan.controller = controller;
        assertEquals(0, new CommandLine(plan).execute("--from", "home", "--to", "school",
                "--date", "2026-11-03", "--depart", "08:00"));
        assertTrue(out().contains("Keele Station -> York University Station"), out());

        assertEquals(0, places("remove", "school"));
        assertEquals(1, places("remove", "school"));
        assertEquals(PlanCommand.EXIT_CLARIFY, places("add", "gym", "Finch Ave"));
    }

    @Test
    void prefsCommandUpdatesAndLearns() {
        PrefsCommand cmd = new PrefsCommand();
        cmd.controller = controller;
        assertEquals(0, new CommandLine(cmd).execute("--strategy", "least-walking", "--avoid-route", "29"));
        assertTrue(out().contains("default strategy : least-walking"), out());
        assertTrue(out().contains("29 Dufferin"), out());
        PrefsCommand learn = new PrefsCommand();
        learn.controller = controller;
        assertEquals(0, new CommandLine(learn).execute("--learn", "I use a wheelchair"));
        assertTrue(out().contains("Learned: always plan step-free trips"), out());
    }
}
