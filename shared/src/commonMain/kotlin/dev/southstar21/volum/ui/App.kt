package dev.southstar21.volum.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.southstar21.volum.ui.components.clearDecodedPhotos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dev.southstar21.volum.data.AppContainer
import dev.southstar21.volum.data.UserSession
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.platform.LocalNativeViewsSettled
import dev.southstar21.volum.platform.SystemNavbarState
import dev.southstar21.volum.platform.platformNavbar
import dev.southstar21.volum.ui.auth.AuthScreen
import dev.southstar21.volum.ui.auth.OnboardingScreen
import dev.southstar21.volum.ui.components.EdgeBlur
import dev.southstar21.volum.ui.components.GlassRoot
import dev.southstar21.volum.ui.components.LocalOverlayHost
import dev.southstar21.volum.ui.components.OverlayHost
import dev.southstar21.volum.ui.components.OverlayLayer
import dev.southstar21.volum.ui.components.readableWidth
import dev.southstar21.volum.ui.history.HistoryScreen
import dev.southstar21.volum.ui.history.WorkoutDetailScreen
import dev.southstar21.volum.ui.lifts.ExerciseDetailScreen
import dev.southstar21.volum.ui.lifts.LiftsScreen
import dev.southstar21.volum.ui.profile.PairPartnerScreen
import dev.southstar21.volum.ui.profile.ProfileScreen
import dev.southstar21.volum.ui.routines.RoutineEditorScreen
import dev.southstar21.volum.ui.session.PartnerSessionScreen
import dev.southstar21.volum.ui.session.RetainLiveHolds
import dev.southstar21.volum.ui.session.SessionScreen
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.Entrance
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.LocalEntrance
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.VolumTheme
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.today.TodayScreen
import dev.southstar21.volum.ui.update.UpdateRequiredScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
enum class Tab(val label: String) { Today("Today"), History("History"), Lifts("Lifts"), Profile("Profile") }

@Serializable
sealed interface Route {
    @Serializable data class Root(val tab: Tab) : Route
    @Serializable data class RoutineEditor(val routineId: String?) : Route
    @Serializable data class Session(val workoutId: String) : Route
    @Serializable data class PartnerSession(val workoutId: String) : Route
    @Serializable data class WorkoutDetail(val workoutId: String) : Route
    @Serializable data class ExerciseDetail(val exerciseId: String) : Route
    @Serializable data object PairPartner : Route
}

/** One screen on the stack: its route and an id unique on the stack, so the same route can be open twice. */
@Serializable
data class Screen(val route: Route, val id: Int)

/**
 * The back stack: a tab's root at the bottom, pushed screens above it. Saved with the activity (and
 * across process death), so a recreated app comes back on the same screen.
 *
 * Each screen sees it through [forScreen]: a page stays composed and tappable for its whole exit
 * transition, so a second tap on a button that pushed or popped would otherwise move again (two
 * Session screens, a double pop). Once a screen is no longer on top, its pushes and pops are dropped.
 */
@Stable
class Navigator private constructor(private val core: Core, private val owner: Int?) {
    private class Core(initial: List<Screen>) {
        val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
        var nextId = (initial.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0) + 1
        var forward by mutableStateOf(true)
    }

    val stack: SnapshotStateList<Screen> get() = core.stack

    /** Whether the last move went deeper (a push or a tab pick) rather than back. */
    val forward: Boolean get() = core.forward

    val tab: Tab get() = (stack.first().route as Route.Root).tab
    val atRoot: Boolean get() = stack.size <= 1
    val top: Route get() = stack.last().route

    /** False for a screen that has already been covered or popped. */
    private val onTop: Boolean get() = owner == null || stack.lastOrNull()?.id == owner

    /** Pushes [route], unless this screen is no longer on top or [route] is already showing. */
    fun push(route: Route): Boolean {
        if (!onTop || top == route) return false
        core.forward = true
        stack.add(Screen(route, core.nextId++))
        return true
    }

