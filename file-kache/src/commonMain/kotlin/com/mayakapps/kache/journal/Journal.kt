/*
 * Copyright 2023 MayakaApps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mayakapps.kache.journal

import com.mayakapps.kache.atomicMove
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

internal data class JournalData(
    val cleanEntriesKeys: List<String>,
    val dirtyEntriesKeys: List<String>,
    val redundantEntriesCount: Int,
)

internal fun FileSystem.readJournalIfExists(directory: Path, cacheVersion: Int = 1): JournalData? {
    val journalFile = directory.resolve(JOURNAL_FILE)
    val tempJournalFile = directory.resolve(JOURNAL_FILE_TEMP)
    val backupJournalFile = directory.resolve(JOURNAL_FILE_BACKUP)

    // If a backup file exists, use it instead.
    if (exists(backupJournalFile)) {
        // If journal file also exists just delete backup file.
        if (exists(journalFile)) {
            delete(backupJournalFile)
        } else {
            atomicMove(backupJournalFile, journalFile)
        }
    }

    // If a temp file exists, delete it
    delete(tempJournalFile)

    if (!exists(journalFile)) return null

    var entriesCount = 0
    val dirtyEntriesKeys = mutableListOf<String>()
    val cleanEntriesKeys = mutableListOf<String>()

    JournalReader(source(journalFile).buffer(), cacheVersion).use { reader ->
        reader.validateHeader()

        while (true) {
            val entry = reader.readEntry() ?: break
            entriesCount++

            when (entry) {
                is JournalEntry.Dirty -> {
                    dirtyEntriesKeys += entry.key
                }

                is JournalEntry.Clean -> {
                    // Remove existing entry if it exists to avoid duplicates
                    cleanEntriesKeys.remove(entry.key)

                    dirtyEntriesKeys.remove(entry.key)
                    cleanEntriesKeys += entry.key
                }

                is JournalEntry.Cancel -> {
                    dirtyEntriesKeys.remove(entry.key)
                }

                is JournalEntry.Remove -> {
                    dirtyEntriesKeys.remove(entry.key)
                    cleanEntriesKeys.remove(entry.key)
                }

                is JournalEntry.Read -> {
                    cleanEntriesKeys.remove(entry.key)
                    cleanEntriesKeys += entry.key
                }
            }
        }
    }

    return JournalData(
        cleanEntriesKeys = cleanEntriesKeys,
        dirtyEntriesKeys = dirtyEntriesKeys,
        redundantEntriesCount = entriesCount - cleanEntriesKeys.size,
    )
}

internal fun FileSystem.writeJournalAtomically(
    directory: Path,
    cleanEntriesKeys: Collection<String>,
    dirtyEntriesKeys: Collection<String>
) {
    val journalFile = directory.resolve(JOURNAL_FILE)
    val tempJournalFile = directory.resolve(JOURNAL_FILE_TEMP)
    val backupJournalFile = directory.resolve(JOURNAL_FILE_BACKUP)

    delete(tempJournalFile)

    JournalWriter(sink(tempJournalFile, mustCreate = true).buffer()).use { writer ->
        writer.writeHeader()
        writer.writeAll(cleanEntriesKeys, dirtyEntriesKeys)
    }

    if (exists(journalFile)) atomicMove(journalFile, backupJournalFile, deleteTarget = true)
    atomicMove(tempJournalFile, journalFile)
    delete(backupJournalFile)
}
