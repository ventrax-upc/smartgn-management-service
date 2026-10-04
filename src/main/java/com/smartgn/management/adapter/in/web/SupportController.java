package com.smartgn.management.adapter.in.web;

import com.smartgn.management.application.support.SupportService;
import com.smartgn.management.application.support.SupportService.*;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.ManagementModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Support and maintenance", description = "Verified gas technicians, external IoT installers and account maintenance history")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode="400", description="Invalid payload, parameter format or business input", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="401", description="Missing, expired or invalid IAM bearer token", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="403", description="Role or resource account is not authorized", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="404", description="Requested record or associated resource does not exist", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="409", description="Stale version, duplicate identification or invalid lifecycle state", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class)))
})
public final class SupportController {
    private final SupportService service;
    private final int maintenanceReviewYears;
    private final int auditReviewYears;

    public SupportController(SupportService service,
            @Value("${smartgn.retention.maintenance-years:5}") int maintenanceReviewYears,
            @Value("${smartgn.retention.audit-years:5}") int auditReviewYears) {
        this.service = service;
        if (maintenanceReviewYears < 1 || maintenanceReviewYears > 100 || auditReviewYears < 1 || auditReviewYears > 100)
            throw new IllegalArgumentException("History review periods must be between 1 and 100 years");
        this.maintenanceReviewYears = maintenanceReviewYears;
        this.auditReviewYears = auditReviewYears;
    }

    @Schema(description="Administrative gas technician input; registration does not verify accreditation automatically")
    public record TechnicianRequest(@Schema(example="Ana") @NotBlank @Size(max=100) String firstName,
            @NotBlank @Size(max=100) String lastName, @NotBlank @Size(max=50) String identification,
            @Schema(example="Residential gas maintenance") @NotBlank @Size(max=200) String specialty,
            @Schema(description="Manually supplied accreditation description or reference", example="ACC-33421") @NotBlank @Size(max=100) String accreditation,
            @Schema(example="+51999111222") @NotBlank @Size(max=40) String phone, @Schema(example="ana@example.test") @Email @Size(max=254) String email) {
        TechnicianData data() { return new TechnicianData(firstName, lastName, identification, specialty, accreditation, phone, email); }
    }
    public record TechnicianUpdate(@NotNull @Valid TechnicianRequest technician, @NotNull @Min(0) Long version) {}
    public record InstallerRequest(@NotBlank @Size(max=100) String firstName,
            @NotBlank @Size(max=100) String lastName, @NotBlank @Size(max=50) String identification,
            @NotBlank @Size(max=40) String phone, @Email @Size(max=254) String email) {
        InstallerData data() { return new InstallerData(firstName, lastName, identification, phone, email); }
    }
    public record InstallerUpdate(@NotNull @Valid InstallerRequest installer, @NotNull @Min(0) Long version) {}
    public record StatusRequest(@Schema(example="true") @NotNull Boolean enabled,
            @Schema(description="Version returned by the last read; stale updates return HTTP 409", example="1") @NotNull @Min(0) Long version) {}
    public record VerificationRequest(@NotNull Boolean verified, @Size(max=500) String reference, @NotNull @Min(0) Long version) {}
    public record MaintenanceRequest(@NotNull UUID propertyId, UUID pointId, UUID technicianId,
            @Schema(description="UTC instant of the intervention already performed", example="2026-10-03T10:15:30Z") @NotNull @PastOrPresent Instant performedAt,
            @Schema(example="Replaced the regulator and recorded a leak-test result") @NotBlank @Size(max=4000) String description) {
        MaintenanceData data() { return new MaintenanceData(propertyId, pointId, technicianId, performedAt, description); }
    }
    public record MaintenanceCorrection(@NotNull @Valid MaintenanceRequest maintenance, @NotNull @Min(0) Long version) {}
    public record HistoryPolicy(int maintenanceRecommendedReviewYears, int auditRecommendedReviewYears, boolean automaticDeletion,
            String basis, String telemetryRetentionApplies) {}

    @GetMapping("/directory/technicians")
    @Operation(summary = "List enabled, manually verified gas technicians", description = "Contact information supports external contact; SmartGN does not book or pay for interventions.")
    public List<DirectoryEntry> directory(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        return service.directory(actor,new ListWindow(offset,limit));
    }

    @GetMapping("/technicians")
    @Operation(summary = "List the administrative gas-technician catalogue", description = "Requires SuperAdmin. Directory consumers use /directory/technicians.")
    public List<Technician> technicians(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        return service.technicians(actor,new ListWindow(offset,limit));
    }

    @GetMapping("/technicians/{id}")
    @Operation(summary = "Get an administrative gas-technician record", description = "Requires SuperAdmin.")
    public Technician technician(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id) {
        return service.technician(actor, id);
    }

    @PostMapping("/technicians")
    @ApiResponse(responseCode="201", description="Unverified, disabled technician created", content=@Content(mediaType="application/json", schema=@Schema(implementation=Technician.class)))
    @Operation(summary = "Register an unverified gas technician", description = "Requires SuperAdmin. New entries remain disabled until manual verification and explicit enabling.")
    public ResponseEntity<Technician> createTechnician(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @Valid @RequestBody TechnicianRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        Technician value = service.createTechnician(actor, body.data(), correlation(correlation));
        return ResponseEntity.created(URI.create("/api/v1/technicians/" + value.id())).body(value);
    }

