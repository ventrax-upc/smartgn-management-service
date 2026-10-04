package com.smartgn.management.adapter.out.persistence;

import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.Role;
import com.smartgn.management.shared.domain.Plan;
import com.smartgn.management.installations.application.InstallationOperations;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a temporary schema in the explicitly configured Management test database. */
@EnabledIfEnvironmentVariable(named="SMARTGN_TEST_DATABASE_URL",matches=".+")
class JdbcManagementStoreDatabaseTest {
    private String schema;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcManagementStore store;
    private SpringTransactionRunner transaction;
    private HikariDataSource pool;
    @BeforeEach void prepare() {
        String url=System.getenv("SMARTGN_TEST_DATABASE_URL");
        String user=Objects.requireNonNullElse(System.getenv("SMARTGN_TEST_DATABASE_USERNAME"),"postgres");
        String password=Objects.requireNonNullElse(System.getenv("SMARTGN_TEST_DATABASE_PASSWORD"),"");
        DriverManagerDataSource administrative=new DriverManagerDataSource(url,user,password);
        admin=new JdbcTemplate(administrative);
        schema="management_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        String scopedUrl=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
        pool=new HikariDataSource(); pool.setJdbcUrl(scopedUrl);pool.setUsername(user);pool.setPassword(password);pool.setMaximumPoolSize(8);pool.setMinimumIdle(0);
        DataSource data=pool;
        Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(data);
        JdbcTransactionManager manager=new JdbcTransactionManager(data);
        store=new JdbcManagementStore(jdbc,manager); transaction=new SpringTransactionRunner(manager);
    }
    @AfterEach void cleanup() { if(pool!=null) pool.close(); if(schema!=null&&schema.matches("management_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    private Property property() { Property p=new Property(UUID.randomUUID(),UUID.randomUUID(),"Home","Street","HOUSE",true,0); store.saveProperty(p,-1); return p; }
    @Test void reassignmentAndCancellationPreserveAuditAndReleaseTheOpenInstallationSlot() {
        Property p=property();SupplyPoint point=new SupplyPoint(UUID.randomUUID(),p.id(),"CANCEL-POINT","Kitchen",true,0);store.saveSupplyPoint(point,-1);
        Installer first=new Installer(UUID.randomUUID(),"First","Installer","DOC-FIRST","123",null,true,0);
        Installer second=new Installer(UUID.randomUUID(),"Second","Installer","DOC-SECOND","123",null,true,0);
        store.saveInstaller(first,-1);store.saveInstaller(second,-1);
        Actor actor=new Actor(UUID.randomUUID(),Role.SUPERADMIN,Plan.FREE);UUID correlation=UUID.randomUUID();
        InstallationOperations operations=new InstallationOperations(store,transaction,Clock.systemUTC());
        Installation pending=operations.create(actor,p.id(),point.id(),correlation);
        Installation assigned=operations.assign(actor,pending.id(),first.id(),correlation);
        Installation reassigned=operations.reassign(actor,pending.id(),second.id(),assigned.version(),"Original installer unavailable",correlation);
        assertEquals(second.id(),reassigned.installerId());
        assertThrows(DomainException.class,()->operations.reassign(actor,pending.id(),first.id(),assigned.version(),"Stale decision",correlation));
        Installation cancelled=operations.cancel(actor,pending.id(),reassigned.version(),"Owner cancelled visit",correlation);
        assertEquals(InstallationStatus.CANCELLED,cancelled.status());assertNull(cancelled.completedAt());
        assertEquals(cancelled,operations.cancel(actor,pending.id(),reassigned.version(),"Replay",correlation));
        assertFalse(store.propertyHasOpenWork(p.id()));
        assertDoesNotThrow(()->operations.create(actor,p.id(),point.id(),correlation));
        assertEquals(1,store.listAudit("INSTALLATION",pending.id()).stream().filter(a->a.action().equals("CANCEL")).count());
        assertTrue(store.listAudit("INSTALLATION",pending.id()).stream().allMatch(a->a.correlationId().equals(correlation)));
        assertEquals(1,store.listInstallations(new com.smartgn.management.shared.domain.ListWindow(0,10),InstallationStatus.CANCELLED).size());
    }
    @Test void deviceCancellationRequiresConfirmedRevocationAndClosesAssociationDurably() {
        Property p=property();SupplyPoint point=new SupplyPoint(UUID.randomUUID(),p.id(),"DEVICE-CANCEL-POINT","Kitchen",true,0);store.saveSupplyPoint(point,-1);
        Installer installer=new Installer(UUID.randomUUID(),"First","Installer","CANCEL-DEVICE-INSTALLER","123",null,true,0);store.saveInstaller(installer,-1);
        Actor actor=new Actor(UUID.randomUUID(),Role.SUPERADMIN,Plan.FREE);UUID correlation=UUID.randomUUID();Clock clock=Clock.systemUTC();
        InstallationOperations operations=new InstallationOperations(store,transaction,clock);
        Installation pending=operations.create(actor,p.id(),point.id());
        operations.assign(actor,pending.id(),installer.id());Installation ongoing=operations.transition(actor,pending.id(),InstallationStatus.IN_PROGRESS);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var deviceOperations=new com.smartgn.management.devices.application.DeviceOperations(store,transaction,clock,
                new com.smartgn.management.integration.CredentialVault(Base64.getEncoder().encodeToString(new byte[32])),
                org.mockito.Mockito.mock(com.smartgn.management.integration.TelemetryGateway.class),mapper);
        var cancellations=new InstallationOperations(store,transaction,clock,deviceOperations);
        var receipt=deviceOperations.register(actor,ongoing.id(),"CANCEL-DEVICE","Kitchen",clock.instant(),null,correlation);
        Installation withDevice=store.findInstallation(ongoing.id()).orElseThrow();
        assertThrows(DomainException.class,()->cancellations.cancel(actor,ongoing.id(),withDevice.version(),"Stop work",correlation));
        Device revoked=deviceOperations.revoke(actor,receipt.device().id(),correlation);
        assertThrows(DomainException.class,()->cancellations.cancel(actor,ongoing.id(),withDevice.version(),"Stop work",correlation));
        store.saveDevice(com.smartgn.management.devices.application.DeviceOperations.copy(revoked,DeviceStatus.REVOKED,true,false),revoked.version());
        assertEquals(InstallationStatus.CANCELLED,cancellations.cancel(actor,ongoing.id(),withDevice.version(),"Stop work",correlation).status());
        assertNotNull(store.findAssociation(revoked.id(),1).orElseThrow().validTo());
        assertTrue(store.listJobs(new com.smartgn.management.shared.domain.ListWindow(0,100),null,revoked.id(),false).stream().anyMatch(j->j.payload().contains("SYNC_END")));
        assertTrue(store.listJobs(new com.smartgn.management.shared.domain.ListWindow(0,100),null,revoked.id(),false).stream()
                .allMatch(j->mapper.readValue(j.payload(),com.smartgn.management.devices.application.DeviceOperations.ProvisioningJob.class).correlationId().equals(correlation)));
    }
    @Test void tariffHistoryAndScopedBatchesRemainAtomicAndPaginationIsStable() {
        Property p=property(),foreign=property();Instant start=Instant.parse("2026-10-01T00:00:00Z");
        Tariff first=new Tariff(p.accountId(),decimal("2"),start,0);
        Tariff second=new Tariff(p.accountId(),decimal("3"),start.plusSeconds(3600),1);
        store.saveTariff(first,-1);store.saveTariff(second,0);
        assertEquals(2,store.listTariffHistory(p.accountId()).size());
        assertEquals(first.pricePerM3().doubleValue(),store.listTariffsForPeriod(p.accountId(),start,start.plusSeconds(60)).getFirst().pricePerM3().doubleValue());
        assertThrows(DomainException.class,()->store.saveTariff(new Tariff(p.accountId(),decimal("9"),start.plusSeconds(7200),1),0));
        assertEquals(2,store.listTariffHistory(p.accountId()).size());
        for(int i=0;i<3;i++) store.saveSupplyPoint(new SupplyPoint(UUID.randomUUID(),p.id(),"BATCH-PAGE-"+i,"Room",true,0),-1);
        SupplyPoint other=new SupplyPoint(UUID.randomUUID(),foreign.id(),"BATCH-FOREIGN","Room",true,0);store.saveSupplyPoint(other,-1);
        var window=new com.smartgn.management.shared.domain.ListWindow(0,2);
        List<SupplyPoint> page=store.listSupplyPoints(p.id(),window);assertEquals(2,page.size());
        assertEquals(page,store.listSupplyPoints(p.id(),window));
        assertEquals(1,store.listSupplyPoints(p.id(),new com.smartgn.management.shared.domain.ListWindow(2,2)).size());
        assertEquals(3,store.listActivePointScopes(p.accountId(),List.of()).size());
        List<PointScope> scoped=store.findPointsByIds(List.of(page.getFirst().id(),other.id()));assertEquals(2,scoped.size());
        assertTrue(scoped.stream().anyMatch(s->s.accountId().equals(foreign.accountId())));
    }
    private static java.math.BigDecimal decimal(String value) { return new java.math.BigDecimal(value); }
    @Test void migrationRoundTripAndOptimisticVersionPreventLostUpdate() {
        Property p=property(); assertEquals(p,store.findProperty(p.id()).orElseThrow());
        Property next=new Property(p.id(),p.accountId(),"New","Street","HOUSE",true,1); store.saveProperty(next,0);
        assertThrows(DomainException.class,()->store.saveProperty(new Property(p.id(),p.accountId(),"Stale","Street","HOUSE",true,1),0));
        assertEquals("New",store.findProperty(p.id()).orElseThrow().name());
    }
    @Test void transactionRollsBackResourceAndAuditTogether() {
        UUID id=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->transaction.run(()->{
            store.saveProperty(new Property(id,UUID.randomUUID(),"Home","Street","HOUSE",true,0),-1);
            store.audit(new Audit(UUID.randomUUID(),UUID.randomUUID(),"CREATED","PROPERTY",id,UUID.randomUUID(),"details",Instant.now()));
            throw new IllegalStateException("Injected failure");
        }));
        assertTrue(store.findProperty(id).isEmpty()); assertTrue(store.listAudit("PROPERTY",id).isEmpty());
    }
    @Test void databaseRejectsPointBoundToDifferentPropertyThanInstallation() {
        Property p=property(),other=property(); SupplyPoint point=new SupplyPoint(UUID.randomUUID(),p.id(),"METER", "Kitchen",true,0);store.saveSupplyPoint(point,-1);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.saveInstallation(new Installation(UUID.randomUUID(),other.accountId(),other.id(),point.id(),null,InstallationStatus.PENDING,null,Instant.now(),null,0),-1));
    }
    @Test void batchQueriesPreserveAccountScopeEvenWhenAnotherAccountIdsAreSupplied() {
        Property own=property(),foreign=property();
        SupplyPoint ownPoint=new SupplyPoint(UUID.randomUUID(),own.id(),"OWN-METER","Kitchen",true,0),foreignPoint=new SupplyPoint(UUID.randomUUID(),foreign.id(),"FOREIGN-METER","Kitchen",true,0);
        store.saveSupplyPoint(ownPoint,-1);store.saveSupplyPoint(foreignPoint,-1);
        assertEquals(List.of(ownPoint),store.listSupplyPointsByProperties(own.accountId(),List.of(own.id(),foreign.id())));
        Installer installer=new Installer(UUID.randomUUID(),"First","Last","BATCH-INSTALLER","123",null,true,0);store.saveInstaller(installer,-1);
        for(SupplyPoint point:List.of(ownPoint,foreignPoint)) {
            Property property=point==ownPoint?own:foreign;
            Installation installation=new Installation(UUID.randomUUID(),property.accountId(),property.id(),point.id(),installer.id(),InstallationStatus.IN_PROGRESS,null,Instant.now(),null,0);store.saveInstallation(installation,-1);
            store.saveDevice(new Device(UUID.randomUUID(),point==ownPoint?"OWN-DEVICE":"FOREIGN-DEVICE",property.accountId(),property.id(),point.id(),installation.id(),installer.id(),"Kitchen",Instant.now(),DeviceStatus.PENDING,1,false,false,0),-1);
        }
        List<Device> result=store.listDevicesByPoints(own.accountId(),List.of(ownPoint.id(),foreignPoint.id()));
        assertEquals(1,result.size());assertEquals(own.accountId(),result.getFirst().accountId());assertEquals(ownPoint.id(),result.getFirst().pointId());
    }
    @Test void concurrentClaimsLeaseOnlyOneJobPerDeviceAndExpiredTokenCannotAcknowledge() throws Exception {
        Property p=property(); SupplyPoint point=new SupplyPoint(UUID.randomUUID(),p.id(),"METER","Kitchen",true,0);store.saveSupplyPoint(point,-1);
        Installer installer=new Installer(UUID.randomUUID(),"First","Last","ID1","123",null,true,0);store.saveInstaller(installer,-1);
        Installation installation=new Installation(UUID.randomUUID(),p.accountId(),p.id(),point.id(),installer.id(),InstallationStatus.IN_PROGRESS,null,Instant.now(),null,0);store.saveInstallation(installation,-1);
        Device device=new Device(UUID.randomUUID(),"DEVICE1",p.accountId(),p.id(),point.id(),installation.id(),installer.id(),"Kitchen",Instant.now(),DeviceStatus.PENDING,1,false,false,0);store.saveDevice(device,-1);
        Instant now=Instant.now().minusSeconds(1);
        OutboxJob job=new OutboxJob(UUID.randomUUID(),"PROVISION",device.id(),"{}","PENDING",now,now,0,null,null,null);store.enqueue(job);
        store.enqueue(new OutboxJob(UUID.randomUUID(),"SYNC",device.id(),"{}","PENDING",now.plusMillis(1),now,0,null,null,null));
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<List<OutboxJob>> claim=()->{start.await();return store.claimJobs(5,Duration.ofSeconds(30));};
            Future<List<OutboxJob>> first=pool.submit(claim),second=pool.submit(claim);start.countDown();
            List<OutboxJob> all=new ArrayList<>(first.get());all.addAll(second.get());assertEquals(1,all.size());
            OutboxJob leased=all.getFirst();assertTrue(store.ownsJob(leased.id(),leased.claimToken()));assertFalse(store.completeJob(leased.id(),UUID.randomUUID()));
            assertFalse(store.expediteJob(leased.id()),"Manual retry must not steal a live worker lease");
            jdbc.update("UPDATE outbox_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",leased.id());
            assertFalse(store.ownsJob(leased.id(),leased.claimToken()));assertFalse(store.completeJob(leased.id(),leased.claimToken()));
            OutboxJob reclaimed=store.claimJobs(5,Duration.ofSeconds(30)).getFirst();assertNotEquals(leased.claimToken(),reclaimed.claimToken());
            assertTrue(store.completeJob(reclaimed.id(),reclaimed.claimToken()));
            assertFalse(store.expediteJob(reclaimed.id()),"Completed work must never be manually replayed");
            OutboxJob secondJob=store.claimJobs(5,Duration.ofSeconds(30)).getFirst();
            assertTrue(store.retryJob(secondJob.id(),secondJob.claimToken(),Instant.now().plusSeconds(60),"Unavailable"));
            OutboxJob revoke=new OutboxJob(UUID.randomUUID(),"DEVICE_REVOKE",device.id(),"{}","PENDING",Instant.now(),Instant.now().minusSeconds(1),0,null,null,null);
            store.enqueue(revoke);
            assertEquals(revoke.id(),store.claimJobs(5,Duration.ofSeconds(30)).getFirst().id(),"Revocation must bypass older deferred provisioning");
        }
    }
    @Test void qas19OneHundredConcurrentClosurePairsHaveOneEffectiveTransitionAndStableDate() throws Exception {
        Property property=property();
        Installer installer=new Installer(UUID.randomUUID(),"First","Last","INSTALLER-QAS19","123",null,true,0);store.saveInstaller(installer,-1);
        Instant completedAt=Instant.parse("2026-10-04T00:00:00.123456Z");
        Clock clock=Clock.fixed(completedAt.plusNanos(789),ZoneOffset.UTC);
        InstallationOperations operations=new InstallationOperations(store,transaction,clock);
        Actor adminActor=new Actor(UUID.randomUUID(),Role.SUPERADMIN,Plan.PRO);
        try(ExecutorService executor=Executors.newFixedThreadPool(2)) {
            for(int pair=0;pair<100;pair++) {
                SupplyPoint point=new SupplyPoint(UUID.randomUUID(),property.id(),"QAS19-METER-"+pair,"Kitchen",true,0);store.saveSupplyPoint(point,-1);
                Installation installation=new Installation(UUID.randomUUID(),property.accountId(),property.id(),point.id(),installer.id(),InstallationStatus.IN_PROGRESS,null,completedAt.minusSeconds(60),null,0);store.saveInstallation(installation,-1);
                Device device=new Device(UUID.randomUUID(),"QAS19-DEVICE-"+pair,property.accountId(),property.id(),point.id(),installation.id(),installer.id(),"Kitchen",completedAt.minusSeconds(30),DeviceStatus.ACTIVE,1,true,true,0);store.saveDevice(device,-1);
                store.appendAssociation(new Association(device.id(),1,property.accountId(),property.id(),point.id(),completedAt.minusSeconds(30),null,true));
                store.saveInstallation(new Installation(installation.id(),installation.accountId(),installation.propertyId(),installation.pointId(),installer.id(),InstallationStatus.IN_PROGRESS,device.id(),installation.createdAt(),null,1),0);
                CountDownLatch barrier=new CountDownLatch(1);
                Callable<Installation> close=()->{barrier.await();return operations.transition(adminActor,installation.id(),InstallationStatus.COMPLETED);};
                Future<Installation> first=executor.submit(close),second=executor.submit(close);barrier.countDown();
                Installation firstResult=first.get(10,TimeUnit.SECONDS),secondResult=second.get(10,TimeUnit.SECONDS);
                assertEquals(firstResult,secondResult,"Concurrent replays must return the same final state");
                Installation stored=store.findInstallation(installation.id()).orElseThrow();
                assertEquals(InstallationStatus.COMPLETED,stored.status());assertEquals(2,stored.version());assertEquals(device.id(),stored.deviceId());assertEquals(completedAt,stored.completedAt());
                assertEquals(1,store.listAudit("INSTALLATION",installation.id()).stream().filter(a->a.action().equals("TRANSITION_COMPLETED")).count());
                assertEquals(stored,operations.transition(adminActor,installation.id(),InstallationStatus.COMPLETED),"Later replay preserves original completion date and version");
            }
        }
        SupplyPoint point=new SupplyPoint(UUID.randomUUID(),property.id(),"QAS19-INVALID-METER","Kitchen",true,0);store.saveSupplyPoint(point,-1);
        Installation noDevice=new Installation(UUID.randomUUID(),property.accountId(),property.id(),point.id(),installer.id(),InstallationStatus.IN_PROGRESS,null,completedAt.minusSeconds(60),null,0);store.saveInstallation(noDevice,-1);
        assertThrows(DomainException.class,()->operations.transition(adminActor,noDevice.id(),InstallationStatus.COMPLETED));
        assertEquals(InstallationStatus.IN_PROGRESS,store.findInstallation(noDevice.id()).orElseThrow().status());
        Device pending=new Device(UUID.randomUUID(),"QAS19-INVALID-DEVICE",property.accountId(),property.id(),point.id(),noDevice.id(),installer.id(),"Kitchen",completedAt.minusSeconds(30),DeviceStatus.PENDING,1,false,false,0);store.saveDevice(pending,-1);
        store.appendAssociation(new Association(pending.id(),1,property.accountId(),property.id(),point.id(),completedAt.minusSeconds(30),null,false));
        store.saveInstallation(new Installation(noDevice.id(),noDevice.accountId(),noDevice.propertyId(),noDevice.pointId(),installer.id(),InstallationStatus.IN_PROGRESS,pending.id(),noDevice.createdAt(),null,1),0);
        assertThrows(DomainException.class,()->operations.transition(adminActor,noDevice.id(),InstallationStatus.COMPLETED));
        assertEquals(InstallationStatus.IN_PROGRESS,store.findInstallation(noDevice.id()).orElseThrow().status());
        assertEquals(0,store.listAudit("INSTALLATION",noDevice.id()).size());
    }
}

