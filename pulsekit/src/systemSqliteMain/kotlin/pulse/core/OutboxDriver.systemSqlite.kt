package pulse.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver

internal actual fun openOutboxDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    storageDir: String,
    name: String,
): SqlDriver = NativeSqliteDriver(
    schema = schema,
    name = name,
    onConfiguration = { config ->
        config.copy(extendedConfig = config.extendedConfig.copy(basePath = storageDir))
    },
)
