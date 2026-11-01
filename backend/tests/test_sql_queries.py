import pytest

from sql_queries import (
    INSERT_CONVERSATION_SQL,
    INSERT_MEMORY_SQL,
    SEARCH_CHUNKS_SQL,
    SEARCH_MEMORY_SQL,
    error_code,
    ilike_pattern,
    tags_literal,
)


def test_ilike_pattern_wraps_with_percent():
    assert ilike_pattern("cat") == "%cat%"


def test_ilike_pattern_escapes_wildcards_and_backslash():
    assert ilike_pattern("100%_x\\y") == "%100\\%\\_x\\\\y%"


def test_tags_literal_renders_postgres_array():
    assert tags_literal(["a", "b c"]) == '{"a","b c"}'


def test_tags_literal_escapes_quotes():
    assert tags_literal(['he said "hi"']) == '{"he said ""hi"""}'


def test_tags_literal_empty():
    assert tags_literal([]) == "{}"


def test_error_code_auth():
    assert error_code(Exception("password authentication failed")) == "auth"
    assert error_code(Exception("FATAL: 28P01 something")) == "auth"


def test_error_code_not_found():
    assert error_code(Exception("relation does not exist")) == "not_found"


def test_error_code_network():
    assert error_code(Exception("connection refused")) == "network"


def test_error_code_default():
    assert error_code(Exception("other")) == "server"


def test_sql_strings_are_parameterized():
    # No f-string / %-format interpolation of user values: placeholders are the DB driver's %s.
    assert "%s" in SEARCH_CHUNKS_SQL
    assert "%s" in INSERT_MEMORY_SQL
    assert "%s" in INSERT_CONVERSATION_SQL
    assert SEARCH_MEMORY_SQL.count("%s") == 3


def test_parity_with_android_second_brain_sql():
    # These exact values are also asserted in app/src/test/.../SecondBrainSqlTest.kt. Keep them in
    # sync: the backend and the Android JDBC path must render byte-identical SQL inputs, or the
    # HTTPS migration would silently change search/memory behavior.
    assert ilike_pattern("hello") == "%hello%"
    assert ilike_pattern("100%") == "%100\\%%"
    assert ilike_pattern("a_b") == "%a\\_b%"
    assert tags_literal([]) == "{}"
    assert tags_literal(["lang"]) == '{"lang"}'
    assert tags_literal(["a", "b c"]) == '{"a","b c"}'

