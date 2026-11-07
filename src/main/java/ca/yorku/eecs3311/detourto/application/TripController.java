package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.AgentContext;
import ca.yorku.eecs3311.detourto.agent.AgentRequest;
import ca.yorku.eecs3311.detourto.agent.AgentResult;
import ca.yorku.eecs3311.detourto.agent.AgentStepListener;
import ca.yorku.eecs3311.detourto.agent.Explanation;
import ca.yorku.eecs3311.detourto.agent.ExplanationAgent;
import ca.yorku.eecs3311.detourto.agent.AlertInterpreterAgent;
import ca.yorku.eecs3311.detourto.agent.RouteStopsTool;
import ca.yorku.eecs3311.detourto.agent.ClarificationRequest;
import ca.yorku.eecs3311.detourto.agent.ConversationHistory;
import ca.yorku.eecs3311.detourto.agent.FindStopTool;
import ca.yorku.eecs3311.detourto.agent.FollowUpIntent;
import ca.yorku.eecs3311.detourto.agent.FollowUpRules;
import ca.yorku.eecs3311.detourto.agent.PreferenceExtractorAgent;
import ca.yorku.eecs3311.detourto.agent.PreferenceRules;
import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;
import ca.yorku.eecs3311.detourto.domain.Commute;
import ca.yorku.eecs3311.detourto.infrastructure.CommuteRepository;
import ca.yorku.eecs3311.detourto.infrastructure.FeedValidationReport;
import ca.yorku.eecs3311.detourto.infrastructure.InMemoryCommuteRepository;
import ca.yorku.eecs3311.detourto.infrastructure.InMemoryProfileRepository;
import ca.yorku.eecs3311.detourto.infrastructure.OpenDataDownloader;
import ca.yorku.eecs3311.detourto.infrastructure.SqliteCommuteRepository;
import ca.yorku.eecs3311.detourto.infrastructure.ProfileRepository;
import ca.yorku.eecs3311.detourto.infrastructure.SqliteDatabase;
import ca.yorku.eecs3311.detourto.infrastructure.SqliteProfileRepository;
import ca.yorku.eecs3311.detourto.agent.GroundingValidator;
import ca.yorku.eecs3311.detourto.agent.LLMClient;
import ca.yorku.eecs3311.detourto.agent.LLMClientFactory;
import ca.yorku.eecs3311.detourto.agent.LLMException;
import ca.yorku.eecs3311.detourto.agent.Message;
import ca.yorku.eecs3311.detourto.agent.PlanJourneyTool;
import ca.yorku.eecs3311.detourto.agent.ResponseParser;
import ca.yorku.eecs3311.detourto.agent.ToolRegistry;
import ca.yorku.eecs3311.detourto.agent.TripRequestAgent;
import ca.yorku.eecs3311.detourto.domain.BenchmarkReport;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.ReplayScenario;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.NetworkView;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.RouteScoringStrategy;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.infrastructure.CachingRealtimeFeed;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import ca.yorku.eecs3311.detourto.infrastructure.RealtimeFeed;
import ca.yorku.eecs3311.detourto.infrastructure.ResilientRealtimeFeed;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Facade pattern: the single entry point the CLI and (later) the GUI use. Clients never touch the
 * planner, resolver or strategies directly, so those can change without changing the clients.
 *
 * <p>Week 1 covers planning from a form or command line. Agents, alerts, commands (undo/redo),
 * monitoring and profiles are added in later weeks behind the same facade.
 */
public final class TripController {

    /** Used when no live feed is configured: no alerts, no live delays. */
    private static final RealtimeFeed SCHEDULE_ONLY = new RealtimeFeed() {
        @Override
        public List<ServiceAlert> alerts() {
            return List.of();
        }

        @Override
        public RealtimeSnapshot snapshot() {
            return RealtimeSnapshot.empty(java.time.Instant.now(), false);
        }
    };

    /** Clarification lists only stops scoring within this much of the best match. */
    static final double CANDIDATE_MARGIN = 0.15;

