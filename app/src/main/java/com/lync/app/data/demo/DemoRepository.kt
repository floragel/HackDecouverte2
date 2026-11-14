package com.lync.app.data.demo

import com.lync.app.data.CampusRepository
import com.lync.app.data.SignUpForm
import com.lync.app.data.model.CampusEvent
import com.lync.app.data.model.Comment
import com.lync.app.data.model.EventCategory
import com.lync.app.data.model.InterestGroup
import com.lync.app.data.model.ConnectionState
import com.lync.app.data.model.Course
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.LocationSharing
import com.lync.app.data.model.MapPin
import com.lync.app.data.model.Organization
import com.lync.app.data.model.Person
import com.lync.app.data.model.PinKind
import com.lync.app.data.model.Plan
import com.lync.app.data.model.PlanActivity
import com.lync.app.data.model.Poll
import com.lync.app.data.model.PollOption
import com.lync.app.data.model.PostDraft
import com.lync.app.data.model.PostKind
import com.lync.app.data.model.Profile
import com.lync.app.data.model.ProfileEdit
import com.lync.app.data.model.ProfileStats
import com.lync.app.data.model.Story
import com.lync.app.data.model.StoryGroup
import com.lync.app.data.model.University
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-memory backend with realistic Concordia data. Used automatically when
 * no Supabase keys are set, so every feature is demo-able offline.
 */
class DemoRepository : CampusRepository {
    override val isDemo = true

    private val _me = MutableStateFlow<Profile?>(null)
    override val me: StateFlow<Profile?> = _me

    private val concordia = University(
        id = "uni-concordia", slug = "concordia", name = "Concordia University", shortName = "Concordia",
        emailDomain = "concordia.ca", primaryColor = "#912338", secondaryColor = "#D4A12A",
        campusLat = 45.4972, campusLng = -73.5790,
    )
    private val unis = listOf(concordia)

    private fun p(id: String, name: String, user: String, program: String, year: Int, status: String? = null, interests: List<String> = emptyList(), bio: String? = null) =
        Profile(
            id = id, universityId = concordia.id, username = user, fullName = name, program = program, year = year,
            avatarUrl = "https://i.pravatar.cc/300?u=$user", statusKey = status, interests = interests, bio = bio,
            lyncCode = demoCode(id),
        )

    private val people = listOf(
        p("u1", "Maya Tremblay", "maya.t", "Computer Science", 1, "coffee", listOf("Climbing", "UI design", "Matcha"), "Webster lib 6th floor regular"),
        p("u2", "Karim Haddad", "karim", "Software Engineering", 2, "study", listOf("F1", "Robotics"), "Building robots that mostly work"),
        p("u3", "Léa Nguyen", "lea.ng", "Psychology", 1, "lunch", listOf("Photography", "K-pop")),
        p("u4", "Noah Gagnon", "noahg", "Finance", 3, null, listOf("Basketball", "Investing")),
        p("u5", "Inès Benali", "ines.b", "Computer Science", 1, "coffee", listOf("Hackathons", "Piano")),
        p("u6", "Lucas Roy", "lucasroy", "Mechanical Engineering", 2, "busy", listOf("Cars", "3D printing")),
        p("u7", "Sofia Rossi", "sofia.r", "Design", 1, null, listOf("Film", "Typography")),
        p("u8", "Adam Pelletier", "adamp", "Computer Science", 1, "study", listOf("Gaming", "Chess")),
        p("u9", "Chloé Martin", "chloe.m", "Communications", 2, null, listOf("Podcasts", "Thrifting")),
        p("u10", "Yanis Cherif", "yanis", "Data Science", 1, "lunch", listOf("Soccer", "AI")),
    )
    private fun person(id: String) = people.firstOrNull { it.id == id } ?: _me.value?.takeIf { it.id == id }

    private val courses = listOf(
        Course("c1", "COMP 248", "Object-Oriented Programming I"),
        Course("c2", "MATH 203", "Differential & Integral Calculus I"),
        Course("c3", "ENGR 201", "Professional Practice"),
        Course("c4", "COMP 228", "System Hardware"),
    )
    /** profileId -> number of courses shared with me */
    private val shared = mapOf("u1" to 3, "u5" to 2, "u8" to 2, "u10" to 1, "u2" to 1)

