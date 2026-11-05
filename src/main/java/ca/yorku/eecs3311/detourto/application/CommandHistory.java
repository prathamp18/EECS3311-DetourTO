package ca.yorku.eecs3311.detourto.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Invoker: runs commands and keeps undo and redo stacks. Running a new command clears redo. */
public final class CommandHistory {

    private final Deque<QueryCommand> undoStack = new ArrayDeque<>();
    private final Deque<QueryCommand> redoStack = new ArrayDeque<>();

    public void run(QueryCommand c) {
        c.execute();
        undoStack.push(c);
        redoStack.clear();
    }

    /** @return false when there is nothing to undo */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        QueryCommand c = undoStack.pop();
        c.undo();
        redoStack.push(c);
        return true;
    }

    /** @return false when there is nothing to redo */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        QueryCommand c = redoStack.pop();
        c.execute();
        undoStack.push(c);
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Descriptions of applied changes, oldest first (shown in the GUI history list). */
    public List<String> applied() {
        List<String> out = new ArrayList<>();
        undoStack.descendingIterator().forEachRemaining(c -> out.add(c.describe()));
        return out;
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
