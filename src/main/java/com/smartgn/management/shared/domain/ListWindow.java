package com.smartgn.management.shared.domain;

/** Bounded, deterministic offset pagination for collection endpoints. */
public record ListWindow(int offset, int limit) {
    public ListWindow {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100) {
            throw new DomainException("INVALID_INPUT", "Use offset 0..1000000 and limit 1..100");
        }
    }
    public static ListWindow defaults() { return new ListWindow(0, 50); }
}
