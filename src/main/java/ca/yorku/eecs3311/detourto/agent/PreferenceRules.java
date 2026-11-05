package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline detection of stated preferences ("never put me on the 29", "I use a wheelchair",
 * "I prefer fewer transfers"). Saved places need a stop lookup, so the rules return the place
 * text and the caller resolves it.
 */
public final class PreferenceRules {

    /** A preference found in text; for SAVE_PLACE the value is "label=place text" (not yet a stop id). */
    public record Found(PreferenceUpdate update) {
    }

    private static final Pattern NEVER_ROUTE = Pattern.compile(
            "\\b(?:never|don'?t ever|do not ever|i hate|i avoid|always avoid)\\b.*?\\b(?:the\\s+)?(?:line|route|bus|streetcar)?\\s*(\\d{1,3})\\b");
    private static final Pattern PLACE = Pattern.compile(
            "\\b(?:my|i live (?:near|at)|i study at|i work at)\\s*(home|school|work|office|gym|campus)?\\s*(?:is|=)?\\s*(?:at|near)?\\s+(.+?)[.!]*$");
    private static final Pattern SAVE = Pattern.compile("\\bsave\\s+(.+?)\\s+as\\s+(?:my\\s+)?([a-z][a-z ]{0,20}?)[.!]*$");

    private PreferenceRules() {
    }

    /** Words that suggest the message states a lasting preference (cheap pre-check before the LLM). */
    public static boolean mentionsPreference(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.matches(".*\\b(never|always|i prefer|i hate|i avoid|wheelchair|walker|stroller|my home|my school|my work"
                + "|i live|i study|i work|save .+ as|remember)\\b.*");
    }

    public static List<PreferenceUpdate> parse(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        List<PreferenceUpdate> out = new ArrayList<>();
        Matcher m = NEVER_ROUTE.matcher(t);
        if (m.find()) {
            out.add(new PreferenceUpdate(PreferenceUpdate.AVOID_ROUTE, m.group(1)));
        }
        if (t.matches(".*\\b(i use a wheelchair|i'm in a wheelchair|i am in a wheelchair|i use a walker|with a stroller"
                + "|i can'?t (?:use|do) stairs|always step[- ]?free)\\b.*")) {
            out.add(new PreferenceUpdate(PreferenceUpdate.STEP_FREE, "true"));
        }
        if (t.matches(".*\\b(?:i prefer|always give me|i like)\\b.*\\b(fewer|fewest|less|least) (transfers?|changes?)\\b.*")) {
            out.add(new PreferenceUpdate(PreferenceUpdate.DEFAULT_STRATEGY, "fewest-transfers"));
        } else if (t.matches(".*\\b(?:i prefer|always give me|i like)\\b.*\\b(less|least) walking\\b.*")) {
            out.add(new PreferenceUpdate(PreferenceUpdate.DEFAULT_STRATEGY, "least-walking"));
        }
        m = SAVE.matcher(t);
        if (m.find()) {
            out.add(new PreferenceUpdate(PreferenceUpdate.SAVE_PLACE, m.group(2).strip() + "=" + m.group(1).strip()));
        } else {
            m = PLACE.matcher(t);
            if (m.find() && m.group(1) != null) {
                out.add(new PreferenceUpdate(PreferenceUpdate.SAVE_PLACE, m.group(1) + "=" + m.group(2).strip()));
            }
        }
        return out;
    }
}