    private final JourneyPlanner planner;
    private final PlaceResolver places;
    private final Map<StrategyKind, RouteScoringStrategy> strategies = new EnumMap<>(StrategyKind.class);
    private final TripRequestAgent tripAgent;
    private final RealtimeFeed feed;
    private final ArrivalBoard arrivalBoard;
    private final AlertService alertService;
    private final ExplanationAgent explanationAgent;
    private final EventBus eventBus = new EventBus();
    private final LLMClient llm;
    private List<Disruption> lastDisruptions = List.of();
    private final String agentUnavailableReason;
    private final ConversationHistory conversation = new ConversationHistory();
    private TripQuery currentQuery;
    private List<Itinerary> lastOptions = List.of();
    private final CommandHistory history = new CommandHistory();
    private ProfileRepository profiles = new InMemoryProfileRepository();
    private PreferenceExtractorAgent preferenceAgent;
    private TripMonitorService monitor;
    private CommuteRepository commuteRepo = new InMemoryCommuteRepository();
    private CommuteScheduler commuteScheduler;
    private QueryCommandFactory commandFactory;

    /** Without an LLM or live feed: form and CLI-flag planning, scheduled arrivals. */
    public TripController(JourneyPlanner planner, PlaceResolver places) {
        this(planner, places, null, null, "no LLM client configured");
    }

    /** With an LLM: natural-language planning (F01) is available too. */
    public TripController(JourneyPlanner planner, PlaceResolver places, LLMClient llm) {
        this(planner, places, llm, null, null);
    }

    /** With an LLM (may be null) and a live feed (may be null: schedule only). */
    public TripController(JourneyPlanner planner, PlaceResolver places, LLMClient llm, RealtimeFeed feed) {
        this(planner, places, llm, feed, llm == null ? "no LLM client configured" : null);
    }

    private TripController(JourneyPlanner planner, PlaceResolver places, LLMClient llm, RealtimeFeed feed,
                           String unavailable) {
        this.planner = planner;
        this.places = places;
        this.llm = llm;
        this.feed = feed != null ? feed : SCHEDULE_ONLY;
        this.arrivalBoard = new ArrivalBoard(planner, this.feed);
        if (llm != null) {
            ToolRegistry tools = new ToolRegistry()
                    .register(new FindStopTool(places))
                    .register(new PlanJourneyTool(planner));
            this.tripAgent = new TripRequestAgent(llm, tools, new ResponseParser(),
                    new GroundingValidator(planner.network()), planner.network());
            ToolRegistry alertTools = new ToolRegistry()
                    .register(new RouteStopsTool(planner.network()))
                    .register(new FindStopTool(places));
            AlertInterpreterAgent interpreter = new AlertInterpreterAgent(llm, alertTools, new ResponseParser(),
                    new GroundingValidator(planner.network()), planner.network());
            this.alertService = new AlertService(this.feed, interpreter, planner.network());
            this.explanationAgent = new ExplanationAgent(llm, new ResponseParser(),
                    new GroundingValidator(planner.network()));
            this.preferenceAgent = new PreferenceExtractorAgent(llm, new ToolRegistry().register(new FindStopTool(places)),
                    new ResponseParser(), new GroundingValidator(planner.network()), planner.network());
            // Observer: every agent step goes onto the event bus (trace file, verbose CLI, GUI)
            AgentStepListener toBus = (agent, kind, detail) -> eventBus.publish(TransitEvent.of(
                    EventType.AGENT_STEP, Map.of("agent", agent, "kind", kind, "detail", detail)));
            tripAgent.setStepListener(toBus);
            interpreter.setStepListener(toBus);
            explanationAgent.setStepListener(toBus);
            preferenceAgent.setStepListener(toBus);
        } else {
            this.tripAgent = null;
            this.alertService = new AlertService(this.feed, null, planner.network());
            this.explanationAgent = null;
        }
        this.agentUnavailableReason = unavailable;
        for (StrategyKind k : StrategyKind.values()) {
            strategies.put(k, RouteScoringStrategy.of(k));
        }
        places.setSavedPlaces(label -> this.profiles.load().placeStopId(label));
    }

