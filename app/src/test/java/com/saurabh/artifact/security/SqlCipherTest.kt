package com.saurabh.artifact.security

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Test

class SqlCipherTest {
    @Test
    fun testChangePassphrase() {
        // This is just to check if the method exists in the classpath
        val db: SQLiteDatabase? = null
        // db?.changePassphrase(byteArrayOf(1, 2, 3))
    }
}
