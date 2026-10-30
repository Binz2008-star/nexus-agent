package com.nexus.agent.data.remote

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.DriverManager

/**
 * Diagnostic (always-pass) test that reveals exactly how pgjdbc behaves on Android.
 * The result is written to logcat under the `NeonDiag` tag so it can be read from the
 * CI instrumentation-test log. This is not a pass/fail test — it exists to surface the
 * real Android-specific failure (LinkageError / ClassNotFound / SQLException) without a
 * physical device's logcat.
 */
@RunWith(AndroidJUnit4::class)
class NeonJdbcDiagnosticTest {

    @Test
    fun diagnosePgjdbcOnAndroid() {
        val log = StringBuilder()

        try {
            Class.forName("org.postgresql.Driver")
            log.append("DRIVER-OK;")
        } catch (t: Throwable) {
            log.append("DRIVER-FAIL [${t.javaClass.name}]: ${t.message};")
        }

        try {
            val c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:1/neondb?sslmode=disable&connectTimeout=2&socketTimeout=2"
            )
            c.close()
            log.append("CONNECT-OK;")
        } catch (t: Throwable) {
            log.append("CONNECT-FAIL [${t.javaClass.name}]: ${t.message};")
        }

        Log.i("NeonDiag", "pgjdbc-on-android: $log")
        assertTrue(true)
    }
}