    /** Loads the GTFS feed named in the config and wires the services together. */
    public static TripController fromConfig(AppConfig config) throws IOException {
        TransitNetwork network = new GtfsStaticLoader().load(config.gtfsPath());
        JourneyPlanner planner = new JourneyPlanner(network);
        PlaceResolver places = new PlaceResolver(network);
        // caching outside, failure handling inside: a failed fetch is also cached for the TTL
        RealtimeFeed feed = new CachingRealtimeFeed(new ResilientRealtimeFeed(
                new GtfsRealtimeAdapter(config.feedBaseUrl())));
        TripController c;
        try {
            c = new TripController(planner, places, LLMClientFactory.create(config), feed, null);
        } catch (LLMException e) {
            // flag-based planning still works without a key; plain-English requests explain why not
            c = new TripController(planner, places, null, feed, e.getMessage());
        }
        c.eventBus().subscribeAll(new TraceLogger(config.traceFile()));
        java.sql.Connection db = SqliteDatabase.open(config.databasePath());
        c.useProfiles(new SqliteProfileRepository(db));
        c.useCommutes(new SqliteCommuteRepository(db, network));
        return c;
    }

    // ------------------------------------------------------------------ F11 commutes

    public void useCommutes(CommuteRepository repo) {
        this.commuteRepo = repo;
        this.commuteScheduler = null;
    }

    public CommuteScheduler commutes() {
        if (commuteScheduler == null) {
            commuteScheduler = new CommuteScheduler(commuteRepo, planner, alertService, eventBus,
                    q -> applyProfile(q, false));
        }
        return commuteScheduler;
    }

    /** Saves a commute; returns the clarification when a place is ambiguous or unknown. */
    public Optional<ClarificationRequest> saveCommute(String name, String fromText, String toText,
                                                      java.time.LocalTime arriveBy, Set<java.time.DayOfWeek> days,
                                                      int warnMinutes) {
        Resolution o = resolvePlace(fromText);
        if (o.stop().isEmpty()) {
            return Optional.of(o.clarification());
        }
        Resolution d = resolvePlace(toText);
        if (d.stop().isEmpty()) {
            return Optional.of(d.clarification());
        }
        commutes().schedule(new Commute(name, o.stop().get(), d.stop().get(), arriveBy, days, warnMinutes));
        return Optional.empty();
    }

    // ------------------------------------------------------------------ F13 data refresh

    /** Outcome of a refresh: the validation report, and whether the new data was swapped in. */
    public record RefreshResult(FeedValidationReport report, boolean swapped, String message) {
    }

    /**
     * Feature F13: loads a new GTFS schedule (downloaded from {@code url}, or from a local file),
     * validates it, and swaps it into the running network only if it is valid. Everything that
     * holds the network (planner, resolver, agents) sees the new data at once.
     */
    public RefreshResult refreshTransitData(Path localFile, String url, Path downloadTo) {
        Path source = localFile;
        try {
            if (source == null) {
                source = new OpenDataDownloader().download(url, downloadTo);
            }
        } catch (IOException | RuntimeException e) {
            return new RefreshResult(null, false, "Download failed, keeping the current data: " + e.getMessage());
        }
        GtfsStaticLoader loader = new GtfsStaticLoader();
        TransitNetwork fresh;
        try {
            fresh = loader.load(source);
        } catch (IOException | RuntimeException e) {
            return new RefreshResult(null, false, "Could not read " + source + ", keeping the current data: " + e.getMessage());
        }
        FeedValidationReport report = loader.lastReport();
        if (!report.ok()) {
            return new RefreshResult(report, false, "The new data failed validation, keeping the current data.");
        }
        planner.network().replaceWith(fresh);
        currentQuery = null;
        lastOptions = List.of();
        history.clear();
        return new RefreshResult(report, true, "Loaded " + source + ": " + report.stopCount() + " stops, "
                + report.tripCount() + " trips.");
    }

