package com.smartgn.management.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record Actor(UUID accountId, Role role, Plan plan) {
    public Actor { Objects.requireNonNull(accountId); Objects.requireNonNull(role); Objects.requireNonNull(plan); }
    public void requireRole(Role required) {
        if (role != required) throw new DomainException("FORBIDDEN", "The required role is " + required);
    }
    public void requireOwner(UUID owner) {
        if (!accountId.equals(owner)) throw new DomainException("FORBIDDEN", "Resource belongs to another account");
    }
    public void requirePro() {
        if (plan != Plan.PRO) throw new DomainException("FORBIDDEN", "This operation requires the Pro plan");
    }
}
