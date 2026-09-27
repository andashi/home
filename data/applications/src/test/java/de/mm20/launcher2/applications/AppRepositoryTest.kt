package de.mm20.launcher2.applications

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.StringNormalizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "No apps read yet" is not "not installed". Right after the process starts
 * the list was empty, and a config reload - the one an ingest starts - read
 * that as every favorite and every gesture's app being absent.
 */
@RunWith(RobolectricTestRunner::class)
class AppRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val personal = Profile(Profile.Type.Personal, Process.myUserHandle(), 0)

    private object Identity : StringNormalizer {
        override val id = "identity"
        override fun normalize(input: String) = input
    }

    @Test
    fun `findOne does not answer before the apps have been read`() = runBlocking {
        val profiles = MutableSharedFlow<List<Profile>>(replay = 1)
        val repository = AppRepositoryImpl(context, profiles, Identity)

        val early = withTimeoutOrNull(500) { Result.success(repository.findOne("com.example.app", personal.userHandle).first()) }

        assertNull("findOne answered ($early) before any profile's apps were read", early)
    }

    @Test
    fun `findMany does not answer before the apps have been read`() = runBlocking {
        val profiles = MutableSharedFlow<List<Profile>>(replay = 1)
        val repository = AppRepositoryImpl(context, profiles, Identity)

        assertNull(withTimeoutOrNull(500) { repository.findMany().first() })
    }

    /** Search reads the same list: an empty result before the read would look like "no match" (#206 review). */
    @Test
    fun `search does not answer before the apps have been read`() = runBlocking {
        val profiles = MutableSharedFlow<List<Profile>>(replay = 1)
        val repository = AppRepositoryImpl(context, profiles, Identity)

        assertNull(withTimeoutOrNull(500) { repository.search("set").first() })

        profiles.emit(listOf(personal))
        assertEquals(emptyList<Any>(), withTimeout(5_000) { repository.search("set").first() })
    }

    /**
     * A newer profile list can cancel the first read before it lands
     * (collectLatest); the list must still be read, whichever update comes
     * next (#206 review).
     */
    @Test
    fun `the apps are read even when a newer profile list arrives before the first read lands`() = runBlocking {
        val profiles = MutableSharedFlow<List<Profile>>(replay = 1)
        val repository = AppRepositoryImpl(context, profiles, Identity)
        val work = Profile(Profile.Type.Work, android.os.UserHandle.getUserHandleForUid(10 * 100_000), 10)

        profiles.emit(listOf(personal))
        profiles.emit(listOf(personal, work))

        assertEquals(emptyList<Any>(), withTimeout(5_000) { repository.findMany().first() })
    }

    /** Control: once the profiles' apps are read, an app that is not there is an answer. */
    @Test
    fun `findOne answers once the apps have been read`() = runBlocking {
        val profiles = MutableSharedFlow<List<Profile>>(replay = 1)
        val repository = AppRepositoryImpl(context, profiles, Identity)

        profiles.emit(listOf(personal))

        assertNull(withTimeout(5_000) { repository.findOne("com.example.missing", personal.userHandle).first() })
        assertEquals(emptyList<Any>(), withTimeout(5_000) { repository.findMany().first() })
    }
}