    @PutMapping("/technicians/{id}")
    @Operation(summary = "Update a gas technician using its current version", description = "Requires SuperAdmin. Identification or accreditation changes revoke verification and disable the entry.")
    public Technician updateTechnician(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
            @Valid @RequestBody TechnicianUpdate body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.updateTechnician(actor, id, body.technician().data(), body.version(), correlation(correlation));
    }

    @PatchMapping("/technicians/{id}/status")
    @Operation(summary = "Enable or disable a gas technician", description = "Requires SuperAdmin and a verified technician before enabling. History is retained.")
    public Technician technicianStatus(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
            @Valid @RequestBody StatusRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.technicianStatus(actor, id, body.enabled(), body.version(), correlation(correlation));
    }

    @PatchMapping("/technicians/{id}/verification")
    @Operation(summary = "Record or revoke a manual verification", description = "Requires SuperAdmin. A reference is required when verifying; this is a manual attestation, not an external authority integration.")
    public Technician verifyTechnician(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
            @Valid @RequestBody VerificationRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.verifyTechnician(actor, id, body.verified(), body.reference(), body.version(), correlation(correlation));
    }

    @GetMapping("/installers")
    @Operation(summary = "List external IoT installers", description = "Requires SuperAdmin. Installer records do not create authenticated accounts.")
    public List<Installer> installers(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) { return service.installers(actor,new ListWindow(offset,limit)); }

    @GetMapping("/installers/{id}")
    @Operation(summary = "Get an external IoT-installer record", description = "Requires SuperAdmin.")
    public Installer installer(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id) {
        return service.installer(actor, id);
    }

    @PostMapping("/installers")
    @ApiResponse(responseCode="201", description="External installer created without a login account", content=@Content(mediaType="application/json", schema=@Schema(implementation=Installer.class)))
    @Operation(summary = "Register an external IoT installer", description = "Requires SuperAdmin.")
    public ResponseEntity<Installer> createInstaller(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @Valid @RequestBody InstallerRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        Installer value = service.createInstaller(actor, body.data(), correlation(correlation));
        return ResponseEntity.created(URI.create("/api/v1/installers/" + value.id())).body(value);
    }

    @PutMapping("/installers/{id}")
    @Operation(summary = "Update an external IoT installer", description = "Requires SuperAdmin and the current record version.")
    public Installer updateInstaller(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
            @Valid @RequestBody InstallerUpdate body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.updateInstaller(actor, id, body.installer().data(), body.version(), correlation(correlation));
    }

    @PatchMapping("/installers/{id}/status")
    @Operation(summary = "Enable or disable an IoT installer", description = "Requires SuperAdmin; existing installation history is retained.")
    public Installer installerStatus(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
            @Valid @RequestBody StatusRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.installerStatus(actor, id, body.enabled(), body.version(), correlation(correlation));
    }

    @GetMapping("/maintenance")
    @Operation(summary = "List maintenance records belonging to the authenticated account")
    public List<Maintenance> maintenance(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) { return service.maintenance(actor,new ListWindow(offset,limit)); }

    @GetMapping("/maintenance/policy")
    @Operation(summary = "Get the configured maintenance and audit history review policy", description = "Operational recommendation, not a legal period. Historical records are not automatically deleted.")
    public HistoryPolicy historyPolicy(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor) {
        return new HistoryPolicy(maintenanceReviewYears, auditReviewYears, false, "Operational review recommendation; no legal retention assertion", "No");
    }

    @GetMapping("/maintenance/{id}")
    @Operation(summary = "Get an account-authorized maintenance record")
    public Maintenance maintenance(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id) {
        return service.maintenance(actor, id);
    }

    @GetMapping("/maintenance/{id}/history")
    @Operation(summary = "Get versioned maintenance corrections and responsible actors", description = "Restricted to the record's account; the audit history is never automatically purged.")
    public List<Audit> history(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id) {
        return service.maintenanceHistory(actor, id);
    }

    @PostMapping("/maintenance")
    @ApiResponse(responseCode="201", description="Maintenance and its initial audit version persisted atomically", content=@Content(mediaType="application/json", schema=@Schema(implementation=Maintenance.class)))
    @Operation(summary = "Record maintenance already performed on an active owned property or point")
    public ResponseEntity<Maintenance> createMaintenance(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @Valid @RequestBody MaintenanceRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        Maintenance value = service.createMaintenance(actor, body.data(), correlation(correlation));
        return ResponseEntity.created(URI.create("/api/v1/maintenance/" + value.id())).body(value);
    }

    @PutMapping("/maintenance/{id}")
    @Operation(summary = "Correct maintenance while preserving previous values", description = "Requires the current version; property and point associations remain immutable.")
    public Maintenance correctMaintenance(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID id,
            @Valid @RequestBody MaintenanceCorrection body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.correctMaintenance(actor, id, body.maintenance().data(), body.version(), correlation(correlation));
    }

    private static UUID correlation(UUID value) { return value == null ? UUID.randomUUID() : value; }
}
