package de.mm20.launcher2.config.service

import android.os.UserHandle
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.config.Profile as ConfigProfile
import kotlinx.coroutines.flow.first

/**
 * Fork addition (Phase 2): suspend-friendly, fakeable port over
 * [ProfileManager] (which is final and binds Android system services in its
 * constructor, so it cannot be used directly in unit tests).
 *
 * Config documents reference profiles by [Profile.Type] only; user serials
 * must never leak into config files or reports.
 */
interface ProfileResolver {
    /**
     * Returns the profile of the given [type], or null if no such profile
     * exists on the device.
     */
    fun getProfile(type: Profile.Type): Profile?

    /**
     * Returns the profile owning [userHandle], or null if it is unknown.
     */
    suspend fun getProfile(userHandle: UserHandle): Profile?

    /**
     * Returns once the device's profiles have been read. Before that,
     * [getProfile] answers null for every type: not "no such profile", but
     * "not read yet".
     */
    suspend fun awaitRead()
}

class ProfileManagerProfileResolver(
    private val profileManager: ProfileManager,
) : ProfileResolver {
    override fun getProfile(type: Profile.Type): Profile? {
        return profileManager.getProfile(type)
    }

    override suspend fun getProfile(userHandle: UserHandle): Profile? {
        return profileManager.getProfileByUserHandle(userHandle).first()
    }

    override suspend fun awaitRead() = profileManager.awaitRead()
}

/** The profile type a file's `profile` names. */
internal val ConfigProfile.type: Profile.Type
    get() = when (this) {
        ConfigProfile.Personal -> Profile.Type.Personal
        ConfigProfile.Work -> Profile.Type.Work
        ConfigProfile.Private -> Profile.Type.Private
    }
