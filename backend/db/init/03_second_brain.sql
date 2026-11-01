-- Second Brain (read-only side): chunks_v4 + memory, trigram-ranked substring search.
\connect second_brain

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE chunks_v4 (
    id bigserial PRIMARY KEY,
    project_id text NOT NULL,
    file_path text NOT NULL,
    chunk_type text,
    content text NOT NULL
);
CREATE INDEX chunks_v4_content_trgm_idx ON chunks_v4 USING gin (content gin_trgm_ops);

CREATE TABLE memory (
    id bigserial PRIMARY KEY,
    type text NOT NULL,
    content text NOT NULL
);
CREATE INDEX memory_content_trgm_idx ON memory USING gin (content gin_trgm_ops);

GRANT USAGE ON SCHEMA public TO nexus_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO nexus_reader;