    // ------------------------------------------------------------------ F07 monitoring

    public TripMonitorService monitor() {
        if (monitor == null) {
            monitor = new TripMonitorService(feed, alertService, eventBus, planner.network(), this::rerouteOptions);
        }
        return monitor;
    }

    /** Starts monitoring one of the current options (e.g. "opt-1"). */
    public Optional<ActiveTrip> startTrip(String itineraryId) {
        if (currentQuery == null) {
            return Optional.empty();
        }
        return lastOptions.stream().filter(i -> i.id().equals(itineraryId)).findFirst()
                .map(i -> monitor().start(i, currentQuery.isArriveBy() ? currentQuery.arriveBy() : null,
                        currentQuery.strategyKind()));
    }

    /** Accepts a reroute option for an at-risk trip. */
    public Optional<Itinerary> acceptReroute(String tripId, String optionId) {
        return monitor().applyReroute(tripId, optionId);
    }

    /** New options from where the rider will be, avoiding the current disruptions. */
    private List<Itinerary> rerouteOptions(ActiveTrip trip, LocalDateTime now) {
        Stop from = trip.rerouteOrigin(now);
        Stop to = trip.itinerary().destination();
        if (from.equals(to)) {
            return List.of();
        }
        LocalDateTime start = now.isAfter(trip.itinerary().departure()) ? now : trip.itinerary().departure();
        TripQuery q = TripQuery.departAt(from, to, start);
        q.setStrategyKind(trip.strategy());
        List<Disruption> applied = alertService.currentDisruptions(now).stream()
                .filter(Disruption::appliesByDefault).toList();
        List<String> currentTrips = trip.itinerary().rideLegs().stream().map(l -> l.trip().id()).toList();
        return planner.plan(q, planner.network().withDisruptions(applied), strategies.get(trip.strategy())).stream()
                .filter(i -> !currentTrips.containsAll(i.rideLegs().stream().map(l -> l.trip().id()).toList()))
                .toList();
    }

    // ------------------------------------------------------------------ F09 profile

    /** Where the rider profile is stored (SQLite in the app, in memory in tests). */
    public void useProfiles(ProfileRepository repo) {
        this.profiles = repo;
    }

    public RiderProfile profile() {
        return profiles.load();
    }

    /** Applies and saves updates; returns a description of each one that was applied. */
    public List<String> updateProfile(List<PreferenceUpdate> updates) {
        RiderProfile p = profiles.load();
        List<String> done = new ArrayList<>();
        for (PreferenceUpdate u : updates) {
            p.apply(u);
            done.add(u.describe());
        }
        if (!done.isEmpty()) {
            profiles.save(p);
        }
        return done;
    }

    /** Saves a place under a label ("home"); the place text must resolve to one stop. */
    public Resolution savePlace(String label, String placeText) {
        Resolution r = resolvePlace(placeText);
        r.stop().ifPresent(s -> updateProfile(List.of(new PreferenceUpdate(PreferenceUpdate.SAVE_PLACE,
                RiderProfile.normalize(label) + "=" + s.id()))));
        return r;
    }

    /**
     * Picks lasting preferences out of a message ("never put me on the 29") with the
     * PreferenceExtractorAgent, or with built-in rules when there is no LLM, and saves them.
     */
    public List<String> learnPreferences(String text, LocalDateTime now) {
        List<PreferenceUpdate> found;
        if (preferenceAgent != null) {
            found = preferenceAgent.extract(text, AgentContext.at(now));
        } else {
            found = new ArrayList<>();
            for (PreferenceUpdate u : PreferenceRules.parse(text)) {
                if (!u.key().equals(PreferenceUpdate.SAVE_PLACE)) {
                    if (!u.key().equals(PreferenceUpdate.AVOID_ROUTE) || planner.network().findRoute(u.value()).isPresent()) {
                        found.add(u);
                    }
                    continue;
                }
                String label = u.value().substring(0, u.value().indexOf('='));
                resolvePlace(u.value().substring(u.value().indexOf('=') + 1)).stop().ifPresent(s ->
                        found.add(new PreferenceUpdate(PreferenceUpdate.SAVE_PLACE, label + "=" + s.id())));
            }
        }
        return updateProfile(found);
    }

