package ca.yorku.eecs3311.detourto.application;

/**
 * Command pattern: one undoable change to the current {@code TripQuery} (the receiver).
 * {@link CommandHistory} is the invoker; {@link QueryCommandFactory} builds commands from the
 * agent's follow-up intents.
 */
public interface QueryCommand {

    void execute();

    void undo();

    /** Short description for the history list, e.g. "Leave 15 min later". */
    String describe();
}
