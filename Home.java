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
    private float swipeX;
    private float swipeY;
    private boolean trackingSwipe;
    private String pendingCall;
    private static final int PICK_MEDIA = 40;
    private int pendingMediaTile = -1;
    private String pendingMediaLabel;
    private int pendingMediaColor;

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
        super.onCreate(state);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
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
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!admin && active == null) showHome();
    }

    @Override protected void onStop() {
        super.onStop();
        admin = false;
        if (active != null) active.dismiss();
        showHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        admin = false;
        if (active != null) active.dismiss();
        showHome();
    }

    @Override public void onBackPressed() {
        if (sorting) showAdmin();
        else if (admin) showHome();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            trackingSwipe = !admin && active == null && tiles.length() > PAGE_SIZE;
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

    private int pageCount() {
        return (tiles.length() + PAGE_SIZE - 1) / PAGE_SIZE;
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

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private void page() {
        root = column();
        root.setBackgroundColor(BLACK);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
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
        view.setTextColor(WHITE);
        return view;
    }

    private void showHome() {
        admin = false;
        sorting = false;
        homePage = Math.min(homePage, pageCount() - 1);
        page();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.END);
        int filter = notifications.getCurrentInterruptionFilter();
        boolean quiet = filter != NotificationManager.INTERRUPTION_FILTER_ALL
            && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        header.addView(iconButton(false, quiet, quiet ? getString(R.string.ui_ruhemodus_aktiv_0) : getString(R.string.ui_ruhemodus_aus_1), this::toggleQuiet));
        header.addView(iconButton(true, false, getString(R.string.ui_einstellungen_2), this::askPin));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout grid = column();
        grid.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        float scale = getResources().getConfiguration().fontScale;
        int width = getResources().getConfiguration().screenWidthDp;
        int offset = homePage * PAGE_SIZE;
        int count = Math.min(PAGE_SIZE, tiles.length() - offset);
        int columns = count <= 2 ? 1 : count <= 6 ? 2 : count <= 9 ? 3 : 4;
        if (width < 480) columns = Math.min(columns, 2);
        else if (width < 600 || scale > 1.25f) columns = Math.min(columns, 3);
        if (scale > 1.5f) columns = Math.min(columns, 2);
        int rows = (count + columns - 1) / columns;
        int tileHeight = count <= 2 ? 214 : 160;
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
                row.addView(tileView(tile, columns), size);
            }
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1,
                dp((int) (tileHeight * Math.max(1f, scale / 1.25f))), 1);
            grid.addView(row, size);
        }
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

    private ImageButton iconButton(boolean lock, boolean quiet, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(new HeaderIcon(lock, quiet));
        button.setBackground(rounded(0xff171717));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private final class HeaderIcon extends Drawable {
        private final boolean lock;
        private final boolean quiet;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HeaderIcon(boolean lock, boolean quiet) {
            this.lock = lock;
            this.quiet = quiet;
        }
        @Override public int getIntrinsicWidth() { return dp(28); }
        @Override public int getIntrinsicHeight() { return dp(28); }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            paint.setColor(WHITE);
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            if (lock) {
                canvas.drawRoundRect(8, 2, 16, 14, 4, 4, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(4, 9, 20, 22, 2, 2, paint);
                paint.setColor(BLACK);
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

    private View tileView(JSONObject tile, int columns) {
        int color = tile.optInt("color", BLUE);
        int ink = Color.luminance(color) >= 0.179f ? BLACK : WHITE;
        String title = tile.optString("label");
        LinearLayout face = column();
        face.setGravity(Gravity.CENTER);
        face.setPadding(dp(5), dp(7), dp(5), dp(7));
        face.setBackground(rounded(color));
        Drawable appIcon = appIcon(tile.optString("app"));
        if (appIcon != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(appIcon);
            int iconSize = dp(columns == 1 ? 88 : 64);
            face.addView(image, new LinearLayout.LayoutParams(iconSize, iconSize));
        } else {
            String glyph = tile.optString("url").isEmpty() && tile.optString("file").isEmpty() ? "＋" : "▶";
            if ("application/pdf".equals(tile.optString("fileType"))) glyph = "PDF";
            if (!tile.optString("phone").isEmpty()) glyph = "☎";
            TextView symbol = text(glyph, columns == 1 ? 72 : 54);
            symbol.setTextColor(ink);
            symbol.setGravity(Gravity.CENTER);
            symbol.setIncludeFontPadding(false);
            face.addView(symbol);
        }
        TextView label = text(title, columns == 1 ? 38 : 26);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(ink);
        label.setTypeface(null, Typeface.BOLD);
        face.addView(label);
        face.setContentDescription(title);
        face.setFocusable(true);
        face.setOnClickListener(v -> launchTile(tile));
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

    private void pickMedia(int index, String label, int color) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/pdf"});
        try {
            startActivityForResult(picker, PICK_MEDIA);
            pendingMediaTile = index;
            pendingMediaLabel = label;
            pendingMediaColor = color;
        } catch (ActivityNotFoundException e) {
            notice(getString(R.string.media_unavailable));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
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

    private void showAdmin() {
        admin = true;
        sorting = false;
        page();
        root.addView(button(getString(R.string.ui_sperren_und_zuruck_45), this::showHome));
        ScrollView scroll = new ScrollView(this);
        LinearLayout controls = column();
        scroll.addView(controls);
        root.addView(scroll);
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            controls.addView(button(getString(R.string.ui_startpertap_als_standard_wahlen_46), this::requestHome));
        }
        controls.addView(button(getString(R.string.ui_anderen_launcher_wahlen_47), this::openHomeSettings));
        controls.addView(button(getString(R.string.ui_kacheln_sortieren_48), () -> showSort(0)));
        controls.addView(button(getString(R.string.language), this::chooseLanguage));
        controls.addView(button(getString(R.string.guide), () -> showInformation(false)));
        controls.addView(button(getString(R.string.about), () -> showInformation(true)));
        controls.addView(text(getString(R.string.count, tiles.length(), MAX_TILES), 25));
        for (int i = 0; i < tiles.length(); i++) {
            int index = i;
            controls.addView(button((i + 1) + ". " + tiles.optJSONObject(i).optString("label"),
                () -> editTile(index)));
        }
        if (tiles.length() < MAX_TILES) controls.addView(button(getString(R.string.ui_kachel_hinzufugen_49), () -> editTile(tiles.length())));
        controls.addView(button(getString(R.string.ui_alle_apps_50), () -> chooseApp(getString(R.string.ui_app_offnen_51), app ->
            open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component(app)), getString(R.string.ui_diese_app_ist_nicht_verfugbar_9)))));
        controls.addView(button(getString(R.string.ui_ruhemodus_freigeben_52), () -> open(
            new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), getString(R.string.ui_einstellung_nicht_verfugbar_53))));
        controls.addView(button(getString(R.string.ui_bildschirmvergro_erung_einrichten_54), () -> open(
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), getString(R.string.ui_einstellung_nicht_verfugbar_53))));
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

    private void showInformation(boolean about) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(22), dp(12), dp(22), dp(16));
        scroll.addView(content);
        if (about) {
            try {
                String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                content.addView(text(getString(R.string.version, version), 24));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        TextView description = text(getString(about ? R.string.aboutbody : R.string.guidebody), 20);
        description.setPadding(0, dp(12), 0, 0);
        content.addView(description);
        dialog(new AlertDialog.Builder(this).setTitle(about ? R.string.about : R.string.guide)
            .setView(scroll).setPositiveButton(android.R.string.ok, null).create());
    }

    private void showSort(int sortPage) {
        sorting = true;
        page();
        root.addView(button(getString(R.string.ui_fertig_55), this::showAdmin));
        LinearLayout tabs = new LinearLayout(this);
        for (int p = 0; p < pageCount(); p++) {
            int destination = p;
            Button tab = button(getString(R.string.tab, p + 1), () -> showSort(destination));
            tab.setEnabled(p != sortPage);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
            int pageStart = p * PAGE_SIZE;
            if (p != sortPage) tab.setOnDragListener((view, event) ->
                handleSortDrop(event, pageStart));
        }
        root.addView(tabs);
        root.addView(text(getString(R.string.ui_kachel_halten_und_ziehen_fur_eine_andere_seite_a_56), 18));
        ScrollView scroll = new ScrollView(this);
        LinearLayout items = column();
        scroll.addView(items);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int end = Math.min(tiles.length(), (sortPage + 1) * PAGE_SIZE);
        for (int i = sortPage * PAGE_SIZE; i < end; i++) {
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
            positions[i] = getString(R.string.position, i / PAGE_SIZE + 1, i % PAGE_SIZE + 1);
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
        showSort(to / PAGE_SIZE);
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

    private void chooseApp(String title, AppSelection selection) {
        List<ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        apps.removeIf(app -> app.activityInfo.packageName.equals(getPackageName()));
        apps.sort(Comparator.comparing(app -> app.loadLabel(getPackageManager()).toString(),
            java.text.Collator.getInstance(getResources().getConfiguration().getLocales().get(0))));
        String[] labels = apps.stream().map(app -> app.loadLabel(getPackageManager()).toString()).toArray(String[]::new);
        dialog(new AlertDialog.Builder(this).setTitle(title).setItems(labels, (d, i) -> selection.selected(apps.get(i)))
            .setNegativeButton(getString(R.string.ui_zuruck_58), null).create());
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
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < tiles.length(); i++) retained.add(tiles.optJSONObject(i).optString("file"));
        for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && !retained.contains(grant.getUri().toString())) {
                try {
                    getContentResolver().releasePersistableUriPermission(grant.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
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
        TextView preview = text(getString(R.string.ui_farbvorschau_82), 20);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(0, dp(10), 0, dp(10));
        Runnable refresh = () -> {
            try {
                int value = parseColor(color.getText().toString());
                preview.setBackground(rounded(value));
                preview.setTextColor(Color.luminance(value) >= 0.179f ? BLACK : WHITE);
            } catch (IllegalArgumentException ignored) { }
        };
        color.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(Editable s) {}
        });
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
        fields.addView(select);
        fields.addView(button(getString(R.string.media_choose), () -> {
            int chosenColor;
            try {
                chosenColor = parseColor(color.getText().toString());
            } catch (IllegalArgumentException e) {
                color.setError(getString(R.string.ui_beispiel_1558ca_98));
                return;
            }
            pickMedia(index, name.getText().toString().trim(), chosenColor);
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
        fields.addView(preview);
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
