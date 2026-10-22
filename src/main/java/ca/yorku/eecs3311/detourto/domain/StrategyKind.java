package ca.yorku.eecs3311.detourto.domain;

/** How the rider wants options ranked (feature F03). */
public enum StrategyKind {
    FASTEST,
    FEWEST_TRANSFERS,
    LEAST_WALKING,
    ACCESSIBLE;

    /** Parses CLI spellings such as "fewest-transfers" or "LEAST_WALKING". */
    public static StrategyKind parse(String text) {
        return valueOf(text.strip().toUpperCase().replace('-', '_'));
    }

    public String cliName() {
        return name().toLowerCase().replace('_', '-');
    }
}
