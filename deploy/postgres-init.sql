-- Two roles: the owner runs migrations; the app role is subject to row-level security.
CREATE ROLE erp_owner LOGIN PASSWORD 'erp_owner';
CREATE ROLE erp_app LOGIN PASSWORD 'erp_app';
CREATE DATABASE erp OWNER erp_owner;
