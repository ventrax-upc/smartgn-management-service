package com.smartgn.management.installations.application;

import com.smartgn.management.installations.domain.InstallationRules;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.Role;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.devices.application.DeviceOperations;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class InstallationOperations {
    private final ManagementStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final DeviceOperations devices;

    public InstallationOperations(ManagementStore store, TransactionRunner transactions, Clock clock) {
        this(store,transactions,clock,null);
    }
    public InstallationOperations(ManagementStore store, TransactionRunner transactions, Clock clock, DeviceOperations devices) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        this.devices = devices;
    }

    public Installation create(Actor actor, UUID propertyId, UUID pointId) {
        return create(actor,propertyId,pointId,UUID.randomUUID());
    }
    public Installation create(Actor actor, UUID propertyId, UUID pointId, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(() -> {
            Property property = store.lockProperty(propertyId).orElseThrow(() -> missing("Property"));
            SupplyPoint point = store.lockSupplyPoint(pointId).orElseThrow(() -> missing("Supply point"));
            if (!property.active() || !point.active() || !point.propertyId().equals(propertyId)) {
                throw new DomainException("INVALID_STATE", "Property and supply point must form an active association");
            }
            Installation value = new Installation(UUID.randomUUID(), property.accountId(), propertyId, pointId,
                    null, InstallationStatus.PENDING, null, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), null, 0);
            store.saveInstallation(value, -1);
            audit(actor, "CREATE", value.id(), correlationId, "Administrative installation created");
            return value;
        });
    }

    public List<Installation> list(Actor actor) {
        actor.requireRole(Role.SUPERADMIN);
        return store.listInstallations();
    }

    public Installation get(Actor actor, UUID id) {
        actor.requireRole(Role.SUPERADMIN);
        return store.findInstallation(id).orElseThrow(() -> missing("Installation"));
    }
    public List<Installation> list(Actor actor,ListWindow window,InstallationStatus status) {
        actor.requireRole(Role.SUPERADMIN);return store.listInstallations(window,status);
    }

    public Installation assign(Actor actor, UUID id, UUID installerId) {
        return assign(actor,id,installerId,UUID.randomUUID());
    }
    public Installation assign(Actor actor, UUID id, UUID installerId, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(() -> {
            Installation before = store.lockInstallation(id).orElseThrow(() -> missing("Installation"));
            Installer installer = store.findInstaller(installerId).orElseThrow(() -> missing("Installer"));
            if (!installer.enabled()) throw new DomainException("INVALID_STATE", "Installer is disabled");
            if (before.status() == InstallationStatus.ASSIGNED && installerId.equals(before.installerId())) return before;
            if (before.status() != InstallationStatus.PENDING) {
                throw new DomainException("INVALID_STATE", "Only a pending installation can be assigned");
            }
            Installation after = new Installation(before.id(), before.accountId(), before.propertyId(), before.pointId(),
                    installerId, InstallationStatus.ASSIGNED, before.deviceId(), before.createdAt(), null, before.version() + 1);
            store.saveInstallation(after, before.version());
            audit(actor, "ASSIGN", id, correlationId, "Assigned installer="+installerId);
            return after;
        });
    }

    public Installation transition(Actor actor, UUID id, InstallationStatus target) {
        return transition(actor,id,target,UUID.randomUUID());
    }
    public Installation transition(Actor actor, UUID id, InstallationStatus target, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        if (target != InstallationStatus.IN_PROGRESS && target != InstallationStatus.COMPLETED) {
            throw new DomainException("INVALID_STATE", "Use the assignment operation; status accepts IN_PROGRESS or COMPLETED");
        }
        return transactions.run(() -> {
            Installation before = store.lockInstallation(id).orElseThrow(() -> missing("Installation"));
            if (before.status() == target) return before;
            InstallationRules.requireTransition(before.status(), target);
            if (target == InstallationStatus.IN_PROGRESS) {
                Installer installer = store.findInstaller(before.installerId()).orElseThrow(() -> missing("Installer"));
                if (!installer.enabled()) throw new DomainException("INVALID_STATE", "Assigned installer is disabled");
            } else {
                if (before.deviceId() == null) throw new DomainException("INVALID_STATE", "Installation has no registered device");
                Device device = store.lockDevice(before.deviceId()).orElseThrow(() -> missing("Device"));
                InstallationRules.requireClosable(before, device);
                Association association = store.findAssociation(device.id(), device.associationVersion())
                        .orElseThrow(() -> missing("Device association"));
                if (!association.confirmed() || association.validTo() != null) {
                    throw new DomainException("INVALID_STATE", "Current association is not confirmed");
                }
            }
            Installation after = new Installation(before.id(), before.accountId(), before.propertyId(), before.pointId(),
                    before.installerId(), target, before.deviceId(), before.createdAt(),
                    target == InstallationStatus.COMPLETED ? clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS) : null, before.version() + 1);
            store.saveInstallation(after, before.version());
            audit(actor, "TRANSITION_" + target, id, correlationId, "Administrative installation transition");
            return after;
        });
    }

    public Installation reassign(Actor actor, UUID id, UUID installerId, long version, String reason, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);reason(reason);
        return transactions.run(() -> {
            Installation before=store.lockInstallation(id).orElseThrow(()->missing("Installation"));
            if(before.version()!=version) throw new DomainException("CONFLICT","Installation changed; reload before reassigning");
            if(before.status()!=InstallationStatus.ASSIGNED) throw new DomainException("INVALID_STATE","Reassignment is allowed only before work starts");
            Installer installer=store.findInstaller(installerId).orElseThrow(()->missing("Installer"));
            if(!installer.enabled()) throw new DomainException("INVALID_STATE","Installer is disabled");
            if(installerId.equals(before.installerId())) return before;
            Installation after=new Installation(before.id(),before.accountId(),before.propertyId(),before.pointId(),installerId,
                    before.status(),before.deviceId(),before.createdAt(),null,before.version()+1);
            store.saveInstallation(after,before.version());
            audit(actor,"REASSIGN",id,correlationId,"before="+before.installerId()+";after="+installerId+";reason="+reason.trim());
            return after;
        });
    }
    public Installation cancel(Actor actor, UUID id, long version, String reason, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);reason(reason);
        return transactions.run(() -> {
            Installation before=store.lockInstallation(id).orElseThrow(()->missing("Installation"));
            if(before.status()==InstallationStatus.CANCELLED) return before;
            if(before.version()!=version) throw new DomainException("CONFLICT","Installation changed; reload before cancelling");
            if(before.status()==InstallationStatus.COMPLETED) throw new DomainException("INVALID_STATE","A completed installation cannot be cancelled");
            if(before.deviceId()!=null) java.util.Objects.requireNonNull(devices,"Device lifecycle operations must be configured").endForCancellation(actor,before,correlationId);
            Installation after=new Installation(before.id(),before.accountId(),before.propertyId(),before.pointId(),before.installerId(),
                    InstallationStatus.CANCELLED,before.deviceId(),before.createdAt(),null,before.version()+1);
            store.saveInstallation(after,before.version());
            audit(actor,"CANCEL",id,correlationId,"previousStatus="+before.status()+";reason="+reason.trim());
            return after;
        });
    }
    private static void reason(String value) {
        if(value==null||value.isBlank()||value.length()>1000) throw new DomainException("INVALID_INPUT","An administrative reason of 1..1000 characters is required");
    }
    private void audit(Actor actor, String action, UUID id, UUID correlationId, String details) {
        store.audit(new Audit(UUID.randomUUID(), actor.accountId(), action, "INSTALLATION", id,
                correlationId, details, clock.instant()));
    }
    private static DomainException missing(String resource) {
        return new DomainException("NOT_FOUND", resource + " was not found");
    }
}
