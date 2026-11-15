package de.startpertap.launcher;

import android.app.*;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.RawContacts;
import android.provider.Settings;
import android.text.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.json.*;

public final class Home extends Activity {
    private static final int BLACK = Color.BLACK;
    private static final int WHITE = Color.WHITE;
    private static final int BLUE = 0xff1558ca;
    private static final int MAX_TILES = 36;
    private static final int PAGE_SIZE = 16;
    private static final int[] TILE_LABEL_SIZES = {0, 18, 22, 26, 30, 34, 38, 42, 46, 50, 54};
    private static final String[] LANGUAGE_TAGS = {"", "de", "en", "es", "fr", "uk"};
    private static final String[] LANGUAGE_NAMES = {"", "Deutsch", "English", "Español", "Français", "Українська"};
    private static final String WHATSAPP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
    private static final String[] PALETTE = {
        "#1558CA", "#087438", "#BB2028", "#7222A0",
        "#A64B00", "#00777D", "#F9D84A", "#F4F4F4"
    };
    private SharedPreferences prefs;
    private NotificationManager notifications;
    private JSONArray tiles;
    private LinearLayout root;
    private Dialog active;
    private boolean admin;
    private boolean sorting;
    private int homePage;
    private HorizontalScrollView homeHeader;
    private float swipeX;
    private float swipeY;
    private boolean trackingSwipe;
    private String pendingCall;
    private static final int PICK_MEDIA = 40;
    private static final int EXPORT_LAYOUT = 42;
    private static final int IMPORT_LAYOUT = 43;
    private static final int MAX_BACKUP_BYTES = 64 * 1024;
    private static final int MAX_PROFILES = 12;
    private String pendingExportProfile;
    private int pendingMediaTile = -1;
    private String pendingMediaLabel;
    private int pendingMediaColor;
    private int pendingMediaLabelSize;
    private String pendingMediaSymbol;

    @Override protected void attachBaseContext(Context base) {
        String tag = base.getSharedPreferences("launcher", MODE_PRIVATE).getString("language", "");
        if (!tag.isEmpty()) {
            Configuration localized = new Configuration(base.getResources().getConfiguration());
            localized.setLocale(Locale.forLanguageTag(tag));
            base = base.createConfigurationContext(localized);
        }
        super.attachBaseContext(base);
    }

    @Override public void onCreate(Bundle state) {
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        if (lightAppearance()) setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (state != null) pendingExportProfile = state.getString("pending_export_profile");
        notifications = getSystemService(NotificationManager.class);
        try {
            tiles = new JSONArray(prefs.getString("tiles", "[]"));
        } catch (JSONException e) {
            tiles = new JSONArray();
        }
        if (tiles.length() < 2) {
            tiles = new JSONArray();
            tiles.put(tile("App 1", "", "", BLUE));
            tiles.put(tile("App 2", "", "", 0xff7222a0));
        }
        showLaunchScreen();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending_export_profile", pendingExportProfile);
    }

