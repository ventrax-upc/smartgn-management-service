package com.smartgn.management.integration;

import java.util.UUID;

/** Broker administration port. Publication remains disabled while provisioning. */
public interface BrokerGateway {
    void stage(UUID deviceId, String credential);
    void activate(UUID deviceId);
    void revoke(UUID deviceId);
}
