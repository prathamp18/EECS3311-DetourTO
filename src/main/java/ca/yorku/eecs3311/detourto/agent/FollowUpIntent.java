package ca.yorku.eecs3311.detourto.agent;

/** A follow-up turned into one structured change, e.g. CHANGE_TIME "+15". */
public record FollowUpIntent(IntentKind kind, String value) {

    public static FollowUpIntent unknown(String reason) {
        return new FollowUpIntent(IntentKind.UNKNOWN, reason);
    }
}
