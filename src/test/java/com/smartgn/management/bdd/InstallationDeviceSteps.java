package com.smartgn.management.bdd;

import com.smartgn.management.devices.application.DeviceOperations;
import com.smartgn.management.devices.application.DeviceProvisioningWorker;
import com.smartgn.management.installations.application.InstallationOperations;
import com.smartgn.management.integration.*;
import com.smartgn.management.shared.application.port.out.*;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Application fixtures; real broker/SQL checks remain separate acceptance gates. */
public class InstallationDeviceSteps {
    private ManagementStore store;
    private BrokerGateway broker;
    private TelemetryGateway telemetry;
    private InstallationOperations installs;
    private DeviceOperations deviceOperations;
    private DeviceProvisioningWorker worker;
    private CredentialVault vault;
    private Actor actor;
    private UUID propertyId, pointId;
    private Installer installer;
    private Installation installation, target;
    private DeviceOperations.ProvisioningReceipt receipt;
    private DomainException failure;
    private long originalAssociation;
    private final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    private final Object transactionLock = new Object();
    private final Map<UUID, Installation> installations = new ConcurrentHashMap<>();
    private final Map<UUID, Device> devices = new ConcurrentHashMap<>();
    private final Map<UUID, Credential> credentials = new ConcurrentHashMap<>();
    private final Map<UUID, List<Association>> associations = new HashMap<>();
    private final Map<UUID, OutboxJob> jobs = new LinkedHashMap<>();
    private final List<Audit> audits = new ArrayList<>();
    private final List<Installation> completions = new ArrayList<>();

