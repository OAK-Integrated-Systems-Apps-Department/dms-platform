package com.oak.dms.search.engine;

/**
 * The search engine could not be used (down, timeout, circuit open, 5xx). Callers
 * treat this as "fall back / retry later", never as a user error. The message never
 * contains document text or query text.
 */
public class SearchEngineException extends RuntimeException {

    public SearchEngineException(String message) {
        super(message);
    }

    public SearchEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
