package com.zcx.chenstack.wiki;

/** Small, safe HTTP error contract, without exposing SQL or source admission details. */
public final class WikiProductException extends RuntimeException {
    private final int status;
    public WikiProductException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