    /** Pops the top screen, unless this screen is no longer on top. */
    fun pop(): Boolean {
        if (!onTop || stack.size <= 1) return false
        core.forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /**
     * A tab's root always has the same id. Coming back to a tab while the page transition away from
     * it is still running then picks up the page that's leaving, instead of composing a second copy
     * of it beside the first: two copies both claimed the tab's saved state and crashed the app.
     */
    fun select(t: Tab) { core.forward = true; stack.clear(); stack.add(Screen(Route.Root(t), rootId(t))) }

    /** The same back stack, as seen by the screen with [id]. */
    fun forScreen(id: Int): Navigator = Navigator(core, id)

    companion object {
        private val serializer = ListSerializer(Screen.serializer())

        /** Tab roots' ids, negative so they never meet a pushed screen's. */
        private fun rootId(t: Tab) = -1 - t.ordinal

        fun initial() = Navigator(Core(listOf(Screen(Route.Root(Tab.Today), rootId(Tab.Today)))), owner = null)

        val Saver: Saver<Navigator, String> = Saver(
            save = { VolumJson.encodeToString(serializer, it.stack.toList()) },
            restore = { saved ->
                runCatching { VolumJson.decodeFromString(serializer, saved) }.getOrNull()
                    ?.takeIf { it.firstOrNull()?.route is Route.Root }
                    ?.let { Navigator(Core(it), owner = null) }
            },
        )
    }
}

val LocalApp = staticCompositionLocalOf<AppContainer> { error("No app") }
val LocalSession = staticCompositionLocalOf<UserSession> { error("No session") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("No navigator") }
/** How much bottom space the floating tab bar covers, so lists can scroll clear of it. Set per screen. */
val LocalDockPadding = staticCompositionLocalOf { 0.dp }

/** The whole app, hosted by MainActivity on Android and MainViewController on iOS. */
@Composable
fun VolumApp(app: AppContainer) {
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val overlays = remember { OverlayHost() }
    VolumTheme {
        CompositionLocalProvider(LocalApp provides app, LocalOverlayHost provides overlays) {
            GlassRoot(solid = prefs.solidBackground) {
                val ready by app.ready.collectAsStateWithLifecycle()
                val session by app.session.collectAsStateWithLifecycle()
                val updateRequired by app.updateRequired.collectAsStateWithLifecycle()
                LifecycleStartEffect(app) {
                    app.checkUpdateRequired()
                    onStopOrDispose {}
                }
                // A sheet is modal: while one is up, screen readers don't wander into the screen under it.
                Box(Modifier.fillMaxSize().then(if (overlays.isShowing) Modifier.clearAndSetSemantics {} else Modifier)) {
                    val gate = when {
                        !ready -> Gate.Loading
                        updateRequired != null -> Gate.Update
                        session == null -> Gate.Auth
                        !prefs.onboardingDone -> Gate.Onboarding
                        else -> Gate.Main
                    }
                    // The app leaving as it signs out still needs the session it was showing.
                    var lastSession by remember { mutableStateOf(session) }
                    if (session != null) lastSession = session
                    // Likewise the update screen, as it leaves once the server accepts this build again.
                    var lastUpdate by remember { mutableStateOf(updateRequired) }
                    if (updateRequired != null) lastUpdate = updateRequired
                    // Log in, onboarding and the app cross over: the old one fades out first, then the new
                    // one fades in (with its own staggered entrance), so they never overlap.
                    androidx.compose.animation.AnimatedContent(
                        gate,
                        transitionSpec = {
                            fadeIn(tween(Durations.Enter, delayMillis = Durations.Exit, easing = LinearOutSlowInEasing)) togetherWith
                                fadeOut(tween(Durations.Exit, easing = LinearEasing)) using
                                androidx.compose.animation.SizeTransform(clip = false)
                        },
                        label = "gate",
                    ) { g ->
                        // Native controls (iOS switchers) can't fade with the page, so they wait it out.
                        val moving = transition.currentState != EnterExitState.Visible || transition.targetState != EnterExitState.Visible
                        CompositionLocalProvider(LocalNativeViewsSettled provides (LocalNativeViewsSettled.current && !moving)) {
                            when (g) {
                                Gate.Loading -> Unit
                                Gate.Update -> {
                                    LaunchedEffect(Unit) { platformNavbar?.detach() }
                                    Entered { UpdateRequiredScreen(lastUpdate?.minVersion) }
                                }
                                Gate.Auth -> {
                                    LaunchedEffect(Unit) { platformNavbar?.detach() }
                                    Entered { AuthScreen() }
                                }
                                Gate.Onboarding -> {
                                    LaunchedEffect(Unit) { platformNavbar?.detach() }
                                    Entered { OnboardingScreen() }
                                }
                                Gate.Main -> lastSession?.let { current ->
                                    CompositionLocalProvider(LocalSession provides current) {
                                        key(current.user.id) { MainShell(overlays) }
                                    }
                                }
                            }
                        }
                    }
                }
                ForegroundSync(session)
                // Decoded avatars belong to the account that showed them: a sign-out or switch drops them.
                LaunchedEffect(session?.user?.id) { clearDecodedPhotos() }
                RetainLiveHolds(session)
                OverlayLayer(overlays)
            }
        }
    }
}

private enum class Gate { Loading, Update, Auth, Onboarding, Main }

/**
 * Sync polls quickly during a workout only while the app is on screen. Coming back to the foreground
 * also checks the sign-in token's age: the periodic check can't run while the process is suspended.
 */
@Composable
private fun ForegroundSync(session: UserSession?) {
    if (session == null) return
    LifecycleStartEffect(session) {
        session.sync.foreground = true
        session.partner.setForeground(true)
        session.app.refreshAuthIfStale()
        onStopOrDispose {
            session.sync.foreground = false
            session.partner.setForeground(false)
            // Writes are batched for a moment (see LocalStore), and a backgrounded process can die at
            // any time: write what is pending now. On the app's scope, which outlives this effect; it
            // can't clash with a sign-out's own final write (the store serialises them).
            session.app.scope.launch { session.store.flushNow() }
        }
    }
}

@Composable
private fun MainShell(overlays: OverlayHost) {
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator.initial() }
    val c = Volum.colors
    val atRoot = nav.atRoot
    val dockVisible = atRoot && !overlays.isShowing
    // The tab roots' saved state (scroll positions), kept while another tab is showing.
    val tabStates = rememberSaveableStateHolder()
    val navbar = platformNavbar

