-- customer.is_new/is_review_disabled are dropped entirely -- see Customer.java's own comment.
-- Nothing in this app ever read either field back; they were only ever set (to a hardcoded
-- false) on insert because both columns are NOT NULL with no DEFAULT, so something had to supply
-- a value. TicketController#resolveCustomerId no longer sets them and Customer no longer maps
-- them at all as of this change.
--
-- Because both columns are NOT NULL with no DEFAULT, the two steps below cannot collapse into
-- one DROP COLUMN run after the deploy the way a nullable column could: the new jar's
-- Hibernate-generated INSERT for a brand-new customer omits is_new/is_review_disabled entirely
-- (they're gone from the entity), and MySQL in strict mode rejects an INSERT that omits a
-- NOT-NULL, no-DEFAULT column outright ("Field 'is_new' doesn't have a default value") --
-- so the new jar cannot go live against the *old* schema. Running PHASE 1 first removes that
-- constraint ahead of the deploy: once each column has a DEFAULT, omitting it from the INSERT
-- list is fine, and the *old* jar (which still explicitly sets both to false on insert) keeps
-- working unchanged in the meantime, since a DEFAULT doesn't stop a column from also being
-- written explicitly.
--
-- PHASE 1 (the ALTERs below) is safe to run before the deploy -- run it first.

ALTER TABLE crm_dev.customer
    MODIFY COLUMN is_new BIT(1) NOT NULL DEFAULT 0,
    MODIFY COLUMN is_review_disabled BIT(1) NOT NULL DEFAULT 0;

-- Deploy the jar built without is_new/is_review_disabled on Customer here, after PHASE 1 and
-- before PHASE 2.

-- PHASE 2 (the actual drop) must wait until the new jar is the only thing writing to this table --
-- run it only once that deploy is confirmed live, so no in-flight old-jar insert is still relying
-- on these columns existing.

ALTER TABLE crm_dev.customer
    DROP COLUMN is_new,
    DROP COLUMN is_review_disabled;
