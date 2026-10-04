package com.smartgn.management.installations.domain;

import static org.assertj.core.api.Assertions.*;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstallationRulesTest {
    @Test void lifecycleCannotSkipAssignmentAndProgress() {
        assertThatThrownBy(() -> InstallationRules.requireTransition(InstallationStatus.PENDING, InstallationStatus.COMPLETED))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> InstallationRules.requireTransition(InstallationStatus.COMPLETED, InstallationStatus.IN_PROGRESS))
                .isInstanceOf(DomainException.class);
        assertThatCode(() -> InstallationRules.requireTransition(InstallationStatus.COMPLETED, InstallationStatus.COMPLETED)).doesNotThrowAnyException();
    }

    @Test void closureNeedsTheSameActiveDeviceAndBothConfirmations() {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID(), property = UUID.randomUUID(), point = UUID.randomUUID(), device = UUID.randomUUID();
        Installation installation = new Installation(id, account, property, point, UUID.randomUUID(), InstallationStatus.IN_PROGRESS,
                device, Instant.now(), null, 2);
        Device valid = new Device(device, "physical-meter", account, property, point, id, installation.installerId(), "Kitchen",
                Instant.now(), DeviceStatus.ACTIVE, 1, true, true, 3);
        assertThatCode(() -> InstallationRules.requireClosable(installation, valid)).doesNotThrowAnyException();
        Device unconfirmed = new Device(device, valid.serialNumber(), account, property, point, id, valid.installerId(), valid.location(),
                valid.installedAt(), DeviceStatus.ACTIVE, 1, true, false, 3);
        assertThatThrownBy(() -> InstallationRules.requireClosable(installation, unconfirmed)).isInstanceOf(DomainException.class);
        Device foreign = new Device(device, valid.serialNumber(), UUID.randomUUID(), property, point, id, valid.installerId(), valid.location(),
                valid.installedAt(), DeviceStatus.ACTIVE, 1, true, true, 3);
        assertThatThrownBy(() -> InstallationRules.requireClosable(installation, foreign)).isInstanceOf(DomainException.class);
    }
}