    // iOS: the real UITabBarController draws the bar; keep it in step with Compose.
    if (navbar != null) {
        // The system bar can't fade with the app as it arrives after log in: it shows once that's done.
        val shown = dockVisible && LocalNativeViewsSettled.current
        val accent = argb(c.accent)
        LaunchedEffect(nav.tab, shown, accent) {
            navbar.update(
                SystemNavbarState(
                    selectedIndex = nav.tab.ordinal,
                    hidden = !shown,
                    accentArgb = accent,
                ),
            )
        }
        DisposableEffect(navbar) {
            navbar.onSelect = { index -> if (nav.tab.ordinal == index && nav.atRoot) Unit else nav.select(Tab.entries[index]) }
            navbar.onAction = { nav.push(Route.RoutineEditor(null)) }
            onDispose {
                navbar.onSelect = null
                navbar.onAction = null
                navbar.detach()
            }
        }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        val density = LocalDensity.current
        val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val navBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val reduce = LocalReduceMotion.current
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Back (Android's predictive back, iOS's edge swipe) drives the pop transition itself. It
                // pops through the shell's navigator: by the time it lands the gesture has already played
                // the transition, so it must not be refused.
                NavDisplay(
                    backStack = nav.stack,
                    onBack = { nav.pop() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                    transitionSpec = { pushTransition(forward = true, reduce) },
                    popTransitionSpec = { pushTransition(forward = false, reduce) },
                    predictivePopTransitionSpec = { pushTransition(forward = false, reduce) },
                    entryProvider = { screen ->
                        NavEntry(screen, contentKey = screen.id) { ScreenContent(it, nav, tabStates, overlays) }
                    },
                )
            }
            EdgeBlur(top = true, height = statusBar + 18.dp, solid = statusBar, modifier = Modifier.align(Alignment.TopCenter))
            BottomStrip(atRoot, navBar, Modifier.align(Alignment.BottomCenter))
            if (navbar == null) {
                AndroidDock(
                    visible = dockVisible,
                    selected = nav.tab,
                    onSelect = { nav.select(it) },
                    onAction = { nav.push(Route.RoutineEditor(null)) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * The frosted strip along the bottom. At the tab roots the dock floats over the list, so it's tall;
 * elsewhere only the system bar needs it. It grows and shrinks over the page transition rather than
 * jumping at its first frame, which showed as a flash under the page still on screen. Its own
 * composable, so the per-frame height recomposes only the strip, not the shell.
 */
@Composable
private fun BottomStrip(atRoot: Boolean, navBar: Dp, modifier: Modifier) {
    val dock by animateFloatAsState(
        if (atRoot) 1f else 0f,
        if (LocalReduceMotion.current) snap() else tween(Durations.Page, easing = Easings.Standard),
        label = "bottomStrip",
    )
    EdgeBlur(
        top = false,
        height = navBar + lerp(12.dp, 104.dp, dock),
        solid = navBar + lerp(0.dp, 64.dp, dock),
        modifier = modifier,
    )
}

@Composable
private fun ScreenContent(screen: Screen, nav: Navigator, tabStates: SaveableStateHolder, overlays: OverlayHost) {
    // Staggered rise-in when a screen is pushed or a tab picked; not when popping back to it, nor for
    // the screen a back swipe reveals underneath.
    val entrance = remember { Entrance(animate = nav.forward && nav.stack.lastOrNull()?.id == screen.id) }
    val transition = LocalNavAnimatedContentScope.current.transition
    val still by remember(transition) {
        derivedStateOf { transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible }
    }
    // Native controls float above Compose: they step aside while the page moves or a sheet covers it.
    val settled = LocalNativeViewsSettled.current && still && !overlays.isShowing
    val route = screen.route
    val screenNav = remember(nav, screen.id) { nav.forScreen(screen.id) }
    CompositionLocalProvider(
        LocalNav provides screenNav,
        LocalEntrance provides entrance,
        LocalNativeViewsSettled provides settled,
        LocalDockPadding provides if (route is Route.Root) 112.dp else 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().readableWidth()) {
                if (route is Route.Root) tabStates.SaveableStateProvider("tab-${route.tab.name}") { RouteContent(route) }
                else RouteContent(route)
            }
        }
    }
}

@Composable
private fun Entered(content: @Composable () -> Unit) {
    val entrance = remember { Entrance(animate = true) }
    CompositionLocalProvider(LocalEntrance provides entrance) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().readableWidth()) { content() }
        }
    }
}

