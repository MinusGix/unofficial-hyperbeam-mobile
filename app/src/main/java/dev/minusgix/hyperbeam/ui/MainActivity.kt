package dev.minusgix.hyperbeam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.minusgix.hyperbeam.R
import dev.minusgix.hyperbeam.core.RoomLink
import dev.minusgix.hyperbeam.room.RecentRooms
import dev.minusgix.hyperbeam.input.Mods
import dev.minusgix.hyperbeam.input.RemoteKeySink
import dev.minusgix.hyperbeam.room.PageHost
import dev.minusgix.hyperbeam.room.RoomService
import dev.minusgix.hyperbeam.room.RoomSession
import dev.minusgix.hyperbeam.web.BridgeEvent
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity(), PageHost {

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var keyboardBar: LinearLayout
    private lateinit var composeRow: LinearLayout
    private lateinit var composeInput: EditText
    private lateinit var fabs: View
    private lateinit var fabKeyboard: ImageButton

    private enum class KbMode { OFF, TYPING, COMPOSE }

    private var kbMode = KbMode.OFF
    private var imeWasVisible = false
    private val modButtons = mutableMapOf<String, TextView>()
    private var armed = Mods.NONE

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var pendingPermissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            pendingPermissionResult?.invoke(res.values.all { it })
            pendingPermissionResult = null
        }

    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            pendingFiles = null
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the room runs either way */ }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webContainer = findViewById(R.id.web_container)
        keyboardBar = findViewById(R.id.keyboard_bar)
        composeRow = findViewById(R.id.compose_row)
        composeInput = findViewById(R.id.compose_input)
        fabs = findViewById(R.id.fabs)
        fabKeyboard = findViewById(R.id.fab_keyboard)

        ContextCompat.registerReceiver(
            this, finishReceiver, IntentFilter(RoomService.ACTION_FINISH_UI), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setupInsets()
        setupKeyboardBar()
        fabKeyboard.setOnClickListener { setKbMode(if (kbMode == KbMode.OFF) KbMode.TYPING else KbMode.OFF) }
        findViewById<ImageButton>(R.id.fab_menu).setOnClickListener { showMenu(it) }

        setupStartPanel()
        RoomSession.host = this
        val wasOpen = RoomSession.isOpen
        val link = urlFromIntent(intent)
        when {
            wasOpen -> {
                attachWebView()
                handleIncoming(intent)
            }
            link != null -> openPage(link)
            else -> showStartPanel()
        }
        maybeAskForNotifications()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = RoomSession.webView
                when {
                    startPanel.visibility == View.VISIBLE -> finish()
                    kbMode != KbMode.OFF -> setKbMode(KbMode.OFF)
                    wv != null && wv.canGoBack() -> wv.goBack()
                    // Keep the room running; the notification brings you back.
                    else -> moveTaskToBack(true)
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RoomSession.events.collect(::onBridgeEvent) }
                launch {
                    RoomSession.viewVersion.collect { if (RoomSession.isOpen) attachWebView() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        RoomSession.host = this
        // Make sure the page keeps reporting itself visible (it's on by default).
        RoomSession.bridge?.pinVisibility(true)
    }

    override fun onDestroy() {
        unregisterReceiver(finishReceiver)
        RoomSession.detach(this)
        super.onDestroy()
    }

    /** Open (or navigate) the page and keep it alive in the background. */
    private fun openPage(url: String) {
        RoomSession.open(url)
        attachWebView()
        startPanel.visibility = View.GONE
        fabs.visibility = View.VISIBLE
        RoomService.start(this)
    }

    private fun attachWebView() {
        val wv = RoomSession.webView ?: return
        if (wv.parent === webContainer) return
        RoomSession.attach(this, webContainer)
        wv.smartKeyboard = prefs.getBoolean(PREF_SMART_KEYBOARD, true)
        wv.onStickyModsConsumed = { setArmed(Mods.NONE) }
    }

    // --- Incoming links and shares ---------------------------------------

    private fun urlFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (intent.action == Intent.ACTION_VIEW && RoomSession.isHyperbeamUrl(data)) data.toString() else null
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> urlFromIntent(intent)?.let { openPage(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val url = URL_RE.find(text)?.value ?: return
                openInSharedBrowser(url)
            }
        }
    }

    /** Navigate the shared browser: focus its address bar (Ctrl+L), type, Enter. */
    private fun openInSharedBrowser(url: String) {
        val sink = RoomSession.bridge ?: return
        if (!RoomSession.state.value.inRoom) {
            Toast.makeText(this, "Join a room first, then share the link again", Toast.LENGTH_LONG).show()
            return
        }
        sink.key("l", Mods(ctrl = true))
        sink.text(url)
        sink.key("Enter")
    }

    // --- Insets and keyboard ---------------------------------------------

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            // Closing the keyboard (back gesture, its own hide key) ends remote typing.
            if (imeWasVisible && !imeVisible && kbMode == KbMode.TYPING) setKbMode(KbMode.OFF)
            imeWasVisible = imeVisible
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setKbMode(mode: KbMode) {
        val wv = RoomSession.webView ?: return
        kbMode = mode
        keyboardBar.visibility = if (mode == KbMode.OFF) View.GONE else View.VISIBLE
        composeRow.visibility = if (mode == KbMode.COMPOSE) View.VISIBLE else View.GONE
        fabs.visibility = if (mode == KbMode.OFF) View.VISIBLE else View.GONE
        when (mode) {
            KbMode.OFF -> {
                wv.stopRemoteTyping()
                setArmed(Mods.NONE)
                imeWasVisible = false
            }
            KbMode.TYPING -> {
                wv.startRemoteTyping()
            }
            KbMode.COMPOSE -> {
                wv.stopRemoteTyping(hideKeyboard = false)
                composeInput.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                composeInput.post { imm.showSoftInput(composeInput, 0) }
            }
        }
    }

    private fun setupKeyboardBar() {
        val keys = findViewById<LinearLayout>(R.id.extra_keys)
        fun add(label: String, onClick: (View) -> Unit): TextView {
            val tv = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.hb_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                minWidth = dp(40)
                setPadding(dp(10), 0, dp(10), 0)
                setBackgroundResource(R.drawable.key_bg)
                isClickable = true
                setOnClickListener(onClick)
            }
            keys.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
            return tv
        }
        fun key(label: String, dom: String) = add(label) { press(dom) }
        fun mod(label: String, name: String) {
            modButtons[name] = add(label) { toggleMod(name) }
        }

        add("✕") { setKbMode(KbMode.OFF) }
        add("✎") { setKbMode(if (kbMode == KbMode.COMPOSE) KbMode.TYPING else KbMode.COMPOSE) }
        key("Esc", "Escape")
        key("Tab", "Tab")
        mod("Ctrl", "ctrl")
        mod("Alt", "alt")
        mod("Shift", "shift")
        key("←", "ArrowLeft")
        key("↑", "ArrowUp")
        key("↓", "ArrowDown")
        key("→", "ArrowRight")
        key("Home", "Home")
        key("End", "End")
        key("PgUp", "PageUp")
        key("PgDn", "PageDown")
        key("Del", "Delete")
        key("⏎", "Enter")
        add("Paste") { pasteClipboard() }

        findViewById<Button>(R.id.compose_send).setOnClickListener {
            val text = composeInput.text.toString()
            if (text.isNotEmpty()) {
                RoomSession.bridge?.text(text)
                composeInput.text.clear()
            }
        }
    }

    private fun press(domKey: String) {
        val mods = armed
        RoomSession.bridge?.key(domKey, mods, RemoteKeySink.Phase.PRESS)
        setArmed(Mods.NONE)
        RoomSession.webView?.resetBuffer()
    }

    private fun toggleMod(name: String) {
        setArmed(
            when (name) {
                "ctrl" -> armed.copy(ctrl = !armed.ctrl)
                "alt" -> armed.copy(alt = !armed.alt)
                "shift" -> armed.copy(shift = !armed.shift)
                else -> armed
            },
        )
    }

    private fun setArmed(m: Mods) {
        armed = m
        RoomSession.webView?.stickyMods = m
        modButtons["ctrl"]?.isActivated = m.ctrl
        modButtons["alt"]?.isActivated = m.alt
        modButtons["shift"]?.isActivated = m.shift
    }

    private fun pasteClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        RoomSession.bridge?.text(text)
        RoomSession.webView?.resetBuffer()
    }

    private fun onBridgeEvent(ev: BridgeEvent) {
        when (ev) {
            // The page's own text field (chat, room name) took focus: give it the keyboard.
            is BridgeEvent.PageFocus -> if (ev.editable && !ev.inClient && kbMode == KbMode.TYPING) {
                RoomSession.webView?.stopRemoteTyping(hideKeyboard = false)
                kbMode = KbMode.OFF
                keyboardBar.visibility = View.GONE
                fabs.visibility = View.VISIBLE
            }
            is BridgeEvent.Snapshot -> shareSnapshot(ev)
            else -> {}
        }
    }

    // --- Start screen ----------------------------------------------------

    private val startPanel: View by lazy { findViewById(R.id.start_panel) }
    private val roomInput: EditText by lazy { findViewById(R.id.room_input) }

    private fun setupStartPanel() {
        val group = findViewById<android.widget.RadioGroup>(R.id.server_group)
        group.check(
            if (prefs.getString(PREF_SERVER, RoomLink.CANARY) == RoomLink.MAIN) R.id.server_main else R.id.server_canary,
        )
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(PREF_SERVER, if (id == R.id.server_main) RoomLink.MAIN else RoomLink.CANARY).apply()
        }
        findViewById<Button>(R.id.join_button).setOnClickListener { joinFromInput() }
        roomInput.setOnEditorActionListener { _, _, _ ->
            joinFromInput()
            true
        }
        findViewById<View>(R.id.sign_in).setOnClickListener { openPage(RoomSession.appUrl(selectedServer())) }
    }

    private fun selectedServer(): String = prefs.getString(PREF_SERVER, RoomLink.CANARY) ?: RoomLink.CANARY

    private fun joinFromInput() {
        val link = RoomLink.parse(roomInput.text.toString(), selectedServer())
        if (link == null) {
            Toast.makeText(this, R.string.start_bad_room, Toast.LENGTH_LONG).show()
            return
        }
        hideIme(roomInput)
        openPage(link.url)
    }

    private fun showStartPanel() {
        startPanel.visibility = View.VISIBLE
        fabs.visibility = View.GONE
        keyboardBar.visibility = View.GONE
        kbMode = KbMode.OFF
        renderRecentRooms()
    }

    private fun renderRecentRooms() {
        val list = findViewById<LinearLayout>(R.id.recent_list)
        list.removeAllViews()
        val rooms = RecentRooms.list(this)
        findViewById<View>(R.id.recent_header).visibility = if (rooms.isEmpty()) View.GONE else View.VISIBLE
        for (entry in rooms) {
            val link = entry.link
            val row = TextView(this).apply {
                val server = if (link.server == RoomLink.MAIN) "" else "  ·  ${link.server.removeSuffix(".hyperbeam.com")}"
                text = "${link.name}$server"
                setTextColor(ContextCompat.getColor(context, R.color.hb_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(R.drawable.key_bg)
                setOnClickListener { openPage(link.url) }
                setOnLongClickListener {
                    RecentRooms.remove(context, link)
                    Toast.makeText(context, getString(R.string.start_removed, link.name), Toast.LENGTH_SHORT).show()
                    renderRecentRooms()
                    true
                }
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    /** Close the current page and go back to the start screen. */
    private fun leaveToStart() {
        setKbMode(KbMode.OFF)
        RoomSession.close()
        startService(Intent(this, RoomService::class.java).setAction(RoomService.ACTION_LEAVE))
        roomInput.text.clear()
        showStartPanel()
    }

    private fun hideIme(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // --- Menu ------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val smart = prefs.getBoolean(PREF_SMART_KEYBOARD, true)
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 6, 0, R.string.menu_switch_room)
        menu.menu.add(0, 1, 0, R.string.menu_reload)
        menu.menu.add(0, 2, 1, if (smart) R.string.menu_keyboard_mode_direct else R.string.menu_keyboard_mode_smart)
        menu.menu.add(0, 3, 2, R.string.menu_snapshot)
        if (!isIgnoringBatteryOptimizations()) menu.menu.add(0, 4, 3, R.string.menu_battery)
        menu.menu.add(0, 5, 4, R.string.menu_exit)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> RoomSession.webView?.reload()
                2 -> {
                    prefs.edit().putBoolean(PREF_SMART_KEYBOARD, !smart).apply()
                    RoomSession.webView?.smartKeyboard = !smart
                }
                3 -> requestSnapshot()
                4 -> requestBatteryExemption()
                5 -> startService(Intent(this, RoomService::class.java).setAction(RoomService.ACTION_EXIT))
                6 -> leaveToStart()
            }
            true
        }
        menu.show()
    }

    private var snapshotId = 0

    private fun requestSnapshot() {
        val bridge = RoomSession.bridge
        if (bridge == null || !bridge.connected) {
            Toast.makeText(this, R.string.snapshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        bridge.requestSnapshot(++snapshotId)
    }

    private fun shareSnapshot(ev: BridgeEvent.Snapshot) {
        if (ev.id != snapshotId) return
        runCatching {
            val dir = File(cacheDir, "snapshots").apply { mkdirs() }
            val file = File(dir, "hyperbeam-snapshot-${System.currentTimeMillis()}.json")
            val body = JSONObject().put("app", appInfo()).put("page", ev.data)
            file.writeText(body.toString(2))
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.menu_snapshot)))
        }.onFailure {
            Toast.makeText(this, R.string.snapshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appInfo(): JSONObject {
        val st = RoomSession.state.value
        return JSONObject()
            .put("package", packageName)
            .put("version", packageManager.getPackageInfo(packageName, 0).versionName)
            .put("android", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("inRoom", st.inRoom)
            .put("connection", st.connection)
            .put("inputPath", st.inputPath)
    }

    // --- Background and permissions --------------------------------------

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // Sideloaded app whose core job is staying connected in the background.
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
    }

    override fun requestPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        pendingPermissionResult?.invoke(false)
        pendingPermissionResult = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        pendingFiles?.onReceiveValue(null)
        pendingFiles = callback
        return runCatching { fileLauncher.launch(params.createIntent()); true }
            .getOrElse {
                pendingFiles = null
                false
            }
    }

    override fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREF_SMART_KEYBOARD = "smart_keyboard"
        private const val PREF_SERVER = "server"
        private val URL_RE = Regex("""https?://\S+""")
    }
}
