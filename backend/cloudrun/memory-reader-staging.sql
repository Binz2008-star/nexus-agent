-- STAGING PREPARATION ONLY. NOT EXECUTED BY THE APPLICATION OR CI.
-- Before using this file, create a dedicated LOGIN role named nexus_memory_reader
-- on the disposable Neon personal-ai-core staging branch. Generate a unique password
-- via the Neon console and store it only in Secret Manager. Do not share the write URL.
--
-- Run only after owner approval for live staging database role changes.
-- A newly created nonowner role should not inherit other grants. Verify memberships
-- and role-level grants before proceeding.
GRANT USAGE ON SCHEMA public TO nexus_memory_reader;
GRANT SELECT ON TABLE public.nexus_memory TO nexus_memory_reader;

-- Post-grant verification (no personal memory content is read):
SELECT
    has_schema_privilege('nexus_memory_reader', 'public', 'USAGE') AS schema_usage,
    has_table_privilege('nexus_memory_reader', 'public.nexus_memory', 'SELECT') AS memory_select,
    has_table_privilege('nexus_memory_reader', 'public.nexus_memory', 'INSERT') AS memory_insert,
    has_table_privilege('nexus_memory_reader', 'public.nexus_memory', 'UPDATE') AS memory_update,
    has_table_privilege('nexus_memory_reader', 'public.nexus_memory', 'DELETE') AS memory_delete,
    has_table_privilege('nexus_memory_reader', 'public.nexus_conversations', 'SELECT') AS transcript_select;
-- Expected: true, true, false, false, false, false.
-- Do NOT point NEON_MEMORY_READ_URL to a role if any negative condition is true.