    /** Profile defaults for a new query (avoided routes, step-free, default strategy). */
    public void applyProfile(TripQuery q, boolean strategyChosen) {
        profiles.load().applyDefaults(q, strategyChosen);
    }

    public EventBus eventBus() {
        return eventBus;
    }

    /** Feature F12: replay scenarios through the pipeline and measure the results. */
    public List<BenchmarkReport> runReplay(List<ReplayScenario> scenarios) {
        return new BenchmarkRunner(planner, places, llm).runAll(scenarios);
    }

    /**
     * Feature F06: compares the current options. Uses the LLM when available (validated against
     * the options), otherwise a deterministic comparison.
     */
    public Explanation explain(LocalDateTime now) {
        if (currentQuery == null || lastOptions.isEmpty()) {
            return new Explanation("Plan a trip first; there are no options to explain.", Map.of(), List.of(),
                    List.of(), false);
        }
        if (explanationAgent == null) {
            return ExplanationAgent.fallback(currentQuery, lastOptions);
        }
        return explanationAgent.explain(currentQuery, lastOptions, lastDisruptions, now);
    }

    /**
     * Feature F08: next departures at a stop, with live delays where the TTC publishes them.
     * Returns the clarification as the error when the stop text is ambiguous.
     */
    public ArrivalsResult getArrivals(String stopText, LocalDateTime now, int n) {
        Resolution r = resolvePlace(stopText);
        if (r.stop().isEmpty()) {
            return new ArrivalsResult(null, r.clarification());
        }
        return new ArrivalsResult(arrivalBoard.arrivals(r.stop().get(), now, n), null);
    }

    /** An arrivals board, or a question about which stop was meant. */
    public record ArrivalsResult(ArrivalBoard.Board board, ClarificationRequest clarification) {
    }

    public RealtimeFeed feed() {
        return feed;
    }

    /**
     * Feature F01: plan from plain English. The agent resolves places with tools and returns a
     * validated query (or a clarification question); the deterministic planner does the rest.
     */
    public PlanResult planFromText(String text, LocalDateTime now) {
        if (tripAgent == null) {
            return new PlanResult(null, List.of(), null,
                    "Plain-English planning needs an LLM: " + agentUnavailableReason);
        }
        List<String> learned = PreferenceRules.mentionsPreference(text) ? learnPreferences(text, now) : List.of();
        AgentContext ctx = new AgentContext(now, currentQuery, lastOptions, conversation);
        AgentResult result = tripAgent.run(new AgentRequest("trip_request", text, ctx));
        conversation.append(Message.user(text));
        if (!result.success()) {
            return new PlanResult(null, List.of(), null,
                    "Sorry, I couldn't turn that into a trip: " + String.join("; ", result.errors()));
        }
        if (result.output() instanceof ClarificationRequest c) {
            conversation.append(Message.assistant(c.question(), List.of(), null));
            return PlanResult.needsClarification(c);
        }
        TripQuery q = (TripQuery) result.output();
        applyProfile(q, q.strategyKind() != StrategyKind.FASTEST);
        conversation.append(Message.assistant("Planned: " + q, List.of(), null));
        PlanResult planned = planFromForm(q);
        return learned.isEmpty() ? planned
                : withMessage(planned, "Saved to your profile: " + String.join("; ", learned) + ".");
    }

