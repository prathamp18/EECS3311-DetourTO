# DetourTO — EECS 3311 Course Project

**A disruption-aware TTC trip planning agent.**

EECS 3311 Software Design, York University, Fall 2026 · Pratham Patel

DetourTO plans trips on the Toronto transit network and keeps them working when things go wrong. You describe a trip in plain English ("York U by 10, no streetcars, step-free please"). A deterministic Java planner computes real itineraries from the TTC's published schedule. An LLM agent (Claude on Amazon Bedrock) reads free-text service alerts, re-plans around closures, explains the trade-offs, remembers your places and preferences, and watches an active trip so it can warn you before a disruption ruins it.

The agent proposes and the Java code verifies. Every stop, route, time and itinerary the rider sees was computed by the planner and checked by `GroundingValidator`, never taken from model text.

## Status

| Stage | State |
|---|---|
| Stage 1 — Design | Complete (this commit) |
| Stage 2 — AI-assisted implementation | Not started |
| Stage 3 — Testing (JUnit + KUMA) | Not started |

## Stage 1 Report

**[docs/DetourTO-Stage1-Design-Report.md](docs/DetourTO-Stage1-Design-Report.md)**. The whole report is in one document, and GitHub shows the diagrams inline. A PDF copy is at [docs/DetourTO-Stage1-Design-Report.pdf](docs/DetourTO-Stage1-Design-Report.pdf).

| Section | Contents |
|---|---|
| 1 | Problem, users, what the agent does, why an agent, AI model and integration, architecture, technology |
| 2 | 13 feature specifications (description, GUI interaction, input, output, AI involvement, workflow, error cases) |
| 3 | UML class diagram: one model shown as 6 layer views, relationships and multiplicities |
| 4 | 8 design patterns from the course, each with its own diagram, plus supporting patterns and SOLID |
| 5–6 | Use case diagram (6 actors, 19 use cases) and full use case descriptions |
| 7 | 11 sequence diagrams |
| 8 | Feature-to-design traceability table |
| 9 | How each feature is realised by classes and methods |
| 10 | Stage 2/3 test plan (JUnit targets, KUMA behavioural requirements), CLI map, requirements checklist |

## Architecture at a Glance

![Architecture](docs/diagrams/png/architecture.png)

```
Presentation    MainWindow + 6 JavaFX panels, DetourCli (picocli)
Application     TripController «Facade», EventBus «Observer», CommandHistory «Command»,
                TripMonitorService + ActiveTrip «State», AlertService, CommuteScheduler, BenchmarkRunner
Agent           TransitAgent «Template Method» + 4 agents, ToolRegistry + 5 tools,
                LLMClient ← BedrockClaudeAdapter «Adapter», ResponseParser, GroundingValidator
Domain          TransitNetwork, NetworkView, JourneyPlanner (RAPTOR), RouteScoringStrategy «Strategy»,
                Itinerary, Leg, Disruption, PlaceResolver
Infrastructure  RealtimeFeed ← GtfsRealtimeAdapter «Adapter», RealtimeFeedDecorator «Decorator»,
                GtfsStaticLoader, SQLite repositories, AppConfig
```

## Technology (Stage 2)

| Concern | Choice |
|---|---|
| Language, build | Java 21, Maven |
| GUI | JavaFX 21 (map in a WebView with Leaflet + OpenStreetMap) |
| CLI | picocli, command `detour`, `--json` on every command |
| LLM | Claude on Amazon Bedrock, AWS SDK for Java v2 Converse API (tool use), no LangChain4j |
| Transit data | TTC GTFS schedule (City of Toronto Open Data) + TTC GTFS-Realtime (`bustime.ttc.ca/gtfsrt`) |
| Storage | SQLite (`sqlite-jdbc`) behind repository interfaces |
| Unit tests | JUnit 5, Mockito, AssertJ |
| Agent tests | KUMA (Python SDK) driving `detour ... --json` as a subprocess and reading `trace.jsonl` |

## Planned Source Layout (Stage 2)

```
pom.xml
src/main/java/ca/yorku/eecs3311/detourto/
  presentation/     MainWindow, BasePanel + 6 panels, DetourCli, ConsoleNotifier, JsonResultWriter
  application/      TripController, EventBus, commands, TripMonitorService, trip states,
                    AlertService, CommuteScheduler, BenchmarkRunner
  agent/            TransitAgent + 4 agents, LLMClient, BedrockClaudeAdapter, ScriptedLLMClient,
                    ToolRegistry + 5 tools, ResponseParser, GroundingValidator
  domain/           TransitNetwork, NetworkView, JourneyPlanner, strategies, Itinerary, Disruption, ...
  infrastructure/   GtfsRealtimeAdapter, feed decorators, CircuitBreaker, GtfsStaticLoader,
                    SQLite repositories, AppConfig
src/test/java/      JUnit 5 tests mirroring the packages
scenarios/          recorded disruption scenarios for replay and KUMA
tools/kuma/         Python harness for Stage 3 behaviour tests (no application logic)
```

## Diagrams

All UML diagrams follow the notation from the course UML slides. Sources are in `docs/diagrams/src` and images in `docs/diagrams/png`. The class diagram is a single model (`model.iuml`) cut into views, so the views and sequence diagrams stay consistent. Run `docs/diagrams/render.sh` to regenerate the images.

## Data Licence

Contains information licensed under the Open Government Licence – Toronto.
