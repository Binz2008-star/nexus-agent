-- Two databases, matching the production split:
--   second_brain    -> read URL  (Second Brain chunks_v4 + memory)
--   personal_ai_core -> write URL (nexus_memory + nexus_conversations)
CREATE DATABASE second_brain;
CREATE DATABASE personal_ai_core;
