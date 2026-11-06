package dev.southstar21.volum.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.IntOffset
import dev.southstar21.volum.ui.components.neuToggle
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.LocalEntrance
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.resources.Res
import dev.southstar21.volum.resources.app_icon
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.components.Segmented
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.LabeledField
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.NeuField
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.components.ErrorText
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.ui.components.neuIn
import dev.southstar21.volum.ui.components.neuOut
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.celebrate
import dev.southstar21.volum.ui.theme.popIn
import dev.southstar21.volum.ui.theme.riseIn
import dev.southstar21.volum.ui.theme.textStyle
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.formatWeightValue
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.background

@Composable
fun Wordmark(size: androidx.compose.ui.unit.TextUnit = 58.sp) {
    BasicText(
        "Volum",
        style = textStyle(size, FontWeight.SemiBold, Volum.colors.accent, tracking = (-0.01).em).copy(fontFamily = Volum.fonts.wordmark),
    )
}

@Composable
fun AppIcon(size: androidx.compose.ui.unit.Dp) {
    Image(
        painterResource(Res.drawable.app_icon),
        contentDescription = "Volum",
        modifier = Modifier.size(size).shadow(18.dp, RoundedCornerShape(size * 0.23f), ambientColor = Volum.colors.iconShadow, spotColor = Volum.colors.iconShadow),
    )
}

/** Log in and create account in one place: brand on top, a glass sheet with a switch below. */
@Composable
fun AuthScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Volum.colors

    fun submit() {
        error = null
        if (email.isBlank() || password.isBlank()) { error = "Enter your email and password."; return }
        if (mode == 1 && password.length < 8) { error = "Use at least 8 characters."; return }
        if (mode == 1 && password != confirm) { error = "Passwords don't match."; return }
        busy = true
        scope.launch {
            try {
                if (mode == 0) app.login(email, password) else app.register(email, password)
            } catch (e: PbException) {
                error = when {
                    e.status == 0 -> "You're offline. Connect to sign in."
                    mode == 0 && e.status == 400 -> "Wrong email or password."
                    else -> e.data?.values?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("message")?.toString()?.trim('"') } ?: e.message
                }
            } finally {
                busy = false
            }
        }
    }

    // The card sits at the foot of the screen. While the keyboard is up the page scrolls to its end
    // with every step of the keyboard's slide, so the fields and the button ride above it.
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(scroll) {
        snapshotFlow { (ime.getBottom(density) > 0) to scroll.maxValue }.collect { (up, max) -> if (up) scroll.scrollTo(max) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight)) {
            TopInset(64.dp)
            Column(Modifier.fillMaxWidth().riseIn(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.popIn(120)) { AppIcon(84.dp) }
                Spacer(Modifier.height(20.dp))
                Wordmark()
                Spacer(Modifier.height(10.dp))
                Mute("Your strength training log", size = T.Subhead, weight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .riseIn(48f)
                    .fillMaxWidth()
                    .liquidGlass(RoundedCornerShape(42.dp), tint = c.sheet)
                    .padding(16.dp),
            ) {
                Segmented(listOf("Log in", "Create account"), mode, { mode = it; error = null }, Modifier.fillMaxWidth(), height = 44.dp, fill = true)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(vertical = 4.dp)) {
                    LabeledField("Email", email, { email = it }, keyboardType = KeyboardType.Email, contentType = ContentType.EmailAddress + ContentType.Username)
                    Divider()
                    LabeledField(
                        "Password", password, { password = it }, keyboardType = KeyboardType.Password, password = true,
                        contentType = if (mode == 0) ContentType.Password else ContentType.NewPassword,
                        imeAction = if (mode == 0) ImeAction.Go else ImeAction.Next, onDone = if (mode == 0) ({ submit() }) else null,
                    )
                    if (mode == 1) {
                        Divider()
                        LabeledField("Confirm password", confirm, { confirm = it }, keyboardType = KeyboardType.Password, password = true, imeAction = ImeAction.Go, contentType = ContentType.NewPassword, onDone = { submit() })
                    }
                }
                if (mode == 1 && error == null) {
                    Mute("At least 8 characters. Routines sync across your devices; finished workouts stay private, even from a partner.", Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp), size = T.Small)
                }
                error?.let { ErrorText(it, Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp), weight = Semibold) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (mode == 0) "Log in" else "Create account", height = 58.dp, busy = busy) { submit() }
            }
        }
    }
}

