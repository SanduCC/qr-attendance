-- Teachers are now added by an admin (by email) before they ever sign in, and removed by deactivation so their
-- courses and attendance history are kept.

alter table teacher alter column name drop not null;  -- unknown until the teacher's first sign-in

alter table teacher add column active        boolean      not null default true;
alter table teacher add column added_by      varchar(320);  -- admin email; null for rows created before V2
alter table teacher add column last_login_at timestamptz;