    private val connections = MutableStateFlow(
        mapOf(
            "u1" to ConnectionState.CONNECTED, "u2" to ConnectionState.CONNECTED, "u3" to ConnectionState.CONNECTED,
            "u6" to ConnectionState.CONNECTED, "u7" to ConnectionState.INCOMING, "u9" to ConnectionState.INCOMING,
        )
    )
    private val following = MutableStateFlow(setOf("u1", "u2", "u3", "o1"))

    private val clubs = listOf(
        Organization("o1", "Concordia Robotics", "conu.robotics", "https://i.pravatar.cc/300?u=robotics", verified = true),
        Organization("o2", "HackConcordia", "hackconcordia", "https://i.pravatar.cc/300?u=hackconcordia", verified = true),
        Organization("o3", "Film Society", "conu.film", "https://i.pravatar.cc/300?u=film"),
        Organization("o4", "Climbing Club", "conu.climb", "https://i.pravatar.cc/300?u=climb"),
    )

    private val now get() = System.currentTimeMillis()
    private val min = 60_000L

    private fun base(id: String, author: String, ago: Long, likes: Int, org: Boolean = false, sponsored: Boolean = false): FeedPost {
        val o = if (org) clubs.first { it.id == author } else null
        val pr = if (!org) person(author) else null
        return FeedPost(
            id = id, authorId = author,
            authorName = o?.name ?: pr!!.fullName, authorHandle = o?.handle ?: pr!!.username,
            authorAvatar = o?.avatarUrl ?: pr!!.avatarUrl, isOrganization = org, verified = o?.verified ?: false,
            imageUrl = null, caption = null, createdAtMillis = System.currentTimeMillis() - ago,
            likeCount = likes, likedByMe = false, sponsored = sponsored,
        )
    }

    private val posts = MutableStateFlow(
        listOf(
            base("p0", "u5", 4 * min, 6).copy(
                kind = PostKind.PLAN,
                plan = Plan(PlanActivity.GYM, "Le Gym, EV building", System.currentTimeMillis() + 50 * min, 4, listOf(person("u8")!!), false),
                caption = "Leg day, need motivation 😤",
            ),
            base("p1", "u1", 12 * min, 48).copy(kind = PostKind.PHOTO, imageUrl = photo("library"), caption = "Study session at the Webster library, 6th floor. Pull up 📚"),
            base("p7", "u2", 25 * min, 19).copy(
                kind = PostKind.POLL,
                poll = Poll(
                    "Best study spot on campus?",
                    listOf(PollOption("a", "Webster library", 41), PollOption("b", "EV 2nd floor", 23), PollOption("c", "Hall building cafés", 17), PollOption("d", "Home 🛏️", 30)),
                    myVote = null, endsAtMillis = System.currentTimeMillis() + 20 * 60 * min,
                ),
            ),
            base("p2", "o2", 40 * min, 212, org = true, sponsored = true).copy(kind = PostKind.PHOTO, imageUrl = photo("hackathon"), caption = "HackDécouverte II is live! Good luck everyone 🚀"),
            base("p8", "u3", 70 * min, 33).copy(kind = PostKind.TEXT, textStyle = 2, caption = "Anyone else's 8:45 cancelled or is it just me 🙃"),
            base("p9", "u10", 90 * min, 9).copy(
                kind = PostKind.PLAN,
                plan = Plan(PlanActivity.LIBRARY, "Webster library, 4th floor", System.currentTimeMillis() + 2 * 60 * min, null, listOf(person("u1")!!, person("u5")!!, person("u8")!!), false),
                caption = "MATH 203 midterm grind, bring snacks",
            ),
            base("p3", "u3", 2 * 60 * min, 96).copy(kind = PostKind.PHOTO, imageUrl = photo("snow"), caption = "First snow on campus ❄️"),
            base("p4", "u2", 5 * 60 * min, 131).copy(kind = PostKind.PHOTO, imageUrl = photo("robot"), caption = "Robot arm finally picks up the cup. 3 weeks. Worth it."),
            base("p5", "o3", 8 * 60 * min, 74, org = true).copy(kind = PostKind.PHOTO, imageUrl = photo("cinema"), caption = "Free screening tonight 7pm, H-110. Popcorn on us 🍿"),
            base("p6", "u6", 26 * 60 * min, 158).copy(kind = PostKind.PHOTO, imageUrl = photo("city"), caption = "Hall building at golden hour"),
        )
    )

