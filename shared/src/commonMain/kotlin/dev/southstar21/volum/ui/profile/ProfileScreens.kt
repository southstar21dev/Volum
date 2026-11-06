package dev.southstar21.volum.ui.profile

import androidx.compose.foundation.layout.Arrangement
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.data.SyncState
import dev.southstar21.volum.data.partnerErrorMessage
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.platform.appVersion
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.LocalDockPadding
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.Route
import dev.southstar21.volum.ui.components.Avatar
import dev.southstar21.volum.ui.components.BareField
import dev.southstar21.volum.ui.components.BottomSheet
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.GlassButton
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.Segmented
import dev.southstar21.volum.ui.components.TextButton
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.components.ErrorText
import dev.southstar21.volum.ui.components.ScreenTitle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.util.parseTime
import dev.southstar21.volum.util.plural
import dev.southstar21.volum.util.timeOfDay
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val app = LocalApp.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val user by app.user.collectAsStateWithLifecycle()
    val snapshot by session.partner.snapshot.collectAsStateWithLifecycle()
    val sync by session.sync.status.collectAsStateWithLifecycle()
    val c = Volum.colors
    var editName by remember { mutableStateOf(false) }
    var unpair by remember { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    val name = user?.name.orEmpty().ifBlank { user?.email.orEmpty() }
    val partner = snapshot.partner

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopInset(26.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(name, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Txt(name, size = T.Entry, weight = ExtraBold, tracking = T.TitleTracking, maxLines = 1)
                Mute(user?.email.orEmpty(), size = T.Label, maxLines = 1)
            }
            GlassButton("Edit", height = 40.dp) { editName = true }
        }

        Label("Training together")
        if (partner != null) {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(partner.displayName, size = 44.dp, warm = true)
                Column(Modifier.weight(1f)) {
                    Txt(partner.displayName, size = T.Body, weight = Bold, maxLines = 1)
                    Mute("${partner.email} · sharing routines and lifts", size = T.Caption, maxLines = 2)
                }
                TextButton("Unpair", color = c.danger, size = T.Small) { unpair = true }
            }
        } else {
            val incoming = snapshot.incoming.size
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().tappable { nav.push(Route.PairPartner) }.glassCard(RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.Users, c.accent, size = 22.dp) }
                Column(Modifier.weight(1f)) {
                    Txt(if (incoming > 0) "${plural(incoming, "pair request")} for you" else if (snapshot.outgoing.isNotEmpty()) "Invite sent" else "Add a partner", size = T.Body, weight = Bold)
                    Mute(if (incoming > 0) "View request" else "Train together and adjust each other's sets", size = T.Caption)
                }
                Icon(Icons.ChevronRight, c.mute, size = 18.dp)
            }
        }

        Label("Training")
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
            SettingRow("Weight unit") {
                Segmented(listOf("kg", "lb"), if (prefs.unit == WeightUnit.Kg) 0 else 1, { i -> app.updatePrefs { it.copy(unit = if (i == 0) WeightUnit.Kg else WeightUnit.Lb) } }, height = 32.dp)
            }
            Mute("Weights always show in both units.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), size = T.Caption)
        }

        Label("Appearance")
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
            SettingRow("Background") {
                Segmented(listOf("Glow", "Solid"), if (prefs.solidBackground) 1 else 0, { i -> app.updatePrefs { it.copy(solidBackground = i == 1) } }, height = 32.dp)
            }
        }

        Label("Account")
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
            val (title, detail, icon, tint) = when (sync.state) {
                SyncState.Synced -> Quad("Up to date", sync.lastSyncedAt?.let { "Synced at ${timeOfDay(parseTime(it))}" } ?: "Synced", Icons.CloudCheck, c.accent)
                SyncState.Pending -> Quad("Syncing", plural(sync.pendingCount, "change") + " waiting", Icons.CloudSync, c.ink)
                SyncState.Offline -> Quad("Offline", plural(sync.pendingCount, "change") + " saved on this phone", Icons.CloudOff, c.mute)
                SyncState.Error -> Quad("Sync error", sync.lastError ?: "Tap to retry", Icons.CloudOff, c.danger)
            }
            Row(Modifier.fillMaxWidth().tappable { scope.launch { session.sync.retryNow() } }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, tint, size = 20.dp)
                Column(Modifier.weight(1f)) {
                    Txt(title, size = T.Body, weight = Semibold)
                    Mute(detail, size = T.Caption, maxLines = 2)
                }
                if (sync.state == SyncState.Error) Icon(Icons.Refresh, c.mute, size = 18.dp)
            }
            Divider(inset = 16.dp)
            Row(Modifier.fillMaxWidth().tappable { signOut = true }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Sign out", Modifier.weight(1f), size = T.Body, weight = Semibold, color = c.danger)
                Mute("Volum $appVersion", size = T.Caption)
            }
        }
        Spacer(Modifier.height(LocalDockPadding.current + 24.dp))
    }

    var draft by remember { mutableStateOf(name) }
    var nameError by remember { mutableStateOf<String?>(null) }
    // Each opening starts from the current name, not a cancelled draft or an old error.
    LaunchedEffect(editName) { if (editName) { draft = name; nameError = null } }
    BottomSheet(open = editName, onDismiss = { editName = false }, title = "Display name") {
        Txt("Display name", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Mute("Your partner sees this name.", Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(draft, { draft = it }, Modifier.fillMaxWidth(), placeholder = "Your name", imeAction = ImeAction.Done, label = "Display name", contentType = ContentType.PersonFullName)
        }
        nameError?.let { ErrorText(it, Modifier.padding(8.dp)) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = draft.isNotBlank()) {
            scope.launch {
                try { app.setDisplayName(draft.trim()); editName = false; nameError = null }
                catch (e: PbException) { nameError = if (e.status == 0) "You're offline." else e.message }
            }
        }
    }
    ConfirmSheet(
        open = unpair,
        title = "Unpair from ${partner?.displayName ?: "partner"}?",
        message = "You'll stop seeing each other's routines, lifts and live workouts.",
        confirm = "Unpair",
        onConfirm = { scope.launch { runCatching { session.partner.unpair(); session.repository.deletePartnerLocalData() } } },
        onDismiss = { unpair = false },
    )
    ConfirmSheet(
        open = signOut,
        title = "Sign out?",
        message = if (sync.pendingCount > 0) "${plural(sync.pendingCount, "change")} haven't synced yet and will stay on this phone." else "Your data stays in your account.",
        confirm = "Sign out",
        onConfirm = { app.signOut() },
        onDismiss = { signOut = false },
    )
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun Label(text: String) = Mute(text, Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp), size = T.Small, weight = Bold)

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.weight(1f), size = T.Body, weight = Semibold)
        trailing()
    }
}

