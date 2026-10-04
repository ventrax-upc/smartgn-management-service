package com.smartgn.management.application.support;

import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportServiceTest {
    private ManagementStore store;
    private SupportService service;
    private final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    private final Actor owner = new Actor(UUID.randomUUID(), Role.PROPIETARIO, Plan.FREE);
    private final Actor admin = new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.PRO);
    private final TransactionRunner tx = new TransactionRunner() {
        public <T> T run(Supplier<T> work) { return work.get(); }
    };

    @BeforeEach void setup() {
        store = mock(ManagementStore.class);
        service = new SupportService(store, tx, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void ordinaryAccountsCannotAdministerExternalPersonnel() {
        assertEquals("FORBIDDEN", assertThrows(DomainException.class,
                () -> service.createInstaller(owner, new SupportService.InstallerData("A", "B", "ID", "123", null), UUID.randomUUID())).code());
        verifyNoInteractions(store);
    }

    @Test void directoryExposesOnlyEnabledVerifiedTechniciansAndNoInternalIdentification() {
        Technician visible = technician(true, true, 2);
        when(store.listTechnicians()).thenReturn(List.of(visible, technician(false, true, 1), technician(false, false, 0)));
        var entries = service.directory(owner);
        assertEquals(1, entries.size());
        assertEquals(visible.id(), entries.getFirst().id());
        assertFalse(entries.getFirst().toString().contains("NATIONAL-ID"));
    }

    @Test void technicianCannotBeEnabledBeforeManualVerification() {
        Technician unverified = technician(false, false, 0);
        when(store.findTechnician(unverified.id())).thenReturn(Optional.of(unverified));
        assertEquals("INVALID_STATE", assertThrows(DomainException.class,
                () -> service.technicianStatus(admin, unverified.id(), true, 0, UUID.randomUUID())).code());
        verify(store, never()).saveTechnician(any(), anyLong());
    }

    @Test void accreditationCorrectionDisablesEntryAndRevokesVerification() {
        Technician old = technician(true, true, 3);
        when(store.findTechnician(old.id())).thenReturn(Optional.of(old));
        when(store.listTechnicians()).thenReturn(List.of(old));
        var data = new SupportService.TechnicianData("Ana", "Gas", "NATIONAL-ID", "Gas", "NEW-ACC", "123", null);
        Technician updated = service.updateTechnician(admin, old.id(), data, 3, UUID.randomUUID());
        assertFalse(updated.enabled()); assertFalse(updated.verified()); assertNull(updated.verifiedAt());
        verify(store).saveTechnician(updated, 3);
        verify(store).audit(any());
    }

    @Test void crossAccountMaintenanceIsRejectedBeforeWriteOrHistoryRead() {
        UUID propertyId = UUID.randomUUID();
        when(store.findProperty(propertyId)).thenReturn(Optional.of(new Property(propertyId, admin.accountId(), "Other", "Address", "HOUSE", true, 0)));
        var data = new SupportService.MaintenanceData(propertyId, null, null, now.minusSeconds(30), "Work");
        assertEquals("FORBIDDEN", assertThrows(DomainException.class,
                () -> service.createMaintenance(owner, data, UUID.randomUUID())).code());
        verify(store, never()).saveMaintenance(any(), anyLong()); verify(store, never()).audit(any());
        UUID recordId = UUID.randomUUID();
        when(store.findMaintenance(recordId)).thenReturn(Optional.of(new Maintenance(recordId, admin.accountId(), propertyId,
                null, null, now, "Private", admin.accountId(), UUID.randomUUID(), 0)));
        assertThrows(DomainException.class, () -> service.maintenanceHistory(owner, recordId));
        verify(store, never()).listAudit(anyString(), any());
    }

    @Test void maintenanceCorrectionPreservesPreviousDescriptionAndResponsibleActorInAudit() {
        UUID propertyId = UUID.randomUUID(); UUID id = UUID.randomUUID(); UUID originalCorrelation = UUID.randomUUID();
        Maintenance old = new Maintenance(id, owner.accountId(), propertyId, null, null,
                now.minusSeconds(60), "Original \"report\"\nline", owner.accountId(), originalCorrelation, 2);
        when(store.findMaintenance(id)).thenReturn(Optional.of(old));
        when(store.findProperty(propertyId)).thenReturn(Optional.of(new Property(propertyId, owner.accountId(), "Mine", "Address", "HOUSE", false, 1)));
        Maintenance corrected = service.correctMaintenance(owner, id, new SupportService.MaintenanceData(propertyId, null, null,
                now.minusSeconds(60), "Corrected"), 2, UUID.randomUUID());
        assertEquals(3, corrected.version()); assertEquals(owner.accountId(), corrected.responsibleId());
        ArgumentCaptor<Audit> audit = ArgumentCaptor.forClass(Audit.class);
        verify(store).audit(audit.capture());
        assertTrue(audit.getValue().details().contains("Original \\\"report\\\"\\u000aline"));
        assertTrue(audit.getValue().details().contains("Corrected"));
        assertTrue(audit.getValue().details().contains(originalCorrelation.toString()));
        assertEquals("MAINTENANCE_CORRECTED", audit.getValue().action());
    }

    @Test void staleVersionAndFutureMaintenanceCannotWrite() {
        UUID propertyId = UUID.randomUUID(); UUID id = UUID.randomUUID();
        when(store.findMaintenance(id)).thenReturn(Optional.of(new Maintenance(id, owner.accountId(), propertyId,
                null, null, now.minusSeconds(60), "Previous", owner.accountId(), UUID.randomUUID(), 4)));
        assertEquals("CONFLICT", assertThrows(DomainException.class, () -> service.correctMaintenance(owner, id,
                new SupportService.MaintenanceData(propertyId, null, null, now, "Updated"), 3, UUID.randomUUID())).code());
        assertEquals("INVALID_INPUT", assertThrows(DomainException.class, () -> service.createMaintenance(owner,
                new SupportService.MaintenanceData(propertyId, null, null, now.plusSeconds(1), "Future"), UUID.randomUUID())).code());
        verify(store, never()).saveMaintenance(any(), anyLong());
    }

    private Technician technician(boolean enabled, boolean verified, long version) {
        return new Technician(UUID.randomUUID(), "Ana", "Gas", "NATIONAL-ID", "Gas", "ACC", "123", null,
                enabled, verified, verified ? "manual-reference" : null, verified ? admin.accountId() : null,
                verified ? now : null, version);
    }
}
