package com.smartgn.management.properties.application;

import com.smartgn.management.shared.application.port.out.*;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PropertyServiceTest {
    private ManagementStore store;
    private PropertyService service;
    private Actor owner;
    @BeforeEach void setup() {
        store=mock(ManagementStore.class);
        TransactionRunner runner=new TransactionRunner() { public <T>T run(Supplier<T> work) { return work.get(); } };
        service=new PropertyService(store,runner);
        owner=new Actor(UUID.randomUUID(),Role.PROPIETARIO,Plan.FREE);
    }
    @Test void creationTakesOwnershipFromActorAndPersistsAudit() {
        Property p=service.create(owner," Home "," Street 123 ","HOUSE");
        assertEquals(owner.accountId(),p.accountId()); assertEquals("Home",p.name()); assertTrue(p.active());
        verify(store).saveProperty(p,-1); verify(store).audit(any(Audit.class));
    }
    @Test void deniesCrossAccountReadsAndDoesNotSave() {
        UUID id=UUID.randomUUID(); when(store.findProperty(id)).thenReturn(Optional.of(new Property(id,UUID.randomUUID(),"Other","Street","HOUSE",true,0)));
        DomainException error=assertThrows(DomainException.class,()->service.get(owner,id));
        assertEquals("FORBIDDEN",error.code()); verify(store,never()).saveProperty(any(),anyLong());
    }
    @Test void inactivePropertyCannotAcceptNewSupplyPoint() {
        UUID id=UUID.randomUUID(); when(store.lockProperty(id)).thenReturn(Optional.of(new Property(id,owner.accountId(),"Home","Street","HOUSE",false,0)));
        assertThrows(DomainException.class,()->service.createPoint(owner,id,"METER1","Kitchen"));
        verify(store,never()).saveSupplyPoint(any(),anyLong());
    }
    @Test void staleUpdateDoesNotOverwriteCurrentResource() {
        UUID id=UUID.randomUUID(); when(store.lockProperty(id)).thenReturn(Optional.of(new Property(id,owner.accountId(),"Home","Street","HOUSE",true,2)));
        DomainException error=assertThrows(DomainException.class,()->service.update(owner,id,"New","Street","HOUSE",1));
        assertEquals("CONFLICT",error.code()); verify(store,never()).saveProperty(any(),anyLong());
    }
    @Test void deactivationPreservesPropertyAndHasSingleEffectiveTransition() {
        UUID id=UUID.randomUUID(); Property p=new Property(id,owner.accountId(),"Home","Street","HOUSE",true,2);
        when(store.lockProperty(id)).thenReturn(Optional.of(p)); when(store.listDevices()).thenReturn(List.of()); when(store.listInstallations()).thenReturn(List.of());
        Property result=service.deactivate(owner,id,2); assertFalse(result.active()); assertEquals(3,result.version());
        assertEquals(p.name(),result.name()); verify(store).saveProperty(result,2);
        when(store.lockProperty(id)).thenReturn(Optional.of(result)); assertEquals(result,service.deactivate(owner,id,2));
        verify(store,times(1)).saveProperty(any(),anyLong());
    }
    @Test void administratorCanCreateOwnPropertyButSuperadminCannotUsePersonalApi() {
        assertNotNull(service.create(new Actor(UUID.randomUUID(),Role.ADMINISTRADOR,Plan.FREE),"Building","Street","BUILDING"));
        assertThrows(DomainException.class,()->service.create(new Actor(UUID.randomUUID(),Role.SUPERADMIN,Plan.PRO),"Building","Street","BUILDING"));
    }
    @Test void unknownPropertyTypeIsRejectedBeforeWrite() {
        assertThrows(DomainException.class,()->service.create(owner,"Home","Street","WAREHOUSE"));
        verify(store,never()).saveProperty(any(),anyLong());
    }
    @Test void propertyCannotDeactivateWhileBrokerRevocationIsUnconfirmed() {
        UUID id=UUID.randomUUID();
        when(store.lockProperty(id)).thenReturn(Optional.of(new Property(id,owner.accountId(),"Home","Street","HOUSE",true,0)));
        Device pendingRevocation=new Device(UUID.randomUUID(),"DEVICE",owner.accountId(),id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Kitchen",java.time.Instant.now(),DeviceStatus.REVOKED,1,false,true,3);
        when(store.propertyHasOpenWork(id)).thenReturn(true); when(store.listInstallations()).thenReturn(List.of());
        assertEquals("CONFLICT",assertThrows(DomainException.class,()->service.deactivate(owner,id,0)).code());
        verify(store,never()).saveProperty(any(),anyLong());
    }
}
