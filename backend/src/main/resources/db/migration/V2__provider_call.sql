-- Price provider requests per UTC day, so a restart does not reset the daily allowance.
create table provider_call (day date primary key, calls int not null);
