-- Local demo credentials only. Never reuse these in a public deployment.
CREATE ROLE ledgerline_ai_readonly LOGIN PASSWORD 'ledgerline_readonly';
GRANT CONNECT ON DATABASE ledgerline_java TO ledgerline_ai_readonly;
GRANT USAGE ON SCHEMA public TO ledgerline_ai_readonly;
-- Tables will be created by persistence-sync, connected as ledgerline.
ALTER DEFAULT PRIVILEGES FOR ROLE ledgerline IN SCHEMA public
  GRANT SELECT ON TABLES TO ledgerline_ai_readonly;
