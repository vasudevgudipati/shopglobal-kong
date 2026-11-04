-- Run ONCE as SYSTEM (SQL Developer or sqlplus) on Oracle 11g XE, service XE.
-- One schema per environment, so UAT test data and PROD data never mix.
-- The app logs in as its schema user and creates its own tables, sequence and seed data at startup.
-- Change the passwords below and use the same values in the Kubernetes Secrets (see k8s/*.yaml).

CREATE USER SHOPGLOBAL_UAT IDENTIFIED BY "Shop2026Uat"
  DEFAULT TABLESPACE USERS
  QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO SHOPGLOBAL_UAT;

CREATE USER SHOPGLOBAL_PROD IDENTIFIED BY "Shop2026Prod"
  DEFAULT TABLESPACE USERS
  QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO SHOPGLOBAL_PROD;

-- To start completely fresh later:
-- DROP USER SHOPGLOBAL_UAT CASCADE;
-- DROP USER SHOPGLOBAL_PROD CASCADE;
