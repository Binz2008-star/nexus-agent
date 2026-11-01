-- Realistic, deterministic fixtures (Arabic + English + code), used by the parity tests.

\connect second_brain
INSERT INTO chunks_v4 (project_id, file_path, chunk_type, content) VALUES
  ('projA', 'notes/arabic.md', 'text', 'المستخدم يفضل اللغة العربية في المحادثة اليومية'),
  ('projA', 'notes/tone.md',   'text', 'The user prefers a calm and friendly tone'),
  ('projA', 'notes/color.md',  'text', 'The user favorite color is blue'),
  ('projB', 'src/greet.py',    'code', 'def greet(name): return f"hello {name}"'),
  ('projB', 'src/notes.md',    'text', 'Release checklist for the deployment pipeline');

INSERT INTO memory (type, content) VALUES
  ('fact', 'The user lives in Dubai'),
  ('pref', 'The user prefers Arabic for daily chat');

\connect personal_ai_core
INSERT INTO nexus_memory (type, content, tags) VALUES
  ('fact', 'The user favorite color is blue', '{pref,color}'),
  ('fact', 'The user lives in Dubai', '{location}');

INSERT INTO nexus_conversations (session_id, role, content) VALUES
  ('sess-1', 'user',      'hello'),
  ('sess-1', 'assistant', 'hi there, how can I help?');
