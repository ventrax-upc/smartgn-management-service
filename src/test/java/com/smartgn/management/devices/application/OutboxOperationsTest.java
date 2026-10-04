package com.smartgn.management.devices.application;

import com.smartgn.management.shared.application.port.out.*;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxOperationsTest {
    private final ManagementStore store=mock(ManagementStore.class);
    private final Instant now=Instant.parse("2026-10-04T12:00:00Z");
    private final TransactionRunner tx=new TransactionRunner() {public <T>T run(Supplier<T> work){return work.get();}};
    private final JsonMapper mapper=JsonMapper.builder().findAndAddModules().build();
    private final OutboxOperations operations=new OutboxOperations(store,tx,Clock.fixed(now,ZoneOffset.UTC),mapper);
    private final Actor admin=new Actor(UUID.randomUUID(),Role.SUPERADMIN,Plan.PRO);
    @Test void monitoringCannotLeakPayloadLeaseTokensOrUntrustedExceptionText() {
        UUID id=UUID.randomUUID(),correlation=UUID.randomUUID();
        String payload=mapper.writeValueAsString(new DeviceOperations.ProvisioningJob("PROVISION",1,UUID.randomUUID(),admin.accountId(),correlation));
        OutboxJob job=new OutboxJob(id,"DEVICE",UUID.randomUUID(),payload,"PENDING",now,now,10,UUID.randomUUID(),null,"password=secret");
        when(store.findJob(id)).thenReturn(Optional.of(job));
        var view=operations.get(admin,id);
        assertTrue(view.needsAttention());assertEquals(correlation,view.correlationId());
        String json=mapper.writeValueAsString(view);
        assertFalse(json.contains("secret"));assertFalse(json.contains("payload"));assertFalse(json.contains("claimToken"));
        assertEquals("INTEGRATION_NOT_CONFIRMED",view.lastError());
    }
    @Test void nonAdministrativeActorsCannotInspectOrRetryJobs() {
        Actor owner=new Actor(UUID.randomUUID(),Role.PROPIETARIO,Plan.PRO);
        assertThrows(DomainException.class,()->operations.list(owner,ListWindow.defaults(),null,null,false));
        assertThrows(DomainException.class,()->operations.retry(owner,UUID.randomUUID(),"Reviewed",UUID.randomUUID()));
        verifyNoInteractions(store);
    }
    @Test void manualRetryIsAuditedAndNeverOverridesAnActiveLeaseOrCompletedJob() {
        UUID id=UUID.randomUUID(),correlation=UUID.randomUUID();
        OutboxJob job=new OutboxJob(id,"DEVICE_REVOKE",UUID.randomUUID(),"{}","PENDING",now,now,10,null,null,null);
        when(store.findJob(id)).thenReturn(Optional.of(job));when(store.expediteJob(id)).thenReturn(false);
        assertEquals("CONFLICT",assertThrows(DomainException.class,()->operations.retry(admin,id,"Reviewed broker availability",correlation)).code());
        verify(store,never()).audit(any());
        when(store.expediteJob(id)).thenReturn(true);operations.retry(admin,id,"Reviewed broker availability",correlation);
        verify(store).audit(argThat(a -> a.correlationId().equals(correlation)&&a.action().equals("OUTBOX_RETRY_REQUESTED")));
        assertThrows(DomainException.class,()->new ListWindow(0,101));
    }
}
