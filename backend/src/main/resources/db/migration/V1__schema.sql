create table account (id text primary key, name text not null, broker text not null); -- e.g. 'xtb:IKE', 'xtb:My Trades', 'bonds'
create table instrument (ticker text primary key, name text not null, category text not null,
  price_symbol text, currency text);
create table operation (account_id text not null references account, external_id text not null,
  type text not null, ticker text references instrument, occurred_at timestamptz not null,
  amount_pln numeric(14,2) not null, position_id text, volume numeric(18,6), price numeric(18,6),
  comment text, primary key (account_id, external_id));
create table position_snapshot (account_id text not null references account, ticker text not null,
  volume numeric(18,6) not null, value_pln numeric(14,2) not null, reported_at timestamptz not null,
  primary key (account_id, ticker));
create table edo_series (code text primary key, first_year_rate numeric(6,3) not null, margin numeric(6,3) not null);
create table cpi_announcement (month date primary key, pct numeric(6,3) not null); -- first day of the announcement month
create table bond_purchase (id bigserial primary key, account_id text not null references account,
  series_code text not null references edo_series, purchase_date date not null, quantity int not null check (quantity > 0));
create table price_history (ticker text not null, day date not null, close numeric(18,6) not null,
  currency text not null, primary key (ticker, day));
create table fx_rate (currency text not null, day date not null, mid numeric(12,6) not null, primary key (currency, day));
