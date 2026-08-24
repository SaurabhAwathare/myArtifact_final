package com.saurabh.artifact.security

/**
 * Technical exception thrown when the local database encryption passphrase cannot be
 * decrypted using the device Keystore, but a valid mnemonic recovery wrapper exists.
 *
 * This exception signals that the database is "Locked" and must remain untouched
 * until the user completes the recovery process.
 */
class DatabaseLockedException(message: String) : RuntimeException(message)