/** Two short steps: a display name, then the unit you type in. */
@Composable
fun OnboardingScreen() {
    val app = LocalApp.current
    val user by app.user.collectAsStateWithLifecycle()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(user?.name.orEmpty()) }
    var unit by remember { mutableStateOf(prefs.unit) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Volum.colors

    fun finish() {
        busy = true
        error = null
        scope.launch {
            try {
                if (name.trim() != user?.name) app.setDisplayName(name.trim())
                app.updatePrefs { it.copy(unit = unit, onboardingDone = true) }
            } catch (e: PbException) {
                error = if (e.status == 0) "You're offline. Connect to save your name." else e.message
            } finally {
                busy = false
            }
        }
    }

    dev.southstar21.volum.ui.components.BackHandler(enabled = step == 1) { step = 0 }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            TopInset(14.dp)
            // A fixed-height header: the back button fades in at the start without moving the progress,
            // which stays centred on both steps.
            Box(Modifier.fillMaxWidth().height(44.dp).riseIn()) {
                androidx.compose.animation.AnimatedVisibility(
                    step == 1, Modifier.align(Alignment.CenterStart),
                    enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                ) {
                    CircleButton(Icons.ChevronLeft, "Back") { step = 0 }
                }
                StepProgress(step, count = 2, Modifier.align(Alignment.Center))
            }
            // The steps slide sideways (forward to the left, back to the right) and fade, in sequence so
            // the two never sit on top of each other. No clip: the neumorphic shadows reach past the
            // content's bounds, and a clipped transition cut them off and then snapped them back on.
            val entrance = LocalEntrance.current
            var stepped by remember { mutableStateOf(false) }
            AnimatedContent(
                step,
                Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    val slide = tween<IntOffset>(Durations.Page, easing = Easings.Standard)
                    (slideInHorizontally(slide) { dir * it / 3 } + fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant, easing = LinearOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(slide) { -dir * it / 3 } + fadeOut(tween(Durations.Exit, easing = LinearEasing))) using
                        SizeTransform(clip = false)
                },
                label = "onboarding",
            ) { s ->
                // Only the first step's first showing rises in, staggered; afterwards the slide is the motion.
                LaunchedEffect(s) { if (s != 0) stepped = true }
                CompositionLocalProvider(LocalEntrance provides if (stepped) null else entrance) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (s == 0) {
                        Spacer(Modifier.height(40.dp))
                        Txt("What should we\ncall you?", Modifier.riseIn(), size = T.Hero, weight = ExtraBold, tracking = (-0.035).em, lineHeight = 40.sp)
                        Spacer(Modifier.height(10.dp))
                        Mute("Your training partner sees this name.", Modifier.riseIn(), size = T.BodyLarge)
                        Spacer(Modifier.height(44.dp))
                        Box(Modifier.fillMaxWidth().riseIn(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(128.dp).neuOut(CircleShape), contentAlignment = Alignment.Center) {
                                // The initial changes as you type: a quick cross-fade, not a jump.
                                androidx.compose.animation.Crossfade(name.trim().firstOrNull()?.uppercase() ?: "?", animationSpec = tween(Durations.Fade), label = "initial") { letter ->
                                    Txt(letter, size = T.Monogram, weight = ExtraBold, color = c.accent)
                                }
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                        Box(Modifier.riseIn()) {
                            NeuField(name, { name = it }, placeholder = "Your name", size = T.Headline, align = TextAlign.Center, height = 70.dp, imeAction = ImeAction.Done, label = "Your name", onDone = { if (name.isNotBlank()) step = 1 })
                        }
                    } else {
                        Spacer(Modifier.height(30.dp))
                        Txt("How do you\nload the bar?", size = T.Hero, weight = ExtraBold, tracking = (-0.035).em, lineHeight = 40.sp)
                        Spacer(Modifier.height(10.dp))
                        Mute("Pick the unit you type in. Every weight still shows both.", size = T.BodyLarge)
                        Spacer(Modifier.height(36.dp))
                        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            UnitTile(WeightUnit.Kg, "Kilograms", unit == WeightUnit.Kg, Modifier.weight(1f)) { unit = WeightUnit.Kg }
                            UnitTile(WeightUnit.Lb, "Pounds", unit == WeightUnit.Lb, Modifier.weight(1f)) { unit = WeightUnit.Lb }
                        }
                        Spacer(Modifier.height(30.dp))
                        val sample = if (unit == WeightUnit.Kg) 100.0 else 225.0
                        Row(
                            Modifier.fillMaxWidth().neuOut(RoundedCornerShape(26.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Mute("You'll see", size = T.Caption, weight = Semibold)
                                dev.southstar21.volum.ui.components.NumberUnit(formatWeightValue(sample), unit.label, size = T.Entry)
                            }
                            Icon(Icons.Swap, c.mute, size = 20.dp)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Mute("and", size = T.Caption, weight = Semibold)
                                dev.southstar21.volum.ui.components.NumberUnit(formatWeightValue(convert(sample, unit, unit.other)), unit.other.label, size = T.Entry)
                            }
                        }
                    }
                }
                }
            }
            error?.let { ErrorText(it, Modifier.padding(bottom = 10.dp), weight = Semibold) }
            // One button for both steps, so moving on changes its label instead of swapping in a new button.
            Box(Modifier.navigationBarsPadding().padding(bottom = 24.dp).riseIn(32f)) {
                PrimaryButton(
                    if (step == 0) "Continue" else "Start training",
                    height = 58.dp,
                    enabled = step == 1 || name.isNotBlank(),
                    icon = if (step == 0) Icons.ArrowRight else null,
                    busy = busy,
                ) { if (step == 0) step = 1 else finish() }
            }
        }
    }
}

