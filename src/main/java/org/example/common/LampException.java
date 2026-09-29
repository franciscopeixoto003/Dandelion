package org.example.common;

/** A lamp failure whose message is safe and useful to show to the user. */
public class LampException extends Exception {
    public LampException(String message) {
        super(message);
    }

    public LampException(String message, Throwable cause) {
        super(message, cause);
    }
}
