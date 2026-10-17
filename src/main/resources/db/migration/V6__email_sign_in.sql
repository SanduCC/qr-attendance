-- #52: students without a Google account sign in with a one-time code sent to their email address. They are
-- stored with provider 'EMAIL' and provider_subject = the normalized address (trimmed, lowercased). Identity stays
-- (provider, provider_subject): a Google student and an email student with the same address are two accounts.
-- Expand-only: the constraint only gains a value, so the previous release (which never writes 'EMAIL') keeps working
-- while this one rolls out. 'EMAIL' fits the existing varchar(10); subjects fit varchar(255) (addresses are capped
-- at 254 characters by the app).
alter table student
    drop constraint ck_student_provider,
    add constraint ck_student_provider check (provider in ('GOOGLE', 'APPLE', 'EMAIL'));
