-- ============================================================================
-- This app's own table: app_user_role.
--
-- Context: this app's login system used to store its own accounts (username +
-- BCrypt password hash + role) in a separate app_user table, specifically to
-- avoid touching the real employee-login table s2user. That decision was
-- reversed: this app now authenticates directly against s2user (a real
-- employee must already exist there to log in at all), and app_user/AppUser
-- were removed.
--
-- s2user has no role/authority column, and this app must never alter a table
-- it doesn't own — so role assignment (and, just as importantly, WHETHER an
-- s2user account may use this app at all) lives here instead, keyed by
-- s2user.id. No row here for a given s2user_id means that real employee has
-- no access to this app, even though their login is otherwise valid.
--
-- This is idempotent application startup DDL — see schema.sql, which runs
-- this same statement via spring.sql.init.mode=always. This file exists
-- separately per this project's convention of keeping a DDL script under
-- /doc/<version> alongside any new/changed Domain class.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_user_role (
    s2user_id BIGINT PRIMARY KEY,
    role VARCHAR(20) NOT NULL,
    date_created DATETIME NOT NULL
);
