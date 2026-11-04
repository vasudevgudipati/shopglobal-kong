package com.shopglobal.agent;

/** Kong (or the model behind it) answered with a non-2xx status. */
public class KongException extends RuntimeException {
    private final int status;
    private final String body;

    public KongException(int status, String body) {
        super("Kong returned HTTP " + status);
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() { return status; }
    public String body() { return body; }
}
