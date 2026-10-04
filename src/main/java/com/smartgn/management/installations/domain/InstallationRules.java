package com.smartgn.management.installations.domain;

import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.Device;
import com.smartgn.management.shared.domain.ManagementModels.DeviceStatus;
import com.smartgn.management.shared.domain.ManagementModels.Installation;
import com.smartgn.management.shared.domain.ManagementModels.InstallationStatus;

/** Installation lifecycle invariants, independent of web and persistence. */
public final class InstallationRules {
    private InstallationRules() {}

    public static void requireTransition(InstallationStatus from, InstallationStatus to) {
        if (from == to) return;
        boolean allowed = from == InstallationStatus.PENDING && to == InstallationStatus.ASSIGNED
                || from == InstallationStatus.ASSIGNED && to == InstallationStatus.IN_PROGRESS
                || from == InstallationStatus.IN_PROGRESS && to == InstallationStatus.COMPLETED;
        if (!allowed) throw new DomainException("INVALID_STATE", "Invalid installation transition");
    }

    public static void requireClosable(Installation installation, Device device) {
        if (installation.status() != InstallationStatus.IN_PROGRESS
                || device.status() != DeviceStatus.ACTIVE || !device.brokerConfirmed()
                || !device.telemetryConfirmed() || !installation.pointId().equals(device.pointId())
                || !installation.propertyId().equals(device.propertyId())
                || !installation.accountId().equals(device.accountId())
                || !installation.id().equals(device.installationId())
                || !device.id().equals(installation.deviceId())) {
            throw new DomainException("INVALID_STATE", "Installation requires its active, confirmed device association");
        }
    }
}
