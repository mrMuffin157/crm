-- SecurityConfig has always used a plain BCryptPasswordEncoder (no DelegatingPasswordEncoder
-- wrapper), so it can't verify a password value that carries a literal "{bcrypt}" prefix. 22 of
-- the 24 rows in crm_dev.s2user were found with that prefix baked into the stored hash (68 chars:
-- 8-char "{bcrypt}" + 60-char bcrypt hash), blocking those accounts from logging in. The fix only
-- strips the literal prefix — the underlying bcrypt hash itself, and therefore the real password,
-- is untouched.
--
-- Run manually against crm_dev (and crm_test/prod if they carry the same legacy data).

-- Sanity check before running — expect 22 rows on crm_dev as of 2026-08-25.
USE crm_dev;
SELECT id, username, LENGTH(password) AS len
FROM s2user
WHERE password LIKE '{bcrypt}%' AND LENGTH(password) = 68;

UPDATE s2user
SET password = SUBSTRING(password, 9)
WHERE password LIKE '{bcrypt}%' AND LENGTH(password) = 68;

-- Verify — expect 0 rows left with the prefix.
SELECT COUNT(*) AS still_prefixed
FROM s2user
WHERE password LIKE '{bcrypt}%';
