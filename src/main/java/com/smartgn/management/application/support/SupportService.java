package com.smartgn.management.application.support;

import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Directory, external installer catalogue and versioned maintenance use cases. */
public final class SupportService {
    private final ManagementStore store;
    private final TransactionRunner transactions;
    private final Clock clock;

    public SupportService(ManagementStore store, TransactionRunner transactions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record TechnicianData(String firstName, String lastName, String identification,
            String specialty, String accreditation, String phone, String email) {}
    public record InstallerData(String firstName, String lastName, String identification, String phone, String email) {}
    public record MaintenanceData(UUID propertyId, UUID pointId, UUID technicianId,
            Instant performedAt, String description) {}
    public record DirectoryEntry(UUID id, String firstName, String lastName, String specialty,
            String accreditation, String phone, String email) {}

    public List<Technician> technicians(Actor actor) {
        actor.requireRole(Role.SUPERADMIN);
        return store.listTechnicians();
    }
    public List<Technician> technicians(Actor actor,ListWindow window) {
        actor.requireRole(Role.SUPERADMIN);return store.listTechnicians(window,false);
    }

    public Technician technician(Actor actor, UUID id) {
        actor.requireRole(Role.SUPERADMIN);
        return technician(id);
    }

    public List<DirectoryEntry> directory(Actor actor) {
        return store.listTechnicians().stream().filter(t -> t.enabled() && t.verified())
                .map(t -> new DirectoryEntry(t.id(), t.firstName(), t.lastName(), t.specialty(),
                        t.accreditation(), t.phone(), t.email())).toList();
    }
    public List<DirectoryEntry> directory(Actor actor,ListWindow window) {
        return store.listTechnicians(window,true).stream().map(t -> new DirectoryEntry(t.id(),t.firstName(),t.lastName(),t.specialty(),t.accreditation(),t.phone(),t.email())).toList();
    }

    public Technician createTechnician(Actor actor, TechnicianData data, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        validate(data);
        return transactions.run(() -> {
            uniqueTechnician(data, null);
            Technician value = technician(UUID.randomUUID(), data, false, false, null, null, null, 0);
            store.saveTechnician(value, -1);
            audit(actor, "TECHNICIAN_CREATED", "technician", value.id(), correlationId, "created; verification pending");
            return value;
        });
    }

    public Technician updateTechnician(Actor actor, UUID id, TechnicianData data, long version, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        validate(data);
        return transactions.run(() -> {
            Technician old = technician(id);
            requireVersion(version, old.version());
            uniqueTechnician(data, id);
            boolean verificationKept = old.identification().equals(data.identification())
                    && old.accreditation().equals(data.accreditation());
            Technician value = technician(id, data, verificationKept && old.enabled(), verificationKept && old.verified(),
                    verificationKept ? old.verificationReference() : null,
                    verificationKept ? old.verifiedBy() : null, verificationKept ? old.verifiedAt() : null, old.version() + 1);
            store.saveTechnician(value, old.version());
            audit(actor, "TECHNICIAN_UPDATED", "technician", id, correlationId,
                    "version " + old.version() + " -> " + value.version() + "; verificationKept=" + verificationKept);
            return value;
        });
    }

    public Technician technicianStatus(Actor actor, UUID id, boolean enabled, long version, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(() -> {
            Technician old = technician(id);
            requireVersion(version, old.version());
            if (enabled && !old.verified()) throw new DomainException("INVALID_STATE", "Verify the technician before enabling the directory entry");
            Technician value = new Technician(old.id(), old.firstName(), old.lastName(), old.identification(),
                    old.specialty(), old.accreditation(), old.phone(), old.email(), enabled, old.verified(),
                    old.verificationReference(), old.verifiedBy(), old.verifiedAt(), old.version() + 1);
            store.saveTechnician(value, old.version());
            audit(actor, "TECHNICIAN_STATUS_CHANGED", "technician", id, correlationId, "enabled=" + enabled);
            return value;
        });
    }

    public Technician verifyTechnician(Actor actor, UUID id, boolean verified, String reference,
            long version, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        if (verified) text(reference, "verificationReference", 500);
        return transactions.run(() -> {
            Technician old = technician(id);
            requireVersion(version, old.version());
            Technician value = new Technician(old.id(), old.firstName(), old.lastName(), old.identification(),
                    old.specialty(), old.accreditation(), old.phone(), old.email(), verified && old.enabled(), verified,
                    verified ? reference : null, actor.accountId(), clock.instant(), old.version() + 1);
            store.saveTechnician(value, old.version());
            audit(actor, "TECHNICIAN_VERIFICATION_CHANGED", "technician", id, correlationId,
                    "verified=" + verified + ";reference=" + (verified ? reference : "revoked"));
            return value;
        });
    }

    public List<Installer> installers(Actor actor) {
        actor.requireRole(Role.SUPERADMIN);
        return store.listInstallers();
    }
    public List<Installer> installers(Actor actor,ListWindow window) {
        actor.requireRole(Role.SUPERADMIN);return store.listInstallers(window);
    }

    public Installer installer(Actor actor, UUID id) {
        actor.requireRole(Role.SUPERADMIN);
        return installer(id);
    }

    public Installer createInstaller(Actor actor, InstallerData data, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        validate(data);
        return transactions.run(() -> {
            uniqueInstaller(data.identification(), null);
            Installer value = installer(UUID.randomUUID(), data, true, 0);
            store.saveInstaller(value, -1);
            audit(actor, "INSTALLER_CREATED", "installer", value.id(), correlationId, "external catalogue entry; no login");
            return value;
        });
    }

    public Installer updateInstaller(Actor actor, UUID id, InstallerData data, long version, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        validate(data);
        return transactions.run(() -> {
            Installer old = installer(id);
            requireVersion(version, old.version());
            uniqueInstaller(data.identification(), id);
            Installer value = installer(id, data, old.enabled(), old.version() + 1);
            store.saveInstaller(value, old.version());
            audit(actor, "INSTALLER_UPDATED", "installer", id, correlationId, "version " + old.version() + " -> " + value.version());
            return value;
        });
    }

    public Installer installerStatus(Actor actor, UUID id, boolean enabled, long version, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(() -> {
            Installer old = installer(id);
            requireVersion(version, old.version());
            Installer value = new Installer(old.id(), old.firstName(), old.lastName(), old.identification(),
                    old.phone(), old.email(), enabled, old.version() + 1);
            store.saveInstaller(value, old.version());
            audit(actor, "INSTALLER_STATUS_CHANGED", "installer", id, correlationId, "enabled=" + enabled);
            return value;
        });
    }

    public List<Maintenance> maintenance(Actor actor) {
        return store.listMaintenance(actor.accountId());
    }
    public List<Maintenance> maintenance(Actor actor,ListWindow window) {
        return store.listMaintenance(actor.accountId(),window);
    }

    public Maintenance maintenance(Actor actor, UUID id) {
        Maintenance value = store.findMaintenance(id).orElseThrow(() -> missing("Maintenance record"));
        actor.requireOwner(value.accountId());
        return value;
    }

    public List<Audit> maintenanceHistory(Actor actor, UUID id) {
        maintenance(actor, id);
        return store.listAudit("maintenance", id);
    }

    public Maintenance createMaintenance(Actor actor, MaintenanceData data, UUID correlationId) {
        validate(data);
        return transactions.run(() -> {
            authorizeMaintenance(actor, data, true);
            Maintenance value = new Maintenance(UUID.randomUUID(), actor.accountId(), data.propertyId(),
                    data.pointId(), data.technicianId(), data.performedAt(), data.description(),
                    actor.accountId(), correlationId, 0);
            store.saveMaintenance(value, -1);
            audit(actor, "MAINTENANCE_CREATED", "maintenance", value.id(), correlationId, snapshots(null, value));
            return value;
        });
    }

    public Maintenance correctMaintenance(Actor actor, UUID id, MaintenanceData data, long version, UUID correlationId) {
        validate(data);
        return transactions.run(() -> {
            Maintenance old = maintenance(actor, id);
            requireVersion(version, old.version());
            if (!old.propertyId().equals(data.propertyId()) || !java.util.Objects.equals(old.pointId(), data.pointId())) {
                throw invalid("Maintenance corrections cannot change resource associations");
            }
            authorizeMaintenance(actor, data, false);
            Maintenance value = new Maintenance(id, old.accountId(), old.propertyId(), old.pointId(),
                    data.technicianId(), data.performedAt(), data.description(), actor.accountId(), correlationId, old.version() + 1);
            store.saveMaintenance(value, old.version());
            audit(actor, "MAINTENANCE_CORRECTED", "maintenance", id, correlationId, snapshots(old, value));
            return value;
        });
    }

    private void authorizeMaintenance(Actor actor, MaintenanceData data, boolean requireActive) {
        Property property = store.findProperty(data.propertyId()).orElseThrow(() -> missing("Property"));
        actor.requireOwner(property.accountId());
        if (requireActive && !property.active()) throw new DomainException("INVALID_STATE", "Property is inactive");
        if (data.pointId() != null) {
            SupplyPoint point = store.findSupplyPoint(data.pointId()).orElseThrow(() -> missing("Supply point"));
            if (!point.propertyId().equals(property.id())) throw invalid("Supply point does not belong to the property");
            if (requireActive && !point.active()) throw new DomainException("INVALID_STATE", "Supply point is inactive");
        }
        if (data.technicianId() != null) technician(data.technicianId());
    }

    private void uniqueTechnician(TechnicianData data, UUID excluded) {
        if (store.listTechnicians().stream().filter(t -> !t.id().equals(excluded)).anyMatch(t ->
                t.identification().equals(data.identification()))) {
            throw new DomainException("CONFLICT", "Technician identification already exists");
        }
    }

    private void uniqueInstaller(String identification, UUID excluded) {
        if (store.listInstallers().stream().anyMatch(i -> !i.id().equals(excluded) && i.identification().equals(identification))) {
            throw new DomainException("CONFLICT", "Installer identification already exists");
        }
    }

    private Technician technician(UUID id) {
        return store.findTechnician(id).orElseThrow(() -> missing("Technician"));
    }

    private Installer installer(UUID id) {
        return store.findInstaller(id).orElseThrow(() -> missing("Installer"));
    }

    private static Technician technician(UUID id, TechnicianData d, boolean enabled, boolean verified,
            String reference, UUID verifiedBy, Instant verifiedAt, long version) {
        return new Technician(id, d.firstName(), d.lastName(), d.identification(), d.specialty(),
                d.accreditation(), d.phone(), d.email(), enabled, verified, reference, verifiedBy, verifiedAt, version);
    }

    private static Installer installer(UUID id, InstallerData d, boolean enabled, long version) {
        return new Installer(id, d.firstName(), d.lastName(), d.identification(), d.phone(), d.email(), enabled, version);
    }

    private void validate(MaintenanceData data) {
        if (data == null || data.propertyId() == null || data.performedAt() == null) throw invalid("Property and performedAt are required");
        if (data.performedAt().isAfter(clock.instant())) throw invalid("Maintenance must have already been performed");
        text(data.description(), "description", 4000);
    }

    private static void validate(TechnicianData d) {
        if (d == null) throw invalid("Technician data is required");
        text(d.firstName(), "firstName", 100); text(d.lastName(), "lastName", 100);
        text(d.identification(), "identification", 50); text(d.specialty(), "specialty", 200);
        text(d.accreditation(), "accreditation", 100); text(d.phone(), "phone", 40);
        email(d.email());
    }

    private static void validate(InstallerData d) {
        if (d == null) throw invalid("Installer data is required");
        text(d.firstName(), "firstName", 100); text(d.lastName(), "lastName", 100);
        text(d.identification(), "identification", 50); text(d.phone(), "phone", 40); email(d.email());
    }

    private static void email(String value) {
        if (value != null && !value.isBlank() && (!value.contains("@") || value.length() > 254)) throw invalid("Invalid email");
    }

    private static void text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid(name + " must contain 1 to " + max + " characters");
    }