    /**
     * Feature F10: apply a follow-up ("leave 15 minutes later", "avoid line 2", "undo") to the
     * current trip as an undoable command, then plan again.
     */
    public PlanResult followUp(String text, LocalDateTime now) {
        if (currentQuery == null) {
            return new PlanResult(null, List.of(), null, "Plan a trip first, then ask for changes.");
        }
        FollowUpIntent intent = tripAgent != null
                ? tripAgent.interpretFollowUp(text, new AgentContext(now, currentQuery, lastOptions, conversation))
                : FollowUpRules.parse(text);
        conversation.append(Message.user(text));
        switch (intent.kind()) {
            case UNDO:
                return undo();
            case REDO:
                return redo();
            case UNKNOWN:
                return withMessage(replanCurrent(), "Sorry, " + intent.value()
                        + ". Try e.g. \"leave 15 minutes later\", \"avoid line 2\" or \"fewest transfers\".");
            default:
                break;
        }
        QueryCommand cmd;
        try {
            cmd = commandFactory().create(intent, currentQuery);
        } catch (IllegalArgumentException e) {
            return withMessage(replanCurrent(), e.getMessage());
        }
        history.run(cmd);
        return withMessage(replanCurrent(), cmd.describe() + ".");
    }

    /** Undo the last follow-up change and plan again. */
    public PlanResult undo() {
        if (currentQuery == null || !history.undo()) {
            return currentQuery == null ? new PlanResult(null, List.of(), null, "Nothing to undo.")
                    : withMessage(replanCurrent(), "Nothing to undo.");
        }
        return withMessage(replanCurrent(), "Undone.");
    }

    /** Redo the last undone change and plan again. */
    public PlanResult redo() {
        if (currentQuery == null || !history.redo()) {
            return currentQuery == null ? new PlanResult(null, List.of(), null, "Nothing to redo.")
                    : withMessage(replanCurrent(), "Nothing to redo.");
        }
        return withMessage(replanCurrent(), "Redone.");
    }

    public CommandHistory history() {
        return history;
    }

    private PlanResult replanCurrent() {
        return planWithDisruptions(currentQuery, Set.of(), false);
    }

    private static PlanResult withMessage(PlanResult r, String prefix) {
        return new PlanResult(r.query(), r.options(), r.clarification(), prefix + " " + r.message(),
                r.disruptionsApplied());
    }

    private QueryCommandFactory commandFactory() {
        if (commandFactory == null) {
            commandFactory = new QueryCommandFactory(planner.network(), places);
        }
        return commandFactory;
    }

    public boolean hasAgent() {
        return tripAgent != null;
    }

    public TransitNetwork network() {
        return planner.network();
    }

    /**
     * Resolves place text to one stop. Returns the clarification as the error when the text is
     * ambiguous or matches nothing.
     */
    public Resolution resolvePlace(String text) {
        List<PlaceResolver.Match> matches = places.match(text);
        if (matches.isEmpty()) {
            return Resolution.failed(new ClarificationRequest(
                    "I couldn't find a stop called \"" + text + "\". Try a station or street name.", List.of()));
        }
        if (places.isAmbiguous(matches)) {
            double top = matches.get(0).score();
            List<Stop> options = matches.stream()
                    .filter(m -> m.score() >= top - CANDIDATE_MARGIN)
                    .map(PlaceResolver.Match::stop).toList();
            return Resolution.failed(new ClarificationRequest("Which \"" + text + "\" did you mean: "
                    + options.stream().map(Stop::name).collect(Collectors.joining(", ")) + "?", options));
        }
        return Resolution.found(matches.get(0).stop());
    }

    /** Result of {@link #resolvePlace(String)}: a stop, or a question for the rider. */
    public record Resolution(Optional<Stop> stop, ClarificationRequest clarification) {
        static Resolution found(Stop s) {
            return new Resolution(Optional.of(s), null);
        }

        static Resolution failed(ClarificationRequest c) {
            return new Resolution(Optional.empty(), c);
        }
    }

    /**
     * Plans a fully specified query (Trip Planner form, or CLI flags), routing around every
     * active disruption that is confident enough (F02 with F05).
     */
    public PlanResult planFromForm(TripQuery q) {
        return planWithDisruptions(q, Set.of(), false);
    }

