package com.smartgn.management.properties.adapter.in.web;

import com.smartgn.management.properties.application.PropertyService;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.ManagementModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name="Properties and supply points",description="Account-scoped administrative resources")
public class PropertyController {
    private final PropertyService service;
    public PropertyController(PropertyService service) { this.service=service; }
    public record PropertyInput(@NotBlank @Size(max=150) String name,@NotBlank @Size(max=500) String address,@NotBlank @Pattern(regexp="HOUSE|COMMERCIAL|BUILDING") String propertyType) {}
    public record PropertyUpdate(@NotBlank @Size(max=150) String name,@NotBlank @Size(max=500) String address,@NotBlank @Pattern(regexp="HOUSE|COMMERCIAL|BUILDING") String propertyType,@NotNull @PositiveOrZero Long version) {}
    public record PointInput(@NotBlank @Size(max=100) String serialNumber,@NotBlank @Size(max=200) String locationName) {}
    public record PointUpdate(@NotBlank @Size(max=200) String locationName,@NotNull @PositiveOrZero Long version) {}
    @PostMapping("/properties") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Register a property owned by the authenticated account")
    public Property create(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@Valid @RequestBody PropertyInput input,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) { return service.create(actor,input.name(),input.address(),input.propertyType(),correlation); }
    @GetMapping("/properties") @Operation(summary="List properties owned by the authenticated account")
    public List<Property> list(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) { return service.list(actor,new ListWindow(offset,limit)); }
    @GetMapping("/properties/{id}") @Operation(summary="Get an authorized property")
    public Property get(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id) { return service.get(actor,id); }
    @PutMapping("/properties/{id}") @Operation(summary="Update a property with an optimistic concurrency version")
    public Property update(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id,@Valid @RequestBody PropertyUpdate input,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) { return service.update(actor,id,input.name(),input.address(),input.propertyType(),input.version(),correlation); }
    @DeleteMapping("/properties/{id}") @Operation(summary="Deactivate a property while preserving its history")
    public Property deactivate(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id,@RequestParam @PositiveOrZero long version,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) { return service.deactivate(actor,id,version,correlation); }
    @PostMapping("/properties/{propertyId}/supply-points") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Register a supply point in an active authorized property")
    public SupplyPoint createPoint(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID propertyId,@Valid @RequestBody PointInput input,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) { return service.createPoint(actor,propertyId,input.serialNumber(),input.locationName(),correlation); }
    @GetMapping("/properties/{propertyId}/supply-points") @Operation(summary="List authorized supply points")
    public List<SupplyPoint> listPoints(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID propertyId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) { return service.listPoints(actor,propertyId,new ListWindow(offset,limit)); }
    @GetMapping("/supply-points/{id}") @Operation(summary="Get administrative data for an authorized supply point")
    public SupplyPoint getPoint(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id) { return service.getPoint(actor,id); }
    @PutMapping("/supply-points/{id}") @Operation(summary="Update descriptive supply-point data without changing its meter identity")
    public SupplyPoint updatePoint(@Parameter(hidden=true) @RequestAttribute("actor") Actor actor,@PathVariable UUID id,@Valid @RequestBody PointUpdate input,@Parameter(hidden=true) @RequestAttribute("correlationId") UUID correlation) { return service.updatePoint(actor,id,input.locationName(),input.version(),correlation); }
}
