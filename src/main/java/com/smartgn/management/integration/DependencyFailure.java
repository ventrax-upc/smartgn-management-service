package com.smartgn.management.integration;

/** A sanitized integration error suitable for durable retries. */
public final class DependencyFailure extends RuntimeException {
    public DependencyFailure(String dependency) {
        super(dependency + " is unavailable or returned an invalid response");
    }
}
