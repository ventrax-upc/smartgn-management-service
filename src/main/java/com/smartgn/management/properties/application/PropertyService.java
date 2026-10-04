package com.smartgn.management.properties.application;

import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.properties.domain.PropertyRules;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Business orchestration independent of Spring and transport. */
public final class PropertyService {
    private final ManagementStore store;
    private final TransactionRunner transactions;
    public PropertyService(ManagementStore store,TransactionRunner transactions) { this.store=store; this.transactions=transactions; }
    public List<Property> list(Actor actor) { personal(actor); return store.listProperties(actor.accountId()); }
    public List<Property> list(Actor actor,ListWindow window) { personal(actor);return store.listProperties(actor.accountId(),window); }
    public Property get(Actor actor,UUID id) { personal(actor); Property p=property(id); actor.requireOwner(p.accountId()); return p; }
    public Property create(Actor actor,String name,String address,String type) {
        return create(actor,name,address,type,UUID.randomUUID());
    }
    public Property create(Actor actor,String name,String address,String type,UUID correlationId) {
        personal(actor);
        return transactions.run(() -> {
            Property p=new Property(UUID.randomUUID(),actor.accountId(),text(name,150,"name"),text(address,500,"address"),type(type),true,0);
            store.saveProperty(p,-1); audit(actor,"PROPERTY_CREATED","PROPERTY",p.id(),"Property created",correlationId); return p;
        });
    }
    public Property update(Actor actor,UUID id,String name,String address,String type,long version) {
        return update(actor,id,name,address,type,version,UUID.randomUUID());
    }
    public Property update(Actor actor,UUID id,String name,String address,String type,long version,UUID correlationId) {
        personal(actor);
        return transactions.run(() -> {
            Property p=store.lockProperty(id).orElseThrow(()->missing("Property")); actor.requireOwner(p.accountId()); active(p); version(p.version(),version);
            Property updated=new Property(id,p.accountId(),text(name,150,"name"),text(address,500,"address"),type(type),true,version+1);
            store.saveProperty(updated,version); audit(actor,"PROPERTY_UPDATED","PROPERTY",id,"Property details updated",correlationId); return updated;
        });
    }
    public Property deactivate(Actor actor,UUID id,long version) {
        return deactivate(actor,id,version,UUID.randomUUID());
    }
    public Property deactivate(Actor actor,UUID id,long version,UUID correlationId) {
        personal(actor);
        return transactions.run(() -> {
            Property p=store.lockProperty(id).orElseThrow(()->missing("Property")); actor.requireOwner(p.accountId());
            if(!p.active()) return p;
            version(p.version(),version);
            if(store.propertyHasOpenWork(id)) throw new DomainException("CONFLICT","Revoke devices and finish or cancel open installations before deactivating the property");
            Property updated=new Property(id,p.accountId(),p.name(),p.address(),p.propertyType(),false,version+1);
            store.saveProperty(updated,version); audit(actor,"PROPERTY_DEACTIVATED","PROPERTY",id,"Associations and history preserved",correlationId); return updated;
        });
    }
    public SupplyPoint createPoint(Actor actor,UUID propertyId,String serial,String location) {
        return createPoint(actor,propertyId,serial,location,UUID.randomUUID());
    }
    public SupplyPoint createPoint(Actor actor,UUID propertyId,String serial,String location,UUID correlationId) {
        personal(actor);
        return transactions.run(() -> {
            Property p=store.lockProperty(propertyId).orElseThrow(()->missing("Property")); actor.requireOwner(p.accountId()); active(p);
            SupplyPoint point=new SupplyPoint(UUID.randomUUID(),propertyId,text(serial,100,"serialNumber"),text(location,200,"locationName"),true,0);
            store.saveSupplyPoint(point,-1); audit(actor,"SUPPLY_POINT_CREATED","SUPPLY_POINT",point.id(),"Supply point created",correlationId); return point;
        });
    }
    public List<SupplyPoint> listPoints(Actor actor,UUID propertyId) { get(actor,propertyId); return store.listSupplyPoints(propertyId); }
    public List<SupplyPoint> listPoints(Actor actor,UUID propertyId,ListWindow window) { get(actor,propertyId);return store.listSupplyPoints(propertyId,window); }
    public SupplyPoint getPoint(Actor actor,UUID id) { SupplyPoint point=point(id); get(actor,point.propertyId()); return point; }
    public SupplyPoint updatePoint(Actor actor,UUID id,String location,long version) {
        return updatePoint(actor,id,location,version,UUID.randomUUID());
    }
    public SupplyPoint updatePoint(Actor actor,UUID id,String location,long version,UUID correlationId) {
        personal(actor);
        return transactions.run(() -> {
            SupplyPoint previous=point(id);
            Property p=store.lockProperty(previous.propertyId()).orElseThrow(()->missing("Property")); actor.requireOwner(p.accountId()); active(p);
            SupplyPoint point=store.lockSupplyPoint(id).orElseThrow(()->missing("Supply point")); version(point.version(),version);
            SupplyPoint updated=new SupplyPoint(id,point.propertyId(),point.serialNumber(),text(location,200,"locationName"),point.active(),version+1);
            store.saveSupplyPoint(updated,version); audit(actor,"SUPPLY_POINT_UPDATED","SUPPLY_POINT",id,"Supply point location updated",correlationId); return updated;
        });
    }
    private Property property(UUID id) { return store.findProperty(id).orElseThrow(()->missing("Property")); }
    private SupplyPoint point(UUID id) { return store.findSupplyPoint(id).orElseThrow(()->missing("Supply point")); }
    private static DomainException missing(String resource) { return new DomainException("NOT_FOUND",resource+" not found"); }
    private static void personal(Actor actor) { if(actor.role()==Role.SUPERADMIN) throw new DomainException("FORBIDDEN","Personal property operations require a property owner or administrator"); }
    private static void active(Property property) { PropertyRules.requireActive(property); }
    private static void version(long current,long requested) { PropertyRules.requireVersion(current,requested); }
    private static String type(String input) { return PropertyRules.propertyType(input); }
    private static String text(String value,int max,String field) { return PropertyRules.text(value,max,field); }
    private void audit(Actor actor,String action,String resource,UUID id,String details,UUID correlationId) { store.audit(new Audit(UUID.randomUUID(),actor.accountId(),action,resource,id,correlationId,details,Instant.now())); }
}