private fun argb(color: androidx.compose.ui.graphics.Color): Int {
    val a = (color.alpha * 255).toInt()
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Pages sit on the shared colour field with no backdrop of their own, so two of them visible at once
 * read as one jumbled page. The fades are therefore sequenced rather than crossed: the page being left
 * fades out first, and the one arriving only fades in once the other is mostly gone. A back swipe seeks
 * this same transition with the finger, so the page underneath stays hidden until the top page is
 * about halfway off, instead of showing through it from the first pixel. With Reduce Motion on, pages
 * only fade, in the same order.
 */
private fun pushTransition(forward: Boolean, reduce: Boolean): ContentTransform {
    if (reduce) return fadeIn(tween(Durations.Enter, delayMillis = Durations.Exit)) togetherWith fadeOut(tween(Durations.Exit))
    val page = Durations.Page
    val slide = tween<IntOffset>(page, easing = Easings.Standard)
    val scale = tween<Float>(page, easing = Easings.Standard)
    return if (forward) {
        // The new page slides over from the right; the old one starts fading at once.
        val leave = tween<Float>(page * 2 / 5, easing = LinearEasing)
        val arrive = tween<Float>(page / 2, delayMillis = page / 5, easing = LinearOutSlowInEasing)
        (slideInHorizontally(slide) { it } + fadeIn(arrive)) togetherWith
            (slideOutHorizontally(slide) { -it / 4 } + scaleOut(scale, targetScale = 0.94f) + fadeOut(leave))
    } else {
        // The top page keeps its full opacity for the first part of the slide, then fades; the page
        // underneath appears only after that.
        val leave = tween<Float>(page * 2 / 5, delayMillis = page / 4, easing = LinearEasing)
        val arrive = tween<Float>(page * 2 / 5, delayMillis = page * 9 / 20, easing = LinearOutSlowInEasing)
        (slideInHorizontally(slide) { -it / 4 } + scaleIn(scale, initialScale = 0.94f) + fadeIn(arrive)) togetherWith
            (slideOutHorizontally(slide) { it } + fadeOut(leave))
    }
}

@Composable
private fun RouteContent(route: Route) {
    when (route) {
        is Route.Root -> when (route.tab) {
            Tab.Today -> TodayScreen()
            Tab.History -> HistoryScreen()
            Tab.Lifts -> LiftsScreen()
            Tab.Profile -> ProfileScreen()
        }
        is Route.RoutineEditor -> RoutineEditorScreen(route.routineId)
        is Route.Session -> SessionScreen(route.workoutId)
        is Route.PartnerSession -> PartnerSessionScreen(route.workoutId)
        is Route.WorkoutDetail -> WorkoutDetailScreen(route.workoutId)
        is Route.ExerciseDetail -> ExerciseDetailScreen(route.exerciseId)
        Route.PairPartner -> PairPartnerScreen()
    }
}

/** Android's liquid-glass dock with the four tabs and a round + for new routines; iOS has the native tab bar. */
@Composable
internal expect fun AndroidDock(visible: Boolean, selected: Tab, onSelect: (Tab) -> Unit, onAction: () -> Unit, modifier: Modifier = Modifier)
