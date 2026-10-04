package com.smartgn.management.application.reporting;

import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.integration.TelemetryGateway.*;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.ListWindow;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Account-scoped batch queries and explicit estimates over Telemetry-owned data. */
public final class ReportingService {
    private final ManagementStore store;
    private final TelemetryGateway telemetry;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final SnapshotCache cache;

    public ReportingService(ManagementStore store, TelemetryGateway telemetry, TransactionRunner transactions, Clock clock, SnapshotCache cache) {
        this.store = store;
        this.telemetry = telemetry;
        this.transactions = transactions;
        this.clock = clock;
        this.cache = cache;
    }

    public record ReportRow(UUID pointId, UUID propertyId, String locationName, Metric source,
            BigDecimal knownVolumeM3, BigDecimal estimatedCostPen, boolean complete, String issue, List<CostSegment> costSegments) {
        public ReportRow(UUID pointId,UUID propertyId,String locationName,Metric source,BigDecimal volume,BigDecimal cost,boolean complete,String issue) {
            this(pointId,propertyId,locationName,source,volume,cost,complete,issue,List.of());
        }
    }
    public record CostSegment(TariffPeriod period,BigDecimal knownVolumeM3,BigDecimal estimatedCostPen,boolean complete,String issue) {}
    public record ConsumptionReport(Instant from, Instant to, Instant calculatedAt, List<ReportRow> rows,
            BigDecimal knownVolumeM3, BigDecimal knownEstimatedCostPen, boolean totalsComplete,
            String currency, String estimateDisclaimer, Tariff appliedTariff, List<TariffPeriod> tariffPeriods) {}
    public record TariffPeriod(Instant from, Instant to, Tariff tariff) {}
    public record ConsolidatedView(Instant calculatedAt, List<ReportRow> rows) {}

