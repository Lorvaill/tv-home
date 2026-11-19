package ro.tinulica.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings laid out like tvOS. At the top level: the title, the TV on the left with a line
 * about the focused category under it, and the categories on the right. Opening a category
 * slides the categories over to the left and brings its pages in on the right; Left or Back
 * slides everything back. Pages open the TV's own settings (TCL picture and sound panels
 * included); a few rows are TV Home's own switches.
 */
public class SettingsActivity extends Activity {

    private static final String TV_SETTINGS = "com.android.tv.settings";

    static final class Item {
        String title;
        String detail;
        Intent intent;
        String toggleKey;
        boolean toggleDefault = true;
        boolean header;
        String valueText;
        Runnable action;
        TextView value;
    }

    interface Filler {
        void fill(Category c);
    }

    static final class Category {
        final String title;
        final String detail;
        final List<Item> items = new ArrayList<>();
        String group;
        Filler filler;
        Runnable onClose;
        View row;

        Category(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }
    }

    private SharedPreferences prefs;
    private float dp;
    private int screenW;
    private int screenH;
    private int listLeft;
    private int listWidth;
    private int leftColumn;
    private TextView title;
    private TextView description;
    private TvArt art;
    private ScrollView rootScroll;
    private LinearLayout rootList;
    private ScrollView subScroll;
    private LinearLayout subList;
    private Spring rootScrollSpring;
    private Spring subScrollSpring;
    private Spring slide;
    private Category open;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<Connectivity.Device> found = new ArrayList<>();
    private Runnable stopDiscovery;
    private Sounds sounds;
    private final List<Category> categories = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        dp = metrics.density;
        screenW = Math.max(metrics.widthPixels, metrics.heightPixels);
        screenH = Math.min(metrics.widthPixels, metrics.heightPixels);
        sounds = new Sounds(this, prefs);
        listLeft = Math.round(screenW * 0.53f);
        listWidth = screenW - listLeft - px(40);
        leftColumn = px(56);

