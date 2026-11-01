-- Personal AI core (write side): nexus_memory + nexus_conversations.
\connect personal_ai_core

CREATE TABLE nexus_memory (
    id bigserial PRIMARY KEY,
    type text NOT NULL,
    content text NOT NULL,
    tags text[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nexus_conversations (
    id bigserial PRIMARY KEY,
    session_id text NOT NULL,
    role text NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA public TO nexus_writer;
GRANT SELECT, INSERT ON nexus_memory TO nexus_writer;
GRANT INSERT ON nexus_conversations TO nexus_writer;