    private val commentsByPost = MutableStateFlow(
        mapOf(
            "p1" to listOf(Comment("k1", person("u5")!!, "saving me a seat?", now - 8 * min), Comment("k2", person("u8")!!, "omw 🏃", now - 5 * min)),
            "p4" to listOf(Comment("k3", person("u6")!!, "this is so cool", now - 3 * 60 * min)),
        )
    )

    private val storyData = MutableStateFlow(
        listOf(
            StoryGroup(person("u1")!!, listOf(Story("s1", photo("library"), "6th floor is packed", 0, now - 30 * min)), false),
            StoryGroup(person("u2")!!, listOf(Story("s2", null, "Robotics demo at 4pm, EV-11", 2, now - 50 * min), Story("s3", photo("robot"), null, 0, now - 40 * min)), false),
            StoryGroup(person("u3")!!, listOf(Story("s4", photo("snow"), "❄️❄️❄️", 0, now - 2 * 60 * min)), false),
            StoryGroup(person("u6")!!, listOf(Story("s5", null, "who has the ENGR 201 notes 🙏", 1, now - 3 * 60 * min)), false),
        )
    )

    override suspend fun universities() = unis
    override suspend fun university(id: String) = unis.firstOrNull { it.id == id }

    override suspend fun signUp(form: SignUpForm): Result<Profile> {
        delay(400)
        val profile = Profile(
            id = "me", universityId = form.university.id,
            username = form.email.substringBefore('@').lowercase().trimEnd('.'),
            fullName = form.fullName.trim(), program = form.program.ifBlank { null }, year = form.year,
            interests = listOf("Coffee", "Music", "Hackathons"), statusKey = "coffee", lyncCode = demoCode("me-" + form.fullName),
        )
        _me.value = profile
        return Result.success(profile)
    }

