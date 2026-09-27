package de.mm20.launcher2.config.service

import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.config.Profile as ConfigProfile

/** The file's profile names and the device's profile types, both ways; the file never carries a user serial. */
internal fun ConfigProfile.toProfileType(): Profile.Type = when (this) {
    ConfigProfile.Personal -> Profile.Type.Personal
    ConfigProfile.Work -> Profile.Type.Work
    ConfigProfile.Private -> Profile.Type.Private
}

internal fun Profile.Type.toConfigProfile(): ConfigProfile = when (this) {
    Profile.Type.Personal -> ConfigProfile.Personal
    Profile.Type.Work -> ConfigProfile.Work
    Profile.Type.Private -> ConfigProfile.Private
}
