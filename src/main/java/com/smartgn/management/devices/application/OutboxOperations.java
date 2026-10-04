package com.smartgn.management.devices.application;

import com.smartgn.management.devices.application.DeviceOperations.ProvisioningJob;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Administrative projection: payloads, claim tokens and credentials never leave this use case. */
public final class OutboxOperations {
    public record JobView(UUID id, String type, UUID deviceId, String status, Instant createdAt,
            Instant availableAt, int attempts, Instant leaseUntil, String lastError,
            boolean needsAttention, UUID correlationId) {}
    private final ManagementStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final ObjectMapper mapper;
    public OutboxOperations(ManagementStore store,TransactionRunner transactions,Clock clock,ObjectMapper mapper) {
        this.store=store;this.transactions=transactions;this.clock=clock;this.mapper=mapper;
    }
    public List<JobView> list(Actor actor,ListWindow window,String status,UUID deviceId,boolean attention) {
        actor.requireRole(Role.SUPERADMIN);
        if(status!=null && !List.of("PENDING","PROCESSING","DONE").contains(status))
            throw new DomainException("INVALID_INPUT","Use PENDING, PROCESSING or DONE");
        return store.listJobs(window,status,deviceId,attention).stream().map(this::view).toList();
    }
    public JobView get(Actor actor,UUID id) { actor.requireRole(Role.SUPERADMIN);return view(job(id)); }
    public JobView retry(Actor actor,UUID id,String reason,UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        if(reason==null||reason.isBlank()||reason.length()>1000) throw new DomainException("INVALID_INPUT","A retry reason of 1..1000 characters is required");
        return transactions.run(() -> {
            job(id);
            if(!store.expediteJob(id)) throw new DomainException("CONFLICT","Completed work and active worker leases cannot be retried");
            store.audit(new Audit(UUID.randomUUID(),actor.accountId(),"OUTBOX_RETRY_REQUESTED","OUTBOX",id,correlationId,
                    "reason="+reason.trim(),clock.instant()));
            return view(job(id));
        });
    }
    private OutboxJob job(UUID id) { return store.findJob(id).orElseThrow(()->new DomainException("NOT_FOUND","Outbox job was not found")); }
    private JobView view(OutboxJob job) {
        UUID correlation=null;
        try { correlation=mapper.readValue(job.payload(),ProvisioningJob.class).correlationId(); }
        catch(RuntimeException ignored) { /* Malformed jobs remain inspectable without exposing payloads. */ }
        String error=job.lastError()==null?null:"INTEGRATION_NOT_CONFIRMED";
        if(job.lastError()!=null && job.lastError().matches("INTEGRATION_NOT_CONFIRMED:(LOAD_JOB|REVOKE_BROKER|SYNC_ASSOCIATION_END|STAGE_BROKER|SYNC_ASSOCIATION|CONFIRM_PROJECTION|ACTIVATE_BROKER|PERSIST_ACTIVATION)")) error=job.lastError();
        boolean attention=!"DONE".equals(job.status()) && (job.attempts()>=10 || "PROCESSING".equals(job.status())
                && job.leaseUntil()!=null && !job.leaseUntil().isAfter(clock.instant()));
        return new JobView(job.id(),job.type(),job.aggregateId(),job.status(),job.createdAt(),job.availableAt(),job.attempts(),job.leaseUntil(),error,attention,correlation);
    }
}