    @Before public void initializeInstallationFixture() {
        installations.clear(); devices.clear(); credentials.clear(); associations.clear(); jobs.clear(); audits.clear(); completions.clear();
        failure = null;
        store = mock(ManagementStore.class); broker = mock(BrokerGateway.class); telemetry = mock(TelemetryGateway.class);
        actor = new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.PRO);
        propertyId = UUID.randomUUID(); pointId = UUID.randomUUID();
        installer = new Installer(UUID.randomUUID(), "External", "Installer", "ID-IOT", "123", null, true, 0);
        Property property = new Property(propertyId, UUID.randomUUID(), "Home", "Address", "HOUSE", true, 0);
        when(store.lockProperty(propertyId)).thenReturn(Optional.of(property));
        when(store.lockSupplyPoint(pointId)).thenReturn(Optional.of(new SupplyPoint(pointId, propertyId, "GAS-1", "Kitchen", true, 0)));
        when(store.findInstaller(installer.id())).thenAnswer(c -> Optional.of(installer));
        when(store.findInstallation(any())).thenAnswer(c -> Optional.ofNullable(installations.get(c.getArgument(0))));
        when(store.lockInstallation(any())).thenAnswer(c -> Optional.ofNullable(installations.get(c.getArgument(0))));
        doAnswer(c -> { Installation v = c.getArgument(0); installations.put(v.id(), v); return null; }).when(store).saveInstallation(any(), anyLong());
        when(store.findDevice(any())).thenAnswer(c -> Optional.ofNullable(devices.get(c.getArgument(0))));
        when(store.lockDevice(any())).thenAnswer(c -> Optional.ofNullable(devices.get(c.getArgument(0))));
        doAnswer(c -> { Device v = c.getArgument(0); devices.put(v.id(), v); return null; }).when(store).saveDevice(any(), anyLong());
        when(store.findCredential(any())).thenAnswer(c -> Optional.ofNullable(credentials.get(c.getArgument(0))));
        when(store.listCredentials(any())).thenAnswer(c -> credentials.values().stream().filter(v -> v.deviceId().equals(c.getArgument(0))).toList());
        doAnswer(c -> { Credential v = c.getArgument(0); credentials.put(v.id(), v); return null; }).when(store).saveCredential(any());
        doAnswer(c -> { Association v = c.getArgument(0); associations.computeIfAbsent(v.deviceId(), id -> new ArrayList<>()).add(v); return null; }).when(store).appendAssociation(any());
        when(store.listAssociations(any())).thenAnswer(c -> List.copyOf(associations.getOrDefault(c.getArgument(0), List.of())));
        when(store.findAssociation(any(), anyLong())).thenAnswer(c -> associations.getOrDefault(c.getArgument(0), List.of()).stream().filter(v -> v.version() == (long)c.getArgument(1)).findFirst());
        doAnswer(c -> { replaceAssociation(c.getArgument(0), c.getArgument(1), true, null); return null; }).when(store).confirmAssociation(any(), anyLong());
        doAnswer(c -> { replaceAssociation(c.getArgument(0), c.getArgument(1), null, c.getArgument(2)); return null; }).when(store).endAssociation(any(), anyLong(), any());
        doAnswer(c -> { OutboxJob v = c.getArgument(0); jobs.put(v.id(), v); return null; }).when(store).enqueue(any());
        when(store.claimJobs(anyInt(), any())).thenAnswer(c -> {
            OutboxJob pending = jobs.values().stream().filter(j -> !j.status().equals("DONE")).findFirst().orElse(null);
            if (pending == null) return List.of();
            OutboxJob claimed = new OutboxJob(pending.id(), pending.type(), pending.aggregateId(), pending.payload(), "PROCESSING",
                    pending.createdAt(), pending.availableAt(), pending.attempts(), UUID.randomUUID(), now.plusSeconds(60), pending.lastError());
            jobs.put(claimed.id(), claimed); return List.of(claimed);
        });
        when(store.ownsJob(any(), any())).thenAnswer(c -> {
            OutboxJob job = jobs.get(c.getArgument(0)); return job != null && job.status().equals("PROCESSING") && Objects.equals(job.claimToken(), c.getArgument(1));
        });
        when(store.completeJob(any(), any())).thenAnswer(c -> { changeJob(c.getArgument(0), "DONE", null); return true; });
        when(store.retryJob(any(), any(), any(), anyString())).thenAnswer(c -> { changeJob(c.getArgument(0), "PENDING", c.getArgument(3)); return true; });
        doAnswer(c -> { audits.add(c.getArgument(0)); return null; }).when(store).audit(any());
        TransactionRunner tx = new TransactionRunner() { public <T> T run(Supplier<T> work) { synchronized(transactionLock) { return work.get(); } } };
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        vault = new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));
        var mapper = JsonMapper.builder().findAndAddModules().build();
        installs = new InstallationOperations(store, tx, clock);
        deviceOperations = new DeviceOperations(store, tx, clock, vault, telemetry, mapper);
        worker = new DeviceProvisioningWorker(store, tx, clock, vault, telemetry, broker, mapper);
    }

    @Given("installation fixtures with active owned resources and an enabled installer")
    public void activeResources() { assertTrue(installer.enabled()); }

    @When("SuperAdmin creates a request and assigns the installer")
    public void createAndAssign() {
        installation = installs.create(actor, propertyId, pointId);
        assertEquals(InstallationStatus.PENDING, installation.status());
        installation = installs.assign(actor, installation.id(), installer.id());
    }

    @Then("the request progresses from PENDING to ASSIGNED")
    public void assigned() { assertEquals(InstallationStatus.ASSIGNED, installation.status()); }

    @Then("assigning a disabled installer is rejected")
    public void disabledInstaller() {
        installer = new Installer(installer.id(), installer.firstName(), installer.lastName(), installer.identification(), installer.phone(), installer.email(), false, 1);
        Installation other = installs.create(actor, propertyId, pointId);
        assertThrows(DomainException.class, () -> installs.assign(actor, other.id(), installer.id()));
    }

    @Given("an IN_PROGRESS installation")
    public void inProgress() { createAndAssign(); installation = installs.transition(actor, installation.id(), InstallationStatus.IN_PROGRESS); }

    @When("SuperAdmin registers its physical device")
    public void registerDevice() { receipt = deviceOperations.register(actor, installation.id(), "DEVICE-1", "Kitchen", now.minusSeconds(10)); installation = installations.get(installation.id()); }

    @Then("an encrypted individual credential and association outbox are persisted")
    public void encryptedAndDurable() {
        Credential c = credentials.get(receipt.credentialId());
        assertNotEquals(receipt.oneTimeCredential(), c.protectedValue());
        assertEquals(receipt.oneTimeCredential(), vault.reveal(receipt.device().id(), c.protectedValue()));
        assertEquals(1, jobs.size()); assertFalse(jobs.values().iterator().next().payload().contains(receipt.oneTimeCredential()));
        assertEquals(DeviceStatus.PENDING, currentDevice().status());
    }

    @When("Telemetry association confirmation fails in the worker fixture")
    public void syncFailure() { doThrow(new DependencyFailure("Telemetry")).when(telemetry).synchronize(any()); worker.runOnce(); }

    @Then("the worker does not activate publication and retains retryable work")
    public void durableSyncFailure() {
        verify(broker, never()).activate(any()); assertEquals(DeviceStatus.FAILED, currentDevice().status());
        assertTrue(jobs.values().stream().anyMatch(j -> j.status().equals("PENDING")));
    }

    @When("Telemetry confirms the exact association and the worker retries")
    public void syncSuccess() { doNothing().when(telemetry).synchronize(any()); worker.runOnce(); }

    @Then("activation occurs after association acknowledgement and broker activation")
    public void activatedAfterAck() {
        Device d = currentDevice(); assertEquals(DeviceStatus.ACTIVE, d.status()); assertTrue(d.brokerConfirmed()); assertTrue(d.telemetryConfirmed());
        var order = inOrder(telemetry, broker);
        order.verify(telemetry, atLeastOnce()).synchronize(argThat(a -> a.deviceId().equals(d.id()) && a.version() == d.associationVersion()));
        order.verify(broker).activate(d.id());
    }

    @Given("an IN_PROGRESS installation with its exact ACTIVE confirmed device")
    public void activeConfirmedDevice() { inProgress(); registerDevice(); worker.runOnce(); assertEquals(DeviceStatus.ACTIVE, currentDevice().status()); }

    @When("100 pairs of requests attempt to close it concurrently")
    public void concurrentClose() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int pair = 0; pair < 100; pair++) {
                List<Callable<Installation>> calls = List.of(() -> installs.transition(actor, installation.id(), InstallationStatus.COMPLETED),
                        () -> installs.transition(actor, installation.id(), InstallationStatus.COMPLETED));
                for (var response : executor.invokeAll(calls)) completions.add(response.get());
            }
        }
    }

    @Then("at most one effective transition to COMPLETED occurs")
    public void oneEffectiveClose() { assertEquals(200, completions.size()); assertEquals(1, audits.stream().filter(a -> a.action().equals("TRANSITION_COMPLETED")).count()); }

    @Then("all repeated responses preserve the original completion date")
    public void completionDate() { Instant date = completions.getFirst().completedAt(); assertNotNull(date); assertTrue(completions.stream().allMatch(i -> i.completedAt().equals(date))); }

    @Given("broker provisioning is confirmed but Telemetry synchronization is pending")
    public void incompleteSync() { inProgress(); registerDevice(); Device d = currentDevice(); devices.put(d.id(), DeviceOperations.copy(d, DeviceStatus.PENDING, true, false)); }

    @When("SuperAdmin requests completion")
    public void closeRejected() { failure = assertThrows(DomainException.class, () -> installs.transition(actor, installation.id(), InstallationStatus.COMPLETED)); }

    @Then("completion is rejected and its original state is preserved")
    public void statePreserved() { assertNotNull(failure); assertEquals(InstallationStatus.IN_PROGRESS, installations.get(installation.id()).status()); }

    @When("SuperAdmin requests revocation and the broker fixture fails")
    public void revokeFailure() { deviceOperations.revoke(actor, receipt.device().id()); doThrow(new DependencyFailure("Broker")).when(broker).revoke(any()); worker.runOnce(); }

    @Then("revocation is unconfirmed and durable retry work remains")
    public void revokePending() { assertEquals(DeviceStatus.REVOKED, currentDevice().status()); assertFalse(currentDevice().brokerConfirmed()); assertTrue(jobs.values().stream().anyMatch(j -> j.status().equals("PENDING"))); }

    @When("the broker fixture confirms revocation and the worker retries")
    public void revokeSuccess() { doNothing().when(broker).revoke(any()); worker.runOnce(); }

    @Then("the credential is revoked and broker confirmation is persisted")
    public void revokeConfirmed() { assertTrue(currentDevice().brokerConfirmed()); assertEquals("REVOKED", credentials.get(receipt.credentialId()).state()); assertTrue(audits.stream().anyMatch(a -> a.action().equals("REVOCATION_CONFIRMED"))); }

    @Given("the original installation is complete and broker revocation is confirmed")
    public void completedRevoked() {
        activeConfirmedDevice(); installs.transition(actor, installation.id(), InstallationStatus.COMPLETED);
        deviceOperations.revoke(actor, receipt.device().id()); worker.runOnce(); originalAssociation = currentDevice().associationVersion();
        UUID nextPoint = UUID.randomUUID();
        when(store.lockSupplyPoint(nextPoint)).thenReturn(Optional.of(new SupplyPoint(nextPoint, propertyId, "GAS-2", "Other room", true, 0)));
        target = installs.create(actor, propertyId, nextPoint); target = installs.assign(actor, target.id(), installer.id());
        target = installs.transition(actor, target.id(), InstallationStatus.IN_PROGRESS);
    }

    @Given("the previous association has unconfirmed buffered readings")
    public void pendingReadings() { when(telemetry.drain(any(), anyLong())).thenReturn(new TelemetryGateway.DrainResult(false, 10, null)); }

    @When("SuperAdmin attempts reassociation without a gap resolution")
    public void noGapResolution() { failure = assertThrows(DomainException.class, () -> deviceOperations.reassociate(actor, receipt.device().id(), target.id(), false, null)); }

    @Then("the association does not change")
    public void associationUnchanged() { assertEquals(originalAssociation, currentDevice().associationVersion()); assertNotNull(failure); }

    @When("SuperAdmin explicitly records an administrative gap resolution")
    public void gapResolution() { receipt = deviceOperations.reassociate(actor, receipt.device().id(), target.id(), true, "Investigated missing buffered readings"); }

    @Then("the old version and gap audit are preserved before the new version activates")
    public void gapPreserved() {
        List<Association> history = associations.get(receipt.device().id()); assertEquals(2, history.size()); assertNotNull(history.getFirst().validTo());
        assertEquals(originalAssociation, history.getFirst().version()); assertEquals(originalAssociation + 1, currentDevice().associationVersion());
        assertEquals(DeviceStatus.PENDING, currentDevice().status()); assertTrue(audits.stream().anyMatch(a -> a.details().contains("Explicit unresolved telemetry gap")));
    }

    private Device currentDevice() { return devices.get(receipt.device().id()); }
    private void replaceAssociation(UUID id, long version, Boolean confirmed, Instant end) {
        List<Association> history = associations.get(id);
        for (int i = 0; i < history.size(); i++) {
            Association a = history.get(i);
            if (a.version() == version) history.set(i, new Association(a.deviceId(), a.version(), a.accountId(), a.propertyId(), a.pointId(), a.validFrom(),
                    end == null ? a.validTo() : end, confirmed == null ? a.confirmed() : confirmed));
        }
    }
    private void changeJob(UUID id, String status, String error) {
        OutboxJob j = jobs.get(id);
        jobs.put(id, new OutboxJob(j.id(), j.type(), j.aggregateId(), j.payload(), status, j.createdAt(), j.availableAt(),
                j.attempts() + (status.equals("PENDING") ? 1 : 0), j.claimToken(), j.leaseUntil(), error));
    }
}
