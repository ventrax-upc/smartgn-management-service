package com.smartgn.management.shared.application.port.out;

import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.ListWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Saves use -1 for insertion; updates require the persisted version and a new version + 1. */
public interface ManagementStore {
    Optional<Property> findProperty(UUID id);
    Optional<Property> lockProperty(UUID id);
    void saveProperty(Property value, long expectedVersion);
    List<Property> listProperties(UUID accountId);
    default List<Property> listProperties(UUID accountId, ListWindow window) { return listProperties(accountId).stream().skip(window.offset()).limit(window.limit()).toList(); }
    default List<Property> findPropertiesByIds(List<UUID> ids) { return ids.stream().map(this::findProperty).flatMap(Optional::stream).toList(); }
    Optional<SupplyPoint> findSupplyPoint(UUID id);
    Optional<SupplyPoint> lockSupplyPoint(UUID id);
    void saveSupplyPoint(SupplyPoint value, long expectedVersion);
    List<SupplyPoint> listSupplyPoints(UUID propertyId);
    default List<SupplyPoint> listSupplyPoints(UUID propertyId, ListWindow window) { return listSupplyPoints(propertyId).stream().skip(window.offset()).limit(window.limit()).toList(); }
    default List<PointScope> findPointsByIds(List<UUID> ids) {
        return ids.stream().map(this::findSupplyPoint).flatMap(Optional::stream)
                .map(point -> { Property p = findProperty(point.propertyId()).orElseThrow(); return new PointScope(point,p.accountId(),p.version()); }).toList();
    }
    default List<PointScope> listActivePointScopes(UUID accountId, List<UUID> propertyIds) {
        List<Property> properties = listProperties(accountId).stream().filter(Property::active)
                .filter(p -> propertyIds.isEmpty() || propertyIds.contains(p.id())).toList();
        return properties.stream().flatMap(p -> listSupplyPoints(p.id()).stream().filter(SupplyPoint::active)
                .map(point -> new PointScope(point,p.accountId(),p.version()))).limit(101).toList();
    }
    default boolean propertyHasOpenWork(UUID propertyId) {
        return listDevices().stream().anyMatch(d -> d.propertyId().equals(propertyId) && (d.status()!=DeviceStatus.REVOKED || !d.brokerConfirmed()))
                || listInstallations().stream().anyMatch(i -> i.propertyId().equals(propertyId) && i.status()!=InstallationStatus.COMPLETED && i.status()!=InstallationStatus.CANCELLED);
    }
    List<SupplyPoint> listSupplyPointsByProperties(UUID accountId, List<UUID> propertyIds);
    Optional<Technician> findTechnician(UUID id);
    void saveTechnician(Technician value, long expectedVersion);
    List<Technician> listTechnicians();
    default List<Technician> listTechnicians(ListWindow window, boolean directoryOnly) {
        return listTechnicians().stream().filter(t -> !directoryOnly || t.enabled() && t.verified()).skip(window.offset()).limit(window.limit()).toList();
    }
    Optional<Installer> findInstaller(UUID id);
    void saveInstaller(Installer value, long expectedVersion);
    List<Installer> listInstallers();
    default List<Installer> listInstallers(ListWindow window) { return listInstallers().stream().skip(window.offset()).limit(window.limit()).toList(); }
    Optional<Maintenance> findMaintenance(UUID id);
    void saveMaintenance(Maintenance value, long expectedVersion);
    List<Maintenance> listMaintenance(UUID accountId);
    default List<Maintenance> listMaintenance(UUID accountId,ListWindow window) { return listMaintenance(accountId).stream().skip(window.offset()).limit(window.limit()).toList(); }
    Optional<Tariff> findTariff(UUID accountId);
    void saveTariff(Tariff value, long expectedVersion);
    default List<Tariff> listTariffHistory(UUID accountId) { return findTariff(accountId).map(List::of).orElseGet(List::of); }
    default List<Tariff> listTariffsForPeriod(UUID accountId,Instant from,Instant to) { return listTariffHistory(accountId); }
    default List<Tariff> listTariffHistory(UUID accountId,ListWindow window) { return listTariffHistory(accountId).stream().skip(window.offset()).limit(window.limit()).toList(); }
    Optional<Installation> findInstallation(UUID id);
    Optional<Installation> lockInstallation(UUID id);
    void saveInstallation(Installation value, long expectedVersion);
    List<Installation> listInstallations();
    default List<Installation> listInstallations(ListWindow window, InstallationStatus status) { return listInstallations().stream().filter(i -> status==null || i.status()==status).skip(window.offset()).limit(window.limit()).toList(); }
    Optional<Device> findDevice(UUID id);
    Optional<Device> lockDevice(UUID id);
    Optional<Device> findDeviceByPoint(UUID pointId);
    void saveDevice(Device value, long expectedVersion);
    List<Device> listDevices();
    default List<Device> listDevices(ListWindow window, DeviceStatus status) { return listDevices().stream().filter(d -> status==null || d.status()==status).skip(window.offset()).limit(window.limit()).toList(); }
    List<Device> listDevicesByPoints(UUID accountId, List<UUID> pointIds);
    void appendAssociation(Association value);
    Optional<Association> findAssociation(UUID deviceId, long version);
    List<Association> listAssociations(UUID deviceId);
    void confirmAssociation(UUID deviceId, long version);
    void endAssociation(UUID deviceId, long version, Instant at);
    void saveCredential(Credential value);
    Optional<Credential> findCredential(UUID id);
    Optional<Credential> findActiveCredential(UUID deviceId);
    List<Credential> listCredentials(UUID deviceId);
    void enqueue(OutboxJob value);
    List<OutboxJob> claimJobs(int limit, Duration lease);
    boolean ownsJob(UUID id, UUID claimToken);
    boolean completeJob(UUID id, UUID claimToken);
    boolean retryJob(UUID id, UUID claimToken, Instant availableAt, String error);
    Optional<OutboxJob> findJob(UUID id);
    List<OutboxJob> listJobs(ListWindow window, String status, UUID deviceId, boolean needsAttention);
    boolean expediteJob(UUID id);
    void audit(Audit value);
    List<Audit> listAudit(String resourceType, UUID resourceId);
}
