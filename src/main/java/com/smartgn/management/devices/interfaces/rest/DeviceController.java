package com.smartgn.management.devices.interfaces.rest;

import com.smartgn.management.devices.application.DeviceOperations;
import com.smartgn.management.devices.application.DeviceOperations.DeviceView;
import com.smartgn.management.devices.application.DeviceOperations.ProvisioningReceipt;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.ManagementModels.DeviceStatus;
import com.smartgn.management.shared.domain.ManagementModels.Association;
import com.smartgn.management.shared.domain.ManagementModels.Device;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices", description = "Versioned device associations and durable MQTT provisioning")
@ApiResponses({
    @ApiResponse(responseCode="400", description="Invalid payload, timestamp or parameter"),
    @ApiResponse(responseCode="401", description="Missing, expired or invalid IAM token"),
    @ApiResponse(responseCode="403", description="Role or account is not authorized"),
    @ApiResponse(responseCode="404", description="Device, installation or associated resource was not found"),
    @ApiResponse(responseCode="409", description="Device operation conflicts with its current state or version"),
    @ApiResponse(responseCode="503", description="Required external service did not confirm the operation")
})
public final class DeviceController {
    public record RegisterDevice(@NotNull UUID installationId, @NotBlank @Size(max = 160) String serialNumber,
                                 @NotBlank @Size(max = 300) String location, @NotNull Instant installedAt,
                                 @Size(max = 1000) String replacementGapReason) {}
    public record Reassociate(@NotNull UUID installationId, boolean resolveGap, @Size(max = 1000) String resolutionReason) {}
    private final DeviceOperations operations;
    public DeviceController(DeviceOperations operations) { this.operations = operations; }

    @PostMapping
    @ApiResponse(responseCode="202", description="Registration accepted; save the one-time credential and poll the device for confirmed activation")
    @Operation(summary = "Register an installed device and request provisioning",
            description = "SuperAdmin only. Returns PENDING and a credential once; configure it and the association version on the physical device. Publication is enabled only after broker and Telemetry confirmations.")
    public ResponseEntity<ProvisioningReceipt> register(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
                                                        @Valid @RequestBody RegisterDevice request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(operations.register(actor,
                request.installationId(), request.serialNumber(), request.location(), request.installedAt(), request.replacementGapReason(),correlation));
    }

    @GetMapping
    @Operation(summary = "Query a device by authorized supply point, or list devices as SuperAdmin",
            description = "Telemetry supplies only operational connectivity and last transport contact. An unavailable dependency is explicitly marked UNAVAILABLE.")
    public List<DeviceView> list(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
                                @RequestParam(required = false) UUID pointId,@RequestParam(defaultValue="0") int offset,
                                @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) DeviceStatus status) {
        return operations.query(actor, pointId,new ListWindow(offset,limit),status);
    }

    @GetMapping("/{id}/associations")
    @Operation(summary = "List preserved association versions as SuperAdmin")
    public List<Association> history(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id) {
        return operations.history(actor, id);
    }

    @PostMapping("/{id}/credentials/rotate")
    @ApiResponse(responseCode="202", description="Rotation accepted; new credential returned once, awaiting external confirmation")
    @Operation(summary = "Request credential rotation", description = "Returns the new credential once. Broker sessions are disconnected and publication is gated by durable external confirmations.")
    public ResponseEntity<ProvisioningReceipt> rotate(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
                                                      @PathVariable UUID id,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(operations.rotate(actor, id,correlation));
    }

    @PostMapping("/{id}/credentials/revoke")
    @ApiResponse(responseCode="202", description="Revocation accepted; await brokerConfirmed before treating session revocation as complete")
    @Operation(summary = "Request credential revocation and session disconnection",
            description = "A pending broker confirmation is never reported as successful external revocation.")
    public ResponseEntity<Device> revoke(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return ResponseEntity.accepted().body(operations.revoke(actor, id,correlation));
    }

    @PostMapping("/{id}/associations")
    @ApiResponse(responseCode="202", description="New association version accepted; credential returned once and activation remains pending")
    @Operation(summary = "Reassociate a device while preserving its history",
            description = "Requires a new in-progress installation and confirmed drain of the previous buffer. Explicit SuperAdmin gap resolution with a reason is required if the buffer cannot be recovered.")
    public ResponseEntity<ProvisioningReceipt> reassociate(@Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
                                                          @PathVariable UUID id, @Valid @RequestBody Reassociate request,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(operations.reassociate(actor, id,
                request.installationId(), request.resolveGap(), request.resolutionReason(),correlation));
    }
}
