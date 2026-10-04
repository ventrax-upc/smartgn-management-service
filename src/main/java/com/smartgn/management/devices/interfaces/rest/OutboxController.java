package com.smartgn.management.devices.interfaces.rest;

import com.smartgn.management.devices.application.OutboxOperations;
import com.smartgn.management.devices.application.OutboxOperations.JobView;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/outbox")
@Tag(name="Synchronization operations",description="SuperAdmin-only durable job monitoring")
public final class OutboxController {
    public record RetryRequest(@NotBlank @Size(max=1000) String reason) {}
    private final OutboxOperations operations;
    public OutboxController(OutboxOperations operations) { this.operations=operations; }
    @GetMapping
    @Operation(summary="List synchronization jobs",description="Retries continue automatically. Ten attempts or an expired lease require administrative review. No credentials or payloads are exposed.")
    public List<JobView> list(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) String status,@RequestParam(required=false) UUID deviceId,
            @RequestParam(defaultValue="false") boolean needsAttention) {
        return operations.list(actor,new ListWindow(offset,limit),status,deviceId,needsAttention);
    }
    @GetMapping("/{id}")
    @Operation(summary="Inspect a synchronization job without secret material")
    public JobView get(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id) { return operations.get(actor,id); }
    @PostMapping("/{id}/retry")
    @Operation(summary="Request an immediate retry",description="Requires an administrative reason. Completed jobs and unexpired leases return 409. Attempts and original correlation are preserved.")
    public JobView retry(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id,
            @Valid @RequestBody RetryRequest request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.retry(actor,id,request.reason(),correlation);
    }
}
