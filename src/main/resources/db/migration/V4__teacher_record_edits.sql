-- #10: teachers can add an attendance entry by hand (dead phone, no account). #5 (teachers edit student names) needs
-- no schema change: it updates student.first_name / last_name.
--
-- Expand only, so the previous release keeps working while this one rolls out: columns are relaxed or added with a
-- default, nothing is dropped or renamed. Rows the previous release writes (real scans) satisfy every new constraint.

-- A manual entry has no device and no IP: it was not scanned. A real scan still always has both (ck_record_source).
alter table attendance_record alter column device_id_hash drop not null;
alter table attendance_record alter column ip_hash drop not null;
-- A manual entry for someone without an account has no student row; the name is kept on the record instead.
alter table attendance_record alter column student_id drop not null;

alter table attendance_record add column manual boolean not null default false;
-- The teacher who added the entry (audit). Teachers are deactivated, not deleted, so this normally stays set.
alter table attendance_record add column added_by_teacher_id bigint references teacher (id) on delete set null;
-- Only for manual entries without a student row; same limits as student.first_name / last_name.
alter table attendance_record add column manual_first_name varchar(100);
alter table attendance_record add column manual_last_name varchar(100);

-- A real scan keeps every anti-cheat field; a manual entry has none of the device/IP data.
alter table attendance_record add constraint ck_record_source check (
    (not manual and student_id is not null and device_id_hash is not null and ip_hash is not null)
    or (manual and device_id_hash is null and ip_hash is null));
-- Every record names someone: a student row, or (manual entries only) a free-text name, never both.
alter table attendance_record add constraint ck_record_person check (
    (student_id is not null and manual_first_name is null and manual_last_name is null)
    or (student_id is null and manual and manual_first_name is not null and manual_last_name is not null));

-- uq_record_session_student and uq_record_session_device keep working: Postgres treats NULLs as distinct in a
-- unique constraint, so manual entries (NULL device hash, possibly NULL student) never collide with each other or
-- with real scans, while two real scans still can't share a student or a device.
-- The same free-text name can't be added twice to one session (the "already checked in" of entries without a student).
create unique index uq_record_session_manual_name
    on attendance_record (session_id, lower(manual_last_name), lower(manual_first_name))
    where student_id is null;