    override suspend fun signIn(email: String, password: String): Result<Profile> {
        val name = email.substringBefore('@').split('.', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        return signUp(SignUpForm(concordia, name, email, password, "Computer Science", 1))
    }

    /** Used to restore the demo session after app restart. */
    fun restore(profile: Profile) { _me.value = if (profile.lyncCode == null) profile.copy(lyncCode = demoCode("me-" + profile.fullName)) else profile }

    override suspend fun signOut() { _me.value = null }

    override suspend fun updateProfile(edit: ProfileEdit): Result<Unit> {
        val m = _me.value ?: return Result.failure(IllegalStateException())
        _me.value = m.copy(
            fullName = edit.fullName.trim().ifBlank { m.fullName }, bio = edit.bio.ifBlank { null },
            program = edit.program, year = edit.year, interests = edit.interests,
            avatarUrl = if (edit.avatar != null) "https://i.pravatar.cc/300?u=${UUID.randomUUID()}" else m.avatarUrl,
        )
        return Result.success(Unit)
    }

    override suspend fun updateStatus(key: String?) { _me.value = _me.value?.copy(statusKey = key) }
    override suspend fun setLocationSharing(mode: LocationSharing) { _me.value = _me.value?.copy(locationSharing = mode) }

    override suspend fun stats(profileId: String?): ProfileStats {
        val id = profileId ?: _me.value?.id
        return if (id == _me.value?.id) ProfileStats(
            posts = posts.value.count { it.authorId == id },
            connections = connections.value.count { it.value == ConnectionState.CONNECTED },
            followers = 87,
        ) else ProfileStats(posts.value.count { it.authorId == id }, 40 + (id.hashCode() and 0x3F), 120 + (id.hashCode() and 0xFF))
    }

    override suspend fun myCourses() = courses

    override suspend fun posts(authorId: String): List<FeedPost> = posts.value.filter { it.authorId == authorId }

    override suspend fun feed(): List<FeedPost> {
        delay(200)
        return posts.value.map { it.copy(commentCount = commentsByPost.value[it.id]?.size ?: 0) }
    }

    private fun update(postId: String, f: (FeedPost) -> FeedPost) { posts.value = posts.value.map { if (it.id == postId) f(it) else it } }

    override suspend fun setLiked(postId: String, liked: Boolean) = update(postId) {
        if (it.likedByMe == liked) it else it.copy(likedByMe = liked, likeCount = it.likeCount + if (liked) 1 else -1)
    }

    override suspend fun vote(postId: String, optionId: String) = update(postId) { p ->
        val poll = p.poll ?: return@update p
        if (poll.myVote != null) return@update p
        p.copy(poll = poll.copy(myVote = optionId, options = poll.options.map { if (it.id == optionId) it.copy(votes = it.votes + 1) else it }))
    }

    override suspend fun setGoing(postId: String, going: Boolean) = update(postId) { p ->
        val plan = p.plan ?: return@update p
        val m = _me.value ?: return@update p
        val list = plan.going.filterNot { it.id == m.id } + if (going) listOf(m) else emptyList()
        p.copy(plan = plan.copy(going = list, imGoing = going))
    }

    override suspend fun createPost(draft: PostDraft, groupId: String?): Result<Unit> {
        val m = _me.value ?: return Result.failure(IllegalStateException("not signed in"))
        val base = FeedPost(
            id = UUID.randomUUID().toString(), authorId = m.id, authorName = m.fullName, authorHandle = m.username,
            authorAvatar = m.avatarUrl, isOrganization = false, verified = false, imageUrl = null, caption = null,
            createdAtMillis = now, likeCount = 0, likedByMe = false, sponsored = false,
        )
        val post = when (draft) {
            is PostDraft.Photo -> base.copy(kind = PostKind.PHOTO, imageUrl = photo("campus"), caption = draft.caption.ifBlank { null })
            is PostDraft.Text -> base.copy(kind = PostKind.TEXT, caption = draft.text, textStyle = draft.style)
            is PostDraft.PollDraft -> base.copy(
                kind = PostKind.POLL,
                poll = Poll(draft.question, draft.options.mapIndexed { i, t -> PollOption("o$i", t, 0) }, null, now + draft.hours * 60 * min),
            )
            is PostDraft.PlanDraft -> base.copy(
                kind = PostKind.PLAN, caption = draft.note.ifBlank { null },
                plan = Plan(draft.activity, draft.place, draft.startsAtMillis, draft.spots, listOf(m), true),
            )
        }
        if (groupId != null) groupFeed.value = groupFeed.value + (groupId to (listOf(post) + groupFeed.value[groupId].orEmpty()))
        else posts.value = listOf(post) + posts.value
        return Result.success(Unit)
    }

    override suspend fun comments(postId: String): List<Comment> = commentsByPost.value[postId].orEmpty()

    override suspend fun addComment(postId: String, text: String): Result<Comment> {
        val m = _me.value ?: return Result.failure(IllegalStateException())
        val c = Comment(UUID.randomUUID().toString(), m, text.trim(), now)
        commentsByPost.value = commentsByPost.value + (postId to (commentsByPost.value[postId].orEmpty() + c))
        update(postId) { it.copy(commentCount = it.commentCount + 1) }
        return Result.success(c)
    }

    private val myStories = MutableStateFlow<List<Story>>(emptyList())

    override suspend fun stories(): List<StoryGroup> {
        val m = _me.value
        val mine = if (m != null && myStories.value.isNotEmpty()) listOf(StoryGroup(m, myStories.value, true)) else emptyList()
        return mine + storyData.value.sortedBy { it.seen }
    }

    override suspend fun addStory(text: String?, image: ByteArray?, style: Int): Result<Unit> {
        myStories.value = myStories.value + Story(UUID.randomUUID().toString(), if (image != null) photo("campus") else null, text, style, now)
        return Result.success(Unit)
    }

    override suspend fun markStorySeen(profileId: String) {
        storyData.value = storyData.value.map { if (it.profile.id == profileId) it.copy(seen = true) else it }
    }

    private fun toPerson(pr: Profile) = Person(
        profile = pr,
        sharedCourses = shared[pr.id] ?: 0,
        mutuals = (pr.id.hashCode() and 0x7) + 1,
        connection = connections.value[pr.id] ?: ConnectionState.NONE,
        following = pr.id in following.value,
    )

    override suspend fun requests() = people.filter { connections.value[it.id] == ConnectionState.INCOMING }.map(::toPerson)
    override suspend fun classmates() = people.filter { shared.containsKey(it.id) }.map(::toPerson).sortedByDescending { it.sharedCourses }
    override suspend fun suggestions() = people.filter { connections.value[it.id] == null && !shared.containsKey(it.id) }.map(::toPerson)
    override suspend fun clubs() = clubs

    override suspend fun search(query: String): List<Person> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return people.filter { q in it.fullName.lowercase() || q in it.username || q in (it.program ?: "").lowercase() }.map(::toPerson)
    }

