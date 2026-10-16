-- #30: the course code is optional (a name is enough to start taking attendance).
alter table course alter column code drop not null;