    public ConsolidatedView multiMeter(Actor actor, List<UUID> propertyIds) {
        actor.requireRole(Role.ADMINISTRADOR);
        List<UUID> ids=propertyIds==null?List.of():propertyIds.stream().distinct().toList();
        if(ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)) throw invalid("Request at most 100 properties");
        if(!ids.isEmpty()) {
            List<Property> properties=store.findPropertiesByIds(ids);
            properties.forEach(p -> { actor.requireOwner(p.accountId());if(!p.active()) throw new DomainException("INVALID_STATE","Property is inactive"); });
            if(properties.size()!=ids.size()) throw new DomainException("NOT_FOUND","Property was not found");
        }
        List<PointScope> scopes=store.listActivePointScopes(actor.accountId(),ids);
        if(scopes.size()>100) throw invalid("Select a view containing at most 100 supply points");
        if(scopes.isEmpty()) return new ConsolidatedView(clock.instant(),List.of());
        List<SupplyPoint> points=scopes.stream().map(PointScope::point).toList();
        BatchResult result=queryRanges(actor,scopes,List.of(new Period(null,null))).get(new Period(null,null));
        Map<UUID, Metric> metrics = metrics(result, points);
        return new ConsolidatedView(result.calculatedAt(), points.stream()
                .map(point -> row(point, metrics.get(point.id()), null, false)).toList());
    }

    public ConsumptionReport report(Actor actor, List<UUID> pointIds, Instant from, Instant to) {
        actor.requireRole(Role.ADMINISTRADOR);
        actor.requirePro();
        period(from, to);
        List<PointScope> scopes=authorizedPoints(actor,pointIds);
        List<SupplyPoint> points=scopes.stream().map(PointScope::point).toList();
        List<TariffPeriod> periods=tariffPeriods(actor.accountId(),from,to);
        boolean covered=!periods.isEmpty() && periods.getFirst().from().equals(from);
        List<Period> ranges=new ArrayList<>();ranges.add(new Period(from,to));
        if(covered && periods.size()>1) periods.forEach(p -> ranges.add(new Period(p.from(),p.to())));
        Map<Period,BatchResult> snapshots=queryRanges(actor,scopes,ranges);
        BatchResult result=snapshots.get(new Period(from,to));
        Map<UUID, Metric> metrics = metrics(result, points);
        Tariff single=covered && periods.size()==1?periods.getFirst().tariff():null;
        Map<Period,Map<UUID,Metric>> periodMetrics=new HashMap<>();
        snapshots.forEach((range,snapshot)->periodMetrics.put(range,metrics(snapshot,points)));
        List<ReportRow> rows=points.stream().map(point -> {
            ReportRow full=row(point,metrics.get(point.id()),single,true);
            if(!covered||full.knownVolumeM3()==null) return full;
            if(periods.size()==1) return new ReportRow(full.pointId(),full.propertyId(),full.locationName(),full.source(),full.knownVolumeM3(),
                    full.estimatedCostPen(),full.complete(),full.issue(),List.of(new CostSegment(periods.getFirst(),full.knownVolumeM3(),full.estimatedCostPen(),full.complete(),full.issue())));
            List<ReportRow> slices=periods.stream().map(p -> row(point,periodMetrics.get(new Period(p.from(),p.to())).get(point.id()),p.tariff(),true)).toList();
            boolean complete=slices.stream().allMatch(ReportRow::complete);
            BigDecimal sliceVolume=sum(slices.stream().map(ReportRow::knownVolumeM3).toList());
            boolean matches=(sliceVolume==null||sliceVolume.compareTo(full.knownVolumeM3())<=0) && (!complete||sliceVolume.compareTo(full.knownVolumeM3())==0);
            BigDecimal amount=matches?sum(slices.stream().map(ReportRow::estimatedCostPen).toList()):null;
            String issue=!matches?"PERIOD_TOTAL_MISMATCH":!complete?"INCOMPLETE_PERIOD_TELEMETRY":full.source().gap()?"TELEMETRY_GAP":null;
            List<CostSegment> costs=new ArrayList<>();
            for(int i=0;i<periods.size();i++) { ReportRow slice=slices.get(i);costs.add(new CostSegment(periods.get(i),slice.knownVolumeM3(),slice.estimatedCostPen(),slice.complete(),slice.issue())); }
            return new ReportRow(point.id(),point.propertyId(),point.locationName(),full.source(),full.knownVolumeM3(),amount,
                    complete&&matches&&!full.source().gap(),issue,List.copyOf(costs));
        }).toList();
        BigDecimal volume = sum(rows.stream().map(ReportRow::knownVolumeM3).toList());
        BigDecimal cost = sum(rows.stream().map(ReportRow::estimatedCostPen).toList());
        return new ConsumptionReport(from, to, result.calculatedAt(), rows, volume, cost,
                covered && rows.stream().allMatch(ReportRow::complete), "PEN",
                "Estimate using tariff validity intervals and exact interval volumes; not a utility invoice. Known totals may be partial.",
                single,periods);
    }

    public ConsumptionReport compare(Actor actor, List<UUID> pointIds, Instant from, Instant to) {
        actor.requireRole(Role.ADMINISTRADOR);
        actor.requirePro();
        if (pointIds == null || pointIds.stream().distinct().count() < 2) throw invalid("Comparison requires at least two distinct supply points");
        return report(actor, pointIds, from, to);
    }

    public Tariff tariff(Actor actor) {
        return store.findTariff(actor.accountId()).orElseThrow(() -> new DomainException("NOT_FOUND", "No account estimate tariff is configured"));
    }
    public List<Tariff> tariffHistory(Actor actor,UUID accountId,ListWindow window) {
        if(actor.role()!=Role.SUPERADMIN) actor.requireOwner(accountId);
        return store.listTariffHistory(accountId,window);
    }
    private List<TariffPeriod> tariffPeriods(UUID account,Instant from,Instant to) {
        List<Tariff> history=store.listTariffsForPeriod(account,from,to).stream()
                .filter(t -> t.effectiveFrom().isBefore(to)).sorted(java.util.Comparator.comparing(Tariff::effectiveFrom)).toList();
        if(history.size()>100) throw invalid("Select a shorter period containing at most 100 tariff changes");
        List<TariffPeriod> result=new ArrayList<>();
        for(int i=0;i<history.size();i++) {
            Instant start=history.get(i).effectiveFrom().isBefore(from)?from:history.get(i).effectiveFrom();
            Instant end=i+1<history.size()?history.get(i+1).effectiveFrom():to;
            if(start.isBefore(end)) result.add(new TariffPeriod(start,end,history.get(i)));
        }
        return List.copyOf(result);
    }

    public Tariff setTariff(Actor actor, UUID accountId, BigDecimal price, Instant effectiveFrom,
            long expectedVersion, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        if (accountId == null || price == null || price.signum() <= 0 || price.scale() > 6
                || price.compareTo(new BigDecimal("99999999.999999")) > 0 || effectiveFrom == null
                || effectiveFrom.isAfter(clock.instant())) throw invalid("A positive PEN/m3 tariff with up to six decimals and a past/current effective date is required");
        return transactions.run(() -> {
            Tariff old = store.findTariff(accountId).orElse(null);
            long actual = old == null ? -1 : old.version();
            if (actual != expectedVersion) throw new DomainException("CONFLICT", "Tariff was modified; reload before updating");
            Instant effective=effectiveFrom.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(old!=null && !effective.isAfter(old.effectiveFrom())) throw invalid("A new tariff must start after the latest tariff; historical intervals are immutable");
            Tariff value = new Tariff(accountId, price, effective, actual + 1);
            store.saveTariff(value, actual);
            store.audit(new Audit(UUID.randomUUID(), actor.accountId(), "ESTIMATE_TARIFF_CHANGED", "tariff",
                    accountId, correlationId, "before=" + old + ";after=" + value, clock.instant()));
            return value;
        });
    }

    public String csv(ConsumptionReport report) {
        StringBuilder output = new StringBuilder();
        output.append(SpreadsheetCsv.row(List.of("pointId", "propertyId", "locationName", "from", "to",
                "knownVolumeM3", "estimatedCostPen", "currency", "complete", "issue", "measuredAt",
                "calculatedAt", "validityState", "connectionState", "gap", "tariffPricePerM3", "tariffEffectiveFrom", "tariffPeriods", "costSegments")));
        for (ReportRow row : report.rows()) {
            Metric source = row.source();
            List<Object> fields = new ArrayList<>();
            fields.add(row.pointId()); fields.add(row.propertyId()); fields.add(row.locationName());
            fields.add(report.from()); fields.add(report.to()); fields.add(row.knownVolumeM3());
            fields.add(row.estimatedCostPen()); fields.add(report.currency()); fields.add(row.complete()); fields.add(row.issue());
            fields.add(source == null ? null : source.measuredAt()); fields.add(source == null ? null : source.calculatedAt());
            fields.add(source == null ? "MISSING" : source.validityState()); fields.add(source == null ? "UNKNOWN" : source.connectionState());
            fields.add(source == null || source.gap()); fields.add(report.appliedTariff() == null ? null : report.appliedTariff().pricePerM3());
            fields.add(report.appliedTariff() == null ? null : report.appliedTariff().effectiveFrom());
            fields.add(report.tariffPeriods().stream().map(p -> p.from()+"/"+p.to()+"="+p.tariff().pricePerM3()).collect(java.util.stream.Collectors.joining(";")));
            fields.add(row.costSegments().stream().map(s -> s.period().from()+"/"+s.period().to()+":volume="+s.knownVolumeM3()+",cost="+s.estimatedCostPen()+",complete="+s.complete()).collect(java.util.stream.Collectors.joining(";")));
            output.append(SpreadsheetCsv.row(fields));
        }
        return output.toString();
    }

    private ReportRow row(SupplyPoint point, Metric metric, Tariff tariff, boolean historical) {
        if (metric == null) return new ReportRow(point.id(), point.propertyId(), point.locationName(), null, null, null, false, "MISSING_TELEMETRY");
        boolean usable = "VALID".equalsIgnoreCase(metric.validityState()) && metric.measuredAt() != null
                && metric.calculatedAt() != null && !metric.measuredAt().isAfter(clock.instant())
                && !metric.calculatedAt().isAfter(clock.instant())
                && metric.volumeM3() != null && metric.volumeM3().signum() >= 0;
        boolean staleCurrent = !historical && metric.measuredAt() != null
                && metric.measuredAt().isBefore(clock.instant().minusSeconds(30));
        usable = usable && !staleCurrent;
        BigDecimal volume = usable ? metric.volumeM3() : null;
        BigDecimal cost = usable && tariff != null ? volume.multiply(tariff.pricePerM3()).setScale(2, RoundingMode.HALF_UP) : null;
        String issue = staleCurrent ? "STALE_TELEMETRY" : !usable ? "INVALID_OR_INCOMPLETE_TELEMETRY" : metric.gap() ? "TELEMETRY_GAP"
                : historical && tariff == null ? "TARIFF_NOT_COVERING_PERIOD" : null;
        return new ReportRow(point.id(), point.propertyId(), point.locationName(), metric, volume, cost,
                usable && !metric.gap() && (!historical || tariff != null), issue);
    }

    private List<PointScope> authorizedPoints(Actor actor, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("Request between 1 and 100 supply points");
        }
        List<UUID> requested=ids.stream().distinct().toList();
        List<PointScope> scopes=store.findPointsByIds(requested);
        scopes.forEach(scope -> actor.requireOwner(scope.accountId()));
        if(scopes.size()!=requested.size()) throw new DomainException("NOT_FOUND","Supply point was not found");
        Map<UUID,PointScope> byId=new HashMap<>();scopes.forEach(scope->byId.put(scope.point().id(),scope));
        return requested.stream().map(byId::get).toList();
    }

    private Map<Period,BatchResult> queryRanges(Actor actor,List<PointScope> scopes,List<Period> ranges) {
        List<SupplyPoint> points=scopes.stream().map(PointScope::point).toList();
        String state=cacheState(actor,scopes);
        Map<Period,BatchResult> results=new HashMap<>();List<Period> missing=new ArrayList<>();
        for(Period range:ranges) {
            try { cache.get(cacheKey(actor,state,range)).ifPresent(value -> {metrics(value,points);results.put(range,value);}); }
            catch(RuntimeException ignored) { /* Disposable cache failure falls back to source. */ }
            if(!results.containsKey(range)) missing.add(range);
        }
        if(missing.size()==1 && ranges.size()==1) {
            Period range=missing.getFirst();BatchResult value=telemetry.query(new BatchQuery(actor.accountId(),points.stream().map(SupplyPoint::id).toList(),range.from(),range.to()));
            metrics(value,points);results.put(range,value);
        } else if(!missing.isEmpty()) {
            PeriodBatchResult batch=telemetry.queryPeriods(new PeriodQuery(actor.accountId(),points.stream().map(SupplyPoint::id).toList(),missing));
            if(batch==null||batch.periods()==null||batch.periods().size()!=missing.size()) throw dependency();
            java.util.Set<Period> seen=new java.util.HashSet<>();
            for(PeriodResult entry:batch.periods()) {
                if(entry==null) throw dependency();Period range=new Period(entry.from(),entry.to());
                if(!missing.contains(range)||!seen.add(range)) throw dependency();
                metrics(entry.snapshot(),points);results.put(range,entry.snapshot());
            }
        }
        for(Period range:missing) {
            try { cache.put(cacheKey(actor,state,range),results.get(range),range.from()==null?Duration.ofSeconds(1):Duration.ofSeconds(30)); }
            catch(RuntimeException ignored) { /* Source snapshots remain usable. */ }
        }
        return results;
    }

    private String cacheState(Actor actor,List<PointScope> scopes) {
        StringBuilder material=new StringBuilder();List<SupplyPoint> points=scopes.stream().map(PointScope::point).toList();
        scopes.stream().sorted(java.util.Comparator.comparing(s -> s.point().id().toString())).forEach(s ->
                material.append('|').append(s.point().id()).append(':').append(s.point().version()).append(':').append(s.propertyVersion()));
        List<UUID> pointIds = points.stream().map(SupplyPoint::id).toList();
        store.listDevicesByPoints(actor.accountId(), pointIds).stream()
                .sorted(java.util.Comparator.comparing(d -> d.id().toString()))
                .forEach(d -> material.append('|').append(d.id()).append(':').append(d.version()).append(':').append(d.associationVersion()));
        return material.toString();
    }
    private String cacheKey(Actor actor,String state,Period range) {
        String material=state+'|'+range.from()+'|'+range.to();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
            return "management:telemetry:" + actor.accountId() + ":" + hash;
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is required by Java", e); }
    }

    private static Map<UUID, Metric> metrics(BatchResult result, List<SupplyPoint> points) {
        if (result == null || result.metrics() == null || result.calculatedAt() == null) throw dependency();
        List<UUID> allowed = points.stream().map(SupplyPoint::id).toList();
        Map<UUID, Metric> rows = new HashMap<>();
        for (Metric metric : result.metrics()) {
            if (metric == null || !allowed.contains(metric.pointId()) || rows.put(metric.pointId(), metric) != null) throw dependency();
        }
        return rows;
    }

    private void period(Instant from, Instant to) {
        Instant now = clock.instant();
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(now)
                || from.isBefore(now.atOffset(java.time.ZoneOffset.UTC).minusMonths(12).toInstant())) {
            throw invalid("Use an increasing, nonfuture period within the last twelve months (UTC)");
        }
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal::add).orElse(null);
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_INPUT", message); }
    private static DomainException dependency() { return new DomainException("DEPENDENCY_UNAVAILABLE", "Telemetry returned an inconsistent batch response"); }
}