    private static void requireVersion(long supplied, long actual) {
        if (supplied != actual) throw new DomainException("CONFLICT", "Record was modified; reload before updating");
    }

    private void audit(Actor actor, String action, String type, UUID id, UUID correlationId, String details) {
        store.audit(new Audit(UUID.randomUUID(), actor.accountId(), action, type, id, correlationId, details, clock.instant()));
    }

    private static String snapshots(Maintenance before, Maintenance after) {
        return "{\"before\":" + snapshot(before) + ",\"after\":" + snapshot(after) + "}";
    }

    private static String snapshot(Maintenance value) {
        if (value == null) return "null";
        return "{\"id\":\"" + value.id() + "\",\"accountId\":\"" + value.accountId()
                + "\",\"propertyId\":\"" + value.propertyId() + "\",\"pointId\":" + json(value.pointId())
                + ",\"technicianId\":" + json(value.technicianId()) + ",\"performedAt\":" + json(value.performedAt())
                + ",\"description\":" + json(value.description()) + ",\"responsibleId\":" + json(value.responsibleId())
                + ",\"correlationId\":" + json(value.correlationId()) + ",\"version\":" + value.version() + "}";
    }

    private static String json(Object value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') escaped.append('\\').append(c);
            else if (c < 32) escaped.append(String.format("\\u%04x", (int) c));
            else escaped.append(c);
        }
        return escaped.append('"').toString();
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_INPUT", message); }
    private static DomainException missing(String type) { return new DomainException("NOT_FOUND", type + " was not found"); }
}
