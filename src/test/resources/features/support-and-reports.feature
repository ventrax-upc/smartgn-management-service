Feature: Safe support records and authorized consumption reports
  Management preserves technical history and separates directory verification from login.

  Scenario: Only manually verified and enabled gas technicians appear in the directory
    Given a newly registered unverified gas technician
    Then the gas technician is absent from the contact directory
    When a SuperAdmin verifies the gas technician with a manual reference and enables it
    Then the gas technician appears in the contact directory

  Scenario: A maintenance correction preserves its original version
    Given recorded maintenance for the authenticated account
    When the account corrects the maintenance description using its current version
    Then the maintenance audit contains the original and corrected descriptions

  Scenario: A foreign account cannot record maintenance on another account's property
    Given a maintenance property owned by a different account
    When the authenticated account attempts to record maintenance on that property
    Then support rejects the operation with code "FORBIDDEN" without writing maintenance

  Scenario: A Free Administrator cannot build a Pro report
    Given an Administrator account on the Free plan
    When the account requests a consumption report
    Then reporting rejects the operation with code "FORBIDDEN" before querying Telemetry

  Scenario: Missing telemetry does not become a zero consumption estimate
    Given an Administrator Pro account with one authorized point and no Telemetry readings
    When the account requests a consumption report
    Then the consumption report has no fabricated volume or cost and marks incomplete totals
