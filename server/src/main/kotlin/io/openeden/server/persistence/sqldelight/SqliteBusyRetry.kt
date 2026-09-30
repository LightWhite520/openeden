package io.openeden.server.persistence.sqldelight

import kotlinx.coroutines.delay
import java.sql.SQLException

/** Retry an entire rolled-back transaction; never suspend with a SQLite transaction open. */
internal suspend fun <T> retrySqliteBusy(transaction: () -> T): T {
    var retries = 0
    while (true) {
        try {
            return transaction()
        } catch (failure: SQLException) {
            // Include extended SQLITE_BUSY codes, such as SQLITE_BUSY_SNAPSHOT.
            if ((failure.errorCode and 0xff) != 5 || retries == 5) throw failure
            delay(25L shl retries++)
        }
    }
}
