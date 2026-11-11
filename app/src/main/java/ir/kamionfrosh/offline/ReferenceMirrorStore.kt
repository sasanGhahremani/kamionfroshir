package ir.kamionfrosh.offline

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class ReferenceMirrorStore(
    context: Context,
    accountId: String
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName(accountId),
    null,
    1
) {
    companion object {
        private val tableOrder = listOf(
            "AdvGroup",
            "AdvShowPlaceType",
            "AdvTypeOfSalesType",
            "AdvTypeOfSalesStage",
            "AdvWorkedOrNewGoodsType",
            "AdvState",
            "AdvListEventBehaviorOption"
        )

        private val allowedTables = tableOrder.toSet()

        private val allowedTypes = setOf(
            "INTEGER",
            "REAL",
            "TEXT"
        )

        private fun databaseName(accountId: String): String {
            require(accountId.matches(Regex("[1-9][0-9]*"))) {
                "Invalid account ID"
            }

            return "offline_reference_$accountId.db"
        }

        private fun quotedIdentifier(value: String): String {
            require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                "Invalid SQL identifier"
            }

            return "\"$value\""
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS OfflineProfile (
                Id INTEGER PRIMARY KEY,
                DisplayName TEXT NOT NULL,
                SnapshotId TEXT NOT NULL,
                SyncedAtUtc TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // فعلاً نسخه ۱ است.
        // مهاجرت‌های بعدی نباید پیش‌نویس‌های کاربر را پاک کنند.
    }

    /**
     * این متد باید خارج از Thread رابط کاربری اجرا شود.
     *
     * snapshot باید فقط از پاسخ HTTPS معتبر سایت و حساب تأییدشده
     * به این متد تحویل داده شود، نه از ورودی دلخواه JavaScript.
     */
    @Synchronized
    fun applySnapshot(
        snapshot: JSONObject,
        expectedAccountId: String,
        onProgress: (completed: Int, total: Int) -> Unit
    ) {
        require(snapshot.optBoolean("success")) {
            "Snapshot was not successful"
        }

        require(snapshot.getInt("protocolVersion") == 1) {
            "Unsupported snapshot protocol"
        }

        val profile = snapshot.getJSONObject("profile")

        require(profile.getString("id") == expectedAccountId) {
            "Account mismatch"
        }

        val snapshotId = snapshot.getString("snapshotId")
        val generatedAt = snapshot.getString("generatedAtUtc")
        val tables = snapshot.getJSONArray("tables")

        val tableMap = linkedMapOf<String, JSONObject>()

        for (index in 0 until tables.length()) {
            val table = tables.getJSONObject(index)
            val name = table.getString("name")

            require(name in allowedTables) {
                "Unexpected reference table"
            }

            require(tableMap.put(name, table) == null) {
                "Duplicate table in snapshot"
            }
        }

        require(tableMap.keys == allowedTables) {
            "Incomplete reference snapshot"
        }

        var totalRows = 0

        for (name in tableOrder) {
            totalRows += tableMap.getValue(name)
                .getJSONArray("rows")
                .length()
        }

        var completedRows = 0
        val db = writableDatabase

        db.beginTransaction()

        try {
            for (name in tableOrder) {
                val table = tableMap.getValue(name)
                val columns = table.getJSONArray("columns")
                val rows = table.getJSONArray("rows")

                validateColumns(columns)

                // جدول موقت در همان Transaction ساخته می‌شود.
                // در صورت خطا نسخه قبلی دست‌نخورده برمی‌گردد.
                val stagingName = "${name}_incoming"

                db.execSQL(
                    "DROP TABLE IF EXISTS " +
                        quotedIdentifier(stagingName)
                )

                db.execSQL(createTableSql(stagingName, columns))

                for (rowIndex in 0 until rows.length()) {
                    val row = rows.getJSONObject(rowIndex)
                    val values = ContentValues()

                    for (columnIndex in 0 until columns.length()) {
                        val column = columns.getJSONObject(columnIndex)

                        putValue(
                            values = values,
                            row = row,
                            columnName = column.getString("name"),
                            sqliteType = column.getString("sqliteType")
                        )
                    }

                    // عمداً REPLACE نیست:
                    // شناسه تکراری در پاسخ سرور باید خطا بدهد.
                    db.insertOrThrow(stagingName, null, values)

                    completedRows++

                    if (completedRows % 100 == 0 ||
                        completedRows == totalRows) {
                        onProgress(completedRows, totalRows)
                    }
                }

                val actualCount = DatabaseUtils.longForQuery(
                    db,
                    "SELECT COUNT(*) FROM " +
                        quotedIdentifier(stagingName),
                    null
                )

                require(actualCount == rows.length().toLong()) {
                    "Row count mismatch"
                }

                // فقط جداول مرجع این دیتابیس جایگزین می‌شوند.
                db.execSQL(
                    "DROP TABLE IF EXISTS " +
                        quotedIdentifier(name)
                )

                db.execSQL(
                    "ALTER TABLE " +
                        quotedIdentifier(stagingName) +
                        " RENAME TO " +
                        quotedIdentifier(name)
                )
            }

            val profileValues = ContentValues().apply {
                put("Id", expectedAccountId.toLong())
                put("DisplayName", profile.optString("name", ""))
                put("SnapshotId", snapshotId)
                put("SyncedAtUtc", generatedAt)
            }

            db.insertWithOnConflict(
                "OfflineProfile",
                null,
                profileValues,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // تکمیل ذخیره فقط بعد از Commit گزارش می‌شود.
    }

    fun profileName(): String? {
        readableDatabase.rawQuery(
            "SELECT DisplayName FROM OfflineProfile LIMIT 1",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }

    private fun validateColumns(columns: JSONArray) {
        require(columns.length() > 0) {
            "Missing columns"
        }

        val names = mutableSetOf<String>()
        var primaryKeyCount = 0

        for (index in 0 until columns.length()) {
            val column = columns.getJSONObject(index)
            val name = column.getString("name")
            val type = column.getString("sqliteType")

            quotedIdentifier(name)

            require(names.add(name)) {
                "Duplicate column"
            }

            require(type in allowedTypes) {
                "Unsupported SQLite type"
            }

            if (column.getBoolean("primaryKey")) {
                require(name == "Id" && type == "INTEGER") {
                    "Unexpected primary key"
                }

                primaryKeyCount++
            }
        }

        require(primaryKeyCount == 1) {
            "Exactly one Id primary key is required"
        }
    }

    private fun createTableSql(
        tableName: String,
        columns: JSONArray
    ): String {
        val definitions = mutableListOf<String>()

        for (index in 0 until columns.length()) {
            val column = columns.getJSONObject(index)

            val name = quotedIdentifier(column.getString("name"))
            val type = column.getString("sqliteType")
            val isPrimary = column.getBoolean("primaryKey")
            val isNullable = column.getBoolean("nullable")

            val definition = buildString {
                append(name)
                append(" ")
                append(type)

                if (isPrimary) {
                    append(" PRIMARY KEY")
                }

                if (!isNullable || isPrimary) {
                    append(" NOT NULL")
                }
            }

            definitions.add(definition)
        }

        return "CREATE TABLE " +
            quotedIdentifier(tableName) +
            " (" +
            definitions.joinToString(", ") +
            ")"
    }

    private fun putValue(
        values: ContentValues,
        row: JSONObject,
        columnName: String,
        sqliteType: String
    ) {
        require(row.has(columnName)) {
            "Missing column value"
        }

        if (row.isNull(columnName)) {
            values.putNull(columnName)
            return
        }

        when (sqliteType) {
            "INTEGER" -> {
                // تبدیل مستقیم رشته به Long؛ بدون عبور از Double
                values.put(
                    columnName,
                    row.get(columnName).toString().toLong()
                )
            }

            "REAL" -> {
                val value = row.get(columnName).toString().toDouble()

                require(value.isFinite()) {
                    "Invalid numeric value"
                }

                values.put(columnName, value)
            }

            "TEXT" -> {
                values.put(
                    columnName,
                    row.get(columnName).toString()
                )
            }

            else -> error("Unsupported SQLite type")
        }
    }
}