@Composable
fun PairPartnerScreen() {
    val session = LocalSession.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val snapshot by session.partner.snapshot.collectAsStateWithLifecycle()
    val c = Volum.colors
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true; error = null; message = null
        scope.launch {
            try { block() } catch (e: PbException) { error = partnerErrorMessage(e) } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopInset(10.dp)
        Toolbar(leading = { CircleButton(Icons.ChevronLeft, "Back to profile") { nav.pop() } })
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            ScreenTitle("Add a partner")
            Mute("Train together with one other person.", size = T.Body)
        }
        GlassCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Txt("Shared", size = T.Body, weight = Bold)
            Bullet(Icons.Check, "Your exercises and routines", c.accent)
            Bullet(Icons.Check, "Live view of each other's active workout, with edits to weights, reps and sets", c.accent)
            Spacer(Modifier.height(8.dp))
            Txt("Private", size = T.Body, weight = Bold)
            Bullet(Icons.Lock, "Completed workout history stays yours", c.mute)
        }
        if (snapshot.partner == null) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                fun send() { if (email.contains('@') && !busy) run { session.partner.requestPair(email); message = "Request sent to ${email.trim()}"; email = "" } }
                Mute("Partner email", Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
                BareField(email, { email = it }, Modifier.fillMaxWidth(), placeholder = "alex@example.com", keyboardType = KeyboardType.Email, imeAction = ImeAction.Send, label = "Partner email", contentType = ContentType.EmailAddress, onDone = ::send)
            }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                PrimaryButton("Send request", enabled = email.contains('@'), busy = busy) {
                    run { session.partner.requestPair(email); message = "Request sent to ${email.trim()}"; email = "" }
                }
            }
            Mute("They need a Volum account with this exact email.", Modifier.fillMaxWidth().padding(top = 8.dp), size = T.Caption, align = androidx.compose.ui.text.style.TextAlign.Center)
        } else {
            GlassCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Txt("You're paired with ${snapshot.partner?.displayName.orEmpty()}", size = T.Body, weight = Bold)
                Mute("Unpair from Profile to add someone else.", size = T.Small)
            }
        }
        message?.let { Txt(it, Modifier.padding(start = 24.dp, top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }, size = T.Small, color = c.accent, weight = Semibold) }
        error?.let { ErrorText(it, Modifier.padding(start = 24.dp, top = 10.dp), weight = Semibold) }

        if (snapshot.outgoing.isNotEmpty()) {
            Mute("Waiting for an answer", Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp), weight = Bold)
            snapshot.outgoing.forEach { req ->
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(req.to.displayName, size = 40.dp, warm = true)
                    Column(Modifier.weight(1f)) {
                        Txt(req.to.displayName, size = T.Body, weight = Bold, maxLines = 1)
                        Mute(req.to.email, size = T.Caption, maxLines = 1)
                    }
                    TextButton("Cancel", color = c.danger, size = T.Small) { run { session.partner.cancelRequest(req.id) } }
                }
            }
        }
        if (snapshot.incoming.isNotEmpty()) {
            Mute("Requests for you", Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp), weight = Bold)
            snapshot.incoming.forEach { req ->
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(req.from.displayName, size = 44.dp, warm = true)
                        Column(Modifier.weight(1f)) {
                            Txt(req.from.displayName, size = T.Body, weight = Bold)
                            Mute(req.from.email, size = T.Caption)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Decline", Modifier.weight(1f), height = 46.dp) { run { session.partner.decline(req.id) } }
                        PrimaryButton("Accept", Modifier.weight(1f), height = 46.dp) { run { session.partner.accept(req.id); nav.pop() } }
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun Bullet(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, tint, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Txt(text, size = T.Label, color = if (tint == Volum.colors.mute) Volum.colors.mute else Volum.colors.ink, lineHeight = 19.sp)
    }
}
