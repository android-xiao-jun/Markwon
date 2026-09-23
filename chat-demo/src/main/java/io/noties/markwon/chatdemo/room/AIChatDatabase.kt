package io.noties.markwon.chatdemo.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AI聊天 Room 数据库（单例）
 *
 * 纯本地数据：消息 + 会话历史，不区分用户。
 *
 * 版本升级规范：
 * - **必须**在 [MIGRATIONS] 中追加对应 Migration（禁止依赖 fallback 清库）；
 * - [fallbackToDestructiveMigration] 仅作为「未知版本异常路径」的最后兜底
 *   （正常发布版本链路永远命中显式 Migration，聊天记录不会丢失）。
 */
@Database(
    entities = [MessageEntity::class, SessionEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AIChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun sessionDao(): SessionDao

    companion object {

        @Volatile
        private var INSTANCE: AIChatDatabase? = null

        /** v1 → v2：消息表新增 thinking / agentStepsJson（历史数据取默认值） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE ai_chat_message ADD COLUMN thinking TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE ai_chat_message ADD COLUMN agentStepsJson TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        /** v2 → v3：消息表新增 trailJson（渲染轨迹顺序，历史数据取默认空串） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE ai_chat_message ADD COLUMN trailJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** 全部显式迁移链路（新增版本时在此追加） */
        private val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3)

        fun getInstance(context: Context): AIChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AIChatDatabase::class.java,
                    "ai_chat.db"
                )
                    .addMigrations(*MIGRATIONS)
                    // 仅兜底「未知版本」（如降级安装），正常升级链路不会触发
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}