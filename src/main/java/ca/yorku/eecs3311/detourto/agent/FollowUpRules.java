package ca.yorku.eecs3311.detourto.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline reading of common follow-ups ("leave 15 minutes later", "avoid line 2", "fewest
 * transfers", "what about Union instead?", "undo"). Used when no LLM is configured and as the
 * fallback when the LLM's answer fails validation, so F10 always works.
 */
public final class FollowUpRules {

    private static final String TIME = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?";
    private static final Pattern SHIFT = Pattern.compile(
            "\\b(\\d{1,3})\\s*(min|mins|minute|minutes|hour|hours|hr|hrs)\\s+(later|earlier|sooner)\\b");
    private static final Pattern SHIFT_WORD = Pattern.compile(
            "\\b(half an hour|an hour|a few minutes)\\s+(later|earlier|sooner)\\b");
    private static final Pattern DEPART_AT = Pattern.compile("\\b(?:leave|leaving|depart|departing|go|start)\\s+at\\s+" + TIME);
    private static final Pattern ARRIVE_BY = Pattern.compile(
            "\\b(?:arrive|arriving|be there|get there|reach)\\s+(?:by|before|at)\\s+" + TIME);
    private static final Pattern ROUTE = Pattern.compile(
            "\\b(?:avoid|no|without|skip|not)\\s+(?:taking\\s+|using\\s+)?(?:the\\s+)?(?:line|route|bus|streetcar)?\\s*(\\d{1,3})\\b");
    private static final Pattern MODE = Pattern.compile(
            "\\b(?:avoid|no|without|skip|not)\\s+(?:taking\\s+|using\\s+)?(?:the\\s+)?(subway|subways|streetcar|streetcars|bus|buses|busses)\\b");
    private static final Pattern DESTINATION = Pattern.compile(
            "^(?:what about|how about|instead,?|go to|going to|take me to|change (?:the )?destination to)\\s+(?:going\\s+to\\s+)?(.+?)(?:\\s+instead)?[?.!]*$");

    private FollowUpRules() {
    }

    public static FollowUpIntent parse(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return FollowUpIntent.unknown("empty message");
        }
        if (t.matches("^(undo|go back|never ?mind|revert|cancel that|undo that)[.!]?$")) {
            return new FollowUpIntent(IntentKind.UNDO, "");
        }
        if (t.matches("^(redo|redo that|do that again)[.!]?$")) {
            return new FollowUpIntent(IntentKind.REDO, "");
        }
        Matcher m = SHIFT.matcher(t);
        if (m.find()) {
            int amount = Integer.parseInt(m.group(1));
            if (m.group(2).startsWith("h")) {
                amount *= 60;
            }
            return new FollowUpIntent(IntentKind.CHANGE_TIME, (m.group(3).equals("later") ? "+" : "-") + amount);
        }
        m = SHIFT_WORD.matcher(t);
        if (m.find()) {
            int amount = switch (m.group(1)) {
                case "half an hour" -> 30;
                case "an hour" -> 60;
                default -> 5;
            };
            return new FollowUpIntent(IntentKind.CHANGE_TIME, (m.group(2).equals("later") ? "+" : "-") + amount);
        }
        m = ARRIVE_BY.matcher(t);
        if (m.find()) {
            return new FollowUpIntent(IntentKind.CHANGE_TIME, "arrive@" + clock(m));
        }
        m = DEPART_AT.matcher(t);
        if (m.find()) {
            return new FollowUpIntent(IntentKind.CHANGE_TIME, "depart@" + clock(m));
        }
        m = MODE.matcher(t);
        if (m.find()) {
            String mode = m.group(1).startsWith("sub") ? "SUBWAY" : m.group(1).startsWith("street") ? "STREETCAR" : "BUS";
            return new FollowUpIntent(IntentKind.AVOID_MODE, mode);
        }
        m = ROUTE.matcher(t);
        if (m.find()) {
            return new FollowUpIntent(IntentKind.AVOID_ROUTE, m.group(1));
        }
        if (t.matches(".*\\b(wheelchair|step[- ]?free|no stairs|accessible|stroller|elevators?)\\b.*")) {
            return new FollowUpIntent(IntentKind.CHANGE_STRATEGY, "accessible");
        }
        if (t.matches(".*\\b(fewest|fewer|less|least|no)\\s+(transfers?|changes?)\\b.*")) {
            return new FollowUpIntent(IntentKind.CHANGE_STRATEGY, "fewest-transfers");
        }
        if (t.matches(".*\\b(less|least|minimal|minimum)\\s+walking\\b.*|.*\\bwalk less\\b.*")) {
            return new FollowUpIntent(IntentKind.CHANGE_STRATEGY, "least-walking");
        }
        if (t.matches(".*\\b(fastest|quickest|fastest route)\\b.*")) {
            return new FollowUpIntent(IntentKind.CHANGE_STRATEGY, "fastest");
        }
        m = DESTINATION.matcher(t);
        if (m.find()) {
            return new FollowUpIntent(IntentKind.CHANGE_DESTINATION, text.strip().substring(m.start(1), m.end(1)));
        }
        return FollowUpIntent.unknown("I didn't understand \"" + text.strip() + "\"");
    }

    /** "9", "9:30", "9pm", "21:15" to HH:mm. Hours 1-6 without am/pm are read as pm. */
    private static String clock(Matcher m) {
        int n = m.groupCount();
        int h = Integer.parseInt(m.group(n - 2));
        int min = m.group(n - 1) == null ? 0 : Integer.parseInt(m.group(n - 1));
        String ampm = m.group(n);
        if (ampm != null && ampm.startsWith("p") && h < 12) {
            h += 12;
        } else if (ampm != null && ampm.startsWith("a") && h == 12) {
            h = 0;
        } else if (ampm == null && h >= 1 && h <= 6) {
            h += 12;
        }
        if (h > 23 || min > 59) {
            throw new IllegalArgumentException("not a time");
        }
        return String.format("%02d:%02d", h, min);
    }
}