/** The onboarding progress: one capsule per step, filled up to the current one, and "n of m". */
@Composable
private fun StepProgress(step: Int, count: Int, modifier: Modifier = Modifier) {
    val c = Volum.colors
    Row(
        modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { i ->
            val fill by androidx.compose.animation.core.animateFloatAsState(if (i <= step) 1f else 0f, dev.southstar21.volum.ui.theme.Springs.grow(), label = "step")
            // The track is a shallow groove; the done steps fill it with the accent gel, shadows scaled to
            // the capsule's 8dp so they read as depth rather than a smudge.
            Box(
                Modifier.width(36.dp).height(8.dp)
                    .background(c.neuBg, CircleShape)
                    .innerShadow(CircleShape, Shadow(radius = 2.dp, color = c.neuDark, offset = DpOffset(0.dp, 1.dp))),
            ) {
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer { alpha = fill }
                        .background(Brush.verticalGradient(listOf(c.accentTop, c.accentBottom)), CircleShape)
                        .innerShadow(CircleShape, Shadow(radius = 1.dp, color = Color.White.copy(alpha = 0.4f), offset = DpOffset(0.dp, 1.dp))),
                )
            }
        }
        Mute("${step + 1} of $count", Modifier.padding(start = 4.dp), weight = Bold)
    }
}

@Composable
private fun UnitTile(unit: WeightUnit, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Volum.colors
    val shape = RoundedCornerShape(30.dp)
    Column(
        modifier.heightIn(min = 168.dp).tappable(role = Role.RadioButton, haptic = true, onClick = onClick).semantics { this.selected = selected }.neuToggle(shape, pressed = selected),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ink by androidx.compose.animation.animateColorAsState(if (selected) c.accent else c.ink, tween(Durations.Fade), label = "unit ink")
        Txt(unit.label, size = T.Display, weight = ExtraBold, tracking = T.TitleTracking, color = ink)
        Mute(label, weight = Semibold)
        Spacer(Modifier.height(8.dp))
        // The empty well stays put; the green check pops into it (and shrinks back out of it).
        val check by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, if (selected) Springs.bouncy() else tween(Durations.Exit), label = "unit check")
        Box(Modifier.size(26.dp).neuIn(CircleShape), contentAlignment = Alignment.Center) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = check.coerceAtLeast(0f); scaleY = check.coerceAtLeast(0f); alpha = (check * 2f).coerceIn(0f, 1f) }
                    .celebrate(selected, c.accent, initial = false)
                    .accentGel(CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Check, c.onAccent, size = 14.dp) }
        }
    }
}
