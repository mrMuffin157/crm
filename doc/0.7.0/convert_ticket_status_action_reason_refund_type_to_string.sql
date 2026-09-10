-- ticket.status/action/reason/refund_type (and their Envers shadow, ticket_aud) move from the
-- legacy numeric id (legacy-app's GORM `identity` enum type, mirrored by this app's own JPA
-- converters) to the corresponding TicketStatus/TicketAction/TicketReason/RefundType enum's own
-- name() string -- see TicketStatusConverter/TicketActionConverter/TicketReasonConverter/
-- RefundTypeConverter for the app-side half of this change, which still accepts the old numeric
-- id on read via LegacyTicketCode, so a row this script hasn't reached yet in a given
-- environment keeps working.
--
-- Run the two phases below with the deploy in between, not as one block after it:
--
-- PHASE 1 (the ALTERs) is safe to run before the deploy, and should be -- it only widens int
-- columns to VARCHAR, which the *old*, still-running Integer-typed converter reads and writes
-- exactly as before (every value is still a plain digit string at this point, e.g. 3 -> '3',
-- and MySQL round-trips a digit-only VARCHAR through getInt/setInt without issue). The new
-- converter, once deployed, writes the enum's name() unconditionally with no numeric fallback on
-- write -- if the column were still INT at that moment, its first write of a non-numeric string
-- (e.g. "Closed") would fail outright. Running PHASE 1 first removes that window entirely: the
-- column already accepts arbitrary strings by the time the new jar's first write happens.
--
-- Deploy the jar built with the new converters here, after PHASE 1 and before PHASE 2.
--
-- PHASE 2 (the UPDATEs) must wait until the new jar is the only thing reading/writing this
-- column -- LegacyTicketCode's numeric fallback is what makes it safe to run at any point after
-- that, since a row PHASE 2 hasn't reached yet still reads correctly. Each UPDATE maps the
-- now-VARCHAR digit strings to the enum name; any value already a name (a fresh row written
-- after deploy but before PHASE 2 runs) or otherwise unmapped simply falls through the CASE's
-- ELSE unchanged, so PHASE 2 is safe to re-run.
--
-- reason additionally carries one confirmed value outside its enum -- crm_dev.ticket id 230
-- holds reason = 0 (see TicketReasonConverter's own comment) -- which has no name to map to and
-- is deliberately left as the literal string '0'; the converter's existing unmappable-value
-- tolerance now checks a numeric string instead of a numeric column and still reads it as null.

-- ===== PHASE 1: run before deploying the jar built with the new converters =====

ALTER TABLE crm_dev.ticket
    MODIFY COLUMN status VARCHAR(20) NOT NULL,
    MODIFY COLUMN action VARCHAR(20) NULL,
    MODIFY COLUMN reason VARCHAR(30) NOT NULL,
    MODIFY COLUMN refund_type VARCHAR(20) NULL;

ALTER TABLE crm_dev.ticket_aud
    MODIFY COLUMN status VARCHAR(20) NULL,
    MODIFY COLUMN action VARCHAR(20) NULL,
    MODIFY COLUMN reason VARCHAR(30) NULL,
    MODIFY COLUMN refund_type VARCHAR(20) NULL;

-- ===== Deploy the jar built with TicketStatusConverter/TicketActionConverter/ =====
-- ===== TicketReasonConverter/RefundTypeConverter now, before PHASE 2.        =====

-- ===== PHASE 2: run only once the new jar is live =====

UPDATE crm_dev.ticket
SET status = CASE status
    WHEN '1' THEN 'New'
    WHEN '2' THEN 'Processing'
    WHEN '3' THEN 'Closed'
    WHEN '-1' THEN 'Cancelled'
    WHEN '-2' THEN 'ProcessFailed'
    ELSE status
END;

UPDATE crm_dev.ticket
SET action = CASE action
    WHEN '0' THEN 'NoAction'
    WHEN '1' THEN 'Replacement'
    WHEN '2' THEN 'Refund'
    WHEN '3' THEN 'ManualHandling'
    ELSE action
END
WHERE action IS NOT NULL;

UPDATE crm_dev.ticket
SET reason = CASE reason
    WHEN '1' THEN 'Defective'
    WHEN '2' THEN 'MissingParts'
    WHEN '3' THEN 'QualityUnacceptable'
    WHEN '4' THEN 'NotAsDescribed'
    WHEN '5' THEN 'PartNotCompatible'
    WHEN '6' THEN 'NotCompatible'
    WHEN '7' THEN 'ShippingDamage'
    WHEN '8' THEN 'UnwantedItem'
    WHEN '9' THEN 'MissedEstimatedDelivery'
    ELSE reason
END;

UPDATE crm_dev.ticket
SET refund_type = CASE refund_type
    WHEN '1' THEN 'CustomerReturn'
    WHEN '2' THEN 'GeneralAdjustment'
    ELSE refund_type
END
WHERE refund_type IS NOT NULL;

-- Same conversion for the Envers audit shadow table (already widened above in PHASE 1;
-- schema.sql's ticket_aud definition was widened in lockstep for a brand-new environment, so
-- this UPDATE is what handles one where ticket_aud already exists from the 0.6.0 rollout).

UPDATE crm_dev.ticket_aud
SET status = CASE status
    WHEN '1' THEN 'New'
    WHEN '2' THEN 'Processing'
    WHEN '3' THEN 'Closed'
    WHEN '-1' THEN 'Cancelled'
    WHEN '-2' THEN 'ProcessFailed'
    ELSE status
END
WHERE status IS NOT NULL;

UPDATE crm_dev.ticket_aud
SET action = CASE action
    WHEN '0' THEN 'NoAction'
    WHEN '1' THEN 'Replacement'
    WHEN '2' THEN 'Refund'
    WHEN '3' THEN 'ManualHandling'
    ELSE action
END
WHERE action IS NOT NULL;

UPDATE crm_dev.ticket_aud
SET reason = CASE reason
    WHEN '1' THEN 'Defective'
    WHEN '2' THEN 'MissingParts'
    WHEN '3' THEN 'QualityUnacceptable'
    WHEN '4' THEN 'NotAsDescribed'
    WHEN '5' THEN 'PartNotCompatible'
    WHEN '6' THEN 'NotCompatible'
    WHEN '7' THEN 'ShippingDamage'
    WHEN '8' THEN 'UnwantedItem'
    WHEN '9' THEN 'MissedEstimatedDelivery'
    ELSE reason
END
WHERE reason IS NOT NULL;

UPDATE crm_dev.ticket_aud
SET refund_type = CASE refund_type
    WHEN '1' THEN 'CustomerReturn'
    WHEN '2' THEN 'GeneralAdjustment'
    ELSE refund_type
END
WHERE refund_type IS NOT NULL;
