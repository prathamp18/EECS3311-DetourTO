package ca.yorku.eecs3311.detourto.agent;

/** The model's final answer was not in the required JSON format. */
public final class ResponseFormatException extends RuntimeException {

    public ResponseFormatException(String message) {
        super(message);
    }
}
