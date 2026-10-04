package com.smartgn.management.bdd;

import com.smartgn.management.application.reporting.ReportingService;
import com.smartgn.management.application.reporting.SnapshotCache;
import com.smartgn.management.application.support.SupportService;
import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SupportReportingSteps {
    private ManagementStore store;
    private TelemetryGateway telemetry;
    private SupportService support;
    private ReportingService reporting;
    private Actor actor;
    private Actor superAdmin;
    private Technician technician;
    private Maintenance maintenance;
    private DomainException failure;
    private ReportingService.ConsumptionReport report;
    private UUID propertyId;
    private UUID pointId;
    private final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    private final Map<UUID, Technician> technicians = new HashMap<>();
    private final Map<UUID, Maintenance> maintenanceRecords = new HashMap<>();
    private final List<Audit> audits = new ArrayList<>();

    @Before public void initialize() {
        store = mock(ManagementStore.class); telemetry = mock(TelemetryGateway.class);
        when(store.findPointsByIds(anyList())).thenAnswer(call -> ((List<UUID>)call.getArgument(0)).stream()
                .map(store::findSupplyPoint).flatMap(Optional::stream).map(p -> {
                    Property property=store.findProperty(p.propertyId()).orElseThrow();
                    return new PointScope(p,property.accountId(),property.version());
                }).toList());
        when(store.findPropertiesByIds(anyList())).thenAnswer(call -> ((List<UUID>)call.getArgument(0)).stream().map(store::findProperty).flatMap(Optional::stream).toList());
        when(store.listTariffsForPeriod(any(),any(),any())).thenAnswer(call -> store.findTariff(call.getArgument(0)).map(List::of).orElseGet(List::of));
        when(store.listActivePointScopes(any(),anyList())).thenAnswer(call -> {
            List<UUID> ids=call.getArgument(1);UUID account=call.getArgument(0);
            List<Property> properties=ids.isEmpty()?store.listProperties(account):store.findPropertiesByIds(ids);
            return properties.stream().filter(Property::active).flatMap(p -> store.listSupplyPointsByProperties(account,List.of(p.id())).stream()
                    .filter(SupplyPoint::active).map(s -> new PointScope(s,p.accountId(),p.version()))).limit(101).toList();
        });

        actor = new Actor(UUID.randomUUID(), Role.PROPIETARIO, Plan.FREE);
        superAdmin = new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.PRO);
        propertyId = UUID.randomUUID(); pointId = UUID.randomUUID();
        technicians.clear(); maintenanceRecords.clear(); audits.clear(); failure = null;
        when(store.listTechnicians()).thenAnswer(call -> new ArrayList<>(technicians.values()));
        when(store.findTechnician(any())).thenAnswer(call -> Optional.ofNullable(technicians.get(call.getArgument(0))));
        doAnswer(call -> { Technician value = call.getArgument(0); technicians.put(value.id(), value); return null; })
                .when(store).saveTechnician(any(), anyLong());
        when(store.findMaintenance(any())).thenAnswer(call -> Optional.ofNullable(maintenanceRecords.get(call.getArgument(0))));
        doAnswer(call -> { Maintenance value = call.getArgument(0); maintenanceRecords.put(value.id(), value); return null; })
                .when(store).saveMaintenance(any(), anyLong());
        doAnswer(call -> { audits.add(call.getArgument(0)); return null; }).when(store).audit(any());
        when(store.listDevices()).thenReturn(List.of());
        TransactionRunner tx = new TransactionRunner() { public <T> T run(Supplier<T> work) { return work.get(); } };
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        support = new SupportService(store, tx, clock);
        reporting = new ReportingService(store, telemetry, tx, clock, SnapshotCache.disabled());
    }

    @Given("a newly registered unverified gas technician")
    public void newTechnician() {
        technician = support.createTechnician(superAdmin, new SupportService.TechnicianData("Ana", "Gas", "ID-1",
                "Gas installations", "ACC-1", "+51999111222", "ana@example.test"), UUID.randomUUID());
    }

    @Then("the gas technician is absent from the contact directory")
    public void absentDirectory() { assertTrue(support.directory(actor).isEmpty()); }

    @When("a SuperAdmin verifies the gas technician with a manual reference and enables it")
    public void verifyAndEnable() {
        technician = support.verifyTechnician(superAdmin, technician.id(), true, "manual-reference-1", technician.version(), UUID.randomUUID());
        technician = support.technicianStatus(superAdmin, technician.id(), true, technician.version(), UUID.randomUUID());
    }

    @Then("the gas technician appears in the contact directory")
    public void appearsDirectory() { assertEquals(technician.id(), support.directory(actor).getFirst().id()); }

    @Given("recorded maintenance for the authenticated account")
    public void recordedMaintenance() {
        ownedProperty(actor.accountId());
        maintenance = support.createMaintenance(actor, maintenanceData("Original intervention"), UUID.randomUUID());
    }

    @When("the account corrects the maintenance description using its current version")
    public void correction() {
        maintenance = support.correctMaintenance(actor, maintenance.id(), maintenanceData("Corrected intervention"), maintenance.version(), UUID.randomUUID());
    }

    @Then("the maintenance audit contains the original and corrected descriptions")
    public void auditPreserved() {
        Audit correction = audits.stream().filter(a -> a.action().equals("MAINTENANCE_CORRECTED")).findFirst().orElseThrow();
        assertTrue(correction.details().contains("Original intervention"));
        assertTrue(correction.details().contains("Corrected intervention"));
        assertEquals(1, maintenance.version());
    }

    @Given("a maintenance property owned by a different account")
    public void foreignProperty() { ownedProperty(UUID.randomUUID()); }

    @When("the authenticated account attempts to record maintenance on that property")
    public void foreignMaintenance() {
        failure = assertThrows(DomainException.class, () -> support.createMaintenance(actor, maintenanceData("Intervention"), UUID.randomUUID()));
    }

    @Then("support rejects the operation with code {string} without writing maintenance")
    public void supportDenial(String code) {
        assertEquals(code, failure.code()); assertTrue(maintenanceRecords.isEmpty()); assertTrue(audits.isEmpty());
    }

    @Given("an Administrator account on the Free plan")
    public void freeAdministrator() { actor = new Actor(actor.accountId(), Role.ADMINISTRADOR, Plan.FREE); }

    @Given("an Administrator Pro account with one authorized point and no Telemetry readings")
    public void noTelemetry() {
        actor = new Actor(actor.accountId(), Role.ADMINISTRADOR, Plan.PRO);
        ownedProperty(actor.accountId());
        when(store.findSupplyPoint(pointId)).thenReturn(Optional.of(new SupplyPoint(pointId, propertyId, "SERIAL-1", "Kitchen", true, 0)));
        when(telemetry.query(any())).thenReturn(new TelemetryGateway.BatchResult(List.of(), now));
    }

    @When("the account requests a consumption report")
    public void requestReport() {
        try { report = reporting.report(actor, List.of(pointId), now.minusSeconds(3600), now); }
        catch (DomainException e) { failure = e; }
    }

    @Then("reporting rejects the operation with code {string} before querying Telemetry")
    public void reportingDenial(String code) {
        assertNotNull(failure); assertEquals(code, failure.code()); verifyNoInteractions(telemetry);
    }

    @Then("the consumption report has no fabricated volume or cost and marks incomplete totals")
    public void reportMissing() {
        assertNull(failure); assertNotNull(report); assertNull(report.knownVolumeM3());
        assertNull(report.knownEstimatedCostPen()); assertFalse(report.totalsComplete());
    }

    private void ownedProperty(UUID accountId) {
        when(store.findProperty(propertyId)).thenReturn(Optional.of(new Property(propertyId, accountId, "House", "Address", "HOUSE", true, 0)));
    }

    private SupportService.MaintenanceData maintenanceData(String description) {
        return new SupportService.MaintenanceData(propertyId, null, null, now.minusSeconds(30), description);
    }
}
