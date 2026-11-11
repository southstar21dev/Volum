package dev.southstar21.volum.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.Backdrop
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.southstar21.volum.data.AppContainer
import dev.southstar21.volum.data.UserSession
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.platform.LocalNativeViewsSettled
import dev.southstar21.volum.platform.SystemNavbarState
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.platform.platformNavbar
import dev.southstar21.volum.ui.auth.AuthScreen
import dev.southstar21.volum.ui.auth.OnboardingScreen
import dev.southstar21.volum.ui.components.EdgeBlur
import dev.southstar21.volum.ui.components.GlassRoot
import dev.southstar21.volum.ui.components.glassEffects
import dev.southstar21.volum.ui.components.LocalBackdrop
import dev.southstar21.volum.ui.components.LocalOverlayHost
import dev.southstar21.volum.ui.components.OverlayHost
import dev.southstar21.volum.ui.components.OverlayLayer
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.liquidRim
import dev.southstar21.volum.ui.components.liquidSheen
import dev.southstar21.volum.ui.components.readableWidth
import dev.southstar21.volum.ui.history.HistoryScreen
import dev.southstar21.volum.ui.history.WorkoutDetailScreen
import dev.southstar21.volum.ui.lifts.ExerciseDetailScreen
import dev.southstar21.volum.ui.lifts.LiftsScreen
import dev.southstar21.volum.ui.liquid.LiquidTabs
import dev.southstar21.volum.ui.profile.PairPartnerScreen
import dev.southstar21.volum.ui.profile.ProfileScreen
import dev.southstar21.volum.ui.routines.RoutineEditorScreen
import dev.southstar21.volum.ui.session.PartnerSessionScreen
import dev.southstar21.volum.ui.session.SessionScreen
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.Entrance
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.LocalEntrance
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.VolumTheme
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.today.TodayScreen
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
 */
@Stable
class Navigator private constructor(initial: List<Screen>) {
    val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0) + 1

    /** Whether the last move went deeper (a push or a tab pick) rather than back. */
    var forward by mutableStateOf(true)
        private set

    val tab: Tab get() = (stack.first().route as Route.Root).tab
    val atRoot: Boolean get() = stack.size <= 1
    val top: Route get() = stack.last().route

    fun push(route: Route) { forward = true; stack.add(Screen(route, nextId++)) }
    fun pop() { if (stack.size > 1) { forward = false; stack.removeAt(stack.lastIndex) } }
    fun replace(route: Route) { forward = true; if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(Screen(route, nextId++)) }
    /**
     * A tab's root always has the same id. Coming back to a tab while the page transition away from
     * it is still running then picks up the page that's leaving, instead of composing a second copy
     * of it beside the first: two copies both claimed the tab's saved state and crashed the app.
     */
    fun select(t: Tab) { forward = true; stack.clear(); stack.add(Screen(Route.Root(t), rootId(t))) }
    fun popToRoot() { forward = false; while (stack.size > 1) stack.removeAt(stack.lastIndex) }

    companion object {
        private val serializer = ListSerializer(Screen.serializer())

        /** Tab roots' ids, negative so they never meet a pushed screen's. */
        private fun rootId(t: Tab) = -1 - t.ordinal

        fun initial() = Navigator(listOf(Screen(Route.Root(Tab.Today), rootId(Tab.Today))))

        val Saver: Saver<Navigator, String> = Saver(
            save = { VolumJson.encodeToString(serializer, it.stack.toList()) },
            restore = { saved ->
                runCatching { VolumJson.decodeFromString(serializer, saved) }.getOrNull()
                    ?.takeIf { it.firstOrNull()?.route is Route.Root }
                    ?.let(::Navigator)
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
                // A sheet is modal: while one is up, screen readers don't wander into the screen under it.
                Box(Modifier.fillMaxSize().then(if (overlays.isShowing) Modifier.clearAndSetSemantics {} else Modifier)) {
                    val gate = when {
                        !ready -> Gate.Loading
                        session == null -> Gate.Auth
                        !prefs.onboardingDone -> Gate.Onboarding
                        else -> Gate.Main
                    }
                    // The app leaving as it signs out still needs the session it was showing.
                    var lastSession by remember { mutableStateOf(session) }
                    if (session != null) lastSession = session
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
                OverlayLayer(overlays)
            }
        }
    }
}

private enum class Gate { Loading, Auth, Onboarding, Main }

