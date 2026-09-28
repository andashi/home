package de.mm20.launcher2.search

interface SearchableDeserializer {
    suspend fun deserialize(serialized: String): SavableSearchable?

    /**
     * What a stored item resolves to now. The repository deletes a stored row
     * only on [Resolved.Gone], so an implementation answers that only when it
     * knows the item no longer exists. The default reads a null from
     * [deserialize] as gone, which holds for apps, where "not installed" is
     * knowable. A source that cannot know it overrides this.
     */
    suspend fun resolve(serialized: String): Resolved =
        deserialize(serialized)?.let { Resolved.Found(it) } ?: Resolved.Gone
}

sealed interface Resolved {
    data class Found(val searchable: SavableSearchable) : Resolved

    /** The item is known not to exist any more; its row may be deleted. */
    data object Gone : Resolved

    /**
     * The item could not be resolved, and that is no evidence that it is gone:
     * a permission not granted, or a contact hidden by GrapheneOS's Contact
     * Scopes, which looks exactly like a deleted one. The row is kept, inert,
     * and resolves again once the item is visible (#237).
     */
    data object Unknown : Resolved
}

class NullDeserializer: SearchableDeserializer {
    override suspend fun deserialize(serialized: String): SavableSearchable? {
        return null
    }

}
