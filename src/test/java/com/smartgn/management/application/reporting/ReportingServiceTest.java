package com.smartgn.management.application.reporting;

import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.integration.TelemetryGateway.*;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportingServiceTest {
    private ManagementStore store;
    private TelemetryGateway telemetry;
    private SnapshotCache cache;
    private ReportingService service;
    private final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    private final Instant from = now.minusSeconds(3600);
    private final Actor admin = new Actor(UUID.randomUUID(), Role.ADMINISTRADOR, Plan.PRO);
    private final UUID propertyId = UUID.randomUUID();
    private final UUID pointId = UUID.randomUUID();
    private final TransactionRunner tx = new TransactionRunner() {
        public <T> T run(Supplier<T> work) { return work.get(); }
    };

    @BeforeEach void setup() {
        store = mock(ManagementStore.class); telemetry = mock(TelemetryGateway.class); cache = mock(SnapshotCache.class);
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(store.listDevices()).thenReturn(List.of());
        when(store.findProperty(propertyId)).thenReturn(Optional.of(new Property(propertyId, admin.accountId(), "Home", "Address", "HOUSE", true, 2)));
        when(store.findSupplyPoint(pointId)).thenReturn(Optional.of(new SupplyPoint(pointId, propertyId, "SERIAL", "Kitchen", true, 1)));
        when(store.findTariff(admin.accountId())).thenReturn(Optional.of(new Tariff(admin.accountId(), new BigDecimal("2.123456"), from.minusSeconds(1), 0)));

        when(store.findPointsByIds(anyList())).thenAnswer(call -> ((List<UUID>)call.getArgument(0)).stream()
                .map(store::findSupplyPoint).flatMap(Optional::stream).map(p -> {
                    Property property=store.findProperty(p.propertyId()).orElseThrow();
                    return new PointScope(p,property.accountId(),property.version());
                }).toList());
        when(store.findPropertiesByIds(anyList())).thenAnswer(call -> ((List<UUID>)call.getArgument(0)).stream().map(store::findProperty).flatMap(Optional::stream).toList());
        when(store.listTariffsForPeriod(any(),any(),any())).thenAnswer(call -> store.findTariff(call.getArgument(0)).map(List::of).orElseGet(List::of));
        when(store.listActivePointScopes(any(),anyList())).thenAnswer(call -> {
            List<UUID> ids=call.getArgument(1);UUID account=call.getArgument(0);
            List<Property> properties=ids.isEmpty()?store.listProperties(account):store.findPropertiesByIds(ids);
            return properties.stream().filter(Property::active).flatMap(p -> store.listSupplyPointsByProperties(account,List.of(p.id())).stream()
                    .filter(SupplyPoint::active).map(s -> new PointScope(s,p.accountId(),p.version()))).limit(101).toList();
        });

        service = new ReportingService(store, telemetry, tx, Clock.fixed(now, ZoneOffset.UTC), cache);
    }

    @Test void freeAdministratorCanUseViewButCannotReportOrCompare() {
        Actor free = new Actor(admin.accountId(), Role.ADMINISTRADOR, Plan.FREE);
        when(store.listProperties(free.accountId())).thenReturn(List.of());
        assertTrue(service.multiMeter(free, List.of()).rows().isEmpty());
        assertEquals("FORBIDDEN", assertThrows(DomainException.class, () -> service.report(free, List.of(pointId), from, now)).code());
        assertThrows(DomainException.class, () -> service.compare(free, List.of(pointId, UUID.randomUUID()), from, now));
        verifyNoInteractions(telemetry, cache);
    }

    @Test void superAdminDoesNotGainGeneralTelemetryAccess() {
        assertThrows(DomainException.class, () -> service.multiMeter(new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.PRO), List.of(propertyId)));
        verifyNoInteractions(telemetry, cache);
    }

    @Test void crossAccountPointNeverReachesCacheOrTelemetry() {
        Actor stranger = new Actor(UUID.randomUUID(), Role.ADMINISTRADOR, Plan.PRO);
        assertEquals("FORBIDDEN", assertThrows(DomainException.class, () -> service.report(stranger, List.of(pointId), from, now)).code());
        verifyNoInteractions(cache, telemetry);
    }

    @Test void validReportUsesDecimalEstimatesAndPreservesSourceTimes() {
        Metric metric = metric(pointId, "1.25", false);
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(metric), now));
        var report = service.report(admin, List.of(pointId), from, now);
        assertEquals(new BigDecimal("1.25"), report.knownVolumeM3());
        assertEquals(new BigDecimal("2.65"), report.knownEstimatedCostPen());
        assertTrue(report.totalsComplete());
        assertEquals(metric.measuredAt(), report.rows().getFirst().source().measuredAt());
        assertEquals("PEN", report.currency());
        verify(telemetry).query(new BatchQuery(admin.accountId(), List.of(pointId), from, now));
    }

    @Test void missingTelemetryDoesNotProduceZeroOrNormalValues() {
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(), now));
        var report = service.report(admin, List.of(pointId), from, now);
        assertNull(report.knownVolumeM3()); assertNull(report.knownEstimatedCostPen()); assertFalse(report.totalsComplete());
        assertNull(report.rows().getFirst().source()); assertEquals("MISSING_TELEMETRY", report.rows().getFirst().issue());
    }

    @Test void gapsProduceExplicitlyPartialTotalsAndTariffCannotReachBeforeItsEffectiveDate() {
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(metric(pointId, "3", true)), now));
        var gap = service.report(admin, List.of(pointId), from, now);
        assertEquals(new BigDecimal("3"), gap.knownVolumeM3()); assertFalse(gap.totalsComplete());
        assertEquals("TELEMETRY_GAP", gap.rows().getFirst().issue());
        when(store.findTariff(admin.accountId())).thenReturn(Optional.of(new Tariff(admin.accountId(), BigDecimal.TEN, from.plusSeconds(1), 2)));
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(metric(pointId, "3", false)), now));
        var noTariff = service.report(admin, List.of(pointId), from, now);
        assertNull(noTariff.knownEstimatedCostPen()); assertNull(noTariff.appliedTariff()); assertFalse(noTariff.totalsComplete());
        assertEquals("TARIFF_NOT_COVERING_PERIOD", noTariff.rows().getFirst().issue());
    }

    @Test void cacheFailureFallsBackAndForeignBatchResponseIsRejected() {
        when(cache.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(metric(pointId, "1", false)), now));
        assertTrue(service.report(admin, List.of(pointId), from, now).totalsComplete());
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(metric(UUID.randomUUID(), "1", false)), now));
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(DomainException.class, () -> service.report(admin, List.of(pointId), from, now)).code());
    }

    @Test void invalidMetricsDoNotContributeToTotalsAndDuplicateRowsAreRejected() {
        Metric invalid = new Metric(pointId, BigDecimal.TEN, null, null, "UNKNOWN", "UNKNOWN", "INVALID", now, now, false);
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(invalid), now));
        assertNull(service.report(admin, List.of(pointId), from, now).knownVolumeM3());
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(metric(pointId, "1", false), metric(pointId, "1", false)), now));
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(DomainException.class, () -> service.report(admin, List.of(pointId), from, now)).code());
    }

    @Test void invalidIntervalsFailBeforeReadingOrCallingDependencies() {
        assertEquals("INVALID_INPUT", assertThrows(DomainException.class, () -> service.report(admin, List.of(pointId), now, from)).code());
        assertThrows(DomainException.class, () -> service.report(admin, List.of(pointId), from, now.plusSeconds(1)));
        assertThrows(DomainException.class, () -> service.report(admin, List.of(pointId), now.minusSeconds(400L * 86400), now));
        verifyNoInteractions(telemetry, cache);
    }

    @Test void staleSnapshotsAreHistoricalRatherThanCurrentConsumptionAndFutureDatesAreUnusable() {
        Property property = new Property(propertyId, admin.accountId(), "Home", "Address", "HOUSE", true, 2);
        SupplyPoint point = new SupplyPoint(pointId, propertyId, "SERIAL", "Kitchen", true, 1);
        when(store.listProperties(admin.accountId())).thenReturn(List.of(property));
        when(store.listSupplyPointsByProperties(admin.accountId(), List.of(propertyId))).thenReturn(List.of(point));
        Metric stale = new Metric(pointId, BigDecimal.TEN, null, null, "UNKNOWN", "DISCONNECTED", "VALID", now.minusSeconds(31), now, false);
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(stale), now));
        var current = service.multiMeter(admin, List.of(propertyId)).rows().getFirst();
        assertEquals("STALE_TELEMETRY", current.issue()); assertNull(current.knownVolumeM3());
        assertEquals(BigDecimal.TEN, current.source().volumeM3());
        Metric future = new Metric(pointId, BigDecimal.TEN, null, null, "UNKNOWN", "UNKNOWN", "VALID", now.plusSeconds(1), now, false);
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(future), now));
        assertNull(service.report(admin, List.of(pointId), from, now).knownVolumeM3());
    }

    @Test void tariffAdministrationRequiresExplicitRoleAndOptimisticVersion() {
        assertThrows(DomainException.class, () -> service.setTariff(admin, admin.accountId(), BigDecimal.ONE, from, -1, UUID.randomUUID()));
        Actor superAdmin = new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.PRO);
        assertEquals("CONFLICT", assertThrows(DomainException.class, () -> service.setTariff(superAdmin, admin.accountId(), BigDecimal.ONE, from, -1, UUID.randomUUID())).code());
        Tariff updated = service.setTariff(superAdmin, admin.accountId(), BigDecimal.ONE, from, 0, UUID.randomUUID());
        assertEquals(1, updated.version()); verify(store).audit(any());
    }

    @Test void csvQuotesFieldsAndNeutralizesSpreadsheetFormulaPrefixesAndControlCharacters() {
        assertEquals("\"[text] =1+2\"", SpreadsheetCsv.cell("=1+2"));
        assertEquals("\"[text]   @SUM(1)\"", SpreadsheetCsv.cell("  @SUM(1)"));
        assertEquals("\"comma,quote\"\"value\"", SpreadsheetCsv.cell("comma,quote\"value"));
        assertEquals("\"[text] line =DANGEROUS\"", SpreadsheetCsv.cell("line\n=DANGEROUS"));
        assertTrue(SpreadsheetCsv.row(List.of("ok", "＋1")).endsWith("\r\n"));
        assertTrue(SpreadsheetCsv.cell("＋1").startsWith("\"[text] "));
    }
    @Test void twoHistoricalTariffsUseExactIntervalVolumesWithoutProratingTheTotal() {
        Instant change=from.plusSeconds(1800);
        Tariff first=new Tariff(admin.accountId(),new BigDecimal("2"),from.minusSeconds(1),0);
        Tariff second=new Tariff(admin.accountId(),new BigDecimal("3"),change,1);
        doReturn(List.of(first,second)).when(store).listTariffsForPeriod(admin.accountId(),from,now);
        when(telemetry.queryPeriods(any())).thenReturn(new PeriodBatchResult(List.of(
                new PeriodResult(from,now,new BatchResult(List.of(metric(pointId,"10",false)),now)),
                new PeriodResult(from,change,new BatchResult(List.of(metric(pointId,"4",false)),now)),
                new PeriodResult(change,now,new BatchResult(List.of(metric(pointId,"6",false)),now)))));
        var report=service.report(admin,List.of(pointId),from,now);
        assertEquals(new BigDecimal("26.00"),report.knownEstimatedCostPen());
        assertEquals(new BigDecimal("10"),report.knownVolumeM3());assertTrue(report.totalsComplete());
        assertNull(report.appliedTariff());assertEquals(2,report.tariffPeriods().size());
        assertEquals(new BigDecimal("4"),report.rows().getFirst().costSegments().getFirst().knownVolumeM3());
        assertEquals(new BigDecimal("18.00"),report.rows().getFirst().costSegments().get(1).estimatedCostPen());
        verify(telemetry,never()).query(any());
    }
    @Test void historicalCostsStayPartialWhenAnExactIntervalIsMissingAndInconsistentTotalsAreRejected() {
        Instant change=from.plusSeconds(1800);
        doReturn(List.of(new Tariff(admin.accountId(),BigDecimal.ONE,from,0),new Tariff(admin.accountId(),BigDecimal.TEN,change,1)))
                .when(store).listTariffsForPeriod(admin.accountId(),from,now);
        PeriodResult whole=new PeriodResult(from,now,new BatchResult(List.of(metric(pointId,"10",false)),now));
        PeriodResult early=new PeriodResult(from,change,new BatchResult(List.of(metric(pointId,"4",false)),now));
        when(telemetry.queryPeriods(any())).thenReturn(new PeriodBatchResult(List.of(whole,early,
                new PeriodResult(change,now,new BatchResult(List.of(),now)))));
        var partial=service.report(admin,List.of(pointId),from,now);
        assertEquals(new BigDecimal("4.00"),partial.knownEstimatedCostPen());assertFalse(partial.totalsComplete());
        assertEquals("INCOMPLETE_PERIOD_TELEMETRY",partial.rows().getFirst().issue());
        when(telemetry.queryPeriods(any())).thenReturn(new PeriodBatchResult(List.of(whole,early,
                new PeriodResult(change,now,new BatchResult(List.of(metric(pointId,"9",false)),now)))));
        var mismatch=service.report(admin,List.of(pointId),from,now);
        assertNull(mismatch.knownEstimatedCostPen());assertEquals("PERIOD_TOTAL_MISMATCH",mismatch.rows().getFirst().issue());
    }
    @Test void hundredPointsAuthorizeInOneBatchBeforeCacheAndRejectForeignPointsEvenOnWarmCache() {
        List<PointScope> scopes=java.util.stream.IntStream.range(0,100).mapToObj(i -> new PointScope(
                new SupplyPoint(UUID.randomUUID(),propertyId,"meter-"+i,"Room",true,0),admin.accountId(),2)).toList();
        List<UUID> ids=scopes.stream().map(s -> s.point().id()).toList();
        doReturn(scopes).when(store).findPointsByIds(ids);
        when(telemetry.query(any())).thenReturn(new BatchResult(List.of(),now));
        clearInvocations(store);
        service.report(admin,ids,from,now);
        verify(store).findPointsByIds(ids);verify(store,never()).findSupplyPoint(any());verify(store,never()).findProperty(any());
        when(cache.get(anyString())).thenReturn(Optional.of(new BatchResult(List.of(),now)));
        clearInvocations(cache,telemetry);
        Actor stranger=new Actor(UUID.randomUUID(),Role.ADMINISTRADOR,Plan.PRO);
        assertEquals("FORBIDDEN",assertThrows(DomainException.class,()->service.report(stranger,ids,from,now)).code());
        verifyNoInteractions(cache,telemetry);
    }
    @Test void tariffChangesCannotRewriteAnExistingHistoricalInterval() {
        Actor superAdmin=new Actor(UUID.randomUUID(),Role.SUPERADMIN,Plan.PRO);
        assertThrows(DomainException.class,()->service.setTariff(superAdmin,admin.accountId(),BigDecimal.ONE,from.minusSeconds(2),0,UUID.randomUUID()));
        verify(store,never()).saveTariff(any(),anyLong());
    }
    @Test void aSingleUncachedTariffIntervalStillUsesTheExactPeriodContract() {
        Instant change=from.plusSeconds(1800);
        doReturn(List.of(new Tariff(admin.accountId(),BigDecimal.ONE,from,0),new Tariff(admin.accountId(),BigDecimal.TEN,change,1)))
                .when(store).listTariffsForPeriod(admin.accountId(),from,now);
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        when(cache.get(anyString())).thenAnswer(call -> switch(reads.getAndIncrement()) {
            case 0 -> Optional.of(new BatchResult(List.of(metric(pointId,"10",false)),now));
            case 1 -> Optional.of(new BatchResult(List.of(metric(pointId,"4",false)),now));
            default -> Optional.empty();
        });
        when(telemetry.queryPeriods(any())).thenReturn(new PeriodBatchResult(List.of(
                new PeriodResult(change,now,new BatchResult(List.of(metric(pointId,"6",false)),now)))));
        assertEquals(new BigDecimal("64.00"),service.report(admin,List.of(pointId),from,now).knownEstimatedCostPen());
        verify(telemetry,never()).query(any());
        verify(telemetry).queryPeriods(new PeriodQuery(admin.accountId(),List.of(pointId),List.of(new Period(change,now))));
    }

    private Metric metric(UUID point, String volume, boolean gap) {
        return new Metric(point, new BigDecimal(volume), new BigDecimal("1.5"), false, "OPEN", "CONNECTED", "VALID", now.minusSeconds(10), now, gap);
    }
}
