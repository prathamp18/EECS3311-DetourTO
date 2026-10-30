package ca.yorku.eecs3311.detourto.infrastructure;

/** The live feed could not be read (network error, HTTP error, corrupt data). */
public final class FeedException extends RuntimeException {

    public FeedException(String message, Throwable cause) {
        super(message, cause);
    }

    public FeedException(String message) {
        super(message);
    }
}