    override suspend fun profile(id: String) = person(id)?.let(::toPerson)

    override suspend fun profileByCode(code: Long) = people.firstOrNull { it.lyncCode == code }?.let(::toPerson)

    override suspend fun connect(profileId: String, source: String) {
        // In the demo, scanning someone's card (or NFC) connects instantly — that's the magic moment.
        val state = if (source == "app") ConnectionState.OUTGOING else ConnectionState.CONNECTED
        connections.value = connections.value + (profileId to state)
    }

    override suspend fun respond(profileId: String, accept: Boolean) {
        connections.value = if (accept) connections.value + (profileId to ConnectionState.CONNECTED) else connections.value - profileId
    }

    override suspend fun setFollowing(profileId: String, follow: Boolean) {
        following.value = if (follow) following.value + profileId else following.value - profileId
    }

    // ------------------------------------------------------------- Campus life
    private fun at(daysFromNow: Int, hour: Int, minute: Int = 0): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, daysFromNow); set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
    }.timeInMillis

    private val eventData = MutableStateFlow(
        listOf(
            CampusEvent("e1", "Robotics open lab", "Come see the rover we're sending to the University Rover Challenge. Pizza after 6.",
                clubs[0], EventCategory.TECH, at(0, 17), at(0, 20), "EV 11.119", photo("robot"), 42, false, listOf(person("u2")!!, person("u5")!!), featured = true),
            CampusEvent("e2", "Free movie night: Dune", "Big screen, free popcorn, bring a friend.",
                clubs[2], EventCategory.CULTURE, at(0, 19), at(0, 22), "H-110", photo("cinema"), 88, false, listOf(person("u3")!!), featured = false),
            CampusEvent("e3", "Bouldering for beginners", "No experience needed, shoes provided. Meet in the lobby.",
                clubs[3], EventCategory.SPORT, at(1, 18), at(1, 20), "Le Gym, EV building", photo("campus"), 23, false, listOf(person("u1")!!), featured = false),
            CampusEvent("e4", "Hackathon info session", "Everything about HackConcordia's next events, teams and prizes.",
                clubs[1], EventCategory.TECH, at(2, 12), at(2, 13), "Webster Library, LB-322", photo("hackathon"), 61, false, emptyList(), featured = false),
            CampusEvent("e5", "Career fair: tech & engineering", "60 companies hiring interns. Bring your CV.",
                clubs[1], EventCategory.CAREER, at(4, 10), at(4, 16), "JMSB atrium", photo("city"), 310, false, listOf(person("u2")!!, person("u6")!!, person("u8")!!), featured = true),
        )
    )

    override suspend fun events(): List<CampusEvent> = eventData.value.sortedBy { it.startsAtMillis }
    override suspend fun event(id: String) = eventData.value.firstOrNull { it.id == id }

    override suspend fun setRsvp(eventId: String, going: Boolean) {
        eventData.value = eventData.value.map {
            if (it.id == eventId && it.imGoing != going) it.copy(imGoing = going, goingCount = it.goingCount + if (going) 1 else -1) else it
        }
    }

    private val groupData = MutableStateFlow(
        listOf(
            InterestGroup("g1", "Climbing crew", "🧗", "Bouldering sessions every week, all levels.", 64, true, listOf(person("u1")!!, person("u6")!!)),
            InterestGroup("g2", "F1 & motorsport", "🏎️", "Race weekends, watch parties, hot takes.", 41, false, listOf(person("u2")!!)),
            InterestGroup("g3", "K-pop dance", "💃", "Learn choreos, perform at Shuffle.", 87, false, listOf(person("u3")!!)),
            InterestGroup("g4", "Chess club", "♟️", "Blitz at the library café, Thursdays.", 33, false, listOf(person("u8")!!)),
            InterestGroup("g5", "Photo walks", "📷", "Montreal photo walks, film and digital.", 52, true, listOf(person("u3")!!, person("u7")!!)),
            InterestGroup("g6", "AI & machine learning", "🤖", "Paper club, projects, Kaggle teams.", 120, false, listOf(person("u10")!!, person("u5")!!)),
        )
    )

    private val groupFeed = MutableStateFlow(
        mapOf(
            "g1" to listOf(base("gp1", "u1", 3 * 60 * min, 12).copy(kind = PostKind.PLAN, caption = "Who's in for a session tonight?",
                plan = Plan(PlanActivity.SPORT, "Allez Up, Saint-Henri", System.currentTimeMillis() + 4 * 60 * min, 6, listOf(person("u6")!!), false))),
            "g5" to listOf(base("gp2", "u3", 26 * 60 * min, 30).copy(kind = PostKind.PHOTO, imageUrl = photo("city"), caption = "Golden hour on Sainte-Catherine")),
        )
    )

    override suspend fun groups(): List<InterestGroup> = groupData.value.sortedByDescending { it.isMember }
    override suspend fun group(id: String) = groupData.value.firstOrNull { it.id == id }

    override suspend fun setMember(groupId: String, join: Boolean) {
        val m = _me.value
        groupData.value = groupData.value.map {
            if (it.id == groupId && it.isMember != join) it.copy(
                isMember = join, memberCount = it.memberCount + if (join) 1 else -1,
                members = if (join && m != null) listOf(m) + it.members else it.members.filterNot { p -> p.id == m?.id },
            ) else it
        }
    }

    override suspend fun groupPosts(groupId: String): List<FeedPost> = groupFeed.value[groupId].orEmpty()

    override suspend fun createGroup(name: String, emoji: String, description: String): Result<InterestGroup> {
        val m = _me.value ?: return Result.failure(IllegalStateException())
        val g = InterestGroup(UUID.randomUUID().toString(), name.trim(), emoji, description.trim(), 1, true, listOf(m))
        groupData.value = listOf(g) + groupData.value
        return Result.success(g)
    }

    private val myLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override suspend fun pushLocation(lat: Double, lng: Double) { myLocation.value = lat to lng }
    override suspend fun clearLocation() { myLocation.value = null }

    // Real Concordia SGW / Loyola spots so the demo map looks right.
    private val spots = mapOf(
        "u1" to (45.49680 to -73.57790), // Webster Library
        "u2" to (45.49560 to -73.57820), // EV building
        "u3" to (45.49725 to -73.57890), // Hall building
        "u6" to (45.49530 to -73.57905), // JMSB
        "u5" to (45.49745 to -73.57955), // Hall, west side
        "u8" to (45.45820 to -73.64050), // Loyola
        "u10" to (45.49610 to -73.57700), // Guy-Concordia metro
    )

    override fun mapPins(): Flow<List<MapPin>> {
        val ticker = flow { var t = 0; while (true) { emit(t++); delay(4_000) } }
        return combine(ticker, connections) { t, conns ->
            spots.mapNotNull { (id, pos) ->
                val pr = person(id) ?: return@mapNotNull null
                val kind = when {
                    conns[id] == ConnectionState.CONNECTED -> PinKind.FRIEND
                    shared.containsKey(id) -> PinKind.CLASSMATE
                    else -> return@mapNotNull null
                }
                val a = (t + id.hashCode()) * 0.6
                MapPin(pr, pos.first + sin(a) * 0.00004, pos.second + cos(a) * 0.00005, now - (id.hashCode() and 0xF) * min, kind)
            }
        }
    }

    companion object {
        private val photos = mapOf(
            "library" to "1521587760476-6c12a4b040da", "hackathon" to "1531482615713-2afd69097998",
            "snow" to "1491002052546-bf38f186af56", "robot" to "1485827404703-89b55fcc595e",
            "cinema" to "1489599849927-2ee91cede3ba", "city" to "1477959858617-67f85cf4f1df",
            "campus" to "1541339907198-e08756dedf3f",
        )
        private fun photo(key: String) = "https://images.unsplash.com/photo-${photos[key] ?: photos.getValue("campus")}?w=900&q=80"

        /** Stable 32-bit code per demo profile. */
        fun demoCode(seed: String): Long = (seed.hashCode().toLong() and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }
    }
}
