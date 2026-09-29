package org.example.common;

/** A failure with a specific HTTP status; the message is safe to show to the user. */
public class ApiException extends LampException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