/** Sync polls quickly during a workout only while the app is on screen. */
@Composable
private fun ForegroundSync(session: UserSession?) {
    if (session == null) return
    LifecycleStartEffect(session) {
        session.sync.foreground = true
        onStopOrDispose { session.sync.foreground = false }
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
        LaunchedEffect(nav.tab, shown) {
            navbar.update(
                SystemNavbarState(
                    selectedIndex = nav.tab.ordinal,
                    showAction = true,
                    hidden = !shown,
                    accentArgb = argb(c.accent),
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
        val content = rememberLayerBackdrop()
        val density = LocalDensity.current
        val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val navBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        Box(Modifier.fillMaxSize()) {
            // The screens are recorded so the edge strips can blur what scrolls under them.
            Box(Modifier.fillMaxSize().layerBackdrop(content)) {
                // Back (Android's predictive back, iOS's edge swipe) drives the pop transition itself.
                NavDisplay(
                    backStack = nav.stack,
                    onBack = { nav.pop() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                    transitionSpec = { pushTransition(forward = true) },
                    popTransitionSpec = { pushTransition(forward = false) },
                    predictivePopTransitionSpec = { pushTransition(forward = false) },
                    entryProvider = { screen ->
                        NavEntry(screen, contentKey = screen.id) { ScreenContent(it, nav, tabStates, overlays) }
                    },
                )
            }
            EdgeBlur(content, top = true, height = statusBar + 18.dp, solid = statusBar, modifier = Modifier.align(Alignment.TopCenter))
            BottomStrip(content, atRoot, navBar, Modifier.align(Alignment.BottomCenter))
            if (navbar == null) {
                AndroidDock(
                    visible = dockVisible,
                    selected = nav.tab,
                    showAction = true,
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
private fun BottomStrip(content: Backdrop, atRoot: Boolean, navBar: Dp, modifier: Modifier) {
    val dock by animateFloatAsState(
        if (atRoot) 1f else 0f,
        tween(Durations.Page, easing = Easings.Standard),
        label = "bottomStrip",
    )
    EdgeBlur(
        content, top = false,
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
    CompositionLocalProvider(
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
 * about halfway off, instead of showing through it from the first pixel.
 */
private fun pushTransition(forward: Boolean): ContentTransform {
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

/** Android: a Kyant liquid-glass dock with the four tabs, plus a separate round + for new routines. */
@Composable
private fun AndroidDock(visible: Boolean, selected: Tab, showAction: Boolean, onSelect: (Tab) -> Unit, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = Volum.colors
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        alpha.animateTo(if (visible) 1f else 0f, if (visible) tween(Durations.Enter, easing = Easings.Enter) else tween(Durations.Exit, easing = Easings.Exit))
    }
    // Read through derivedStateOf: the fade itself runs in the graphics layer below, so the dock
    // (and its liquid tabs) recompose only when it fully hides or shows, not on every fade frame.
    val gone by remember { androidx.compose.runtime.derivedStateOf { alpha.value == 0f } }
    if (gone && !visible) return
    // The iOS bar's filled symbols, on every tab.
    val tabIcons = remember { listOf(Icons.TabHome, Icons.TabHistory, Icons.TabLifts, Icons.TabProfile) }
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .graphicsLayer { this.alpha = alpha.value; translationY = (1f - alpha.value) * 40.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiquidTabs(
            count = Tab.entries.size,
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(Tab.entries[it]) },
            backdrop = LocalBackdrop.current,
            accent = c.accent,
            // What the selection drop shows the pane as: a solid stand-in for the glass below.
            container = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.66f),
            // The mockups' `.lg` glass: a white gradient (not a dark tint) with its rim and sheen.
            surface = { shape ->
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.liquidTop, c.liquidBottom)))
                liquidSheen(shape, c)
                liquidRim(shape, c)
            },
            // iOS's selected-tab platter: the bar's glass a shade darker, tinted by what's behind it
            // (sage here), over the near-white stand-in the selection draws on.
            selection = androidx.compose.ui.graphics.Color(0xFF5F7F45).copy(alpha = 0.24f),
            modifier = Modifier.weight(1f),
            height = 62.dp,
            frosted = !glassEffects(),
            onCross = { hapticTick() },
        ) { index, tinted ->
            // As on iOS, only the colour (and the icon's fill) changes: the label keeps its weight.
            val ink = if (tinted) c.accent else c.ink
            Icon(tabIcons[index], ink, size = 26.dp)
            Txt(Tab.entries[index].label, size = T.Tiny, weight = Bold, color = ink, maxLines = 1)
        }
        if (showAction) {
            Box(
                Modifier.size(62.dp).tappable(haptic = true, onClick = onAction).liquidGlass(CircleShape).semantics { contentDescription = "New routine" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Plus, c.ink, size = 24.dp) }
        }
    }
}

