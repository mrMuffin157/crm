-- ============================================================================
-- This app's own table: app_user_channel (Ticket Data Authority).
--
-- Which ticket channels (source_system codes — see TicketEnums.SOURCE_SYSTEM_CHANNELS) a MEMBER
-- may see or create tickets for. A row here is an explicit per-channel grant, composite-keyed on
-- (s2user_id, source_system) since a member can be granted more than one channel. An admin's
-- access is never stored here: ChannelAccessService treats ROLE_ADMIN as implicitly authorized
-- for every channel, so this table is only ever written to for MEMBER accounts.
--
-- No rows for a given s2user_id means that member currently has NO channel access at all, not
-- "every channel" — unlike app_user_role's access-allowlist default, there is no implicit grant
-- here. This is a deliberate, breaking-on-purpose default for existing members too: every member
-- account starts with zero grants until an admin explicitly assigns at least one channel via the
-- Members admin page, at which point they can see/create tickets for that channel only.
--
-- This is idempotent application startup DDL — see schema.sql, which runs this same statement via
-- spring.sql.init.mode=always. This file exists separately per this project's convention of
-- keeping a DDL script under /doc/<version> alongside any new/changed Domain class.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_user_channel (
    s2user_id BIGINT NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    PRIMARY KEY (s2user_id, source_system)
);
