package com.smartgn.management.shared.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable business data. Framework and persistence types belong to adapters. */
public final class ManagementModels {
    private ManagementModels() {}
    public enum InstallationStatus { PENDING, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }
    public enum DeviceStatus { PENDING, ACTIVE, REVOKED, FAILED }
    public record Property(UUID id, UUID accountId, String name, String address, String propertyType, boolean active, long version) {}
    public record SupplyPoint(UUID id, UUID propertyId, String serialNumber, String locationName, boolean active, long version) {}
    public record PointScope(SupplyPoint point, UUID accountId, long propertyVersion) {}
    public record Technician(UUID id, String firstName, String lastName, String identification, String specialty, String accreditation, String phone, String email, boolean enabled, boolean verified, String verificationReference, UUID verifiedBy, Instant verifiedAt, long version) {}
    public record Installer(UUID id, String firstName, String lastName, String identification, String phone, String email, boolean enabled, long version) {}
    public record Maintenance(UUID id, UUID accountId, UUID propertyId, UUID pointId, UUID technicianId, Instant performedAt, String description, UUID responsibleId, UUID correlationId, long version) {}
    public record Tariff(UUID accountId, BigDecimal pricePerM3, Instant effectiveFrom, long version) {}
    public record Installation(UUID id, UUID accountId, UUID propertyId, UUID pointId, UUID installerId, InstallationStatus status, UUID deviceId, Instant createdAt, Instant completedAt, long version) {}
    public record Device(UUID id, String serialNumber, UUID accountId, UUID propertyId, UUID pointId, UUID installationId, UUID installerId, String location, Instant installedAt, DeviceStatus status, long associationVersion, boolean brokerConfirmed, boolean telemetryConfirmed, long version) {}
    public record Association(UUID deviceId, long version, UUID accountId, UUID propertyId, UUID pointId, Instant validFrom, Instant validTo, boolean confirmed) {}
    public record Credential(UUID id, UUID deviceId, String protectedValue, String state, long credentialVersion, Instant createdAt, Instant revokedAt) {}
    public record OutboxJob(UUID id, String type, UUID aggregateId, String payload, String status, Instant createdAt, Instant availableAt, int attempts, UUID claimToken, Instant leaseUntil, String lastError) {}
    public record Audit(UUID id, UUID actorId, String action, String resourceType, UUID resourceId, UUID correlationId, String details, Instant occurredAt) {}
}
