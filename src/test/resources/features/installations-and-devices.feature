@application-model
Feature: Installation and device operations traced to SmartGN US32 through US37
  These scenarios use application services with transactional in-memory fixtures.
  Live broker ACL, session revocation latency and PostgreSQL contention are separate integration gates.

  Scenario: US32 and US34 create and assign an installation
    Given installation fixtures with active owned resources and an enabled installer
    When SuperAdmin creates a request and assigns the installer
    Then the request progresses from PENDING to ASSIGNED
    And assigning a disabled installer is rejected

  Scenario: US35 publication is gated by both external confirmations
    Given an IN_PROGRESS installation
    When SuperAdmin registers its physical device
    Then an encrypted individual credential and association outbox are persisted
    When Telemetry association confirmation fails in the worker fixture
    Then the worker does not activate publication and retains retryable work
    When Telemetry confirms the exact association and the worker retries
    Then activation occurs after association acknowledgement and broker activation

  Scenario: US36 concurrent and repeated close
    Given an IN_PROGRESS installation with its exact ACTIVE confirmed device
    When 100 pairs of requests attempt to close it concurrently
    Then at most one effective transition to COMPLETED occurs
    And all repeated responses preserve the original completion date

  Scenario: US36 missing synchronization cannot close an installation
    Given broker provisioning is confirmed but Telemetry synchronization is pending
    When SuperAdmin requests completion
    Then completion is rejected and its original state is preserved

  Scenario: US37 external revocation requires broker confirmation
    Given an IN_PROGRESS installation with its exact ACTIVE confirmed device
    When SuperAdmin requests revocation and the broker fixture fails
    Then revocation is unconfirmed and durable retry work remains
    When the broker fixture confirms revocation and the worker retries
    Then the credential is revoked and broker confirmation is persisted

  Scenario: US37 preserve unresolved historical readings on reassociation
    Given the original installation is complete and broker revocation is confirmed
    And the previous association has unconfirmed buffered readings
    When SuperAdmin attempts reassociation without a gap resolution
    Then the association does not change
    When SuperAdmin explicitly records an administrative gap resolution
    Then the old version and gap audit are preserved before the new version activates
