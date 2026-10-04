package com.smartgn.management.adapter.out.persistence;

import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.ListWindow;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit PostgreSQL tables; record mapping is confined to this persistence adapter. */
@Repository
public class JdbcManagementStore implements ManagementStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcManagementStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }
    private static String column(String name) { return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    private static Object sql(Object value) {
        if (value instanceof Instant instant) return Timestamp.from(instant);
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        return value;
    }
    private static <T> RowMapper<T> mapper(Class<T> type) {
        return (row, index) -> {
            RecordComponent[] parts = type.getRecordComponents();
            Object[] values = new Object[parts.length];
            Class<?>[] types = new Class<?>[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                String name = column(parts[i].getName());
                Object value = row.getObject(name);
                if (types[i] == Instant.class) { Timestamp timestamp = row.getTimestamp(name); value = timestamp == null ? null : timestamp.toInstant(); }
                else if (value != null && types[i] == long.class) value = ((Number)value).longValue();
                else if (value != null && types[i] == int.class) value = ((Number)value).intValue();
                else if (value != null && types[i].isEnum()) value = enumValue(types[i], value.toString());
                values[i] = value;
            }
            try { return type.getDeclaredConstructor(types).newInstance(values); }
            catch (ReflectiveOperationException error) { throw new SQLException("Cannot map " + type.getSimpleName(), error); }
        };
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String value) { return Enum.valueOf((Class)type, value); }
    private <T> Optional<T> find(String table, Class<T> type, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM " + table + " WHERE " + (type == Tariff.class ? "account_id" : "id") + " = ?" + (lock ? " FOR UPDATE" : ""), mapper(type), id).stream().findFirst();
    }
    private <T> List<T> list(String table, Class<T> type, String condition, Object... args) {
        return jdbc.query("SELECT * FROM " + table + (condition.isEmpty() ? "" : " WHERE " + condition), mapper(type), args);
    }
    private void save(String table, Object record, long expectedVersion) {
        LinkedHashMap<String, Object> values = values(record);
        String key = record instanceof Tariff ? "account_id" : "id";
        long newVersion = ((Number)values.get("version")).longValue();
        if ((expectedVersion == -1 && newVersion != 0) || (expectedVersion >= 0 && newVersion != expectedVersion + 1))
            throw new DomainException("CONFLICT", "Invalid resource version");
        if (expectedVersion == -1) { insert(table, values); return; }
        Object id = values.remove(key);
        String assignments = values.keySet().stream().map(c -> c + " = ?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(values.values()); args.add(id); args.add(expectedVersion);
        int changed = jdbc.update("UPDATE " + table + " SET " + assignments + " WHERE " + key + " = ? AND version = ?", args.toArray());
        if (changed != 1) throw new DomainException("CONFLICT", "The resource was modified concurrently; reload it and retry");
    }
    private static LinkedHashMap<String, Object> values(Object record) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        try { for (RecordComponent part : record.getClass().getRecordComponents()) values.put(column(part.getName()), sql(part.getAccessor().invoke(record))); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot persist record", error); }
        return values;
    }
    private void insert(String table, LinkedHashMap<String, Object> values) {
        jdbc.update("INSERT INTO " + table + " (" + String.join(",",values.keySet()) + ") VALUES (" + String.join(",",Collections.nCopies(values.size(),"?")) + ")", values.values().toArray());
    }
    public Optional<Property> findProperty(UUID id) { return find("properties", Property.class,id,false); }
    public Optional<Property> lockProperty(UUID id) { return find("properties", Property.class,id,true); }
    public void saveProperty(Property value,long expected) { save("properties",value,expected); }
    public List<Property> listProperties(UUID account) { return list("properties",Property.class,"account_id = ? ORDER BY id",account); }
    public List<Property> listProperties(UUID account,ListWindow w) { return list("properties",Property.class,"account_id=? ORDER BY id LIMIT ? OFFSET ?",account,w.limit(),w.offset()); }
    public List<Property> findPropertiesByIds(List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        return list("properties",Property.class,"id IN ("+placeholders(ids.size())+") ORDER BY id",ids.toArray());
    }
    public Optional<SupplyPoint> findSupplyPoint(UUID id) { return find("supply_points",SupplyPoint.class,id,false); }
    public Optional<SupplyPoint> lockSupplyPoint(UUID id) { return find("supply_points",SupplyPoint.class,id,true); }
    public void saveSupplyPoint(SupplyPoint value,long expected) { save("supply_points",value,expected); }
    public List<SupplyPoint> listSupplyPoints(UUID property) { return list("supply_points",SupplyPoint.class,"property_id = ? ORDER BY id",property); }
    public List<SupplyPoint> listSupplyPoints(UUID property,ListWindow w) { return list("supply_points",SupplyPoint.class,"property_id=? ORDER BY id LIMIT ? OFFSET ?",property,w.limit(),w.offset()); }
    private static String placeholders(int count) { return String.join(",",Collections.nCopies(count,"?")); }
    private List<PointScope> pointScopes(String condition,Object... args) {
        return jdbc.query("SELECT s.*,p.account_id,p.version AS property_version FROM supply_points s JOIN properties p ON p.id=s.property_id WHERE "+condition,
                (r,i)->new PointScope(mapper(SupplyPoint.class).mapRow(r,i),r.getObject("account_id",UUID.class),r.getLong("property_version")),args);
    }
    public List<PointScope> findPointsByIds(List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        return pointScopes("s.id IN ("+placeholders(ids.size())+") ORDER BY s.id",ids.toArray());
    }
    public List<PointScope> listActivePointScopes(UUID account,List<UUID> properties) {
        List<Object> args=new ArrayList<>();args.add(account);args.addAll(properties);
        return pointScopes("p.account_id=? AND p.active AND s.active"+(properties.isEmpty()?"":" AND p.id IN ("+placeholders(properties.size())+")")+" ORDER BY s.id LIMIT 101",args.toArray());
    }
    public boolean propertyHasOpenWork(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE property_id=? AND (status<>'REVOKED' OR NOT broker_confirmed)) OR EXISTS(SELECT 1 FROM installations WHERE property_id=? AND status NOT IN ('COMPLETED','CANCELLED'))",Boolean.class,id,id));
    }
    public List<SupplyPoint> listSupplyPointsByProperties(UUID account,List<UUID> properties) {
        if(properties.isEmpty()) return List.of();
        List<Object> args=new ArrayList<>();args.add(account);args.addAll(properties);
        return jdbc.query("SELECT s.* FROM supply_points s JOIN properties p ON p.id=s.property_id WHERE p.account_id=? AND s.property_id IN ("+String.join(",",Collections.nCopies(properties.size(),"?"))+") ORDER BY s.id",mapper(SupplyPoint.class),args.toArray());
    }
    public Optional<Technician> findTechnician(UUID id) { return find("technicians",Technician.class,id,false); }
    public void saveTechnician(Technician value,long expected) { save("technicians",value,expected); }
    public List<Technician> listTechnicians() { return list("technicians",Technician.class,"true ORDER BY id"); }
    public List<Technician> listTechnicians(ListWindow w,boolean directory) { return list("technicians",Technician.class,(directory?"enabled AND verified":"true")+" ORDER BY id LIMIT ? OFFSET ?",w.limit(),w.offset()); }
    public Optional<Installer> findInstaller(UUID id) { return find("installers",Installer.class,id,false); }
    public void saveInstaller(Installer value,long expected) { save("installers",value,expected); }
    public List<Installer> listInstallers() { return list("installers",Installer.class,"true ORDER BY id"); }
    public List<Installer> listInstallers(ListWindow w) { return list("installers",Installer.class,"true ORDER BY id LIMIT ? OFFSET ?",w.limit(),w.offset()); }
    public Optional<Maintenance> findMaintenance(UUID id) { return find("maintenance",Maintenance.class,id,false); }
    public void saveMaintenance(Maintenance value,long expected) { save("maintenance",value,expected); }
    public List<Maintenance> listMaintenance(UUID account) { return list("maintenance",Maintenance.class,"account_id = ? ORDER BY performed_at DESC",account); }
    public List<Maintenance> listMaintenance(UUID account,ListWindow w) { return list("maintenance",Maintenance.class,"account_id=? ORDER BY performed_at DESC,id LIMIT ? OFFSET ?",account,w.limit(),w.offset()); }
    public Optional<Tariff> findTariff(UUID account) { return find("tariffs",Tariff.class,account,false); }
    public void saveTariff(Tariff value,long expected) {
        transaction.executeWithoutResult(status -> {
            save("tariffs",value,expected);
            insert("tariff_history",values(value));
        });
    }
    public List<Tariff> listTariffHistory(UUID account) { return list("tariff_history",Tariff.class,"account_id=? ORDER BY effective_from,version",account); }
    public List<Tariff> listTariffHistory(UUID account,ListWindow w) { return list("tariff_history",Tariff.class,"account_id=? ORDER BY effective_from DESC,version DESC LIMIT ? OFFSET ?",account,w.limit(),w.offset()); }
    public List<Tariff> listTariffsForPeriod(UUID account,Instant from,Instant to) {
        return list("tariff_history",Tariff.class,"account_id=? AND effective_from<? AND (effective_from>=? OR effective_from=(SELECT MAX(effective_from) FROM tariff_history WHERE account_id=? AND effective_from<=?)) ORDER BY effective_from",account,Timestamp.from(to),Timestamp.from(from),account,Timestamp.from(from));
    }
    public Optional<Installation> findInstallation(UUID id) { return find("installations",Installation.class,id,false); }
    public Optional<Installation> lockInstallation(UUID id) { return find("installations",Installation.class,id,true); }
    public void saveInstallation(Installation value,long expected) { save("installations",value,expected); }
    public List<Installation> listInstallations() { return list("installations",Installation.class,"true ORDER BY created_at DESC"); }
    public List<Installation> listInstallations(ListWindow w,InstallationStatus status) {
        return status==null?list("installations",Installation.class,"true ORDER BY created_at DESC,id LIMIT ? OFFSET ?",w.limit(),w.offset())
                :list("installations",Installation.class,"status=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",status.name(),w.limit(),w.offset());
    }
    public Optional<Device> findDevice(UUID id) { return find("devices",Device.class,id,false); }
    public Optional<Device> lockDevice(UUID id) { return find("devices",Device.class,id,true); }
    public Optional<Device> findDeviceByPoint(UUID point) { return list("devices",Device.class,"point_id = ? AND status <> 'REVOKED'",point).stream().findFirst(); }
    public void saveDevice(Device value,long expected) { save("devices",value,expected); }
    public List<Device> listDevices() { return list("devices",Device.class,"true ORDER BY id"); }
    public List<Device> listDevices(ListWindow w,DeviceStatus status) {
        return status==null?list("devices",Device.class,"true ORDER BY id LIMIT ? OFFSET ?",w.limit(),w.offset())
                :list("devices",Device.class,"status=? ORDER BY id LIMIT ? OFFSET ?",status.name(),w.limit(),w.offset());
    }
    public List<Device> listDevicesByPoints(UUID account,List<UUID> points) {
        if(points.isEmpty()) return List.of();
        List<Object> args=new ArrayList<>();args.add(account);args.addAll(points);
        return list("devices",Device.class,"account_id=? AND point_id IN ("+String.join(",",Collections.nCopies(points.size(),"?"))+") AND status <> 'REVOKED' ORDER BY id",args.toArray());
    }
    public void appendAssociation(Association value) { insert("associations",values(value)); }
    public Optional<Association> findAssociation(UUID device,long version) { return list("associations",Association.class,"device_id = ? AND version = ?",device,version).stream().findFirst(); }
    public List<Association> listAssociations(UUID device) { return list("associations",Association.class,"device_id = ? ORDER BY version",device); }
    public void confirmAssociation(UUID device,long version) {
        if (jdbc.update("UPDATE associations SET confirmed=true WHERE device_id=? AND version=?",device,version)!=1) throw new DomainException("NOT_FOUND","Association not found");
    }
    public void endAssociation(UUID device,long version,Instant at) {
        if (jdbc.update("UPDATE associations SET valid_to=? WHERE device_id=? AND version=? AND valid_to IS NULL",Timestamp.from(at),device,version)!=1) throw new DomainException("CONFLICT","Association already ended or missing");
    }
    public void saveCredential(Credential value) {
        LinkedHashMap<String,Object> fields = values(value);
        String updates = fields.keySet().stream().filter(c -> !c.equals("id")).map(c -> c+"=EXCLUDED."+c).collect(Collectors.joining(","));
        jdbc.update("INSERT INTO credentials ("+String.join(",",fields.keySet())+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+") ON CONFLICT(id) DO UPDATE SET "+updates,fields.values().toArray());
    }
    public Optional<Credential> findCredential(UUID id) { return find("credentials",Credential.class,id,false); }
    public Optional<Credential> findActiveCredential(UUID device) { return list("credentials",Credential.class,"device_id = ? AND state = 'ACTIVE' ORDER BY credential_version DESC",device).stream().findFirst(); }
    public List<Credential> listCredentials(UUID device) { return list("credentials",Credential.class,"device_id = ? ORDER BY credential_version",device); }
    public void enqueue(OutboxJob job) { insert("outbox_jobs",values(job)); }
    public List<OutboxJob> claimJobs(int limit,Duration lease) {
        if (limit<1 || limit>100 || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("Invalid outbox claim bounds");
        return transaction.execute(status -> {
            // An aggregate row lock serializes claimers before inspecting leases, including expired workers.
            List<UUID> aggregates = jdbc.query("SELECT d.id FROM devices d JOIN LATERAL (SELECT CASE WHEN j.type='DEVICE_REVOKE' THEN 0 ELSE 1 END priority,j.created_at,j.available_at FROM outbox_jobs j WHERE j.aggregate_id=d.id AND j.status<>'DONE' ORDER BY CASE WHEN j.type='DEVICE_REVOKE' THEN 0 ELSE 1 END,j.created_at,j.id LIMIT 1) ready ON true WHERE ready.available_at<=clock_timestamp() AND NOT EXISTS (SELECT 1 FROM outbox_jobs busy WHERE busy.aggregate_id=d.id AND busy.status='PROCESSING' AND busy.lease_until>clock_timestamp()) ORDER BY ready.priority,ready.created_at,d.id LIMIT ? FOR UPDATE OF d SKIP LOCKED",(r,i)->r.getObject(1,UUID.class),limit);
            List<OutboxJob> claimed = new ArrayList<>();
            for(UUID aggregate:aggregates) {
                boolean busy = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM outbox_jobs WHERE aggregate_id=? AND status='PROCESSING' AND lease_until>clock_timestamp())",Boolean.class,aggregate));
                if(busy) continue;
                List<OutboxJob> oldest = list("outbox_jobs",OutboxJob.class,"aggregate_id=? AND status<>'DONE' ORDER BY CASE WHEN type='DEVICE_REVOKE' THEN 0 ELSE 1 END,created_at,id LIMIT 1",aggregate);
                if(oldest.isEmpty()) continue;
                OutboxJob job=oldest.getFirst();
                UUID token=UUID.randomUUID();
                int changed=jdbc.update("UPDATE outbox_jobs SET status='PROCESSING',claim_token=?,lease_until=clock_timestamp()+(? * interval '1 millisecond'),attempts=attempts+1 WHERE id=? AND available_at<=clock_timestamp() AND (status='PENDING' OR lease_until<=clock_timestamp())",token,lease.toMillis(),job.id());
                if(changed==1) claimed.add(find("outbox_jobs",OutboxJob.class,job.id(),false).orElseThrow());
            }
            return claimed;
        });
    }
    public boolean ownsJob(UUID id,UUID token) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM outbox_jobs WHERE id=? AND status='PROCESSING' AND claim_token=? AND lease_until>clock_timestamp())",Boolean.class,id,token)); }
    public boolean completeJob(UUID id,UUID token) { return jdbc.update("UPDATE outbox_jobs SET status='DONE',claim_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND status='PROCESSING' AND claim_token=? AND lease_until>clock_timestamp()",id,token)==1; }
    public boolean retryJob(UUID id,UUID token,Instant at,String error) { return jdbc.update("UPDATE outbox_jobs SET status='PENDING',claim_token=NULL,lease_until=NULL,available_at=?,last_error=? WHERE id=? AND status='PROCESSING' AND claim_token=? AND lease_until>clock_timestamp()",Timestamp.from(at),error==null?null:error.substring(0,Math.min(1000,error.length())),id,token)==1; }
    public void audit(Audit value) { insert("audit",values(value)); }
    public Optional<OutboxJob> findJob(UUID id) { return find("outbox_jobs",OutboxJob.class,id,false); }
    public List<OutboxJob> listJobs(ListWindow w,String status,UUID deviceId,boolean attention) {
        StringBuilder condition=new StringBuilder("true");List<Object> args=new ArrayList<>();
        if(status!=null) {condition.append(" AND status=?");args.add(status);}
        if(deviceId!=null) {condition.append(" AND aggregate_id=?");args.add(deviceId);}
        if(attention) condition.append(" AND status<>'DONE' AND (attempts>=10 OR (status='PROCESSING' AND lease_until<=clock_timestamp()))");
        condition.append(" ORDER BY created_at DESC,id LIMIT ? OFFSET ?");args.add(w.limit());args.add(w.offset());
        return list("outbox_jobs",OutboxJob.class,condition.toString(),args.toArray());
    }
    public boolean expediteJob(UUID id) {
        return jdbc.update("UPDATE outbox_jobs SET status='PENDING',available_at=clock_timestamp(),claim_token=NULL,lease_until=NULL WHERE id=? AND (status='PENDING' OR (status='PROCESSING' AND lease_until<=clock_timestamp()))",id)==1;
    }
    public List<Audit> listAudit(String type,UUID id) { return list("audit",Audit.class,"resource_type=? AND resource_id=? ORDER BY occurred_at",type,id); }
}
