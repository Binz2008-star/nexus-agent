package com.nexus.agent.data.remote

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * JDBC seam behind [com.nexus.agent.data.repository.NeonRepository]. Extracted as an interface so
 * the repository's logic (validation, merging, error mapping) is unit-testable with a fake, while
 * the real implementation does blocking JDBC I/O that is always called from `Dispatchers.IO`.
 */
interface NeonJdbc {
    /** Runs [sql] with [params] and maps each result row (as a positional `List<Any?>`) via [rowMapper]. */
    fun <T> query(url: String, sql: String, params: List<Any?>, rowMapper: (List<Any?>) -> T): List<T>

    /** Runs a mutating [sql] and returns the affected row count. */
    fun execute(url: String, sql: String, params: List<Any?>): Int
}

/** Real pgjdbc-backed implementation. The driver is registered once, lazily. */
class NeonJdbcClient : NeonJdbc {

    override fun <T> query(
        url: String,
        sql: String,
        params: List<Any?>,
        rowMapper: (List<Any?>) -> T
    ): List<T> {
        val connection = open(url)
        try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, params)
                statement.executeQuery().use { rs ->
                    val columnCount = rs.metaData.columnCount
                    val rows = ArrayList<T>()
                    while (rs.next()) {
                        val row = (1..columnCount).map { rs.getObject(it) }
                        rows.add(rowMapper(row))
                    }
                    return rows
                }
            }
        } finally {
            connection.close()
        }
    }

    override fun execute(url: String, sql: String, params: List<Any?>): Int {
        val connection = open(url)
        try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, params)
                return statement.executeUpdate()
            }
        } finally {
            connection.close()
        }
    }

    private fun open(url: String): Connection {
        ensureRegistered()
        return DriverManager.getConnection(ensureTimeouts(url))
    }

    private fun bind(statement: PreparedStatement, params: List<Any?>) {
        params.forEachIndexed { index, param -> statement.setObject(index + 1, param) }
    }

    companion object {
        @Volatile
        private var registered = false

        private fun ensureRegistered() {
            if (registered) return
            synchronized(this) {
                if (!registered) {
                    // Explicit registration: Android's ServiceLoader does not always auto-load
                    // the pgjdbc driver from META-INF/services.
                    Class.forName("org.postgresql.Driver")
                    registered = true
                }
            }
        }

        /** Appends connect/socket timeouts so a dead host can't hang the request. */
        private fun ensureTimeouts(url: String): String {
            var result = url
            if (!result.contains("connectTimeout")) {
                result += if (result.contains("?")) "&connectTimeout=15" else "?connectTimeout=15"
            }
            if (!result.contains("socketTimeout")) {
                result += "&socketTimeout=30"
            }
            return result
        }
    }
}