    private ImageButton wifiControl;
    private ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback wifiChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            updateWifiStatus();
        }

        @Override public void onLost(Network network) {
            updateWifiStatus();
        }
    };

    @Override protected void onStart() {
        super.onStart();
        connectivity = getSystemService(ConnectivityManager.class);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build(), wifiChanges, new Handler(Looper.getMainLooper()));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!admin && active == null && !emergencyStartVisible) showHome();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyNavigationBarPreference();
    }

    private void applyNavigationBarPreference() {
        boolean hide = prefs.getBoolean("hide_navigation_bar", false);
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller == null) return;
            if (hide) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.navigationBars());
            } else {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                controller.show(WindowInsets.Type.navigationBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            int current = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(hide ? current | flags : current & ~flags);
        }
        if (root != null) root.requestApplyInsets();
    }

    @Override protected void onStop() {
        connectivity.unregisterNetworkCallback(wifiChanges);
        super.onStop();
        admin = false;
        if (active != null) active.dismiss();
        showHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        admin = false;
        if (active != null) active.dismiss();
        showLaunchScreen();
    }

    @Override public void onBackPressed() {
        if (emergencyStartVisible) showHome();
        else if (sorting) showTileSettings();
        else if (admin && tileSettingsVisible) showAdmin();
        else if (admin) showHome();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            trackingSwipe = !admin && active == null && pageCount() > 1 && !touchesScrollableHeader(event);
            swipeX = event.getX();
            swipeY = event.getY();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            trackingSwipe = false;
        } else if (action == MotionEvent.ACTION_UP && trackingSwipe) {
            trackingSwipe = false;
            float horizontal = event.getX() - swipeX;
            float vertical = event.getY() - swipeY;
            if (Math.abs(horizontal) >= dp(64) && Math.abs(horizontal) > Math.abs(vertical) * 1.5f) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                turnPage(horizontal < 0 ? 1 : -1);
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean touchesScrollableHeader(MotionEvent event) {
        if (homeHeader == null || (!homeHeader.canScrollHorizontally(-1) && !homeHeader.canScrollHorizontally(1))) {
            return false;
        }
        int[] location = new int[2];
        homeHeader.getLocationOnScreen(location);
        return event.getRawY() >= location[1] && event.getRawY() < location[1] + homeHeader.getHeight();
    }

    private int pageCount() {
        int remaining = Math.max(0, tiles.length() - firstPageSize());
        return 1 + (remaining + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private int firstPageSize() {
        return prefs.getInt("first_page_size", PAGE_SIZE);
    }

    private int pageStart(int page) {
        return page == 0 ? 0 : firstPageSize() + (page - 1) * PAGE_SIZE;
    }

    private int pageForTile(int index) {
        return index < firstPageSize() ? 0 : 1 + (index - firstPageSize()) / PAGE_SIZE;
    }

    private void turnPage(int direction) {
        int next = Math.max(0, Math.min(pageCount() - 1, homePage + direction));
        if (homePage != next) {
            homePage = next;
            showHome();
        }
    }

    private int dp(float size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private boolean lightAppearance() {
        Theme theme = currentTheme();
        return theme == Theme.CUSTOM ? prefs.getBoolean("light_appearance", false) : theme.light;
    }

    private int foreground() {
        return lightAppearance() ? BLACK : WHITE;
    }

    private int background() {
        Theme theme = currentTheme();
        return theme == Theme.CUSTOM ? (lightAppearance() ? WHITE : BLACK) : theme.surface;
    }

    private enum Theme {
        CUSTOM(R.string.theme_current, false, BLACK),
        PASTEL(R.string.theme_pastel, true, 0xfffaf6f0,
            0xffbfd9ee, 0xfff6d2de, 0xffc5e4d2, 0xfff7dfb7, 0xffddd0ee, 0xffbfe3e3),
        CONTRAST(R.string.theme_contrast, false, BLACK,
            0xffffe400, 0xff00eaff, 0xffff9630, 0xfff8f8f8, 0xff71ff44, 0xffff70c6),
        MONO(R.string.theme_mono, false, BLACK, WHITE);

        final int title;
        final boolean light;
        final int surface;
        final int[] palette;

        Theme(int title, boolean light, int surface, int... palette) {
            this.title = title;
            this.light = light;
            this.surface = surface;
            this.palette = palette;
        }

        int colorAt(int position) {
            return palette[position % palette.length];
        }
    }

    private Theme currentTheme() {
        String stored = prefs.getString("design_theme", Theme.CUSTOM.name());
        for (Theme theme : Theme.values()) {
            if (theme.name().equals(stored)) return theme;
        }
        return Theme.CUSTOM;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(prefs.getInt("corner_radius", 14)));
        return shape;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private void page() {
        homeHeader = null;
        root = column();
        root.setBackgroundColor(background());
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                if (prefs.getBoolean("hide_navigation_bar", false)) {
                    types &= ~WindowInsets.Type.navigationBars();
                }
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(types);
                view.setPadding(bars.left + dp(12), bars.top + dp(8),
                    bars.right + dp(12), bars.bottom + dp(8));
                return insets;
            });
        }
        setContentView(root);
    }

    private Button button(String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(19);
        button.setMinHeight(dp(56));
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(foreground());
        return view;
    }

    private static final int PICK_EMERGENCY_PDF = 41;
    private static final int EMERGENCY_CONTACTS = 3;

    private ImageButton emergencyButton() {
        ImageButton control = new ImageButton(this);
        control.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        control.setImageResource(R.drawable.emergency_ambulance);
        if (prefs.getBoolean("emergency_red_background", false)) {
            control.setBackground(rounded(0xffa92530));
        } else {
            control.setColorFilter(foreground());
            control.setBackground(new RippleDrawable(
                android.content.res.ColorStateList.valueOf(lightAppearance() ? 0x44000000 : 0x44ffffff), null, rounded(WHITE)));
        }
        control.setContentDescription(getString(R.string.emergency));
        control.setTooltipText(getString(R.string.emergency));
        control.setOnClickListener(view -> showEmergency());
        return control;
    }

    private LinearLayout emergencyContent() {
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        return content;
    }

    private ScrollView emergencyScroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private boolean emergencyStartVisible;

    private void showLaunchScreen() {
        if (!prefs.getBoolean("emergency_at_start", false)) {
            showHome();
            return;
        }
        emergencyStartVisible = true;
        page();
        root.addView(text(getString(R.string.emergency), 28));
        root.addView(emergencyScroll(emergencyDetails()), new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(button(getString(R.string.emergency_go_home), this::showHome));
        LinearLayout launchView = root;
        launchView.postDelayed(() -> {
            if (emergencyStartVisible && root == launchView && !isDestroyed()) showHome();
        }, 4000L);
    }

    private LinearLayout emergencyDetails() {
        LinearLayout content = emergencyContent();
        String address = prefs.getString("emergency_address", "");
        TextView addressView = new TextView(this);
        addressView.setTextSize(22);
        addressView.setTextColor(foreground());
        addressView.setText(address.isEmpty() ? getString(R.string.emergency_empty) : address);
        addressView.setTextIsSelectable(!address.isEmpty());
        content.addView(addressView);
        for (int i = 0; i < EMERGENCY_CONTACTS; i++) {
            String phone = prefs.getString("emergency_phone_" + i, "");
            if (phone.isEmpty()) continue;
            String name = prefs.getString("emergency_name_" + i, "");
            content.addView(button(name.isEmpty() ? phone : name + "\n" + phone, () ->
                open(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)),
                    getString(R.string.emergency_unavailable))));
        }
        String pdf = prefs.getString("emergency_pdf", "");
        if (!pdf.isEmpty()) {
            content.addView(button(getString(R.string.emergency_open_pdf), () ->
                open(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(pdf), "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.emergency_unavailable))));
        }
        return content;
    }

    private void showEmergency() {
        dialog(new AlertDialog.Builder(this).setTitle(R.string.emergency)
            .setView(emergencyScroll(emergencyDetails())).setPositiveButton(android.R.string.ok, null).create());
    }

    private void editEmergency() {
        LinearLayout content = emergencyContent();
        EditText address = field(getString(R.string.emergency_address),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        address.setSingleLine(false);
        address.setText(prefs.getString("emergency_address", ""));
        content.addView(address);
        EditText[] names = new EditText[EMERGENCY_CONTACTS];
        EditText[] phones = new EditText[EMERGENCY_CONTACTS];
        for (int i = 0; i < EMERGENCY_CONTACTS; i++) {
            names[i] = field(getString(R.string.emergency_name, i + 1), InputType.TYPE_CLASS_TEXT);
            phones[i] = field(getString(R.string.emergency_phone, i + 1), InputType.TYPE_CLASS_PHONE);
            names[i].setText(prefs.getString("emergency_name_" + i, ""));
            phones[i].setText(prefs.getString("emergency_phone_" + i, ""));
            content.addView(names[i]);
            content.addView(phones[i]);
        }
        dialog(new AlertDialog.Builder(this).setTitle(R.string.emergency_setup)
            .setView(emergencyScroll(content))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                SharedPreferences.Editor edit = prefs.edit()
                    .putString("emergency_address", address.getText().toString().trim());
                for (int i = 0; i < EMERGENCY_CONTACTS; i++) {
                    edit.putString("emergency_name_" + i, names[i].getText().toString().trim());
                    edit.putString("emergency_phone_" + i, phones[i].getText().toString().trim());
                }
                edit.apply();
            }).create());
    }

    private void pickEmergencyPdf() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf").putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(picker, PICK_EMERGENCY_PDF);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.emergency_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void saveEmergencyPdf(Intent data) {
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) return;
        try {
            if (!"application/pdf".equals(getContentResolver().getType(uri))) {
                throw new IllegalArgumentException();
            }
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            prefs.edit().putString("emergency_pdf", uri.toString()).apply();
            if (admin) showAdmin();
        } catch (IllegalArgumentException | SecurityException e) {
            Toast.makeText(this, R.string.emergency_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void showHome() {
        emergencyStartVisible = false;
        admin = false;
        sorting = false;
        homePage = Math.min(homePage, pageCount() - 1);
        page();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.END);
        Map<Symbol, ImageButton> buttons = new EnumMap<>(Symbol.class);
        wifiControl = iconButton(false, false, "", this::confirmWifiSettings);
        wifiControl.setImageResource(R.drawable.wifi_status);
        wifiControl.setColorFilter(foreground());
        applyHeaderColor(wifiControl, Symbol.WIFI);
        updateWifiStatus();
        buttons.put(Symbol.WIFI, wifiControl);
        if (headerVisible(Symbol.APPS)) {
            ImageButton apps = iconButton(false, false, getString(R.string.ui_alle_apps_50), this::showAllApps);
            apps.setImageResource(R.drawable.all_apps);
            apps.setColorFilter(foreground());
            applyHeaderColor(apps, Symbol.APPS);
            buttons.put(Symbol.APPS, apps);
        }
        int filter = notifications.getCurrentInterruptionFilter();
        boolean quiet = filter != NotificationManager.INTERRUPTION_FILTER_ALL
            && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        ImageButton bell = iconButton(false, quiet, quiet ? getString(R.string.ui_ruhemodus_aktiv_0) : getString(R.string.ui_ruhemodus_aus_1), this::toggleQuiet);
        applyHeaderColor(bell, Symbol.QUIET);
        buttons.put(Symbol.QUIET, bell);
        ImageButton emergency = emergencyButton();
        applyHeaderColor(emergency, Symbol.EMERGENCY);
        buttons.put(Symbol.EMERGENCY, emergency);
        ImageButton lock = iconButton(true, false, getString(R.string.ui_einstellungen_2), this::askPin);
        applyHeaderColor(lock, Symbol.LOCK);
        buttons.put(Symbol.LOCK, lock);
        if (headerVisible(Symbol.SETTINGS)) {
            ImageButton settings = iconButton(false, false, getString(Symbol.SETTINGS.title), () ->
                open(new Intent(Settings.ACTION_SETTINGS), getString(R.string.ui_einstellung_nicht_verfugbar_53)));
            settings.setImageResource(R.drawable.settings_gear);
            settings.setColorFilter(foreground());
            applyHeaderColor(settings, Symbol.SETTINGS);
            buttons.put(Symbol.SETTINGS, settings);
        }
        for (Symbol symbol : headerOrder()) {
            ImageButton control = buttons.get(symbol);
            if (control != null && headerVisible(symbol)) header.addView(control);
        }
        if (!headerVisible(Symbol.LOCK)) {
            Button access = button(getString(R.string.launcher_settings_access), this::askPin);
            header.addView(access, 0);
        }
        if (header.getChildCount() * 56 + 24 > getResources().getConfiguration().screenWidthDp) {
            for (int i = 0; i < header.getChildCount(); i++) {
                View control = header.getChildAt(i);
                if (control instanceof ImageButton) {
                    control.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(56)));
                }
            }
        }
        homeHeader = new HorizontalScrollView(this);
        homeHeader.setFillViewport(true);
        homeHeader.setHorizontalScrollBarEnabled(false);
        homeHeader.addView(header);
        root.addView(homeHeader);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout grid = column();
        grid.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int offset = pageStart(homePage);
        int capacity = homePage == 0 ? firstPageSize() : PAGE_SIZE;
        populateTileGrid(grid, offset, Math.min(capacity, tiles.length() - offset), true);
        if (pageCount() > 1) {
            LinearLayout navigation = new LinearLayout(this);
            navigation.setGravity(Gravity.CENTER);
            Button previous = button("‹", () -> turnPage(-1));
            previous.setContentDescription(getString(R.string.ui_vorherige_kachelseite_3));
            previous.setEnabled(homePage > 0);
            Button next = button("›", () -> turnPage(1));
            next.setContentDescription(getString(R.string.ui_nachste_kachelseite_4));
            next.setEnabled(homePage + 1 < pageCount());
            TextView position = text(getString(R.string.page, homePage + 1, pageCount()), 18);
            position.setGravity(Gravity.CENTER);
            navigation.addView(previous);
            navigation.addView(position, new LinearLayout.LayoutParams(0, -2, 1));
            navigation.addView(next);
            root.addView(navigation);
        }
    }

    private void populateTileGrid(LinearLayout grid, int offset, int count, boolean interactive) {
        float scale = getResources().getConfiguration().fontScale;
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = count <= 2 ? 1 : count <= 6 ? 2 : count <= 9 ? 3 : 4;
        if (width < 480) columns = Math.min(columns, 2);
        else if (width < 600 || scale > 1.25f) columns = Math.min(columns, 3);
        if (scale > 1.5f) columns = Math.min(columns, 2);
        int rows = (count + columns - 1) / columns;
        int tileHeight = count <= 2 ? 214 : 160;
        Theme theme = currentTheme();
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < columns; col++) {
                int index = rowIndex * columns + col;
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
                size.setMargins(dp(3), dp(3), dp(3), dp(3));
                if (index >= count) {
                    row.addView(new Space(this), size);
                    continue;
                }
                JSONObject tile = tiles.optJSONObject(offset + index);
                int color = theme == Theme.CUSTOM ? tile.optInt("color", BLUE) : theme.colorAt(offset + index);
                View face = tileView(tile, columns, color, interactive);
                if (theme == Theme.MONO) {
                    GradientDrawable bordered = rounded(color);
                    bordered.setStroke(dp(2), BLACK);
                    face.setBackground(bordered);
                }
                row.addView(face, size);
            }
            row.setMinimumHeight(dp((int) (tileHeight * Math.max(1f, scale / 1.25f))));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2, 1);
            grid.addView(row, size);
        }
    }

    private int wifiStatus() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return R.string.wifi_connected;
            }
        }
        return R.string.wifi_disconnected;
    }

    private void updateWifiStatus() {
        if (wifiControl == null) return;
        int status = wifiStatus();
        String label = getString(status);
        wifiControl.setContentDescription(label);
        wifiControl.setTooltipText(label);
        wifiControl.setAlpha(status == R.string.wifi_connected || currentTheme() != Theme.CUSTOM
            || prefs.contains(Symbol.WIFI.colorPreference())
            ? 1f : 0.65f);
    }

    private void confirmWifiSettings() {
        dialog(new AlertDialog.Builder(this)
            .setTitle(wifiStatus())
            .setMessage(R.string.wifi_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.wifi_open, (dialog, which) -> {
                try {
                    startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                } catch (ActivityNotFoundException e) {
                    open(new Intent(Settings.ACTION_WIFI_SETTINGS), getString(R.string.wifi_unavailable));
                }
            }).create());
    }

    private ImageButton iconButton(boolean lock, boolean quiet, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(new HeaderIcon(lock, quiet, foreground(), background()));
        button.setBackground(rounded(lightAppearance() ? 0xffeeeeee : 0xff171717));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private enum Symbol {
        WIFI(R.string.symbol_wifi), APPS(R.string.ui_alle_apps_50), QUIET(R.string.symbol_quiet),
        EMERGENCY(R.string.emergency), LOCK(R.string.symbol_lock), SETTINGS(R.string.device_settings);

        final int title;
        Symbol(int title) { this.title = title; }
        String colorPreference() { return "header_color_" + name(); }
        String visibilityPreference() { return "header_visible_" + name(); }
    }

    private boolean headerVisible(Symbol symbol) {
        return prefs.getBoolean(symbol.visibilityPreference(), true);
    }

    private List<Symbol> headerOrder() {
        List<Symbol> order = new ArrayList<>();
        List<String> stored = Arrays.asList(prefs.getString("header_order", "").split(","));
        for (String name : stored) {
            for (Symbol symbol : Symbol.values()) {
                if (symbol.name().equals(name) && !order.contains(symbol)) order.add(symbol);
            }
        }
        for (Symbol symbol : Symbol.values()) {
            if (!order.contains(symbol)) order.add(symbol);
        }
        return order;
    }

    private Symbol tileSymbol(JSONObject tile) {
        String stored = tile.optString("symbol");
        for (Symbol symbol : Symbol.values()) {
            if (symbol.name().equals(stored)) return symbol;
        }
        return null;
    }

    private int contrastingInk(int color) {
        return Color.luminance(color) >= 0.179f ? BLACK : WHITE;
    }

    private Drawable symbolDrawable(Symbol symbol, int color, boolean quiet) {
        int ink = contrastingInk(color);
        if (symbol == Symbol.QUIET || symbol == Symbol.LOCK) {
            return new HeaderIcon(symbol == Symbol.LOCK, quiet, ink, color);
        }
        int drawable;
        switch (symbol) {
            case WIFI: drawable = R.drawable.wifi_status; break;
            case APPS: drawable = R.drawable.all_apps; break;
            case SETTINGS: drawable = R.drawable.settings_gear; break;
            default: drawable = R.drawable.emergency_ambulance;
        }
        Drawable icon = getDrawable(drawable).mutate();
        icon.setTint(ink);
        return icon;
    }

    private void applyHeaderColor(ImageButton button, Symbol symbol) {
        String key = symbol.colorPreference();
        Theme theme = currentTheme();
        if (theme == Theme.CUSTOM && !prefs.contains(key)) return;
        int color = theme == Theme.CUSTOM ? prefs.getInt(key, BLUE) : theme.colorAt(symbol.ordinal());
        button.clearColorFilter();
        button.setBackground(rounded(color));
        if (symbol == Symbol.QUIET) {
            button.setImageDrawable(new HeaderIcon(false, ((HeaderIcon) button.getDrawable()).quiet,
                contrastingInk(color), color));
        } else {
            button.setImageDrawable(symbolDrawable(symbol, color, false));
        }
    }

    private final class HeaderIcon extends Drawable {
        private final boolean lock;
        private final boolean quiet;
        private final int ink;
        private final int surface;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HeaderIcon(boolean lock, boolean quiet, int ink, int surface) {
            this.lock = lock;
            this.quiet = quiet;
            this.ink = ink;
            this.surface = surface;
        }
        @Override public int getIntrinsicWidth() { return dp(28); }
        @Override public int getIntrinsicHeight() { return dp(28); }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            paint.setColor(ink);
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            if (lock) {
                canvas.drawRoundRect(8, 2, 16, 14, 4, 4, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(4, 9, 20, 22, 2, 2, paint);
                paint.setColor(surface);
                canvas.drawCircle(12, 15, 1.5f, paint);
            } else {
                Path bell = new Path();
                bell.moveTo(4, 17);
                bell.lineTo(6, 14);
                bell.lineTo(6, 10);
                bell.cubicTo(6, 2, 18, 2, 18, 10);
                bell.lineTo(18, 14);
                bell.lineTo(20, 17);
                bell.close();
                canvas.drawPath(bell, paint);
                canvas.drawLine(10, 21, 14, 21, paint);
                if (quiet) canvas.drawLine(3, 22, 22, 3, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int value) { paint.setAlpha(value); }
        @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private View tileView(JSONObject tile, int columns, int color, boolean interactive) {
        int ink = contrastingInk(color);
        Symbol displaySymbol = tileSymbol(tile);
        String title = tile.optString("label");
        LinearLayout face = column();
        face.setGravity(Gravity.CENTER);
        face.setPadding(dp(5), dp(7), dp(5), dp(7));
        face.setBackground(rounded(color));
        Drawable appIcon = displaySymbol == null ? appIcon(tile.optString("app"))
            : displaySymbol == Symbol.WIFI ? null : symbolDrawable(displaySymbol, color, false);
        if (appIcon != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(appIcon);
            if (currentTheme() == Theme.MONO) {
                ColorMatrix grayscale = new ColorMatrix();
                grayscale.setSaturation(0);
                image.setColorFilter(new ColorMatrixColorFilter(grayscale));
            }
            int iconSize = dp(columns == 1 ? 88 : 64);
            face.addView(image, new LinearLayout.LayoutParams(iconSize, iconSize));
        } else if (displaySymbol == null) {
            String glyph = tile.optString("url").isEmpty() && tile.optString("file").isEmpty() ? "＋" : "▶";
            if ("application/pdf".equals(tile.optString("fileType"))) glyph = "PDF";
            if (!tile.optString("phone").isEmpty()) glyph = "☎";
            TextView symbol = text(glyph, columns == 1 ? 72 : 54);
            symbol.setTextColor(ink);
            symbol.setGravity(Gravity.CENTER);
            symbol.setIncludeFontPadding(false);
            face.addView(symbol);
        }
        int labelSize = tile.optInt("labelSize");
        TextView label = text(title, labelSize > 0 ? labelSize : columns == 1 ? 38 : 26);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(ink);
        label.setTypeface(null, Typeface.BOLD);
        face.addView(label);
        if (interactive) {
            face.setContentDescription(displaySymbol == null || displaySymbol == Symbol.WIFI ? title
                : getString(R.string.symbol_tile_description, title, getString(displaySymbol.title)));
            face.setFocusable(true);
            if (displaySymbol == null) face.setOnClickListener(v -> launchTile(tile));
            else face.setScreenReaderFocusable(true);
        }
        return face;
    }

    private Drawable appIcon(String value) {
        ComponentName component = ComponentName.unflattenFromString(value);
        if (component == null) return null;
        try {
            return getPackageManager().getActivityIcon(component);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void launchTile(JSONObject tile) {
        String file = tile.optString("file");
        if (!file.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file), tile.optString("fileType"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open(view, getString(R.string.media_unavailable));
            return;
        }
        String phone = tile.optString("phone");
        if (!phone.isEmpty()) {
            if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                pendingCall = phone;
                requestPermissions(new String[]{android.Manifest.permission.CALL_PHONE}, 20);
            } else {
                call(phone);
            }
            return;
        }
        String url = tile.optString("url");
        ComponentName component = ComponentName.unflattenFromString(tile.optString("app"));
        if (!url.isEmpty()) {
            if (tile.optBoolean("whatsappCall")) {
                String issue = tryWhatsAppCall(whatsappLinkNumber(url));
                if (issue == null) return;
                dialog(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_whatsapp_direktanruf_5))
                    .setMessage(issue).setNegativeButton(getString(R.string.ui_schlie_en_6), null)
                    .setPositiveButton(getString(R.string.ui_chat_offnen_7), (d, which) ->
                        open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), getString(R.string.ui_kein_programm_kann_den_link_offnen_8)))
                    .create());
                return;
            }
            open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), getString(R.string.ui_kein_programm_kann_den_link_offnen_8));
        } else if (component != null) {
            open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component), getString(R.string.ui_diese_app_ist_nicht_verfugbar_9));
        } else {
            notice(getString(R.string.ui_diese_kachel_ist_frei_zum_belegen_bitte_das_schl_10));
        }
    }

    private void pickMedia(int index, String label, int color, int labelSize, String symbol) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/pdf"});
        try {
            startActivityForResult(picker, PICK_MEDIA);
            pendingMediaTile = index;
            pendingMediaLabel = label;
            pendingMediaColor = color;
            pendingMediaLabelSize = labelSize;
            pendingMediaSymbol = symbol;
        } catch (ActivityNotFoundException e) {
            notice(getString(R.string.media_unavailable));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_LAYOUT || request == IMPORT_LAYOUT) {
            String profile = request == EXPORT_LAYOUT ? pendingExportProfile : null;
            if (request == EXPORT_LAYOUT) pendingExportProfile = null;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                transferLayout(request, data.getData(), profile);
            }
            return;
        }
        if (request == PICK_EMERGENCY_PDF) {
            if (result == RESULT_OK) saveEmergencyPdf(data);
            return;
        }
        if (request != PICK_MEDIA) return;
        int index = pendingMediaTile;
        pendingMediaTile = -1;
        if (result != RESULT_OK || data == null || index < 0 || index > tiles.length()
            || index >= MAX_TILES) return;
        Uri uri = data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) return;
        String type;
        try {
            type = getContentResolver().getType(uri);
            if (type == null || !(type.startsWith("audio/") || type.startsWith("video/")
                || type.equals("application/pdf"))) {
                notice(getString(R.string.media_unavailable));
                return;
            }
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException e) {
            notice(getString(R.string.media_unavailable));
            return;
        }
        String label = pendingMediaLabel.isEmpty() ? getString(R.string.media_file) : pendingMediaLabel;
        JSONObject selected = tile(label, "", "", pendingMediaColor);
        try {
            selected.put("file", uri.toString());
            selected.put("fileType", type);
            selected.put("labelSize", pendingMediaLabelSize);
            selected.put("symbol", pendingMediaSymbol);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        putTile(index, selected);
        persistTiles();
        showHome();
    }

    private static String whatsappLinkNumber(String url) {
        String prefix = "https://wa.me/";
        if (!url.startsWith(prefix)) return "";
        String number = url.substring(prefix.length());
        return number.matches("[1-9][0-9]{5,14}") ? number : "";
    }

    private String tryWhatsAppCall(String number) {
        if (number.isEmpty()) return getString(R.string.ui_a_der_kachel_fehlt_eine_gultige_whatsapp_nummer_11);
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return getString(R.string.ui_b_startpertap_hat_keinen_zugriff_auf_die_android_12);
        try {
            Uri lookup = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode("+" + number));
            Set<Long> contacts = new HashSet<>();
            try (Cursor rows = getContentResolver().query(lookup, new String[]{PhoneLookup._ID}, null, null, null)) {
                while (rows != null && rows.moveToNext()) contacts.add(rows.getLong(0));
            }
            boolean sawCall = false;
            boolean sawContactCall = false;
            try (Cursor rows = getContentResolver().query(Data.CONTENT_URI,
                new String[]{Data._ID, Data.CONTACT_ID, Data.DATA1, Data.DATA3, RawContacts.ACCOUNT_TYPE, Data.MIMETYPE},
                Data.MIMETYPE + " IN (?,?)",
                new String[]{WHATSAPP_CALL, "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call"}, null)) {
                while (rows != null && rows.moveToNext()) {
                    sawCall = true;
                    boolean sameContact = contacts.contains(rows.getLong(1));
                    sawContactCall |= sameContact;
                    String id = Objects.toString(rows.getString(2), "");
                    String description = Objects.toString(rows.getString(3), "").replaceAll("[^0-9]", "");
                    boolean idMatches = id.equals(number + "@s.whatsapp.net")
                        || id.equals(number + "@c.us") || id.equals("+" + number) || id.equals(number);
                    boolean textMatches = sameContact
                        && (description.equals(number) || description.equals("00" + number)
                            || (number.startsWith("49") && description.equals("0" + number.substring(2))));
                    if (!idMatches && !textMatches) continue;
                    Uri entry = ContentUris.withAppendedId(Data.CONTENT_URI, rows.getLong(0));
                    String account = rows.getString(4);
                    String target = "com.whatsapp.w4b".equals(account) ? account : "com.whatsapp";
                    Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(entry, rows.getString(5))
                        .setPackage(target);
                    try {
                        startActivity(intent);
                        notice(getString(R.string.ui_startpertap_hat_den_sprachanruf_an_whatsapp_uber_13));
                        return null;
                    } catch (ActivityNotFoundException e) {
                        return getString(R.string.ui_e_sprachanruf_gefunden_whatsapp_bietet_android_d_14);
                    } catch (SecurityException e) {
                        return getString(R.string.ui_f_sprachanruf_gefunden_android_verweigert_den_au_15);
                    }
                }
            }
            if (!sawCall) return getString(R.string.ui_c_android_liefert_startpertap_keine_whatsapp_spr_16);
            if (sawContactCall) return getString(R.string.ui_d1_sprachanruf_beim_kontakt_gefunden_aber_die_nu_17);
            return getString(R.string.ui_d2_kein_whatsapp_sprachanruf_passt_zur_nummer_di_18);
        } catch (SecurityException | IllegalArgumentException e) {
            return getString(R.string.ui_g_android_lasst_startpertap_die_kontaktaktion_ni_19);
        }
    }

    private void call(String phone) {
        open(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone, null)),
            getString(R.string.ui_direktanruf_auf_diesem_gerat_nicht_verfugbar_20));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 20) {
            String phone = pendingCall;
            pendingCall = null;
            if (phone != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                call(phone);
            } else {
                notice(getString(R.string.ui_fur_direktanrufe_braucht_startpertap_die_telefon_21));
            }
        }
    }

    private void open(Intent intent, String error) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            notice(error);
        }
    }

    private void toggleQuiet() {
        if (!notifications.isNotificationPolicyAccessGranted()) {
            notice(getString(R.string.ui_ruhemodus_zuerst_unter_einstellungen_freigeben_22));
            return;
        }
        try {
            boolean enable = !prefs.getBoolean("quiet", false)
                || notifications.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                String ruleId = prefs.getString("quiet_rule", "");
                if (ruleId.isEmpty() || notifications.getAutomaticZenRule(ruleId) == null) {
                    Uri condition = Uri.parse("condition://de.startpertap.launcher/quiet");
                    AutomaticZenRule rule = new AutomaticZenRule.Builder("StartPerTap", condition)
                        .setConfigurationActivity(new ComponentName(this, Home.class))
                        .setTriggerDescription(getString(R.string.ui_uber_die_glocke_auf_der_startseite_23))
                        .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                        .setZenPolicy(new android.service.notification.ZenPolicy.Builder()
                            .disallowAllSounds().allowAlarms(true).hideAllVisualEffects().build())
                        .setEnabled(true).build();
                    ruleId = notifications.addAutomaticZenRule(rule);
                    prefs.edit().putString("quiet_rule", ruleId).apply();
                }
                AutomaticZenRule rule = notifications.getAutomaticZenRule(ruleId);
                if (rule == null || !rule.isEnabled()) {
                    notice(getString(R.string.ui_die_startpertap_regel_ist_in_android_deaktiviert_24));
                    return;
                }
                enable = notifications.getAutomaticZenRuleState(ruleId)
                    != android.service.notification.Condition.STATE_TRUE;
                notifications.setAutomaticZenRuleState(ruleId, new android.service.notification.Condition(
                    rule.getConditionId(), "StartPerTap", enable
                        ? android.service.notification.Condition.STATE_TRUE
                        : android.service.notification.Condition.STATE_FALSE));
            } else {
                notifications.setInterruptionFilter(enable ? NotificationManager.INTERRUPTION_FILTER_ALARMS
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            prefs.edit().putBoolean("quiet", enable).apply();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!admin && active == null && hasWindowFocus()) showHome();
            }, 400);
        } catch (RuntimeException e) {
            notice(getString(R.string.ui_ruhemodus_konnte_nicht_umgeschaltet_werden_25));
        }
    }

    private void notice(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void dialog(AlertDialog dialog) {
        active = dialog;
        dialog.setOnDismissListener(d -> {
            if (active == dialog) active = null;
        });
        dialog.show();
    }

    private EditText field(String label, int inputType) {
        EditText field = new EditText(this);
        field.setHint(label);
        field.setTextSize(21);
        field.setSingleLine(true);
        field.setInputType(inputType);
        return field;
    }

    private void askPin() { askPin(false); }

    private void askPin(boolean reset) {
        long until = prefs.getLong("retry_at", 0);
        boolean waiting = !reset && System.currentTimeMillis() < until;
        boolean setup = reset || !prefs.contains("pin");
        LinearLayout fields = column();
        fields.setPadding(dp(20), 0, dp(20), 0);
        int type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        EditText pin = field(getString(R.string.ui_vierstellige_pin_26), type);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        fields.addView(pin);
        EditText repeat = field(getString(R.string.ui_pin_wiederholen_27), type);
        repeat.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        if (setup) fields.addView(repeat);
        AlertDialog box = new AlertDialog.Builder(this)
            .setTitle(setup ? getString(R.string.ui_launcher_pin_festlegen_28) : getString(R.string.ui_einstellungen_entsperren_29))
            .setView(fields).setNegativeButton(getString(R.string.ui_abbrechen_30), null)
            .setNeutralButton(setup ? null : getString(R.string.ui_pin_vergessen_31), (d, which) -> confirmPinReset())
            .setPositiveButton(setup ? getString(R.string.ui_festlegen_32) : getString(R.string.ui_offnen_33), null).create();
        box.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        box.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        pin.requestFocus();
        dialog(box);
        box.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (waiting && System.currentTimeMillis() < until) {
                pin.setError(getString(R.string.ui_zu_viele_versuche_bitte_kurz_warten_34));
                return;
            }
            String value = pin.getText().toString();
            if (!value.matches("[0-9]{4}")) {
                pin.setError(getString(R.string.ui_vier_ziffern_eingeben_35));
                return;
            }
            if (setup && !value.contentEquals(repeat.getText())) {
                repeat.setError(getString(R.string.ui_die_pins_stimmen_nicht_uberein_36));
                return;
            }
            if (!verifyPin(value, setup)) {
                int attempts = prefs.getInt("attempts", 0) + 1;
                prefs.edit().putInt("attempts", attempts >= 5 ? 0 : attempts)
                    .putLong("retry_at", attempts >= 5 ? System.currentTimeMillis() + 30000 : 0).apply();
                if (attempts >= 5) {
                    box.dismiss();
                    notice(getString(R.string.ui_bitte_30_sekunden_warten_37));
                } else {
                    pin.setText("");
                    pin.setError(getString(R.string.ui_falsche_pin_38));
                }
                return;
            }
            prefs.edit().remove("attempts").remove("retry_at").apply();
            box.dismiss();
            showAdmin();
        });
    }

    private boolean verifyPin(String value, boolean setup) {
        try {
            byte[] salt = new byte[16];
            if (setup) new SecureRandom().nextBytes(salt);
            else salt = Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP);
            byte[] hash = credentialHash(value, salt);
            if (!setup) return MessageDigest.isEqual(hash,
                Base64.decode(prefs.getString("pin", ""), Base64.NO_WRAP));
            return prefs.edit().putString("pin", Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .remove("recovery").remove("recovery_salt")
                .remove("recovery_attempts").remove("recovery_retry_at").commit();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] credentialHash(String value, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), salt, 120000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private void confirmPinReset() {
        dialog(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_pin_zurucksetzen_39))
            .setMessage(getString(R.string.ui_mochtest_du_deine_pin_wirklich_andern_40))
            .setNegativeButton(getString(R.string.ui_abbrechen_30), null)
            .setPositiveButton(getString(R.string.ui_ja_weiter_41), (d, which) -> confirmFinalPinReset()).create());
    }

    private void confirmFinalPinReset() {
        dialog(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_letzte_warnung_42))
            .setMessage(getString(R.string.ui_mochtest_du_deine_pin_wirklich_andern_sobald_du__43))
            .setNegativeButton(getString(R.string.ui_abbrechen_30), null)
            .setPositiveButton(getString(R.string.ui_pin_zurucksetzen_44), (d, which) -> askPin(true)).create());
    }

    private void showEmergencyTest() {
        LinearLayout content = emergencyDetails();
        TextView instructions = text(getString(R.string.emergency_test_steps), 18);
        instructions.setPadding(0, 0, 0, dp(12));
        content.addView(instructions, 0);
        dialog(new AlertDialog.Builder(this).setTitle(R.string.emergency_test)
            .setView(emergencyScroll(content)).setPositiveButton(android.R.string.ok, null).create());
    }

    private void showAppearance() {
        LinearLayout content = emergencyContent();
        Switch light = new Switch(this);
        light.setText(R.string.light_appearance);
        light.setTextSize(20);
        light.setMinHeight(dp(56));
        light.setChecked(prefs.getBoolean("light_appearance", false));
        content.addView(light);
        int savedRadius = prefs.getInt("corner_radius", 14);
        TextView label = text(getString(R.string.corner_radius, savedRadius), 20);
        content.addView(label);
        SeekBar radius = new SeekBar(this);
        radius.setMax(32);
        radius.setProgress(savedRadius);
        radius.setContentDescription(getString(R.string.corner_radius_name));
        content.addView(radius);
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                label.setText(getString(R.string.corner_radius, value));
            }
        });
        dialog(new AlertDialog.Builder(this).setTitle(R.string.appearance)
            .setView(emergencyScroll(content)).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                boolean themeChanged = currentTheme() == Theme.CUSTOM && light.isChecked() != lightAppearance();
                prefs.edit().putBoolean("light_appearance", light.isChecked())
                    .putInt("corner_radius", radius.getProgress()).apply();
                if (themeChanged) recreate();
                else showAdmin();
            }).create());
    }

    private boolean tileSettingsVisible;

    private void showAdmin() {
        tileSettingsVisible = false;
        admin = true;
        sorting = false;
        page();
        root.addView(button(getString(R.string.ui_sperren_und_zuruck_45), this::showHome));
        ScrollView scroll = new ScrollView(this);
        LinearLayout controls = column();
        scroll.addView(controls);
        root.addView(scroll);
        Switch quickApps = headerVisibilitySwitch(Symbol.APPS);
        controls.addView(quickApps);
        Switch quickSettings = headerVisibilitySwitch(Symbol.SETTINGS);
        controls.addView(quickSettings);
        LinearLayout home = settingsGroup(controls, R.string.settings_home, 0xffcbdcf5);
        home.addView(button(getString(R.string.count, tiles.length(), MAX_TILES), this::showTileSettings));
        home.addView(button(getString(R.string.first_page_layout), this::chooseFirstPageLayout));
        home.addView(button(getString(R.string.layout_profiles), this::showProfiles));
        home.addView(button(getString(R.string.language), this::chooseLanguage));
        home.addView(button(getString(R.string.appearance), this::showAppearance));
        home.addView(button(getString(R.string.design_themes), this::chooseTheme));
        LinearLayout header = settingsGroup(controls, R.string.header_settings, 0xffdce0f5);
        header.addView(button(getString(R.string.header_order), this::reorderHeader));
        for (Symbol symbol : headerOrder()) {
            Button color = new Button(this);
            color.setAllCaps(false);
            color.setTextSize(19);
            color.setMinHeight(dp(56));
            color.setTag(symbol);
            color.setOnClickListener(view -> chooseHeaderColor(symbol, () -> updateHeaderColorButton(color, symbol)));
            LinearLayout.LayoutParams colorLayout = new LinearLayout.LayoutParams(-1, -2);
            colorLayout.topMargin = dp(6);
            header.addView(color, colorLayout);
        }
        LinearLayout system = settingsGroup(controls, R.string.settings_system, 0xffcce9da);
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            system.addView(button(getString(R.string.ui_startpertap_als_standard_wahlen_46), this::requestHome));
        }
        system.addView(button(getString(R.string.ui_anderen_launcher_wahlen_47), this::openHomeSettings));
        Switch hideNavigationBar = new Switch(this);
        hideNavigationBar.setText(R.string.hide_navigation_bar);
        hideNavigationBar.setTextColor(foreground());
        hideNavigationBar.setTextSize(19);
        hideNavigationBar.setMinHeight(dp(56));
        hideNavigationBar.setChecked(prefs.getBoolean("hide_navigation_bar", false));
        hideNavigationBar.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean("hide_navigation_bar", enabled).apply();
            applyNavigationBarPreference();
        });
        system.addView(hideNavigationBar);
        system.addView(text(getString(R.string.reveal_navigation_bar), 16));
        LinearLayout emergency = settingsGroup(controls, R.string.emergency, 0xfff4cbd2);
        Switch emergencyAtStart = new Switch(this);
        emergencyAtStart.setText(R.string.emergency_at_start);
        emergencyAtStart.setTextColor(foreground());
        emergencyAtStart.setTextSize(19);
        emergencyAtStart.setMinHeight(dp(56));
        emergencyAtStart.setChecked(prefs.getBoolean("emergency_at_start", false));
        emergencyAtStart.setOnCheckedChangeListener((view, enabled) ->
            prefs.edit().putBoolean("emergency_at_start", enabled).apply());
        emergency.addView(emergencyAtStart);
        emergency.addView(button(getString(R.string.emergency_test), this::showEmergencyTest));
        emergency.addView(button(getString(R.string.emergency_setup), this::editEmergency));
        emergency.addView(button(getString(R.string.emergency_pick_pdf), this::pickEmergencyPdf));
        if (prefs.contains("emergency_pdf")) {
            emergency.addView(button(getString(R.string.emergency_remove_pdf), () -> {
                prefs.edit().remove("emergency_pdf").apply();
                showAdmin();
            }));
        }
        system.addView(button(getString(R.string.ui_alle_apps_50), this::showAllApps));
        system.addView(button(getString(R.string.ui_ruhemodus_freigeben_52), () -> open(
            new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), getString(R.string.ui_einstellung_nicht_verfugbar_53))));
        system.addView(button(getString(R.string.ui_bildschirmvergro_erung_einrichten_54), () -> open(
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), getString(R.string.ui_einstellung_nicht_verfugbar_53))));
        styleSettingsButtons(home);
        styleSettingsButtons(header);
        for (int i = 0; i < header.getChildCount(); i++) {
            View control = header.getChildAt(i);
            if (control instanceof Button && control.getTag() instanceof Symbol) {
                updateHeaderColorButton((Button) control, (Symbol) control.getTag());
            }
        }
        styleSettingsButtons(system);
        styleSettingsButtons(emergency);
        controls.addView(button(getString(R.string.about), this::showAbout));
    }

    private Switch headerVisibilitySwitch(Symbol symbol) {
        Switch visible = new Switch(this);
        visible.setText(getString(R.string.header_show_symbol, getString(symbol.title)));
        visible.setTextColor(foreground());
        visible.setTextSize(19);
        visible.setMinHeight(dp(56));
        visible.setChecked(headerVisible(symbol));
        visible.setOnCheckedChangeListener((view, enabled) ->
            prefs.edit().putBoolean(symbol.visibilityPreference(), enabled).apply());
        return visible;
    }

    private LinearLayout settingsGroup(LinearLayout parent, int title, int pastel) {
        LinearLayout group = column();
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(12);
        parent.addView(group, layout);

        String label = getString(title);
        LinearLayout content = column();
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.setBackground(rounded(lightAppearance() ? 0xffeeeeee : 0xff191919));
        content.setVisibility(View.GONE);
        Button header = new Button(this);
        header.setText(label + "  ▾");
        header.setAllCaps(false);
        header.setTextSize(19);
        header.setMinHeight(dp(56));
        header.setTextColor(BLACK);
        Theme theme = currentTheme();
        int sectionColor = theme == Theme.MONO || theme == Theme.CONTRAST ? theme.colorAt(0) : pastel;
        header.setBackground(new RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33000000), rounded(sectionColor), null));
        header.setOnClickListener(view -> {
            boolean expand = content.getVisibility() == View.GONE;
            content.setVisibility(expand ? View.VISIBLE : View.GONE);
            header.setText(label + (expand ? "  ▴" : "  ▾"));
            if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(getString(expand ? R.string.section_expanded : R.string.section_collapsed));
        });
        if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(getString(R.string.section_collapsed));
        group.addView(header);
        group.addView(content);
        return content;
    }

    private void styleSettingsButtons(LinearLayout content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child instanceof Button) || child instanceof Switch) continue;
            Button control = (Button) child;
            control.setTextColor(foreground());
            control.setPadding(dp(12), dp(6), dp(12), dp(6));
            control.setBackground(new RippleDrawable(
                android.content.res.ColorStateList.valueOf(lightAppearance() ? 0x33000000 : 0x33ffffff),
                rounded(lightAppearance() ? WHITE : 0xff303030), null));
            LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) control.getLayoutParams();
            layout.topMargin = dp(6);
        }
    }

    private void reorderHeader() {
        List<Symbol> order = headerOrder();
        Map<Symbol, Boolean> visibility = new EnumMap<>(Symbol.class);
        for (Symbol symbol : order) visibility.put(symbol, headerVisible(symbol));
        LinearLayout rows = emergencyContent();
        populateHeaderOrder(rows, order, visibility);
        dialog(new AlertDialog.Builder(this).setTitle(R.string.header_order)
            .setView(emergencyScroll(rows)).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_speichern_93, (d, which) -> {
                List<String> names = new ArrayList<>();
                SharedPreferences.Editor changes = prefs.edit();
                for (Symbol symbol : order) {
                    names.add(symbol.name());
                    changes.putBoolean(symbol.visibilityPreference(), visibility.get(symbol));
                }
                changes.putString("header_order", TextUtils.join(",", names)).apply();
                showAdmin();
            }).create());
    }

    private void populateHeaderOrder(LinearLayout rows, List<Symbol> order, Map<Symbol, Boolean> visibility) {
        rows.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            int position = i;
            Symbol symbol = order.get(i);
            String name = getString(symbol.title);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Switch visible = new Switch(this);
            visible.setText((i + 1) + ". " + name);
            visible.setTextSize(19);
            visible.setMinHeight(dp(56));
            visible.setChecked(visibility.get(symbol));
            visible.setOnCheckedChangeListener((view, checked) -> visibility.put(symbol, checked));
            row.addView(visible, new LinearLayout.LayoutParams(0, -2, 1));
            Button up = button("↑", () -> moveHeaderSymbol(rows, order, visibility, position, -1));
            up.setContentDescription(getString(R.string.header_move_up, name));
            up.setEnabled(i > 0);
            Button down = button("↓", () -> moveHeaderSymbol(rows, order, visibility, position, 1));
            down.setContentDescription(getString(R.string.header_move_down, name));
            down.setEnabled(i < order.size() - 1);
            row.addView(up, new LinearLayout.LayoutParams(dp(56), -2));
            row.addView(down, new LinearLayout.LayoutParams(dp(56), -2));
            rows.addView(row);
        }
    }

    private void moveHeaderSymbol(LinearLayout rows, List<Symbol> order, Map<Symbol, Boolean> visibility,
                                  int position, int direction) {
        int next = position + direction;
        Collections.swap(order, position, next);
        populateHeaderOrder(rows, order, visibility);
        LinearLayout moved = (LinearLayout) rows.getChildAt(next);
        View control = moved.getChildAt(direction < 0 && next > 0 || next == order.size() - 1 ? 1 : 2);
        control.requestFocus();
    }

    private void chooseTheme() {
        LinearLayout content = emergencyContent();
        RadioGroup options = new RadioGroup(this);
        Theme[] themes = Theme.values();
        for (Theme theme : themes) {
            RadioButton choice = new RadioButton(this);
            choice.setId(theme.ordinal() + 1);
            choice.setText(theme.title);
            choice.setTextSize(19);
            choice.setMinHeight(dp(48));
            options.addView(choice);
        }
        content.addView(options);
        content.addView(text(getString(R.string.theme_preview), 19));
        LinearLayout preview = column();
        preview.setPadding(dp(6), dp(10), dp(6), dp(10));
        content.addView(preview);
        options.setOnCheckedChangeListener((group, id) -> previewTheme(preview, themes[id - 1]));
        options.check(currentTheme().ordinal() + 1);
        content.addView(text(getString(R.string.theme_custom_note), 16));
        dialog(new AlertDialog.Builder(this).setTitle(R.string.design_themes)
            .setView(emergencyScroll(content)).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_speichern_93, (d, which) -> {
                Theme chosen = themes[options.getCheckedRadioButtonId() - 1];
                if (chosen == currentTheme()) return;
                boolean wasLight = lightAppearance();
                prefs.edit().putString("design_theme", chosen.name()).apply();
                if (wasLight != lightAppearance()) recreate();
                else showHome();
            }).create());
    }

    private void previewTheme(LinearLayout preview, Theme theme) {
        preview.removeAllViews();
        int surface = theme == Theme.CUSTOM
            ? (prefs.getBoolean("light_appearance", false) ? WHITE : BLACK) : theme.surface;
        preview.setBackground(rounded(surface));
        LinearLayout row = null;
        for (int i = 0; i < Math.min(4, tiles.length()); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                preview.addView(row);
            }
            JSONObject tile = tiles.optJSONObject(i);
            int color = theme == Theme.CUSTOM ? tile.optInt("color", BLUE) : theme.colorAt(i);
            TextView sample = text(tile.optString("label"), 16);
            sample.setGravity(Gravity.CENTER);
            sample.setMinHeight(dp(64));
            sample.setTextColor(contrastingInk(color));
            sample.setBackground(rounded(color));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(sample, size);
        }
    }

    private void updateHeaderColorButton(Button button, Symbol symbol) {
        String key = symbol.colorPreference();
        boolean legacyRed = symbol == Symbol.EMERGENCY && !prefs.contains(key)
            && prefs.getBoolean("emergency_red_background", false);
        boolean custom = prefs.contains(key) || legacyRed;
        int standard = lightAppearance() ? WHITE : 0xff303030;
        int color = prefs.getInt(key, legacyRed ? 0xffa92530 : standard);
        int ink = custom ? contrastingInk(color) : foreground();
        String name = getString(R.string.symbol_default_color);
        if (custom) {
            String[] names = getResources().getStringArray(R.array.symbol_palette_names);
            name = legacyRed ? names[2] : String.format(Locale.ROOT, "#%06X", color & 0xffffff);
            for (int i = 0; i < PALETTE.length; i++) {
                if (Color.parseColor(PALETTE[i]) == color) {
                    name = names[i];
                    break;
                }
            }
        }
        String label = getString(R.string.header_symbol_color, getString(symbol.title));
        button.setText(label);
        button.setContentDescription(label + ", " + name);
        button.setTextColor(ink);
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(new RippleDrawable(
            android.content.res.ColorStateList.valueOf((ink & 0x00ffffff) | 0x33000000), rounded(color), null));
    }

    private void chooseHeaderColor(Symbol symbol, Runnable onColorChanged) {
        String key = symbol.colorPreference();
        LinearLayout options = emergencyContent();
        AlertDialog picker = new AlertDialog.Builder(this).setTitle(symbol.title)
            .setView(emergencyScroll(options))
            .setNeutralButton(R.string.symbol_default_color, (dialog, which) -> {
                SharedPreferences.Editor changes = prefs.edit().remove(key);
                if (symbol == Symbol.EMERGENCY) changes.remove("emergency_red_background");
                changes.apply();
                onColorChanged.run();
            })
            .setNegativeButton(android.R.string.cancel, null).create();
        String[] names = getResources().getStringArray(R.array.symbol_palette_names);
        for (int i = 0; i < PALETTE.length; i++) {
            int color = Color.parseColor(PALETTE[i]);
            Button choice = button(names[i], () -> {
                prefs.edit().putInt(key, color).apply();
                onColorChanged.run();
                picker.dismiss();
            });
            choice.setTextColor(contrastingInk(color));
            choice.setBackground(rounded(color));
            Drawable icon = symbolDrawable(symbol, color, false);
            icon.setBounds(0, 0, dp(28), dp(28));
            choice.setCompoundDrawablesRelative(icon, null, null, null);
            choice.setPadding(dp(12), 0, dp(12), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            options.addView(choice, params);
        }
        dialog(picker);
    }

    private void chooseFirstPageLayout() {
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        RadioGroup options = new RadioGroup(this);
        for (int capacity : new int[]{2, 4, 6, PAGE_SIZE}) {
            RadioButton option = new RadioButton(this);
            option.setId(capacity);
            option.setText(capacity == PAGE_SIZE ? getString(R.string.first_page_standard)
                : getString(R.string.first_page_capacity, capacity));
            option.setTextSize(19);
            option.setMinHeight(dp(48));
            options.addView(option);
        }
        content.addView(options);
        TextView summary = text("", 18);
        content.addView(summary);
        LinearLayout preview = column();
        content.addView(preview);
        options.setOnCheckedChangeListener((group, capacity) -> {
            int visible = Math.min(capacity, tiles.length());
            summary.setText(getString(R.string.first_page_preview, visible, tiles.length()));
            preview.removeAllViews();
            populateTileGrid(preview, 0, visible, false);
        });
        options.check(firstPageSize());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        dialog(new AlertDialog.Builder(this).setTitle(R.string.first_page_layout)
            .setView(scroll)
            .setNegativeButton(R.string.ui_abbrechen_30, null)
            .setPositiveButton(R.string.ui_speichern_93, (dialog, which) -> {
                prefs.edit().putInt("first_page_size", options.getCheckedRadioButtonId()).apply();
                homePage = 0;
            }).create());
    }

    private void showTileSettings() {
        sorting = false;
        tileSettingsVisible = true;
        page();
        root.addView(button(getString(R.string.ui_einstellungen_2), this::showAdmin));
        LinearLayout controls = column();
        root.addView(emergencyScroll(controls), new LinearLayout.LayoutParams(-1, 0, 1));
        controls.addView(button(getString(R.string.ui_kacheln_sortieren_48), () -> showSort(0)));
        for (int i = 0; i < tiles.length(); i++) {
            int index = i;
            controls.addView(button((i + 1) + ". " + tiles.optJSONObject(i).optString("label"),
                () -> editTile(index)));
        }
        if (tiles.length() < MAX_TILES) controls.addView(button(getString(R.string.ui_kachel_hinzufugen_49), () -> editTile(tiles.length())));
    }

    private void chooseLanguage() {
        String selected = prefs.getString("language", "");
        int index = Arrays.asList(LANGUAGE_TAGS).indexOf(selected);
        String[] labels = LANGUAGE_NAMES.clone();
        labels[0] = getString(R.string.system);
        dialog(new AlertDialog.Builder(this).setTitle(R.string.language)
            .setSingleChoiceItems(labels, index, (d, choice) -> {
                d.dismiss();
                if (selected.equals(LANGUAGE_TAGS[choice])) return;
                prefs.edit().putString("language", LANGUAGE_TAGS[choice]).apply();
                recreate();
            }).create());
    }

    private void showAbout() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(22), dp(12), dp(22), dp(16));
        scroll.addView(content);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            content.addView(text(getString(R.string.version, version), 24));
        } catch (PackageManager.NameNotFoundException ignored) { }
        TextView description = text(getString(R.string.aboutbody), 20);
        description.setPadding(0, dp(12), 0, 0);
        content.addView(description);
        content.addView(button(getString(R.string.guide), () -> openAboutDetails(R.string.guide)));
        content.addView(button(getString(R.string.changelog), () -> openAboutDetails(R.string.changelog)));
        dialog(new AlertDialog.Builder(this).setTitle(R.string.about)
            .setView(scroll).setPositiveButton(android.R.string.ok, null).create());
    }

    private void openAboutDetails(int title) {
        if (active != null) active.dismiss();
        LinearLayout content = emergencyContent();
        if (title == R.string.guide) {
            content.addView(text(getString(R.string.guidebody), 20));
        } else {
            int[] entries = {R.string.changes_024, R.string.changes_023, R.string.changes_022, R.string.changes_021, R.string.changes_020, R.string.changes_019,
                R.string.changes_018, R.string.changes_017};
            for (int i = 0; i < entries.length; i++) {
                TextView version = text(getString(R.string.version, "0." + (24 - i)), 24);
                version.setPadding(0, dp(14), 0, dp(4));
                content.addView(version);
                content.addView(text(getString(entries[i]), 20));
            }
        }
        dialog(new AlertDialog.Builder(this).setTitle(title).setView(emergencyScroll(content))
            .setPositiveButton(android.R.string.ok, (d, which) -> showAbout()).create());
    }

    private void showSort(int sortPage) {
        sorting = true;
        page();
        root.addView(button(getString(R.string.ui_fertig_55), this::showTileSettings));
        LinearLayout tabs = new LinearLayout(this);
        for (int p = 0; p < pageCount(); p++) {
            int destination = p;
            Button tab = button(getString(R.string.tab, p + 1), () -> showSort(destination));
            tab.setEnabled(p != sortPage);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
            int pageStart = pageStart(p);
            if (p != sortPage) tab.setOnDragListener((view, event) ->
                handleSortDrop(event, pageStart));
        }
        root.addView(tabs);
        root.addView(text(getString(R.string.ui_kachel_halten_und_ziehen_fur_eine_andere_seite_a_56), 18));
        ScrollView scroll = new ScrollView(this);
        LinearLayout items = column();
        scroll.addView(items);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int end = Math.min(tiles.length(), pageStart(sortPage + 1));
        for (int i = pageStart(sortPage); i < end; i++) {
            int index = i;
            String label = tiles.optJSONObject(i).optString("label");
            Button item = button((i + 1) + ". " + label, () -> chooseSortPosition(index, label));
            item.setOnLongClickListener(view -> view.startDragAndDrop(
                ClipData.newPlainText("Kachel", ""), new View.DragShadowBuilder(view), index, 0));
            item.setOnDragListener((view, event) -> handleSortDrop(event, index));
            items.addView(item);
        }
    }

    private boolean handleSortDrop(DragEvent event, int destination) {
        if (!(event.getLocalState() instanceof Integer)) return false;
        if (event.getAction() == DragEvent.ACTION_DROP) {
            reorderTile((Integer) event.getLocalState(), destination);
        }
        return true;
    }

    private void chooseSortPosition(int index, String label) {
        String[] positions = new String[tiles.length()];
        for (int i = 0; i < positions.length; i++) {
            int page = pageForTile(i);
            positions[i] = getString(R.string.position, page + 1, i - pageStart(page) + 1);
        }
        dialog(new AlertDialog.Builder(this).setTitle(getString(R.string.move, label))
            .setItems(positions, (d, destination) -> reorderTile(index, destination))
            .setNegativeButton(getString(R.string.ui_abbrechen_30), null).create());
    }

    private void reorderTile(int from, int to) {
        if (from != to) {
            JSONObject moved = tiles.optJSONObject(from);
            int step = from < to ? 1 : -1;
            for (int index = from; index != to; index += step) {
                putTile(index, tiles.optJSONObject(index + step));
            }
            putTile(to, moved);
            prefs.edit().putString("tiles", tiles.toString()).apply();
        }
        showSort(pageForTile(to));
    }

    private void requestHome() {
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1);
        } else {
            openHomeSettings();
        }
    }

    private void openHomeSettings() {
        open(new Intent(Settings.ACTION_HOME_SETTINGS), getString(R.string.ui_startseiten_einstellung_nicht_verfugbar_57));
    }

    private interface AppSelection { void selected(ResolveInfo app); }
    private interface ContactSelection { void selected(int index); }

    private ComponentName component(ResolveInfo app) {
        return new ComponentName(app.activityInfo.packageName, app.activityInfo.name);
    }

    private void showAllApps() {
        chooseApp(getString(R.string.ui_alle_apps_50), app ->
            open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component(app)), getString(R.string.ui_diese_app_ist_nicht_verfugbar_9)));
    }

    private void chooseApp(String title, AppSelection selection) {
        List<ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        apps.removeIf(app -> app.activityInfo.packageName.equals(getPackageName()));
        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo app : apps) labels.put(app, app.loadLabel(getPackageManager()).toString());
        Locale language = getResources().getConfiguration().getLocales().get(0);
        apps.sort(Comparator.comparing(labels::get, java.text.Collator.getInstance(language)));
        LinearLayout content = column();
        content.setPadding(dp(16), 0, dp(16), 0);
        content.setFocusableInTouchMode(true);
        EditText search = field(getString(R.string.app_search), InputType.TYPE_CLASS_TEXT);
        search.setContentDescription(getString(R.string.app_search));
        content.addView(search);
        ListView list = new ListView(this);
        TextView empty = text(getString(R.string.app_search_empty), 20);
        empty.setPadding(0, dp(10), 0, dp(10));
        content.addView(empty);
        content.addView(list, new LinearLayout.LayoutParams(-1,
            dp(Math.min(420, getResources().getConfiguration().screenHeightDp / 2))));
        list.setEmptyView(empty);
        ArrayAdapter<ResolveInfo> matches = new ArrayAdapter<ResolveInfo>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>(apps)) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setText(labels.get(getItem(position)));
                row.setTextSize(24);
                row.setMinHeight(dp(56));
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                return row;
            }
        };
        list.setAdapter(matches);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(language);
                matches.setNotifyOnChange(false);
                matches.clear();
                for (ResolveInfo app : apps) {
                    if (labels.get(app).toLowerCase(language).contains(query)) matches.add(app);
                }
                matches.notifyDataSetChanged();
                list.setSelection(0);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        AlertDialog picker = new AlertDialog.Builder(this).setTitle(title).setView(content)
            .setNegativeButton(getString(R.string.ui_zuruck_58), null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo app = matches.getItem(position);
            picker.dismiss();
            selection.selected(app);
        });
        content.requestFocus();
        dialog(picker);
    }

    private void chooseContact(EditText name, EditText phone, EditText link, AlertDialog editor,
        boolean whatsapp) {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 21);
            notice(getString(R.string.ui_nach_der_freigabe_die_kontaktauswahl_erneut_offn_59));
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        try (Cursor rows = getContentResolver().query(Phone.CONTENT_URI,
            new String[]{Phone.DISPLAY_NAME, Phone.NUMBER}, null, null, Phone.SORT_KEY_PRIMARY + " ASC")) {
            while (rows != null && rows.moveToNext()) {
                names.add(rows.getString(0));
                numbers.add(rows.getString(1));
            }
        } catch (SecurityException e) {
            notice(getString(R.string.ui_kontakte_sind_nicht_zuganglich_60));
            return;
        }
        if (names.isEmpty()) {
            notice(getString(R.string.ui_keine_kontakte_mit_telefonnummer_gefunden_61));
            return;
        }
        String[] labels = new String[names.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = names.get(i) + "\n" + numbers.get(i);
        editor.hide();
        ContactSelection select = index -> {
                String number = cleanPhone(numbers.get(index));
                if (whatsapp) {
                    String url = whatsappUrl(number);
                    if (url.isEmpty()) {
                        notice(getString(R.string.ui_bitte_den_kontakt_mit_landesvorwahl_speichern_z__62));
                        return;
                    }
                    name.setText(names.get(index));
                    link.setText(url);
                    phone.setText("");
                    return;
                }
                name.setText(names.get(index));
                phone.setText(number);
                link.setText("");
        };
        if (whatsapp) {
            searchableContacts(labels, names, numbers, select);
        } else {
            dialog(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_kontakt_und_nummer_wahlen_63))
                .setItems(labels, (d, index) -> select.selected(index))
                .setNegativeButton(getString(R.string.ui_zuruck_58), null).create());
        }
        returnToEditor(editor);
    }

    private void searchableContacts(String[] labels, List<String> names, List<String> numbers,
        ContactSelection select) {
        LinearLayout content = column();
        content.setPadding(dp(16), 0, dp(16), 0);
        EditText search = field(getString(R.string.ui_name_oder_telefonnummer_64), InputType.TYPE_CLASS_TEXT);
        search.setContentDescription(getString(R.string.ui_whatsapp_kontakte_durchsuchen_65));
        content.addView(search);
        TextView empty = text(getString(R.string.ui_keine_passenden_kontakte_66), 18);
        empty.setPadding(0, dp(12), 0, dp(12));
        content.addView(empty);
        ListView list = new ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        content.setMinimumHeight(dp(280));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        List<Integer> visible = new ArrayList<>();
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            String digits = cleanPhone(query);
            boolean byPhone = digits.matches("\\+?[0-9]+");
            visible.clear();
            adapter.clear();
            for (int index = 0; index < labels.length; index++) {
                String name = Objects.toString(names.get(index), "").toLowerCase(Locale.ROOT);
                if (name.contains(query) || (byPhone && cleanPhone(numbers.get(index)).contains(digits))) {
                    visible.add(index);
                    adapter.add(labels[index]);
                }
            }
            adapter.notifyDataSetChanged();
            empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable s) {}
        });
        AlertDialog picker = new AlertDialog.Builder(this).setTitle(getString(R.string.ui_whatsapp_kontakt_wahlen_67))
            .setView(content).setNegativeButton(getString(R.string.ui_zuruck_58), null).create();
        picker.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        list.setOnItemClickListener((parent, view, position, id) -> {
            select.selected(visible.get(position));
            picker.dismiss();
        });
        filter.run();
        dialog(picker);
    }

    private static String cleanPhone(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Z}\\s()./-]", "");
    }

    private String whatsappUrl(String number) {
        String international = number;
        if (number.startsWith("00")) {
            international = "+" + number.substring(2);
        } else if (number.startsWith("0")
            && "DE".equals(getApplicationContext().getResources().getConfiguration().getLocales().get(0).getCountry())) {
            international = "+49" + number.substring(1);
        }
        if (!international.matches("\\+[1-9][0-9]{5,14}")) return "";
        return "https://wa.me/" + international.substring(1);
    }

    private void returnToEditor(AlertDialog editor) {
        active.setOnDismissListener(d -> {
            if (!admin) {
                active = null;
                editor.dismiss();
                return;
            }
            active = editor;
            editor.show();
        });
    }

    private void editMeetingLink(boolean whatsapp, EditText name, EditText link, EditText phone,
        AlertDialog editor) {
        String title = whatsapp ? getString(R.string.ui_whatsapp_anruflink_68) : getString(R.string.ui_zoom_meeting_69);
        LinearLayout fields = column();
        fields.setPadding(dp(16), 0, dp(16), dp(8));
        fields.addView(text(whatsapp
            ? getString(R.string.ui_whatsapp_anrufe_neuer_anruflink_kopiere_den_link_70)
            : getString(R.string.ui_kopiere_den_einladungslink_aus_zoom_hierher_71), 18));
        EditText input = field(getString(R.string.ui_einladungslink_einfugen_72),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (isMeetingLink(link.getText().toString().trim(), whatsapp)) input.setText(link.getText());
        fields.addView(input);
        editor.hide();
        AlertDialog box = new AlertDialog.Builder(this).setTitle(title).setView(fields)
            .setNegativeButton(getString(R.string.ui_zuruck_58), null).setPositiveButton(getString(R.string.ui_ubernehmen_73), null).create();
        dialog(box);
        returnToEditor(editor);
        box.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!isMeetingLink(value, whatsapp)) {
                input.setError(whatsapp ? getString(R.string.ui_bitte_einen_whatsapp_anruflink_einfugen_74)
                    : getString(R.string.ui_bitte_einen_zoom_einladungslink_einfugen_75));
                return;
            }
            String previousName = name.getText().toString().trim();
            if (previousName.isEmpty() || previousName.matches("App [12]")) {
                name.setText(whatsapp ? getString(R.string.ui_whatsapp_anruf_76) : getString(R.string.ui_zoom_meeting_69));
            }
            phone.setText("");
            link.setText(value);
            box.dismiss();
        });
    }

    private static boolean isMeetingLink(String value, boolean whatsapp) {
        Uri uri = Uri.parse(value);
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || value.chars().anyMatch(Character::isWhitespace))
            return false;
        if (whatsapp) {
            String path = Objects.toString(uri.getPath(), "");
            return host.equals("call.whatsapp.com")
                && (path.startsWith("/voice/") || path.startsWith("/video/"))
                && path.substring(path.lastIndexOf('/') + 1).matches("[A-Za-z0-9_-]+");
        }
        return host.equals("zoom.us") || host.endsWith(".zoom.us")
            || host.equals("zoom.com") || host.endsWith(".zoom.com");
    }

    private JSONObject tile(String label, String app, String url, int color) {
        JSONObject tile = new JSONObject();
        try {
            tile.put("label", label).put("app", app).put("url", url).put("color", color);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return tile;
    }

    private void saveTiles() {
        persistTiles();
        showAdmin();
    }

    private void persistTiles() {
        prefs.edit().putString("tiles", tiles.toString()).apply();
        releaseUnusedTileFiles();
    }

    private void releaseUnusedTileFiles() {
        Set<String> retained = new HashSet<>();
        retained.add(prefs.getString("emergency_pdf", ""));
        retainTileFiles(tiles, retained);
        JSONObject profiles = savedProfiles();
        for (Iterator<String> keys = profiles.keys(); keys.hasNext();) {
            JSONObject profile = profiles.optJSONObject(keys.next());
            if (profile != null) retainTileFiles(profile.optJSONArray("tiles"), retained);
        }
        for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && !retained.contains(grant.getUri().toString())) {
                try {
                    getContentResolver().releasePersistableUriPermission(grant.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
        }
    }

    private JSONObject savedProfiles() {
        try {
            return new JSONObject(prefs.getString("layout_profiles", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private JSONObject layoutSnapshot() throws JSONException {
        return new JSONObject().put("format", "StartPerTap-layout").put("version", 1)
            .put("tiles", new JSONArray(tiles.toString())).put("firstPageSize", firstPageSize()).put("localFiles", 0);
    }

    private JSONObject portableLayout(JSONObject snapshot) throws JSONException {
        JSONObject portable = new JSONObject(snapshot.toString());
        JSONArray entries = portable.getJSONArray("tiles");
        int files = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!entry.optString("file").isEmpty()) files++;
            entry.remove("file");
            entry.remove("fileType");
        }
        return portable.put("localFiles", files);
    }

    private void showProfiles() {
        LinearLayout options = emergencyContent();
        AlertDialog menu = new AlertDialog.Builder(this).setTitle(R.string.layout_profiles)
            .setView(emergencyScroll(options)).setNegativeButton(R.string.ui_zuruck_58, null).create();
        options.addView(button(getString(R.string.layout_save_profile), () -> {
            menu.dismiss();
            nameProfile();
        }));
        options.addView(button(getString(R.string.layout_choose_profile), () -> {
            menu.dismiss();
            chooseProfile(false);
        }));
        options.addView(button(getString(R.string.layout_export), () -> {
            menu.dismiss();
            pickLayoutFile(true, null);
        }));
        options.addView(button(getString(R.string.layout_export_profile), () -> {
            menu.dismiss();
            chooseProfile(true);
        }));
        options.addView(button(getString(R.string.layout_import), () -> {
            menu.dismiss();
            pickLayoutFile(false, null);
        }));
        dialog(menu);
    }

    private void nameProfile() {
        LinearLayout content = emergencyContent();
        EditText name = field(getString(R.string.layout_profile_name), InputType.TYPE_CLASS_TEXT);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        content.addView(name);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle(R.string.layout_save_profile)
            .setView(content).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_speichern_93, null).create();
        dialog(editor);
        editor.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String key = name.getText().toString().trim();
            if (key.isEmpty()) {
                name.setError(getString(R.string.layout_profile_name));
                return;
            }
            JSONObject profiles = savedProfiles();
            if (profiles.has(key)) {
                editor.dismiss();
                dialog(new AlertDialog.Builder(this).setTitle(R.string.layout_replace)
                    .setMessage(key).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ui_speichern_93, (d, which) -> saveProfile(key)).create());
            } else if (profiles.length() >= MAX_PROFILES) {
                name.setError(getString(R.string.layout_profile_limit, MAX_PROFILES));
            } else {
                editor.dismiss();
                saveProfile(key);
            }
        });
    }

    private void saveProfile(String name) {
        try {
            JSONObject profiles = savedProfiles();
            profiles.put(name, layoutSnapshot());
            prefs.edit().putString("layout_profiles", profiles.toString()).apply();
            releaseUnusedTileFiles();
            notice(getString(R.string.layout_saved));
        } catch (JSONException e) {
            notice(getString(R.string.layout_error));
        }
    }

    private void chooseProfile(boolean export) {
        JSONObject profiles = savedProfiles();
        if (profiles.length() == 0) {
            notice(getString(R.string.layout_no_profiles));
            return;
        }
        List<String> names = new ArrayList<>();
        profiles.keys().forEachRemaining(names::add);
        names.sort(java.text.Collator.getInstance(getResources().getConfiguration().getLocales().get(0)));
        dialog(new AlertDialog.Builder(this).setTitle(export ? R.string.layout_export_profile : R.string.layout_choose_profile)
            .setItems(names.toArray(new String[0]), (d, index) -> {
                String name = names.get(index);
                JSONObject snapshot = profiles.optJSONObject(name);
                if (snapshot != null) {
                    if (export) pickLayoutFile(true, name);
                    else previewLayout(snapshot, name);
                }
            })
            .setNegativeButton(android.R.string.cancel, null).create());
    }

    private void previewLayout(JSONObject snapshot, String profileName) {
        JSONArray entries = snapshot.optJSONArray("tiles");
        if (entries == null) return;
        LinearLayout content = emergencyContent();
        content.addView(text(getString(R.string.layout_replace_current), 19));
        int firstPage = Math.min(entries.length(), snapshot.optInt("firstPageSize", PAGE_SIZE));
        content.addView(text(getString(R.string.first_page_preview, firstPage, entries.length()), 19));
        int files = snapshot.optInt("localFiles");
        if (files > 0) content.addView(text(getString(R.string.layout_reselect_files, files), 19));
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            String name = entry.optString("label");
            if (entry.optBoolean("missingApp") || (!entry.optString("app").isEmpty()
                    && appIcon(entry.optString("app")) == null)) {
                name = getString(R.string.layout_missing_app, name);
            }
            content.addView(text(name, 20));
        }
        AlertDialog.Builder preview = new AlertDialog.Builder(this)
            .setTitle(profileName == null ? getString(R.string.layout_import) : profileName)
            .setView(emergencyScroll(content)).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.layout_apply, (d, which) -> {
                tiles = entries;
                prefs.edit().putString("tiles", tiles.toString())
                    .putInt("first_page_size", snapshot.optInt("firstPageSize", PAGE_SIZE)).apply();
                releaseUnusedTileFiles();
                homePage = 0;
                showHome();
            });
        if (profileName != null) preview.setNeutralButton(R.string.layout_delete, (d, which) ->
            dialog(new AlertDialog.Builder(this).setTitle(R.string.layout_delete).setMessage(profileName)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.layout_delete, (confirmation, choice) -> {
                    JSONObject profiles = savedProfiles();
                    profiles.remove(profileName);
                    prefs.edit().putString("layout_profiles", profiles.toString()).apply();
                    releaseUnusedTileFiles();
                }).create()));
        dialog(preview.create());
    }

    private void pickLayoutFile(boolean export, String profile) {
        Intent picker = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(export ? "application/json" : "*/*");
        if (export) {
            pendingExportProfile = profile;
            String suffix = profile == null ? "Sicherung" : profile.replaceAll("[^\\p{L}\\p{N} _-]", "_");
            picker.putExtra(Intent.EXTRA_TITLE, "StartPerTap-" + suffix + ".json");
        }
        try {
            startActivityForResult(picker, export ? EXPORT_LAYOUT : IMPORT_LAYOUT);
        } catch (ActivityNotFoundException e) {
            if (export) pendingExportProfile = null;
            notice(getString(R.string.layout_error));
        }
    }

    private void transferLayout(int request, Uri uri, String profile) {
        try {
            if (request == EXPORT_LAYOUT) {
                JSONObject snapshot = profile == null ? layoutSnapshot() : savedProfiles().getJSONObject(profile);
                byte[] data = portableLayout(snapshot).toString(2).getBytes(StandardCharsets.UTF_8);
                if (data.length > MAX_BACKUP_BYTES) throw new IOException();
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException();
                    output.write(data);
                }
                notice(getString(R.string.layout_saved));
                return;
            }
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new IOException();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > MAX_BACKUP_BYTES) throw new IOException();
                    bytes.write(buffer, 0, read);
                }
                JSONObject snapshot = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                previewLayout(validateBackup(snapshot), null);
            }
        } catch (IOException | JSONException | IllegalArgumentException | SecurityException e) {
            notice(getString(R.string.layout_error));
        }
    }

    private JSONObject validateBackup(JSONObject snapshot) throws JSONException {
        JSONArray entries = snapshot.getJSONArray("tiles");
        int capacity = snapshot.getInt("firstPageSize");
        if (!"StartPerTap-layout".equals(snapshot.optString("format")) || snapshot.optInt("version") != 1
                || entries.length() < 2 || entries.length() > MAX_TILES
                || (capacity != 2 && capacity != 4 && capacity != 6 && capacity != PAGE_SIZE)) {
            throw new JSONException("Unsupported layout");
        }
        Set<String> launchable = new HashSet<>();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (!app.activityInfo.packageName.equals(getPackageName())) {
                launchable.add(component(app).flattenToString());
            }
        }
        JSONArray clean = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String label = entry.getString("label");
            String url = entry.optString("url");
            String phone = entry.optString("phone");
            String app = entry.optString("app");
            Uri link = Uri.parse(url);
            if (label.trim().isEmpty() || label.length() > 256 || url.length() > 4096
                    || (!url.isEmpty() && (!"https".equalsIgnoreCase(link.getScheme())
                        || TextUtils.isEmpty(link.getHost()) || url.chars().anyMatch(Character::isWhitespace)))
                    || (!phone.isEmpty() && !phone.matches("\\+?[0-9]{3,20}"))
                    || (!phone.isEmpty() && !url.isEmpty())) {
                throw new JSONException("Invalid tile");
            }
            ComponentName target = ComponentName.unflattenFromString(app);
            String canonical = target == null ? "" : target.flattenToString();
            boolean missing = !app.isEmpty() && !launchable.contains(canonical);
            JSONObject value = tile(label, missing ? "" : canonical, url, entry.optInt("color", BLUE) | 0xff000000);
            value.put("phone", phone).put("whatsappCall", entry.optBoolean("whatsappCall")
                && !whatsappLinkNumber(url).isEmpty()).put("missingApp", missing || entry.optBoolean("missingApp"));
            int labelSize = entry.optInt("labelSize");
            for (int allowed : TILE_LABEL_SIZES) {
                if (allowed == labelSize) value.put("labelSize", labelSize);
            }
            Symbol symbol = tileSymbol(entry);
            if (symbol != null) value.put("symbol", symbol.name());
            clean.put(value);
        }
        return snapshot.put("tiles", clean).put("localFiles",
            Math.max(0, Math.min(entries.length(), snapshot.optInt("localFiles"))));
    }

    private void retainTileFiles(JSONArray entries, Set<String> retained) {
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null) retained.add(entry.optString("file"));
        }
    }

    private void editTile(int index) {
        boolean existing = index < tiles.length();
        JSONObject original = existing ? tiles.optJSONObject(index) : tile("", "", "", BLUE);
        String[] app = {original.optString("app")};
        LinearLayout fields = column();
        fields.setPadding(dp(16), 0, dp(16), dp(10));
        EditText name = field(getString(R.string.ui_name_der_kachel_77), InputType.TYPE_CLASS_TEXT);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        name.setText(original.optString("label"));
        EditText link = field(getString(R.string.ui_https_z_b_zoom_link_78), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setText(original.optString("url"));
        CheckBox direct = new CheckBox(this);
        direct.setText(getString(R.string.ui_whatsapp_direktanruf_ausprobieren_79));
        direct.setTextSize(19);
        direct.setChecked(original.optBoolean("whatsappCall"));
        direct.setVisibility(whatsappLinkNumber(link.getText().toString().trim()).isEmpty() ? View.GONE : View.VISIBLE);
        link.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                direct.setVisibility(whatsappLinkNumber(s.toString().trim()).isEmpty() ? View.GONE : View.VISIBLE);
            }
            public void afterTextChanged(Editable s) {}
        });
        EditText phone = field(getString(R.string.ui_telefonnummer_fur_direktanruf_80), InputType.TYPE_CLASS_PHONE);
        phone.setText(original.optString("phone"));
        EditText color = field(getString(R.string.ui_farbe_als_rrggbb_81), InputType.TYPE_CLASS_TEXT);
        color.setText(String.format(Locale.ROOT, "#%06X", original.optInt("color") & 0xffffff));
        Spinner labelSize = new Spinner(this, Spinner.MODE_DIALOG);
        labelSize.setPromptId(R.string.tile_label_size);
        labelSize.setMinimumHeight(dp(48));
        String[] sizes = new String[TILE_LABEL_SIZES.length];
        sizes[0] = getString(R.string.tile_label_size_auto);
        int selectedSize = 0;
        for (int i = 1; i < sizes.length; i++) {
            sizes[i] = getString(R.string.tile_label_size_value, TILE_LABEL_SIZES[i]);
            if (TILE_LABEL_SIZES[i] == original.optInt("labelSize")) selectedSize = i;
        }
        ArrayAdapter<String> sizeOptions = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sizes);
        sizeOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        labelSize.setAdapter(sizeOptions);
        labelSize.setSelection(selectedSize);
        TextView sizeLabel = text(getString(R.string.tile_label_size), 19);
        labelSize.setId(View.generateViewId());
        sizeLabel.setLabelFor(labelSize.getId());
        Symbol[] symbols = {Symbol.APPS, Symbol.QUIET, Symbol.EMERGENCY, Symbol.LOCK};
        Spinner displaySymbol = new Spinner(this, Spinner.MODE_DIALOG);
        displaySymbol.setPromptId(R.string.symbol_tile_setting);
        displaySymbol.setMinimumHeight(dp(48));
        String[] symbolOptions = new String[symbols.length + 1];
        symbolOptions[0] = getString(R.string.symbol_tile_none);
        int selectedSymbol = 0;
        Symbol existingSymbol = tileSymbol(original);
        for (int i = 0; i < symbols.length; i++) {
            symbolOptions[i + 1] = getString(symbols[i].title);
            if (symbols[i] == existingSymbol) selectedSymbol = i + 1;
        }
        ArrayAdapter<String> symbolAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, symbolOptions);
        symbolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySymbol.setAdapter(symbolAdapter);
        displaySymbol.setSelection(selectedSymbol);
        TextView symbolLabel = text(getString(R.string.symbol_tile_setting), 19);
        displaySymbol.setId(View.generateViewId());
        symbolLabel.setLabelFor(displaySymbol.getId());
        TextView preview = text("", 26);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(0, dp(10), 0, dp(10));
        Runnable refresh = () -> {
            String label = name.getText().toString().trim();
            preview.setText(label.isEmpty() ? getString(R.string.ui_farbvorschau_82) : label);
            int size = TILE_LABEL_SIZES[labelSize.getSelectedItemPosition()];
            preview.setTextSize(size == 0 ? 26 : size);
            try {
                int value = parseColor(color.getText().toString());
                preview.setBackground(rounded(value));
                preview.setTextColor(contrastingInk(value));
                int position = displaySymbol.getSelectedItemPosition();
                Drawable icon = position > 0 ? symbolDrawable(symbols[position - 1], value, false) : null;
                if (icon != null) icon.setBounds(0, 0, dp(56), dp(56));
                preview.setCompoundDrawables(null, icon, null, null);
            } catch (IllegalArgumentException ignored) { }
        };
        color.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(Editable s) {}
        });
        name.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(Editable s) {}
        });
        AdapterView.OnItemSelectedListener refreshSelection = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        labelSize.setOnItemSelectedListener(refreshSelection);
        displaySymbol.setOnItemSelectedListener(refreshSelection);
        AlertDialog[] editor = new AlertDialog[1];
        Button select = button(app[0].isEmpty() ? getString(R.string.ui_app_auswahlen_83) : getString(R.string.ui_app_wechseln_84), () -> {
            editor[0].hide();
            chooseApp(getString(R.string.ui_app_auswahlen_83), selected -> {
                app[0] = component(selected).flattenToString();
                link.setText("");
                phone.setText("");
                name.setText(selected.loadLabel(getPackageManager()));
            });
            returnToEditor(editor[0]);
        });
        fields.addView(name);
        fields.addView(sizeLabel);
        fields.addView(labelSize);
        fields.addView(symbolLabel);
        fields.addView(displaySymbol);
        fields.addView(preview);
        fields.addView(select);
        fields.addView(button(getString(R.string.media_choose), () -> {
            int chosenColor;
            try {
                chosenColor = parseColor(color.getText().toString());
            } catch (IllegalArgumentException e) {
                color.setError(getString(R.string.ui_beispiel_1558ca_98));
                return;
            }
            pickMedia(index, name.getText().toString().trim(), chosenColor,
                TILE_LABEL_SIZES[labelSize.getSelectedItemPosition()],
                displaySymbol.getSelectedItemPosition() > 0
                    ? symbols[displaySymbol.getSelectedItemPosition() - 1].name() : "");
        }));
        fields.addView(button(getString(R.string.ui_whatsapp_anruflink_einfugen_85),
            () -> editMeetingLink(true, name, link, phone, editor[0])));
        fields.addView(button(getString(R.string.ui_zoom_meeting_einfugen_86),
            () -> editMeetingLink(false, name, link, phone, editor[0])));
        fields.addView(link);
        fields.addView(button(getString(R.string.ui_whatsapp_kontakt_auswahlen_87),
            () -> chooseContact(name, phone, link, editor[0], true)));
        fields.addView(direct);
        fields.addView(button(getString(R.string.ui_kontakt_fur_telefonanruf_auswahlen_88),
            () -> chooseContact(name, phone, link, editor[0], false)));
        fields.addView(phone);
        fields.addView(color);
        LinearLayout palette = new LinearLayout(this);
        for (String swatch : PALETTE) {
            Button chip = button("", () -> color.setText(swatch));
            chip.setMinWidth(0);
            chip.setMinimumWidth(0);
            chip.setContentDescription(getString(R.string.color, swatch));
            chip.setBackground(rounded(Color.parseColor(swatch)));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, dp(48), 1);
            space.setMargins(dp(2), dp(4), dp(2), dp(4));
            palette.addView(chip, space);
        }
        fields.addView(palette);
        refresh.run();
        if (existing) {
            if (index > 0) fields.addView(button(getString(R.string.ui_eine_position_nach_vorne_89), () -> move(editor[0], index, index - 1)));
            if (index < tiles.length() - 1) fields.addView(button(getString(R.string.ui_eine_position_nach_hinten_90), () -> move(editor[0], index, index + 1)));
            if (tiles.length() > 2) fields.addView(button(getString(R.string.ui_kachel_entfernen_91), () -> {
                editor[0].dismiss();
                tiles.remove(index);
                saveTiles();
            }));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        editor[0] = new AlertDialog.Builder(this).setTitle(getString(R.string.ui_kachel_bearbeiten_92)).setView(scroll)
            .setNegativeButton(getString(R.string.ui_abbrechen_30), null).setPositiveButton(getString(R.string.ui_speichern_93), null).create();
        dialog(editor[0]);
        editor[0].getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            int symbolPosition = displaySymbol.getSelectedItemPosition();
            Symbol symbol = symbolPosition > 0 ? symbols[symbolPosition - 1] : null;
            if (label.isEmpty() && symbol != null) label = getString(symbol.title);
            String url = link.getText().toString().trim();
            String number = cleanPhone(phone.getText().toString());
            if (label.isEmpty()) {
                name.setError(getString(R.string.ui_bitte_einen_namen_eingeben_94));
                return;
            }
            if (!url.isEmpty()) {
                Uri uri = Uri.parse(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || TextUtils.isEmpty(uri.getHost())
                    || url.chars().anyMatch(Character::isWhitespace)) {
                    link.setError(getString(R.string.ui_bitte_einen_vollstandigen_https_link_eingeben_95));
                    return;
                }
            }
            if (!number.isEmpty() && !number.matches("\\+?[0-9]{3,20}")) {
                phone.setError(getString(R.string.ui_bitte_eine_gultige_telefonnummer_eingeben_96));
                return;
            }
            if (!number.isEmpty() && !url.isEmpty()) {
                phone.setError(getString(R.string.ui_bitte_link_oder_telefonnummer_hinterlegen_97));
                return;
            }
            int value;
            try {
                value = parseColor(color.getText().toString());
            } catch (IllegalArgumentException e) {
                color.setError(getString(R.string.ui_beispiel_1558ca_98));
                return;
            }
            JSONObject updated = tile(label, url.isEmpty() && number.isEmpty() ? app[0] : "", url, value);
            try {
                if (url.isEmpty() && number.isEmpty() && app[0].isEmpty() && original.has("file")) {
                    updated.put("file", original.optString("file"));
                    updated.put("fileType", original.optString("fileType"));
                }
                updated.put("phone", number);
                updated.put("labelSize", TILE_LABEL_SIZES[labelSize.getSelectedItemPosition()]);
                if (symbol != null) updated.put("symbol", symbol.name());
                updated.put("whatsappCall", direct.isChecked() && !whatsappLinkNumber(url).isEmpty());
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            putTile(index, updated);
            editor[0].dismiss();
            saveTiles();
        });
    }

    private static int parseColor(String value) {
        if (!value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException();
        return Color.parseColor(value);
    }

    private void putTile(int index, JSONObject tile) {
        try {
            tiles.put(index, tile);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void move(Dialog editor, int from, int to) {
        JSONObject old = tiles.optJSONObject(from);
        putTile(from, tiles.optJSONObject(to));
        putTile(to, old);
        editor.dismiss();
        saveTiles();
    }
}
