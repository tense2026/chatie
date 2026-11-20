package co.chatie.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-first store.
 *
 * The rule this schema exists to enforce: a conversation belongs to exactly
 * zero or one project, and its context is assembled from a fixed, ordered list
 * of individually switchable layers. Nothing is inherited sideways between
 * projects and no layer is ever applied implicitly.
 * See [co.chatie.app.context.ContextAssembler].
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        // WAL must be asked for through the helper. Running
        // "PRAGMA journal_mode = WAL" through execSQL throws, because that
        // statement returns a row and execSQL rejects anything that does.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        MIGRATIONS.forEach { step -> step.forEach(db::execSQL) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in oldVersion until newVersion) {
            MIGRATIONS.getOrNull(v)?.forEach(db::execSQL)
        }
    }

    companion object {
        const val NAME = "chatie.db"
        const val VERSION = 1

        /** One list per schema version; index 0 creates version 1. */
        private val MIGRATIONS: List<List<String>> = listOf(
            listOf(
                """
                CREATE TABLE IF NOT EXISTS projects (
                  id                TEXT PRIMARY KEY NOT NULL,
                  name              TEXT NOT NULL,
                  description       TEXT NOT NULL DEFAULT '',
                  instructions      TEXT NOT NULL DEFAULT '',
                  default_model     TEXT,
                  use_global_memory INTEGER NOT NULL DEFAULT 0,
                  archived          INTEGER NOT NULL DEFAULT 0,
                  created_at        INTEGER NOT NULL,
                  updated_at        INTEGER NOT NULL
                );
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS conversations (
                  id                       TEXT PRIMARY KEY NOT NULL,
                  project_id               TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  title                    TEXT NOT NULL DEFAULT '',
                  model                    TEXT NOT NULL,
                  system_override          TEXT,
                  use_project_instructions INTEGER NOT NULL DEFAULT 1,
                  use_project_memory       INTEGER NOT NULL DEFAULT 1,
                  use_global_memory        INTEGER,
                  pinned                   INTEGER NOT NULL DEFAULT 0,
                  archived                 INTEGER NOT NULL DEFAULT 0,
                  created_at               INTEGER NOT NULL,
                  updated_at               INTEGER NOT NULL
                );
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS messages (
                  id                TEXT PRIMARY KEY NOT NULL,
                  conversation_id   TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                  seq               INTEGER NOT NULL,
                  role              TEXT NOT NULL,
                  content           TEXT NOT NULL DEFAULT '',
                  reasoning         TEXT,
                  model             TEXT,
                  status            TEXT NOT NULL DEFAULT 'done',
                  error             TEXT,
                  prompt_tokens     INTEGER,
                  completion_tokens INTEGER,
                  credits           REAL,
                  created_at        INTEGER NOT NULL
                );
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS memories (
                  id              TEXT PRIMARY KEY NOT NULL,
                  scope           TEXT NOT NULL CHECK (scope IN ('global','project','conversation')),
                  project_id      TEXT REFERENCES projects(id) ON DELETE CASCADE,
                  conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,
                  text            TEXT NOT NULL,
                  enabled         INTEGER NOT NULL DEFAULT 1,
                  source          TEXT NOT NULL DEFAULT 'user',
                  created_at      INTEGER NOT NULL,
                  updated_at      INTEGER NOT NULL,
                  CHECK (
                    (scope = 'global'       AND project_id IS NULL     AND conversation_id IS NULL) OR
                    (scope = 'project'      AND project_id IS NOT NULL AND conversation_id IS NULL) OR
                    (scope = 'conversation' AND project_id IS NULL     AND conversation_id IS NOT NULL)
                  )
                );
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS settings (
                  key   TEXT PRIMARY KEY NOT NULL,
                  value TEXT NOT NULL
                );
                """.trimIndent(),
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_conv_seq ON messages (conversation_id, seq);",
                "CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations (archived, updated_at DESC);",
                "CREATE INDEX IF NOT EXISTS idx_conversations_project ON conversations (project_id, updated_at DESC);",
                "CREATE INDEX IF NOT EXISTS idx_memories_project ON memories (scope, project_id);",
                "CREATE INDEX IF NOT EXISTS idx_memories_conversation ON memories (scope, conversation_id);",
            ),
        )
    }
}
