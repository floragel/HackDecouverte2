package com.lync.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Field names mirror supabase/schema.sql (snake_case via @SerialName).
// iOS port: these become `Codable` structs with CodingKeys.

@Serializable
data class University(
    val id: String,
    val slug: String,
    val name: String,
    @SerialName("short_name") val shortName: String,
    @SerialName("email_domain") val emailDomain: String,
    @SerialName("primary_color") val primaryColor: String,
    @SerialName("secondary_color") val secondaryColor: String,
    @SerialName("campus_lat") val campusLat: Double,
    @SerialName("campus_lng") val campusLng: Double,
)

@Serializable
enum class LocationSharing {
    @SerialName("off") OFF,
    @SerialName("friends") FRIENDS,
    @SerialName("friends_and_classmates") FRIENDS_AND_CLASSMATES,
}

@Serializable
data class Profile(
    val id: String,
    @SerialName("university_id") val universityId: String,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val program: String? = null,
    val year: Int? = null,
    val bio: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val interests: List<String> = emptyList(),
    @SerialName("status_key") val statusKey: String? = null,
    @SerialName("location_sharing") val locationSharing: LocationSharing = LocationSharing.FRIENDS,
    /** 32-bit number drawn as the Lync code on the back of the card (and sent over NFC). */
    @SerialName("lync_code") val lyncCode: Long? = null,
) {
    val firstName: String get() = fullName.substringBefore(' ')
    val initials: String
        get() = fullName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}

@Serializable
data class Course(
    val id: String,
    val code: String,
    val title: String,
)

@Serializable
data class Organization(
    val id: String,
    val name: String,
    val handle: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val verified: Boolean = false,
)

enum class PostKind { PHOTO, TEXT, POLL, PLAN }

data class PollOption(val id: String, val text: String, val votes: Int)

data class Poll(
    val question: String,
    val options: List<PollOption>,
    val myVote: String?,
    val endsAtMillis: Long,
) {
    val total: Int get() = options.sumOf { it.votes }
}

/** "Who's coming to the gym at 5?" — the social glue of Lync. */
enum class PlanActivity(val key: String, val emoji: String) {
    GYM("gym", "🏋️"), LIBRARY("library", "📚"), COFFEE("coffee", "☕"), LUNCH("lunch", "🍜"),
    STUDY("study", "✏️"), SPORT("sport", "⚽"), PARTY("party", "🎉"), OTHER("other", "✨");

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OTHER }
}

data class Plan(
    val activity: PlanActivity,
    val place: String,
    val startsAtMillis: Long,
    val spots: Int?,
    val going: List<Profile>,
    val imGoing: Boolean,
)

/** What the feed shows. Author is either a student or a club. */
data class FeedPost(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val authorAvatar: String?,
    val isOrganization: Boolean,
    val verified: Boolean,
    val imageUrl: String?,
    val caption: String?,
    val createdAtMillis: Long,
    val likeCount: Int,
    val likedByMe: Boolean,
    val sponsored: Boolean,
    val kind: PostKind = if (imageUrl != null) PostKind.PHOTO else PostKind.TEXT,
    val textStyle: Int = 0,
    val poll: Poll? = null,
    val plan: Plan? = null,
    val commentCount: Int = 0,
)

data class Comment(val id: String, val author: Profile, val text: String, val createdAtMillis: Long)

data class Story(val id: String, val imageUrl: String?, val text: String?, val textStyle: Int, val createdAtMillis: Long)

data class StoryGroup(val profile: Profile, val stories: List<Story>, val seen: Boolean)

/** Everything the composer can publish. */
sealed interface PostDraft {
    data class Photo(val image: ByteArray, val caption: String) : PostDraft
    data class Text(val text: String, val style: Int) : PostDraft
    data class PollDraft(val question: String, val options: List<String>, val hours: Int) : PostDraft
    data class PlanDraft(val activity: PlanActivity, val place: String, val startsAtMillis: Long, val spots: Int?, val note: String) : PostDraft
}

data class ProfileEdit(
    val fullName: String,
    val bio: String,
    val program: String?,
    val year: Int?,
    val interests: List<String>,
    val avatar: ByteArray?,
)

data class Program(val slug: String, val name: String, val degree: String) {
    val label: String get() = if (degree.isBlank()) name else "$name ($degree)"
}

enum class ConnectionState { NONE, OUTGOING, INCOMING, CONNECTED }

/** A person card in the Network tab. */
data class Person(
    val profile: Profile,
    val sharedCourses: Int = 0,
    val mutuals: Int = 0,
    val connection: ConnectionState = ConnectionState.NONE,
    val following: Boolean = false,
)

enum class PinKind { ME, FRIEND, CLASSMATE }

data class MapPin(
    val profile: Profile,
    val lat: Double,
    val lng: Double,
    val updatedAtMillis: Long,
    val kind: PinKind,
)

data class ProfileStats(val posts: Int, val connections: Int, val followers: Int)

/** Deep link shared by text ("Share card"). Scanning uses the Lync code (see ui/code). */
object CardPayload {
    private const val PREFIX = "https://lync.app/u/"
    fun encode(profileId: String) = PREFIX + profileId
    fun decode(raw: String): String? = raw.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }
}

/** Visual styles for text posts / stories (index into this list). Same list on iOS. */
val TextStyles: List<Pair<Long, Long>> = listOf(
    0xFF1A1D29 to 0xFF3A3F55, // night
    0xFFFF6B6B to 0xFFFFB86B, // sunset
    0xFF5B8CFF to 0xFF9B6BFF, // dusk
    0xFF1FB58F to 0xFF9BE15D, // mint
    0xFFFFD166 to 0xFFFF9F1C, // honey
)

/** Preset statuses shown on the card and the feed's top row. Keys are stored in DB. */
enum class StatusPreset(val key: String, val emoji: String) {
    COFFEE("coffee", "☕"),
    STUDY("study", "📚"),
    LUNCH("lunch", "🍜"),
    BUSY("busy", "🎧");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

// ------------------------------------------------------------------ Campus life

enum class EventCategory(val key: String, val emoji: String) {
    SOCIAL("social", "🎉"), ACADEMIC("academic", "🎓"), SPORT("sport", "🏀"), CAREER("career", "💼"),
    CULTURE("culture", "🎭"), TECH("tech", "💻");

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SOCIAL }
}

/** Published by a club (organizations). Clubs on a paid plan can be "featured". */
data class CampusEvent(
    val id: String,
    val title: String,
    val description: String,
    val organizer: Organization,
    val category: EventCategory,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    val place: String,
    val coverUrl: String?,
    val goingCount: Int,
    val imGoing: Boolean,
    val friendsGoing: List<Profile>,
    val featured: Boolean,
)

data class InterestGroup(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
    val memberCount: Int,
    val isMember: Boolean,
    val members: List<Profile>,
)

/** A campus building, used to answer "who's here?". */
data class Building(val code: String, val name: String, val lat: Double, val lng: Double, val radiusM: Double)

/** Friends currently inside a building. */
data class BuildingPresence(val building: Building, val people: List<Profile>, val includesMe: Boolean)
