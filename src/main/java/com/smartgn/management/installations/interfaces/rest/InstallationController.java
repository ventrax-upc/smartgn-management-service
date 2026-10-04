package com.smartgn.management.installations.interfaces.rest;

import com.smartgn.management.installations.application.InstallationOperations;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ManagementModels.Installation;
import com.smartgn.management.shared.domain.ManagementModels.InstallationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/installations")
@Tag(name = "Installations", description = "SuperAdmin installation lifecycle operations")
public final class InstallationController {
    public record CreateInstallation(@NotNull UUID propertyId, @NotNull UUID pointId) {}
    public record AssignInstaller(@NotNull UUID installerId) {}
    public record ChangeStatus(@NotNull InstallationStatus status) {}
    public record ReassignInstaller(@NotNull UUID installerId,@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String reason) {}
    public record CancelInstallation(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String reason) {}
    private final InstallationOperations operations;

    public InstallationController(InstallationOperations operations) { this.operations = operations; }

    @PostMapping
    @Operation(summary = "Create a pending installation for an existing property and supply point")
    public ResponseEntity<Installation> create(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
                                               @Valid @RequestBody CreateInstallation request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        Installation result = operations.create(actor, request.propertyId(), request.pointId(),correlation);
        return ResponseEntity.created(URI.create("/api/v1/installations/" + result.id())).body(result);
    }

    @GetMapping
    @Operation(summary = "List installations administered by SuperAdmin")
    public List<Installation> list(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) InstallationStatus status) {
        return operations.list(actor,new ListWindow(offset,limit),status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an installation and its confirmed lifecycle state")
    public Installation get(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id) {
        return operations.get(actor, id);
    }

    @PatchMapping("/{id}/assign")
    @Operation(summary = "Assign an enabled external IoT installer to a pending request")
    public Installation assign(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
                               @Valid @RequestBody AssignInstaller request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.assign(actor, id, request.installerId(),correlation);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Start or complete an installation", description = "Accepts IN_PROGRESS or COMPLETED. Completion requires the exact broker and Telemetry confirmations. Repeating a completed transition preserves its date.")
    public Installation transition(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
                                   @Valid @RequestBody ChangeStatus request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.transition(actor, id, request.status(),correlation);
    }
    @PatchMapping("/{id}/reassign")
    @Operation(summary="Replace the assigned installer before work starts",description="SuperAdmin only. Requires the current version and an administrative reason.")
    public Installation reassign(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id,
            @Valid @RequestBody ReassignInstaller request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.reassign(actor,id,request.installerId(),request.version(),request.reason(),correlation);
    }
    @PostMapping("/{id}/cancel")
    @Operation(summary="Cancel an unfinished installation while preserving history",description="Requires SuperAdmin, current version and reason. If a device exists, revoke it first and wait for brokerConfirmed=true. Repeated cancellation is idempotent.")
    public Installation cancel(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id,
            @Valid @RequestBody CancelInstallation request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.cancel(actor,id,request.version(),request.reason(),correlation);
    }
}
