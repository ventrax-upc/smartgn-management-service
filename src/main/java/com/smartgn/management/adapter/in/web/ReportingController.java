package com.smartgn.management.adapter.in.web;

import com.smartgn.management.application.reporting.ReportingService;
import com.smartgn.management.application.reporting.ReportingService.*;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.ManagementModels.Tariff;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Multi-meter reports", description = "Authorized batch views and explicit account tariff estimates")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode="400", description="Invalid point selection, date range or tariff", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="401", description="Missing, expired or invalid IAM bearer token", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="403", description="Account, role or Pro entitlement is not authorized", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="404", description="Point, property or configured tariff was not found", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="409", description="Tariff version changed or the property is inactive", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class))),
    @ApiResponse(responseCode="503", description="Telemetry is unavailable or returned an inconsistent batch; no fabricated readings are substituted", content=@Content(mediaType="application/problem+json", schema=@Schema(implementation=org.springframework.http.ProblemDetail.class)))
})
public final class ReportingController {
    private final ReportingService service;
    public ReportingController(ReportingService service) { this.service = service; }

    @Schema(description="Manual account estimate in PEN per cubic metre; no utility billing integration")
    public record TariffRequest(@Schema(description="Positive estimate rate, up to eight integer and six decimal digits", example="2.123456") @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=8, fraction=6) BigDecimal pricePerM3,
            @Schema(description="UTC start of a new immutable rate interval; must follow the previous rate's start", example="2026-10-01T00:00:00Z") @NotNull @PastOrPresent Instant effectiveFrom,
            @Schema(description="-1 inserts the first tariff; otherwise use its current version", example="-1") @NotNull @Min(-1) Long expectedVersion) {}

    @GetMapping("/multi-meter")
    @Operation(summary = "Query up to 100 authorized points in one view", description = "Requires Administrator; available to Free and Pro. Missing telemetry remains explicitly missing. Cache lifetime is at most one second.")
    public ConsolidatedView multiMeter(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @RequestParam(required=false) List<UUID> propertyIds) {
        return service.multiMeter(actor, propertyIds);
    }

    @GetMapping("/reports/consumption")
    @ApiResponse(responseCode="200", description="A report with source timestamps, explicit gaps and potentially partial known totals", content=@Content(mediaType="application/json", schema=@Schema(implementation=ConsumptionReport.class)))
    @Operation(summary = "Build a consumption report in JSON", description = "Requires Administrator and Pro. UTC from/to must be within the last twelve months. Costs are manual tariff estimates, not invoices; partial data has partial known totals.")
    public ConsumptionReport report(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @RequestParam List<UUID> pointIds, @RequestParam Instant from, @RequestParam Instant to) {
        return service.report(actor, pointIds, from, to);
    }

    @GetMapping(value="/reports/consumption.csv", produces="text/csv")
    @ApiResponse(responseCode="200", description="UTF-8 CSV attachment with fixed headers and quoted CRLF records", content=@Content(mediaType="text/csv", schema=@Schema(type="string", format="binary")))
    @Operation(summary = "Download a consumption report as UTF-8 CSV", description = "Requires Administrator and Pro. Spreadsheet formula prefixes are marked as text; JSON is the lossless interchange contract.")
    public ResponseEntity<byte[]> csv(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @RequestParam List<UUID> pointIds, @RequestParam Instant from, @RequestParam Instant to) {
        String csv = service.csv(service.report(actor, pointIds, from, to));
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=consumption-report.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/comparisons/meters")
    @Operation(summary = "Compare consumption of at least two authorized points", description = "Requires Administrator and Pro. Uses the same date, validity and tariff semantics as a consumption report.")
    public ConsumptionReport compare(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @RequestParam List<UUID> pointIds, @RequestParam Instant from, @RequestParam Instant to) {
        return service.compare(actor, pointIds, from, to);
    }

    @GetMapping("/tariffs/me")
    @Operation(summary = "Get the authenticated account's configured estimate tariff", description = "A manual PEN per cubic metre estimate, not a utility tariff or billing contract.")
    public Tariff tariff(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor) { return service.tariff(actor); }
    @GetMapping("/tariffs/me/history")
    @Operation(summary="List the account's immutable estimate tariff history")
    public List<Tariff> tariffHistory(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        return service.tariffHistory(actor,actor.accountId(),new ListWindow(offset,limit));
    }
    @GetMapping("/tariffs/accounts/{accountId}/history")
    @Operation(summary="List an account's tariff history",description="Restricted to that account or SuperAdmin.")
    public List<Tariff> accountTariffHistory(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID accountId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        return service.tariffHistory(actor,accountId,new ListWindow(offset,limit));
    }

    @PutMapping("/tariffs/accounts/{accountId}")
    @Operation(summary = "Append a manual account estimate tariff", description = "Requires SuperAdmin. Supply expectedVersion=-1 initially, otherwise the current version. Each rate starts after the previous one and preserves historical rates; every change is audited.")
    public Tariff setTariff(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor, @PathVariable UUID accountId,
            @Valid @RequestBody TariffRequest body, @Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) {
        return service.setTariff(actor, accountId, body.pricePerM3(), body.effectiveFrom(), body.expectedVersion(),
                correlation == null ? UUID.randomUUID() : correlation);
    }
}
