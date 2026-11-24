package ru.pokanevich.familybudget.data.sync

import ru.pokanevich.familybudget.BuildConfig
import ru.pokanevich.familybudget.data.FamilyPreferences

/**
 * Изолированные облачные контуры синхронизации Cloud Firestore:
 * - [FAMILY]: общий бюджет семьи (`zfold_family_spaces/{spaceKey}`)
 * - [GRANDMA_SOLO]: изолированный личный контур (`zfold_grandma_spaces/{spaceKey}`)
 */
enum class SyncContour(
    val id: String,
    val rootCollection: String,
    val defaultSpaceKey: String
) {
    FAMILY(
        id = "FAMILY",
        rootCollection = "zfold_family_spaces",
        defaultSpaceKey = "POKANE-FAMILY"
    ),
    GRANDMA_SOLO(
        id = "GRANDMA_SOLO",
        rootCollection = "zfold_grandma_spaces",
        defaultSpaceKey = "GRANDMA-SOLO"
    );

    companion object {
        private val VALID_SPACE_KEY_REGEX = Regex("^[A-Za-z0-9_-]{6,64}$")

        fun current(): SyncContour = fromId(BuildConfig.SYNC_CONTOUR)

        fun fromId(raw: String?): SyncContour = when (raw?.trim()?.uppercase()) {
            GRANDMA_SOLO.id -> GRANDMA_SOLO
            else -> FAMILY
        }

        fun validateAndNormalizeSpaceKey(
            raw: String,
            contour: SyncContour,
            isSpaceCodeLocked: Boolean = FamilyPreferences.isSpaceCodeLocked
        ): String {
            if (contour == GRANDMA_SOLO && isSpaceCodeLocked) {
                return GRANDMA_SOLO.defaultSpaceKey
            }
            val cleaned = raw.trim()
                .uppercase()
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .ifBlank { contour.defaultSpaceKey }

            if (contour == GRANDMA_SOLO && cleaned == FAMILY.defaultSpaceKey) {
                return GRANDMA_SOLO.defaultSpaceKey
            }
            val bounded = cleaned.take(64)
            return if (VALID_SPACE_KEY_REGEX.matches(bounded)) {
                bounded
            } else {
                contour.defaultSpaceKey
            }
        }
    }
}
