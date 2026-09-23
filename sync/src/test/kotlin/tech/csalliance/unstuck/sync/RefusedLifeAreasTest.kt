package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// What onboarding on builds up to vc100 left on a phone: its own copies of the
// server-seeded areas, whose writes unique(user_id, name) refuses for good
// (Android audit 2026-09-23, A8).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefusedLifeAreasTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
    }

    @After fun teardown() = db.close()

    /** The server's signup seed, as a pull stores it (nothing queued). */
    private suspend fun pulledServerSeed() =
        listOf("Work", "Personal", "Volunteering", "Home", "Health").forEachIndexed { i, n ->
            val a = LifeArea("server-$i", n, "indigo", i)
            store.upsert(Tables.LIFE_AREAS, a, LifeArea.serializer(), a.id)
        }

    private suspend fun lifeAreaOps() = store.pending().filter { it.recordTable == Tables.LIFE_AREAS }

    @Test fun theOldOnboardingCopiesGo_rowsAndWrites_andEverythingLegalStays() = runTest {
        pulledServerSeed()
        val write = WriteThrough(store)
        // The old seed: Work / Personal / Home / Health again under fresh ids...
        listOf("Work", "Personal", "Home", "Health").forEachIndexed { i, n -> write.upsertLifeArea(LifeArea("old-$i", n, "indigo", i)) }
        // ...plus a pick the server never had (it lands) and a name that differs only
        // in case (legal under the case-sensitive constraint).
        write.upsertLifeArea(LifeArea("family", "Family", "blue", 5))
        write.upsertLifeArea(LifeArea("lower-work", "work", "red", 6))
        // A copy parked at sign-out comes back at the next sign-in with no local row.
        store.enqueue(OutboxEntity(
            op = "upsert", recordTable = Tables.LIFE_AREAS, recordId = "parked-home",
            payload = DbRowCodec.encodeLifeArea(LifeArea("parked-home", "Home", "amber", 3)).toString(), createdAt = 0,
        ))

        assertEquals(5, RefusedLifeAreas.heal(store))

        assertEquals(
            listOf("Family", "Health", "Home", "Personal", "Volunteering", "Work", "work"),
            store.lifeAreas().first().map { it.name }.sorted(),
        )
        assertEquals("only the writes the server can take are left", setOf("family", "lower-work"), lifeAreaOps().map { it.recordId }.toSet())
    }

    @Test fun nothingIsDropped_whenTheServerHasNoRowByThatName() = runTest {
        // A first pull that hasn't landed: nothing is known to be on the server.
        WriteThrough(store).upsertLifeArea(LifeArea("old-0", "Work", "indigo", 0))
        assertEquals(0, RefusedLifeAreas.heal(store))
        assertEquals(listOf("old-0"), lifeAreaOps().map { it.recordId })
    }

    @Test fun aRenamedCopyOrADeletedOneIsLeftToTheOutbox() = runTest {
        pulledServerSeed()
        val write = WriteThrough(store)
        // Renamed since: the latest write no longer clashes, so it can land.
        write.upsertLifeArea(LifeArea("old-0", "Work", "indigo", 0))
        write.upsertLifeArea(LifeArea("old-0", "Office", "indigo", 0))
        // Deleted since: its delete is harmless on the server.
        write.upsertLifeArea(LifeArea("old-1", "Personal", "coral", 1))
        write.deleteLifeArea("old-1")

        assertEquals(0, RefusedLifeAreas.heal(store))
        assertEquals(setOf("old-0", "old-1"), lifeAreaOps().map { it.recordId }.toSet())
    }
}
