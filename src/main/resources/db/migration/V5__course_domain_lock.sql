-- #4 (anti-cheat rule 7): optional Google Workspace domain lock per course. Comma-separated lowercase domains that
-- the student's Google ID token "hd" claim must match (e.g. 'university.ro,student.university.ro'); null = anyone.
-- Expand-only (nullable, no default), so the previous release keeps working while this one rolls out.
alter table course add column allowed_domains varchar(500);
