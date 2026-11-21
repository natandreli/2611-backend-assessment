-- Database users of the services' managed identities, with least privilege (SOLUTION_DESIGN.md, section 1).
-- Run once per environment by a member of the SQL admin group, after the schema migration.
-- Bicep cannot create database users; Azure SQL is reachable only through its private endpoint,
-- so this runs from inside the VNet (see infra/README.md).
-- Replace <env> with dev, test or prod.

CREATE USER [id-ppn-<env>-intake] FROM EXTERNAL PROVIDER;
CREATE USER [id-ppn-<env>-writer] FROM EXTERNAL PROVIDER;

-- Intake: records every notification, looks up event IDs, marks a notification as failed.
GRANT SELECT, INSERT, UPDATE ON dbo.notification TO [id-ppn-<env>-intake];

-- Writer: owns the payment state and updates the processing status of notifications.
GRANT SELECT, INSERT, UPDATE ON dbo.payment TO [id-ppn-<env>-writer];
GRANT SELECT, UPDATE ON dbo.notification TO [id-ppn-<env>-writer];

-- Neither identity can delete rows or change the schema. Retention cleanup and migrations run
-- with their own identities.