        FrameLayout root = new FrameLayout(this);
        // Matte frosted glass: a deep graphite gradient with a fine grain, no picture behind.
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF43464E, 0xFF26282D, 0xFF141518}));
        View grain = new View(this);
        android.graphics.drawable.BitmapDrawable grainTile =
                new android.graphics.drawable.BitmapDrawable(getResources(), grain());
        grainTile.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        grain.setBackground(grainTile);
        root.addView(grain, new FrameLayout.LayoutParams(-1, -1));

        title = text(26, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText("Réglages");
        title.setAlpha(0.92f);
        root.addView(title, at(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, px(30), 0, 0));

        // Left: the TV, and a line about the focused category under it.
        int artW = Math.round(screenW * 0.34f);
        int artH = Math.round(artW * 9f / 16f);
        int artCenterX = Math.round(screenW * 0.27f);
        int artTop = Math.round(screenH * 0.26f);
        art = new TvArt(this);
        root.addView(art, at(artW, artH, Gravity.TOP | Gravity.START, artCenterX - artW / 2, artTop, 0, 0));
        // On the TV: the home screen's current wallpaper, so it shows this TV rather than an empty frame.
        final String wallpaper = prefs.getString("wallpaper_now", null);
        if (wallpaper != null) {
            final int w = artW;
            final int h = artH;
            new Thread(() -> {
                Bitmap still = null;
                // A still made on the PC by sync-fonds (this TV cannot take a frame out of 4K HEVC).
                File video = new File(wallpaper);
                String base = video.getName().replaceFirst("\\.[^.]+$", "");
                File made = new File(new File(video.getParentFile(), "stills"), base + ".jpg");
                if (made.exists()) {
                    still = BitmapFactory.decodeFile(made.getAbsolutePath());
                }
                File poster = MainActivity.posterFile(this, wallpaper);
                if (still == null && poster.exists()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    still = BitmapFactory.decodeFile(poster.getAbsolutePath(), o);
                }
                if (still == null && new File(wallpaper).exists()) {
                    still = MainActivity.videoFrame(wallpaper, w, h);
                }
                final Bitmap shown = still;
                if (shown != null) {
                    art.post(() -> art.setScreen(shown));
                }
            }, "settings-tv").start();
        }
        description = text(13, Typeface.DEFAULT);
        description.setSingleLine(false);
        description.setMaxLines(3);
        description.setGravity(Gravity.CENTER_HORIZONTAL);
        description.setAlpha(0.72f);
        root.addView(description, at(artW, -2, Gravity.TOP | Gravity.START, artCenterX - artW / 2,
                artTop + artH + px(26), 0, 0));

        rootList = list();
        rootScroll = scroller(rootList, true);
        root.addView(rootScroll, at(listWidth, -1, Gravity.TOP | Gravity.START, listLeft, px(70), 0, 0));
        subList = list();
        subScroll = scroller(subList, false);
        subScroll.setAlpha(0f);
        subScroll.setVisibility(View.INVISIBLE);
        root.addView(subScroll, at(listWidth, -1, Gravity.TOP | Gravity.START, listLeft, px(70), 0, 0));

        // One spring moves the whole page between "categories" (0) and "inside a category" (1).
        slide = new Spring(0f, 0.001f, t -> {
            float x = (leftColumn - listLeft) * t;
            rootScroll.setTranslationX(x);
            float fade = Math.max(0f, Math.min(1f, t));
            art.setAlpha(1f - fade);
            art.setTranslationX(-px(60) * t);
            description.setAlpha(0.72f * (1f - fade));
            subScroll.setAlpha(fade);
            subScroll.setTranslationX(px(70) * (1f - t));
        });
        slide.configure(0.36f, 0.92f);

        build();
        String group = null;
        for (Category category : categories) {
            if (category.group != null && !category.group.equals(group)) {
                group = category.group;
                rootList.addView(sectionLabel(group, rootList.getChildCount() == 0));
            }
            addCategoryRow(category);
        }
        setContentView(root);
        if (!categories.isEmpty()) {
            categories.get(0).row.requestFocus();
        }
    }

    // ------------------------------------------------------------------ content: the TV's own menu

    private void build() {
        group("Téléviseur");
        Category c = category("Chaînes et entrées", "Les chaînes de l'antenne, la liste des chaînes et les entrées HDMI.");
        page(c, "Recherche de chaînes", "Recherche les chaînes par antenne, câble ou satellite.",
                componentOf("com.tcl.tv", "com.tcl.tv.scan.launch.ScanMainActivity"));
        page(c, "Liste des chaînes", "L'ordre, les favoris et les chaînes masquées.",
                componentOf("com.tcl.tv", "com.tcl.tv.channellist.ui.activity.ChannelManagerActivity"));
        page(c, "Entrées", "Le nom des entrées HDMI et le contrôle des appareils connectés (HDMI-CEC).",
                component("com.tcl.settings.channel.InputControlActivity"));

        c = category("Image et son", "L'image, le son, la résolution, l'écran de veille et la consommation.");
        page(c, "Image", "Le mode d'image, la luminosité, le contraste et les couleurs.",
                // TCL's page opens its panel only when asked with this action; without it, it just closes.
                component("com.tcl.settings.PictureActivity").setAction("android.settings.DISPLAY_SETTINGS"));
        page(c, "Son", "Le mode son, l'égaliseur et la sortie audio.", component("com.tcl.settings.SoundActivity")
                .setAction("com.android.tv.action.VIEW_SOUND_SETTINGS"));
        page(c, "Affichage et formats", "La résolution, le HDR et les formats audio numériques.",
                component(".device.displaysound.DisplaySoundActivity"));
        page(c, "Écran de veille", "Ce qu'affiche la TV quand elle n'est pas utilisée.",
                component("com.google.android.tv.settings.AmbientActivity"));
        page(c, "Économie d'énergie", "Arrêt automatique, écran éteint et consommation.",
                new Intent("com.android.tv.settings.action.ENERGY_SAVING"));

        group("Connexions");
        c = category("Réseau et internet", "Le Wi-Fi, les réseaux à proximité et les réglages avancés.");
        c.filler = this::fillWifi;
        c = category("Télécommandes et accessoires", "Télécommandes, casques, enceintes et manettes Bluetooth.");
        c.filler = this::fillBluetooth;

        group("Compte et confidentialité");
        c = category("Comptes et connexion", "Le compte Google de cette TV.");
        page(c, "Comptes et profil", "Le compte Google de cette TV.",
                component("com.google.android.tv.settings.AccountActivity"));

        c = category("Confidentialité", "La localisation, le micro, la sécurité et la publicité.");
        page(c, "Localisation", "Les applis qui peuvent utiliser la position approximative de la TV.",
                new Intent("android.settings.LOCATION_SOURCE_SETTINGS"));
        page(c, "Micro et caméra", "L'accès des applis au micro de la télécommande et à la caméra.",
                component(".privacy.SensorToggleActivity"));
        page(c, "Sécurité et restrictions", "Sources inconnues et restrictions de profil.",
                component(".system.SecurityActivity"));
        page(c, "Publicité", "L'identifiant publicitaire et la personnalisation des annonces.",
                new Intent("com.google.android.gms.settings.ADS_PRIVACY"));
        page(c, "Accès aux notifications", "Les applis qui peuvent lire les notifications.",
                component(".privacy.NotificationAccessActivity"));

        group("Système");
        c = category("Applications", "Les applis installées, les autorisations et le contrôle parental.");
        page(c, "Applications", "Autorisations, espace occupé et applis installées.", component(".device.apps.AppsActivity"));
        page(c, "Toutes les applications", "Y compris les applis système.", component(".device.apps.AllAppsActivity"));
        page(c, "Installation depuis des sources inconnues", "Les applis qui peuvent installer d'autres applis.",
                component(".device.apps.specialaccess.ExternalSourcesActivity"));
        page(c, "Picture-in-picture", "Les applis qui peuvent s'afficher dans une petite fenêtre.",
                component(".device.apps.specialaccess.PictureInPictureActivity"));
        page(c, "Contrôle parental", "Restrictions pour les chaînes, les applis et les contenus.",
                component("com.tcl.settings.system.ParentControlActivity"));

        c = category("Système", "À propos, mises à jour, langue, heure, stockage et accessibilité.");
        c.filler = this::fillAbout;
        header(c, "Pages système");
        page(c, "À propos (détails)", "Le modèle, la version du système et les mentions légales.", component(".about.AboutActivity"));
        page(c, "Mise à jour logicielle", "Recherche une nouvelle version du système.",
                new Intent("android.settings.SYSTEM_UPDATE_SETTINGS"));
        page(c, "Date et heure", "Le fuseau horaire et le format de l'heure.", new Intent(Settings.ACTION_DATE_SETTINGS));
        page(c, "Langue", "La langue des menus.", new Intent(Settings.ACTION_LOCALE_SETTINGS));
        page(c, "Clavier", "Le clavier à l'écran et ses langues.", component(".inputmethod.KeyboardActivity"));
        page(c, "Stockage", "L'espace utilisé sur la TV.", component("com.tcl.settings.device.StorageSummaryActivity"));
        page(c, "Accessibilité", "Grand texte, contraste élevé, lecteur d'écran.",
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        page(c, "Sous-titres", "L'affichage et le style des sous-titres.", component(".system.CaptionSetupActivity"));
        page(c, "Système TCL", "Redémarrage, sons système, démarrage rapide et autres options TCL.",
                component("com.tcl.settings.device.SystemActivity"));
        page(c, "Options pour les développeurs", "Le débogage USB et d'autres options avancées.",
                component(".system.development.DevelopmentActivity"));

        group("TV Home");
        c = category("TV Home", "Les sons, les fonds d'écran et le reste des réglages Android.");
        toggle(c, "Sons de navigation", "Un petit clic quand on valide avec OK.", "sounds", false);
        toggle(c, "Mode Cinéma avec Plex", "Propose des films et séries de ta bibliothèque Plex. Désactivé : les plateformes de streaming (clé TMDB nécessaire).",
                "cinema_plex", true);
        toggle(c, "Météo", "Une carte animée avec la météo le matin et quand la pluie ou la neige commence.",
                "notify_weather", true);
        toggle(c, "Vigilance météo", "Les vigilances jaune, orange et rouge de Météo-France pour ton département, par-dessus tout.",
                "notify_alerts", true);
        toggle(c, "Son des notifications", "Un « clink » de verre discret quand arrive une notification ou une alerte.",
                "notify_sound", true);
        toggle(c, "Animation au démarrage", "Quand la TV s'allume : l'anneau de verre, l'heure et le jour, puis l'écran.",
                "wake_intro", true);
        toggle(c, "Fonds d'écran intégrés",
                "En plus des photos ajoutées avec tvtool, utilise aussi les fonds de l'appli.",
                "builtin_wallpapers", true);
        page(c, "Tous les réglages Android", "L'écran complet des réglages du système.", new Intent(Settings.ACTION_SETTINGS));
    }

    private String nextGroup;

    private Category category(String name, String detail) {
        Category c = new Category(name, detail);
        c.group = nextGroup;
        categories.add(c);
        return c;
    }

    private void group(String name) {
        nextGroup = name;
    }

    private void header(Category c, String name) {
        Item item = new Item();
        item.header = true;
        item.title = name;
        c.items.add(item);
    }

    private Item info(Category c, String name, String value) {
        Item item = new Item();
        item.title = name;
        item.valueText = value == null || value.isEmpty() ? "—" : value;
        item.action = () -> { };
        c.items.add(item);
        return item;
    }

    private Item action(Category c, String name, String value, String detail, Runnable action) {
        Item item = new Item();
        item.title = name;
        item.valueText = value;
        item.detail = detail;
        item.action = action;
        c.items.add(item);
        return item;
    }

    private TextView sectionLabel(String name, boolean first) {
        TextView label = text(11, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setText(name.toUpperCase(Locale.getDefault()));
        label.setAlpha(0.5f);
        label.setLetterSpacing(0.08f);
        label.setPadding(px(14), first ? 0 : px(16), 0, px(4));
        return label;
    }

    private void page(Category c, String name, String detail, Intent intent) {
        Item item = new Item();
        item.title = name;
        item.detail = detail;
        item.intent = intent;
        c.items.add(item);
    }

    private void toggle(Category c, String name, String detail, String key, boolean byDefault) {
        Item item = new Item();
        item.title = name;
        item.detail = detail;
        item.toggleKey = key;
        item.toggleDefault = byDefault;
        c.items.add(item);
    }

    // ------------------------------------------------------------------ live pages

    /** Wi-Fi: the network in use, the ones around (strongest first) and the advanced page. */
    private void fillWifi(final Category c) {
        Connectivity.scan(this);
        List<Connectivity.Network> networks = Connectivity.networks(this);
        header(c, "Réseau actuel");
        Connectivity.Network current = null;
        for (Connectivity.Network n : networks) {
            if (n.current) {
                current = n;
            }
        }
        info(c, current != null ? current.ssid : "Non connecté", current != null ? "Connecté  " + Connectivity.bars(current.level) : "");
        header(c, "Réseaux disponibles");
        int shown = 0;
        for (final Connectivity.Network n : networks) {
            if (n.current || shown >= 12) {
                continue;
            }
            shown++;
            action(c, n.ssid, (n.secure ? "🔒 " : "") + Connectivity.bars(n.level),
                    n.savedId >= 0 ? "Réseau enregistré : connexion directe." : n.secure ? "Mot de passe requis." : "Réseau ouvert.",
                    () -> Connectivity.join(this, n, () -> refresh(c)));
        }
        if (shown == 0) {
            info(c, "Recherche de réseaux…", "");
        }
        header(c, "Avansat");
        page(c, "Réglages réseau", "Adresse IP, proxy, câble Ethernet et autres options.",
                new Intent(Settings.ACTION_WIFI_SETTINGS));
        // Scan results arrive a few seconds after the scan starts.
        if (!c.title.isEmpty()) {
            handler.postDelayed(() -> refresh(c), 3500);
        }
    }

    /** Bluetooth: paired devices with their state, nearby devices found live, the system pages. */
    private void fillBluetooth(final Category c) {
        header(c, "Mes appareils");
        List<Connectivity.Device> paired = Connectivity.paired();
        for (final Connectivity.Device d : paired) {
            action(c, d.name, d.connected ? "Conectat" : "Non connecté", d.kind,
                    () -> openPage(component("com.tcl.settings.accessories.AccessoriesActivity")));
        }
        if (paired.isEmpty()) {
            info(c, Connectivity.bluetoothOn() ? "Aucun appareil associé" : "Le Bluetooth est désactivé", "");
        }
        header(c, "Autres appareils");
        synchronized (found) {
            for (final Connectivity.Device d : found) {
                action(c, d.name, "Associer", d.kind + ". Appuie sur OK pour l'associer.", () -> {
                    sounds.play("select");
                    SourcesService.say(this, Connectivity.pair(d) ? "Association avec " + d.name + "…"
                            : "Impossible de lancer l'association");
                });
            }
            if (found.isEmpty()) {
                info(c, "Recherche d'appareils à proximité…", "");
            }
        }
        header(c, "Plus");
        page(c, "Associer une télécommande ou un accessoire", "L'assistant d'association de la TV.",
                component(".accessories.AddAccessoryActivity"));
        page(c, "Télécommandes et accessoires TCL", "La page TCL des télécommandes et appareils.",
                component("com.tcl.settings.accessories.AccessoriesActivity"));
        if (stopDiscovery == null) {
            stopDiscovery = Connectivity.discover(this, d -> handler.post(() -> {
                synchronized (found) {
                    for (Connectivity.Device known : found) {
                        if (known.address.equals(d.address)) {
                            return;
                        }
                    }
                    found.add(d);
                }
                if (open == c) {
                    refresh(c);
                }
            }));
            c.onClose = () -> {
                if (stopDiscovery != null) {
                    stopDiscovery.run();
                    stopDiscovery = null;
                }
                synchronized (found) {
                    found.clear();
                }
            };
        }
    }

    /** About: what Apple shows first, read live; the system pages follow. */
    private void fillAbout(Category c) {
        List<Item> pages = new ArrayList<>(c.items);
        c.items.clear();
        header(c, "À propos de cette TV");
        String name = null;
        try {
            name = Settings.Global.getString(getContentResolver(), "device_name");
        } catch (RuntimeException ignored) {
            // Unnamed.
        }
        info(c, "Nom", name);
        info(c, "Model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        info(c, "Versiune Android", android.os.Build.VERSION.RELEASE);
        info(c, "Réseau", Connectivity.currentSsid(this));
        info(c, "Adresse IP", ipAddress());
        File data = android.os.Environment.getDataDirectory();
        info(c, "Espace libre", String.format(Locale.getDefault(), "%.1f Go sur %.1f Go",
                data.getUsableSpace() / 1e9, data.getTotalSpace() / 1e9));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // Unknown.
        }
        info(c, "TV Home", version);
        for (Item item : pages) {
            if (!item.header || !"À propos de cette TV".equals(item.title)) {
                c.items.add(item);
            }
        }
    }

    private String ipAddress() {
        try {
            for (java.net.NetworkInterface nif : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress a : java.util.Collections.list(nif.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // No network.
        }
        return "";
    }

    /** Rebuilds the open page, keeping the focus on the row with the same title. */
    private void refresh(Category c) {
        if (open != c) {
            return;
        }
        View focused = getCurrentFocus();
        String focusedTitle = null;
        if (focused != null && focused.getTag() instanceof Row) {
            focusedTitle = ((Row) focused.getTag()).name.getText().toString();
        }
        renderPage(c);
        if (focusedTitle != null) {
            for (int i = 0; i < subList.getChildCount(); i++) {
                View v = subList.getChildAt(i);
                if (v.getTag() instanceof Row && focusedTitle.equals(((Row) v.getTag()).name.getText().toString())) {
                    v.requestFocus();
                    return;
                }
            }
        }
        View first = firstRow();
        if (first != null) {
            first.requestFocus();
        }
    }

    private View firstRow() {
        for (int i = 0; i < subList.getChildCount(); i++) {
            if (subList.getChildAt(i).getTag() instanceof Row) {
                return subList.getChildAt(i);
            }
        }
        return null;
    }

    private void openPage(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            SourcesService.say(this, "Cette page n'est pas disponible sur cette TV");
        }
    }

    private Intent component(String cls) {
        return new Intent().setClassName(TV_SETTINGS, cls.startsWith(".") ? TV_SETTINGS + cls : cls);
    }

    private static Intent componentOf(String pkg, String cls) {
        return new Intent().setClassName(pkg, cls);
    }

    // ------------------------------------------------------------------ navigation

    private void openCategory(Category c) {
        if (open == c) {
            return;
        }
        open = c;
        sounds.play("select");
        if (subScrollSpring != null) {
            subScrollSpring.snap(0);
        }
        subScroll.scrollTo(0, 0);
        renderPage(c);
        View first = firstRow();
        for (Category other : categories) {
            rowOf(other.row).setSelected(other == c);
        }
        swapTitle(c.title);
        rootList.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        subScroll.setVisibility(View.VISIBLE);
        slide.to(1f);
        if (first != null) {
            first.requestFocus();
        }
    }

    private void closeCategory() {
        if (open == null) {
            return;
        }
        final Category c = open;
        open = null;
        handler.removeCallbacksAndMessages(null);
        if (c.onClose != null) {
            c.onClose.run();
            c.onClose = null;
        }
        sounds.play("back");
        swapTitle("Réglages");
        rootList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rowOf(c.row).setSelected(false);
        c.row.requestFocus();
        slide.to(0f);
        slide.onRest(() -> {
            if (open == null) {
                subScroll.setVisibility(View.INVISIBLE);
            }
        });
    }

    /** The rows of a category; live categories fill themselves first. */
    private void renderPage(Category c) {
        if (c.filler != null) {
            List<Item> fixed = new ArrayList<>();
            for (Item item : c.items) {
                if (item.intent != null || item.toggleKey != null) {
                    fixed.add(item);
                }
            }
            if (c.title.equals("Système")) {
                // About keeps its static pages; the live part is rebuilt every time.
                c.items.clear();
                header(c, "Pages système");
                for (Item item : fixed) {
                    c.items.add(item);
                }
            } else {
                c.items.clear();
            }
            c.filler.fill(c);
        }
        subList.removeAllViews();
        for (Item item : c.items) {
            if (item.header) {
                subList.addView(sectionLabel(item.title, subList.getChildCount() == 0));
            } else {
                addItemRow(item);
            }
        }
    }

    private void swapTitle(final String text) {
        title.animate().cancel();
        title.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            title.setText(text);
            title.animate().alpha(0.92f).setDuration(200).start();
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Press.key(event, getCurrentFocus());
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            View focused = getCurrentFocus();
            if (open != null && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT)) {
                closeCategory();
                return true;
            }
            if (open == null && code == KeyEvent.KEYCODE_DPAD_RIGHT && focused != null
                    && focused.getParent() == rootList) {
                for (Category c : categories) {
                    if (c.row == focused) {
                        openCategory(c);
                        return true;
                    }
                }
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                sounds.play("back");
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void openItem(Item item) {
        sounds.play("select");
        if (item.toggleKey != null) {
            boolean now = !prefs.getBoolean(item.toggleKey, item.toggleDefault);
            prefs.edit().putBoolean(item.toggleKey, now).apply();
            item.value.setText(now ? "Activé" : "Désactivé");
            return;
        }
        try {
            startActivity(new Intent(item.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException again) {
                SourcesService.say(this, "Cette page n'est pas disponible sur cette TV");
            }
        }
    }

    // ------------------------------------------------------------------ rows

    private void addCategoryRow(final Category c) {
        Row row = new Row(this, c.title, "›");
        c.row = row.view;
        row.view.setOnFocusChangeListener((v, has) -> {
            row.focus(has);
            if (has) {
                sounds.play("move");
                description.animate().cancel();
                description.setText(c.detail);
                description.setAlpha(0f);
                if (open == null) {
                    description.animate().alpha(0.72f).setDuration(220).start();
                }
            }
        });
        row.view.setOnClickListener(v -> openCategory(c));
        rootList.addView(row.view, rowParams());
    }

    private View addItemRow(final Item item) {
        String value = item.toggleKey != null
                ? (prefs.getBoolean(item.toggleKey, item.toggleDefault) ? "Activé" : "Désactivé")
                : item.valueText != null ? item.valueText : "›";
        final Row row = new Row(this, item.title, value);
        item.value = row.value;
        row.view.setOnFocusChangeListener((v, has) -> {
            row.focus(has);
            if (has) {
                sounds.play("move");
            }
        });
        row.view.setOnClickListener(v -> {
            if (item.action != null) {
                item.action.run();
            } else {
                openItem(item);
            }
        });
        subList.addView(row.view, rowParams());
        if (item.detail != null && !item.detail.isEmpty()) {
            // A line about the page under its title, small, like the subtitle rows on tvOS.
            TextView detail = text(10, Typeface.DEFAULT);
            detail.setText(item.detail);
            detail.setAlpha(0.5f);
            detail.setPadding(px(14), px(3), px(14), px(2));
            subList.addView(detail);
        }
        return row.view;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(30));
        lp.topMargin = px(6);
        return lp;
    }

    private static Row rowOf(View view) {
        return (Row) view.getTag();
    }

    /** One glass row: regular glass when idle, solid white when focused, light glass when selected. */
    final class Row {
        final FrameLayout view;
        final TextView name;
        final TextView value;
        final GlassDrawable glass;
        final Spring spring;
        boolean selected;
        boolean focused;

        Row(Context context, String title, String valueText) {
            view = new FrameLayout(context);
            glass = new GlassDrawable(px(10), dp, 0x1FFFFFFF);
            view.setBackground(glass);
            view.setTag(this);
            view.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(10));
                }
            });
            name = text(13, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setText(title);
            view.addView(name, at(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START, px(14), 0, px(60), 0));
            value = text(12, Typeface.DEFAULT);
            value.setAlpha(0.6f);
            value.setText(valueText);
            if ("›".equals(valueText)) {
                value.setTextSize(16);
            }
            view.addView(value, at(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, px(14), 0));
            view.setFocusable(true);
            view.setId(View.generateViewId());
            spring = new Spring(0f, 0.002f, p -> {
                float scale = 1f + 0.035f * p;
                view.setScaleX(scale);
                view.setScaleY(scale);
                view.setTranslationZ(Math.max(0f, p) * px(8));
            });
        }

        void focus(boolean has) {
            focused = has;
            // Quick, like tvOS lists: the row is up before the next press, never trailing behind.
            spring.to(has ? 1f : 0f, has ? 0.17f : 0.14f, has ? 0.86f : 1f);
            paint();
        }

        void setSelected(boolean value) {
            selected = value;
            paint();
        }

        private void paint() {
            boolean white = focused;
            glass.setTint(white ? 0xF7FFFFFF : selected ? 0x4DFFFFFF : 0x1FFFFFFF);
            name.setTextColor(white ? 0xFF111111 : Color.WHITE);
            value.setTextColor(white ? 0xFF333333 : Color.WHITE);
        }
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout list() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(px(10), px(70), px(10), px(140));
        return list;
    }

    private ScrollView scroller(final LinearLayout content, final boolean isRoot) {
        ScrollView scroll = new ScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                // Keep the focused row near the middle; the spring keeps its speed on fast moves.
                int center = focused.getTop() + focused.getHeight() / 2;
                int max = Math.max(0, content.getHeight() - getHeight());
                int target = Math.max(0, Math.min(max, center - getHeight() / 2));
                Spring s = isRoot ? rootScrollSpring : subScrollSpring;
                if (s == null) {
                    final ScrollView self = this;
                    s = new Spring(getScrollY(), 0.5f, v -> self.scrollTo(0, Math.round(v)));
                    s.configure(0.26f, 1f);
                    if (isRoot) {
                        rootScrollSpring = s;
                    } else {
                        subScrollSpring = s;
                    }
                }
                s.to(target);
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false;
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        // Rows fade out under the title and at the bottom instead of being cut, like tvOS lists.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(48));
        scroll.setFocusable(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        return scroll;
    }

    /** A small tile of soft noise: what makes a flat gradient read as frosted, matte glass. */
    private static Bitmap grain() {
        int n = 128;
        int[] px = new int[n * n];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) {
            int v = random.nextInt(256);
            int a = 6 + random.nextInt(8);
            px[i] = (a << 24) | (v << 16) | (v << 8) | v;
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    private int px(float value) {
        return Math.round(value * dp);
    }

    private TextView text(float sp, Typeface typeface) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(typeface);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private static FrameLayout.LayoutParams at(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    /**
     * The TV as a product render, the way Apple shows the Apple TV on its Settings screen: a
     * front-on panel of black glass with a thin aluminium edge and a soft sheen, nothing on it.
     */
    static final class TvArt extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        private Bitmap screen;
        private final android.graphics.Rect src = new android.graphics.Rect();

        TvArt(Context context) {
            super(context);
        }

        void setScreen(Bitmap bitmap) {
            screen = bitmap;
            setAlpha(0f);
            animate().alpha(1f).setDuration(400).start();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = w * 0.014f;
            // Aluminium edge: brighter top-left, darker bottom-right.
            paint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFFD9DCE1, 0xFF8E9298, 0xFF55595F, 0xFFA3A7AD}, new float[]{0f, 0.35f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
            box.set(0, 0, w, h);
            canvas.drawRoundRect(box, r, r, paint);
            // Black glass, very slightly lifted at the top.
            float edge = Math.max(1.5f, w * 0.0035f);
            paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF1C1D21, 0xFF050506, Shader.TileMode.CLAMP));
            box.set(edge, edge, w - edge, h - edge);
            canvas.drawRoundRect(box, r - edge, r - edge, paint);
            // The sheen: one soft diagonal band of reflected light across the upper left.
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(box, r - edge, r - edge, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            if (screen != null) {
                // Centre-crop the wallpaper to the glass.
                float scale = Math.max(box.width() / screen.getWidth(), box.height() / screen.getHeight());
                int sw = Math.round(box.width() / scale);
                int sh = Math.round(box.height() / scale);
                int sx = (screen.getWidth() - sw) / 2;
                int sy = (screen.getHeight() - sh) / 2;
                src.set(sx, sy, sx + sw, sy + sh);
                paint.setShader(null);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(screen, src, box, paint);
            }
            paint.setShader(new LinearGradient(0, 0, w * 0.75f, h,
                    new int[]{0x26FFFFFF, 0x0FFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.38f, 0.39f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(box, paint);
            canvas.restore();
            paint.setShader(null);
        }
    }

    /** Navigation sounds for this screen: the same files TV Home uses, honouring its on/off setting. */
    static final class Sounds {
        private final android.media.SoundPool pool;
        private final java.util.Map<String, Integer> ids = new java.util.HashMap<>();
        private final SharedPreferences prefs;
        private final List<Integer> loaded = new ArrayList<>();

        Sounds(Context context, SharedPreferences prefs) {
            this.prefs = prefs;
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            pool = new android.media.SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
            pool.setOnLoadCompleteListener((p, id, status) -> loaded.add(id));
            File custom = context.getExternalFilesDir("sounds");
            for (String name : new String[]{"move", "select", "back"}) {
                File own = custom != null ? new File(custom, name + ".wav") : null;
                try {
                    if (own != null && own.exists()) {
                        ids.put(name, pool.load(own.getAbsolutePath(), 1));
                    } else {
                        ids.put(name, pool.load(context.getAssets().openFd("sounds/" + name + ".wav"), 1));
                    }
                } catch (java.io.IOException ignored) {
                    // Silent.
                }
            }
        }

        void play(String name) {
            Integer id = ids.get(name);
            if (!"select".equals(name)) {
                return;
            }
            if (id != null && loaded.contains(id) && prefs.getBoolean("sounds", false)) {
                float volume = "move".equals(name) ? 0.28f : 0.4f;
                pool.play(id, volume, volume, 1, 0, 1f);
            }
        }
    }
}
