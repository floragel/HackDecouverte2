package com.lync.app.data

import com.lync.app.data.model.CampusEvent
import com.lync.app.data.model.Comment
import com.lync.app.data.model.InterestGroup
import com.lync.app.data.model.Course
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.LocationSharing
import com.lync.app.data.model.MapPin
import com.lync.app.data.model.Organization
import com.lync.app.data.model.Person
import com.lync.app.data.model.PostDraft
import com.lync.app.data.model.Profile
import com.lync.app.data.model.ProfileEdit
import com.lync.app.data.model.ProfileStats
import com.lync.app.data.model.StoryGroup
import com.lync.app.data.model.University
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SignUpForm(
    val university: University,
    val fullName: String,
    val email: String,
    val password: String,
    val program: String,
    val year: Int?,
)

/**
 * Single entry point the UI talks to. Two implementations:
 *  - DemoRepository: in-memory Concordia data (works offline, perfect for demos)
 *  - SupabaseRepository: the real backend
 * The iOS app should expose the exact same protocol.
 */
interface CampusRepository {
    val isDemo: Boolean

    /** Null until signed in / onboarded. */
    val me: StateFlow<Profile?>

    suspend fun universities(): List<University>
    suspend fun university(id: String): University?
    suspend fun signUp(form: SignUpForm): Result<Profile>
    suspend fun signOut()

    // Me
    suspend fun updateProfile(edit: ProfileEdit): Result<Unit>
    suspend fun updateStatus(key: String?)
    suspend fun setLocationSharing(mode: LocationSharing)
    suspend fun stats(profileId: String? = null): ProfileStats
    suspend fun myCourses(): List<Course>
    suspend fun posts(authorId: String): List<FeedPost>

    // Instagram side
    suspend fun feed(): List<FeedPost>
    suspend fun setLiked(postId: String, liked: Boolean)
    suspend fun createPost(draft: PostDraft, groupId: String? = null): Result<Unit>
    suspend fun vote(postId: String, optionId: String)
    suspend fun setGoing(postId: String, going: Boolean)
    suspend fun comments(postId: String): List<Comment>
    suspend fun addComment(postId: String, text: String): Result<Comment>
    suspend fun stories(): List<StoryGroup>
    suspend fun addStory(text: String?, image: ByteArray?, style: Int): Result<Unit>
    suspend fun markStorySeen(profileId: String)

    // LinkedIn side
    suspend fun requests(): List<Person>
    suspend fun classmates(): List<Person>
    suspend fun suggestions(): List<Person>
    suspend fun clubs(): List<Organization>
    suspend fun search(query: String): List<Person>
    suspend fun profile(id: String): Person?
    /** Lync code (scanned or received over NFC) -> person. */
    suspend fun profileByCode(code: Long): Person?
    suspend fun connect(profileId: String, source: String = "app")
    suspend fun respond(profileId: String, accept: Boolean)
    suspend fun setFollowing(profileId: String, follow: Boolean)

    // Campus life
    suspend fun events(): List<CampusEvent>
    suspend fun event(id: String): CampusEvent?
    suspend fun setRsvp(eventId: String, going: Boolean)
    suspend fun groups(): List<InterestGroup>
    suspend fun group(id: String): InterestGroup?
    suspend fun setMember(groupId: String, join: Boolean)
    suspend fun groupPosts(groupId: String): List<FeedPost>
    suspend fun createGroup(name: String, emoji: String, description: String): Result<InterestGroup>

    // Snap side
    suspend fun pushLocation(lat: Double, lng: Double)
    suspend fun clearLocation()
    /** Live positions of friends (and classmates if they allow it). */
    fun mapPins(): Flow<List<MapPin>>
}