    /**
     * Plans with control over disruptions: {@code forceIds} are applied even when their
     * confidence is low (the rider chose to avoid them); {@code ignoreAlerts} plans on the
     * schedule only, for comparison.
     */
    public PlanResult planWithDisruptions(TripQuery q, Set<String> forceIds, boolean ignoreAlerts) {
        List<Disruption> applied = new ArrayList<>();
        if (!ignoreAlerts) {
            for (Disruption d : alertService.currentDisruptions(q.time())) {
                boolean forced = forceIds.contains(d.id()) || forceIds.contains(d.sourceAlertId());
                if (d.affectsRouting() && (forced || d.appliesByDefault())) {
                    applied.add(d);
                }
            }
        }
        PlanResult r = planOn(q, planner.network().withDisruptions(applied));
        String message = r.message();
        if (!applied.isEmpty()) {
            message += ". Avoiding " + applied.size() + " disruption" + (applied.size() == 1 ? "" : "s") + ".";
        }
        if (!r.hasOptions() && !applied.isEmpty()) {
            message = "No route avoids the current disruptions"
                    + (applied.stream().anyMatch(d -> d.summary().toLowerCase().contains("shuttle"))
                    ? "; shuttle buses may be running (see alerts)." : ".");
        }
        lastDisruptions = List.copyOf(applied);
        eventBus.publish(TransitEvent.of(EventType.ITINERARY_UPDATED, Map.of(
                "query", String.valueOf(r.query()),
                "options", r.options().stream().map(Itinerary::id).toList(),
                "disruptions", applied.stream().map(Disruption::id).toList())));
        return new PlanResult(r.query(), r.options(), null, message, applied);
    }

    /** Feature F05: plan the current trip again, avoiding this disruption (even if low confidence). */
    public PlanResult rerouteAround(String disruptionId) {
        if (currentQuery == null) {
            return new PlanResult(null, List.of(), null, "Plan a trip first, then re-route it.");
        }
        return planWithDisruptions(currentQuery, Set.of(disruptionId), false);
    }

    /** Feature F04: interpreted alerts active at {@code at}. */
    public List<Disruption> getAlerts(LocalDateTime at) {
        List<Disruption> ds = alertService.currentDisruptions(at);
        eventBus.publish(TransitEvent.of(EventType.ALERTS_UPDATED, Map.of(
                "count", ds.size(), "ids", ds.stream().map(Disruption::id).toList())));
        return ds;
    }

    public AlertService alertService() {
        return alertService;
    }

    /** Changes the strategy of the current query (undoable) and plans again. */
    public PlanResult setStrategy(StrategyKind kind) {
        if (currentQuery == null) {
            throw new IllegalStateException("plan a trip before choosing a strategy");
        }
        QueryCommand cmd = new ChangeStrategyCommand(currentQuery, kind);
        history.run(cmd);
        return withMessage(replanCurrent(), cmd.describe() + ".");
    }

    PlanResult planOn(TripQuery q, NetworkView view) {
        if (q != currentQuery) {
            history.clear(); // a new trip starts a new undo history
            currentQuery = q;
        }
        if (q.origin().equals(q.destination())) {
            return PlanResult.ok(q, List.of(), "Origin and destination are the same stop.");
        }
        RouteScoringStrategy strategy = strategies.get(q.strategyKind());
        List<Itinerary> options = planner.plan(q, view, strategy);
        currentQuery = q;
        lastOptions = options;
        String message = options.isEmpty()
                ? noRouteMessage(q)
                : options.size() + " option" + (options.size() == 1 ? "" : "s") + ", ranked by "
                        + strategy.label();
        return PlanResult.ok(q, options, message);
    }

    private String noRouteMessage(TripQuery q) {
        if (q.strategyKind() == StrategyKind.ACCESSIBLE) {
            return "No step-free route found. One of the stations on the way may lack elevators.";
        }
        return "No route found at that time. Try a later time or allow more routes.";
    }

    public Optional<TripQuery> currentQuery() {
        return Optional.ofNullable(currentQuery);
    }

    public List<Itinerary> lastOptions() {
        return lastOptions;
    }

    public RouteScoringStrategy strategy(StrategyKind kind) {
        return strategies.get(kind);
    }
}
