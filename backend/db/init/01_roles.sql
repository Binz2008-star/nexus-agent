-- Least-privilege roles (mirror the production Neon roles).
-- nexus_reader: SELECT only on second_brain (search).
-- nexus_writer: narrow INSERT/SELECT on personal_ai_core (memory + mirror); no DDL, no UPDATE/DELETE.
CREATE ROLE nexus_reader LOGIN PASSWORD 'readerpass';
CREATE ROLE nexus_writer LOGIN PASSWORD 'writerpass';
