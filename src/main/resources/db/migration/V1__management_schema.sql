CREATE TABLE properties (
 id uuid PRIMARY KEY, account_id uuid NOT NULL, name varchar(150) NOT NULL,
 address varchar(500) NOT NULL, property_type varchar(30) NOT NULL,
 active boolean NOT NULL, version bigint NOT NULL CHECK (version >= 0),
 UNIQUE (id, account_id)
);
CREATE INDEX properties_account_idx ON properties(account_id);
CREATE TABLE supply_points (
 id uuid PRIMARY KEY, property_id uuid NOT NULL REFERENCES properties(id),
 serial_number varchar(100) NOT NULL UNIQUE, location_name varchar(200) NOT NULL,
 active boolean NOT NULL, version bigint NOT NULL CHECK (version >= 0),
 UNIQUE (id, property_id)
);
CREATE INDEX supply_points_property_idx ON supply_points(property_id);
CREATE TABLE technicians (
 id uuid PRIMARY KEY, first_name varchar(100) NOT NULL, last_name varchar(100) NOT NULL,
 identification varchar(50) NOT NULL UNIQUE, specialty varchar(200) NOT NULL,
 accreditation varchar(200) NOT NULL, phone varchar(50) NOT NULL, email varchar(254),
 enabled boolean NOT NULL, verified boolean NOT NULL,
 verification_reference varchar(500), verified_by uuid, verified_at timestamptz,
 version bigint NOT NULL CHECK (version >= 0),
 CHECK (NOT verified OR (verification_reference IS NOT NULL AND verified_by IS NOT NULL AND verified_at IS NOT NULL)),
 CHECK (NOT enabled OR verified)
);
CREATE TABLE installers (
 id uuid PRIMARY KEY, first_name varchar(100) NOT NULL, last_name varchar(100) NOT NULL,
 identification varchar(50) NOT NULL UNIQUE, phone varchar(50) NOT NULL, email varchar(254),
 enabled boolean NOT NULL, version bigint NOT NULL CHECK (version >= 0)
);
CREATE TABLE maintenance (
 id uuid PRIMARY KEY, account_id uuid NOT NULL, property_id uuid NOT NULL,
 point_id uuid, technician_id uuid REFERENCES technicians(id),
 performed_at timestamptz NOT NULL, description varchar(4000) NOT NULL,
 responsible_id uuid NOT NULL, correlation_id uuid NOT NULL,
 version bigint NOT NULL CHECK (version >= 0),
 FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id),
 FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id)
);
CREATE INDEX maintenance_account_idx ON maintenance(account_id, performed_at);
CREATE TABLE tariffs (
 account_id uuid PRIMARY KEY, price_per_m3 numeric(14,6) NOT NULL CHECK (price_per_m3 > 0),
 effective_from timestamptz NOT NULL, version bigint NOT NULL CHECK (version >= 0)
);
CREATE TABLE installations (
 id uuid PRIMARY KEY, account_id uuid NOT NULL, property_id uuid NOT NULL,
 point_id uuid NOT NULL, installer_id uuid REFERENCES installers(id),
 status varchar(30) NOT NULL CHECK (status IN ('PENDING','ASSIGNED','IN_PROGRESS','COMPLETED')),
 device_id uuid, created_at timestamptz NOT NULL, completed_at timestamptz,
 version bigint NOT NULL CHECK (version >= 0),
 FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id),
 FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id),
 CHECK (status = 'PENDING' OR installer_id IS NOT NULL),
 CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
 CHECK (status <> 'COMPLETED' OR device_id IS NOT NULL)
);
CREATE UNIQUE INDEX installation_open_point_idx ON installations(point_id) WHERE status <> 'COMPLETED';
CREATE TABLE devices (
 id uuid PRIMARY KEY, serial_number varchar(160) NOT NULL UNIQUE,
 account_id uuid NOT NULL, property_id uuid NOT NULL, point_id uuid NOT NULL,
 installation_id uuid NOT NULL REFERENCES installations(id), installer_id uuid NOT NULL REFERENCES installers(id),
 location varchar(500) NOT NULL, installed_at timestamptz NOT NULL,
 status varchar(20) NOT NULL CHECK (status IN ('PENDING','ACTIVE','REVOKED','FAILED')),
 association_version bigint NOT NULL CHECK (association_version > 0),
 broker_confirmed boolean NOT NULL, telemetry_confirmed boolean NOT NULL,
 version bigint NOT NULL CHECK (version >= 0),
 FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id),
 FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id),
 CHECK (status <> 'ACTIVE' OR (broker_confirmed AND telemetry_confirmed))
);
CREATE UNIQUE INDEX device_active_point_idx ON devices(point_id) WHERE status <> 'REVOKED';
ALTER TABLE installations ADD CONSTRAINT installation_device_fk FOREIGN KEY (device_id) REFERENCES devices(id);
CREATE TABLE associations (
 device_id uuid NOT NULL REFERENCES devices(id), version bigint NOT NULL CHECK (version > 0),
 account_id uuid NOT NULL, property_id uuid NOT NULL, point_id uuid NOT NULL,
 valid_from timestamptz NOT NULL, valid_to timestamptz, confirmed boolean NOT NULL,
 PRIMARY KEY(device_id, version),
 FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id),
 FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id),
 CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE UNIQUE INDEX association_current_idx ON associations(device_id) WHERE valid_to IS NULL;
CREATE TABLE credentials (
 id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES devices(id),
 protected_value text NOT NULL, state varchar(30) NOT NULL CHECK(state IN ('PENDING','ACTIVE','REVOKED')),
 credential_version bigint NOT NULL CHECK (credential_version > 0),
 created_at timestamptz NOT NULL, revoked_at timestamptz,
 UNIQUE(device_id, credential_version)
);
CREATE UNIQUE INDEX credential_current_idx ON credentials(device_id) WHERE state IN ('PENDING','ACTIVE');
CREATE TABLE outbox_jobs (
 id uuid PRIMARY KEY, type varchar(50) NOT NULL, aggregate_id uuid NOT NULL,
 payload text NOT NULL, status varchar(20) NOT NULL CHECK(status IN ('PENDING','PROCESSING','DONE')),
 created_at timestamptz NOT NULL, available_at timestamptz NOT NULL,
 attempts integer NOT NULL CHECK(attempts >= 0), claim_token uuid, lease_until timestamptz, last_error varchar(1000)
);
CREATE INDEX outbox_pending_idx ON outbox_jobs(available_at,created_at) WHERE status <> 'DONE';
CREATE INDEX outbox_aggregate_idx ON outbox_jobs(aggregate_id,created_at);
CREATE TABLE audit (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL, action varchar(100) NOT NULL,
 resource_type varchar(100) NOT NULL, resource_id uuid NOT NULL,
 correlation_id uuid NOT NULL, details text NOT NULL, occurred_at timestamptz NOT NULL
);
CREATE INDEX audit_resource_idx ON audit(resource_type,resource_id,occurred_at);
