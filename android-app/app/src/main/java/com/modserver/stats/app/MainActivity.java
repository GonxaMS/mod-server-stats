package com.modserver.stats.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "server_connection";
    private static final String DEFAULT_PORT = "8080";
    private static final int MAX_HISTORY_SAMPLES = 720;
    private static final long CONSOLE_POLL_INTERVAL_MS = 1000L;
    private static final int MAX_CONSOLE_CHARS = 32000;
    private static final int MIN_API_TOKEN_LENGTH = 32;
    private static final int MIN_API_PASSWORD_LENGTH = 12;
    private static final int CURRENT_VERSION_CODE = 27;
    private static final String CURRENT_VERSION_NAME = "1.26";
    private static final int INSTALL_PERMISSION_REQUEST_CODE = 4101;
    private static final String DEFAULT_UPDATE_MANIFEST_URL =
            "https://github.com/GonxaMS/mod-server-stats/releases/latest/download/latest.json";

    // True AMOLED base with high-contrast cyberpunk telemetry accents.
    private static final int COLOR_BACKGROUND = Color.BLACK;
    private static final int COLOR_SURFACE = Color.rgb(3, 6, 9);
    private static final int COLOR_SURFACE_RAISED = Color.rgb(7, 13, 18);
    private static final int COLOR_BORDER = Color.rgb(0, 83, 96);
    private static final int COLOR_TRACK = Color.rgb(8, 28, 37);
    private static final int COLOR_TEXT = Color.rgb(224, 255, 248);
    private static final int COLOR_MUTED = Color.rgb(112, 157, 157);
    private static final int COLOR_DIM = Color.rgb(48, 88, 94);
    private static final int COLOR_CYAN = Color.rgb(0, 245, 255);
    private static final int COLOR_GREEN = Color.rgb(57, 255, 136);
    private static final int COLOR_MAGENTA = Color.rgb(255, 43, 214);
    private static final int COLOR_AMBER = Color.rgb(255, 230, 0);
    private static final int COLOR_RED = Color.rgb(255, 49, 102);
    private static final int COLOR_BLUE = Color.rgb(77, 155, 255);

    private EditText addressInput;
    private EditText portInput;
    private EditText apiUsernameInput;
    private EditText apiPasswordInput;
    private Button refreshButton;
    private TextView statusView;
    private LinearLayout statsContainer;
    private TextView chartTitle;
    private StatsChartView chartView;
    private Button historyStartButton;
    private Button historyEndButton;
    private Button loadHistoryButton;
    private TextView updateView;
    private Button searchUpdateButton;
    private Button updateButton;
    private EditText commandInput;
    private Button commandSendButton;
    private TextView commandOutputView;
    private ScrollView consoleOutputScrollView;
    private Switch autoRefreshSwitch;
    private Spinner intervalSpinner;
    private ExecutorService executor;
    private Handler mainHandler;
    private SharedPreferences preferences;
    private Runnable autoRefreshRunnable;
    private boolean requestInProgress;
    private boolean historyRequestInFlight;
    private String historyLoadedForEndpoint;
    private long historyStartMs;
    private long historyEndMs;
    private boolean updateCheckInFlight;
    private String updateCheckedForEndpoint;
    private String updateDownloadEndpoint;
    private int availableVersionCode;
    private String availableVersionName;
    private File pendingUpdateApk;
    private final ArrayList<StatsSample> history = new ArrayList<>();
    private final StringBuilder consoleTranscript = new StringBuilder();
    private boolean commandInProgress;
    private boolean consoleFollowTail = true;
    private boolean consoleScrollProgrammatic;
    private Runnable consolePollRunnable;
    private boolean consoleScreenActive;
    private boolean consolePolling;
    private boolean consoleRequestInFlight;
    private long consoleCursor;
    private String consoleLoadedForEndpoint;
    private String consoleLastError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureSystemBars();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        historyEndMs = System.currentTimeMillis();
        historyStartMs = historyEndMs - 6L * 60L * 60L * 1000L;
        setContentView(createContentView());
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(14), dp(22), dp(18));
        header.setBackground(headerBackground());
        TextView title = new TextView(this);
        title.setText("MOD SERVER STATS");
        title.setTextColor(COLOR_CYAN);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.04f);
        title.setShadowLayer(dp(8), 0, 0, COLOR_CYAN);
        header.addView(title, matchWidthWrapHeight());
        root.addView(header, matchWidthWrapHeight());

        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(10), dp(8), dp(10), dp(8));
        navigation.setBackground(roundBackground(COLOR_SURFACE, COLOR_BORDER, 0));
        navigation.setElevation(0);
        TextView statusTab = navigationButton("Estado");
        TextView historyTab = navigationButton("Historial");
        TextView consoleTab = navigationButton("CLI");
        TextView settingsTab = navigationButton("Ajustes");
        navigation.addView(statusTab, weightedWidth());
        navigation.addView(historyTab, weightedWidth());
        navigation.addView(consoleTab, weightedWidth());
        navigation.addView(settingsTab, weightedWidth());
        root.addView(navigation, matchWidthWrapHeight());

        FrameLayout screens = new FrameLayout(this);
        View statusScreen = createStatusScreen();
        View historyScreen = createHistoryScreen();
        View consoleScreen = createConsoleScreen();
        View settingsScreen = createSettingsScreen();
        screens.addView(statusScreen, frameMatchParams());
        screens.addView(historyScreen, frameMatchParams());
        screens.addView(consoleScreen, frameMatchParams());
        screens.addView(settingsScreen, frameMatchParams());
        root.addView(screens, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                topInset = bars.top;
                // Keep the command field and execute button above the keyboard.
                bottomInset = Math.max(bars.bottom, ime.bottom);
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            header.setPadding(dp(22), dp(14) + topInset, dp(22), dp(18));
            screens.setPadding(0, 0, 0, bottomInset);
            return insets;
        });
        root.post(root::requestApplyInsets);

        View[] allScreens = {statusScreen, historyScreen, consoleScreen, settingsScreen};
        TextView[] allTabs = {statusTab, historyTab, consoleTab, settingsTab};
        statusTab.setOnClickListener(view -> {
            consoleScreenActive = false;
            stopConsolePolling();
            showScreen(statusScreen, statusTab, allScreens, allTabs);
        });
        historyTab.setOnClickListener(view -> {
            consoleScreenActive = false;
            stopConsolePolling();
            showScreen(historyScreen, historyTab, allScreens, allTabs);
        });
        consoleTab.setOnClickListener(view -> {
            consoleScreenActive = true;
            showScreen(consoleScreen, consoleTab, allScreens, allTabs);
            startConsolePolling();
        });
        settingsTab.setOnClickListener(view -> {
            consoleScreenActive = false;
            stopConsolePolling();
            showScreen(settingsScreen, settingsTab, allScreens, allTabs);
        });
        showScreen(statusScreen, statusTab, allScreens, allTabs);
        applyTerminalTypeface(root);
        return root;
    }

    private View createStatusScreen() {
        ScrollView scroll = screenScroll();
        LinearLayout content = screenContent(scroll);

        TextView eyebrow = label("ESTADO DEL SERVIDOR");
        eyebrow.setTextColor(COLOR_CYAN);
        content.addView(eyebrow, matchWidthWrapHeight());

        statusView = new TextView(this);
        statusView.setText("Sin conexión.");
        statusView.setTextColor(COLOR_MUTED);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusView.setBackground(roundBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, 12));
        content.addView(statusView, cardParams(dp(12)));

        refreshButton = actionButton("[ ACTUALIZAR MÉTRICAS ]", COLOR_CYAN);
        refreshButton.setOnClickListener(view -> refreshStats());
        content.addView(refreshButton, marginParams(dp(14)));

        LinearLayout statsCard = card();
        TextView statsTitle = label("MÉTRICAS EN TIEMPO REAL");
        statsTitle.setTextColor(COLOR_CYAN);
        statsCard.addView(statsTitle, matchWidthWrapHeight());
        statsContainer = new LinearLayout(this);
        statsContainer.setOrientation(LinearLayout.VERTICAL);
        statsContainer.setGravity(Gravity.TOP);
        statsCard.addView(statsContainer, marginParams(dp(12)));
        renderEmptyStats();
        content.addView(statsCard, cardParams(dp(14)));
        return scroll;
    }

    private View createHistoryScreen() {
        ScrollView scroll = screenScroll();
        LinearLayout content = screenContent(scroll);
        chartTitle = label("Historial");
        chartTitle.setTextSize(20);
        content.addView(chartTitle, matchWidthWrapHeight());

        LinearLayout rangeCard = card();
        historyStartButton = actionButton("DESDE", COLOR_SURFACE_RAISED);
        historyStartButton.setOnClickListener(view -> pickHistoryDateTime(true));
        rangeCard.addView(historyStartButton, matchWidthWrapHeight());
        historyEndButton = actionButton("HASTA", COLOR_SURFACE_RAISED);
        historyEndButton.setOnClickListener(view -> pickHistoryDateTime(false));
        rangeCard.addView(historyEndButton, marginParams(dp(8)));
        loadHistoryButton = actionButton("[ CARGAR PERÍODO ]", COLOR_CYAN);
        loadHistoryButton.setOnClickListener(view -> loadSelectedHistory());
        rangeCard.addView(loadHistoryButton, marginParams(dp(10)));
        content.addView(rangeCard, cardParams(dp(14)));
        updateHistoryRangeLabels();

        Spinner chartMetricSpinner = new Spinner(this);
        String[] chartMetrics = {"TPS", "MSPT", "CPU de Minecraft", "CPU del equipo", "Memoria",
                "Jugadores", "Tiempo de respuesta"};
        ArrayAdapter<String> chartMetricAdapter = terminalSpinnerAdapter(chartMetrics);
        chartMetricSpinner.setAdapter(chartMetricAdapter);
        styleSpinner(chartMetricSpinner);
        content.addView(chartMetricSpinner, marginParams(dp(10)));

        LinearLayout chartCard = card();
        chartView = new StatsChartView(this);
        chartCard.addView(chartView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)));
        content.addView(chartCard, cardParams(dp(14)));
        chartMetricSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                chartView.setMetric(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the previously selected chart.
            }
        });
        return scroll;
    }

    private View createConsoleScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(COLOR_BACKGROUND);
        screen.setPadding(dp(18), dp(14), dp(18), dp(12));

        TextView heading = label("REMOTE CONSOLE // OPERATOR");
        heading.setTextSize(20);
        screen.addView(heading, matchWidthWrapHeight());

        LinearLayout outputCard = card();
        outputCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView outputTitle = label("SERVER LOG // latest.log");
        outputTitle.setTextColor(COLOR_GREEN);
        outputCard.addView(outputTitle, marginParams(dp(6)));

        consoleOutputScrollView = new ConsoleOutputScrollView(this);
        consoleOutputScrollView.setFillViewport(true);
        consoleOutputScrollView.setVerticalScrollBarEnabled(true);
        consoleOutputScrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        // The card is the only frame. The scroll view must remain borderless so
        // the live log does not look like a box inside another box.
        consoleOutputScrollView.setBackgroundColor(Color.BLACK);
        consoleOutputScrollView.setPadding(dp(6), dp(6), dp(6), dp(6));
        consoleOutputScrollView.setOnScrollChangeListener(
                (view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (!consoleScrollProgrammatic && view.getHeight() > 0) {
                        consoleFollowTail = isConsoleOutputAtBottom();
                    }
                });
        commandOutputView = new TextView(this);
        commandOutputView.setTextColor(COLOR_GREEN);
        commandOutputView.setTextSize(12);
        commandOutputView.setTypeface(Typeface.MONOSPACE);
        commandOutputView.setGravity(Gravity.TOP | Gravity.START);
        commandOutputView.setTextIsSelectable(true);
        commandOutputView.setMinHeight(0);
        commandOutputView.setIncludeFontPadding(true);
        commandOutputView.setLineSpacing(0, 1.05f);
        commandOutputView.setPadding(dp(4), dp(4), dp(4), dp(4));
        commandOutputView.setBackgroundColor(Color.TRANSPARENT);
        consoleOutputScrollView.addView(commandOutputView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams outputScrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        outputCard.addView(consoleOutputScrollView, outputScrollParams);
        LinearLayout.LayoutParams outputCardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        outputCardParams.topMargin = dp(12);
        outputCardParams.bottomMargin = dp(8);
        screen.addView(outputCard, outputCardParams);

        commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setHint("comando: list, say mensaje...");
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        commandInput.setTextColor(COLOR_TEXT);
        commandInput.setHintTextColor(COLOR_DIM);
        commandInput.setTextSize(14);
        commandInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        commandInput.setMinHeight(dp(48));
        commandInput.setPadding(dp(12), dp(4), dp(12), dp(4));
        commandInput.setBackground(roundBackground(COLOR_SURFACE_RAISED, COLOR_CYAN, 8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            commandInput.setBackgroundTintList(null);
        }
        screen.addView(commandInput, marginParams(dp(6)));

        commandSendButton = actionButton("[ EJECUTAR COMANDO ]", COLOR_MAGENTA);
        commandSendButton.setOnClickListener(view -> sendCommand());
        screen.addView(commandSendButton, marginParams(0));
        return screen;
    }

    private View createSettingsScreen() {
        ScrollView scroll = screenScroll();
        LinearLayout content = screenContent(scroll);
        TextView heading = label("AJUSTES DE CONEXIÓN");
        heading.setTextSize(20);
        content.addView(heading, matchWidthWrapHeight());

        LinearLayout connectionCard = card();
        connectionCard.addView(label("Dirección del servidor"), matchWidthWrapHeight());
        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setText(preferences.getString("address", ""));
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        styleInput(addressInput);
        connectionCard.addView(addressInput, marginParams(dp(4)));
        connectionCard.addView(label("Puerto de la API"), marginParams(dp(8)));
        portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setText(preferences.getString("port", DEFAULT_PORT));
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        styleInput(portInput);
        connectionCard.addView(portInput, marginParams(dp(4)));
        connectionCard.addView(label("Usuario"), marginParams(dp(8)));
        apiUsernameInput = new EditText(this);
        apiUsernameInput.setSingleLine(true);
        apiUsernameInput.setText(preferences.getString("apiUsername", "admin"));
        apiUsernameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        styleInput(apiUsernameInput);
        connectionCard.addView(apiUsernameInput, marginParams(dp(4)));
        connectionCard.addView(label("Contrasena"), marginParams(dp(8)));
        apiPasswordInput = new EditText(this);
        apiPasswordInput.setSingleLine(true);
        apiPasswordInput.setText(preferences.getString("apiPassword", ""));
        apiPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiPasswordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        styleInput(apiPasswordInput);
        connectionCard.addView(apiPasswordInput, marginParams(dp(4)));
        content.addView(connectionCard, cardParams(dp(14)));

        LinearLayout refreshCard = card();
        TextView refreshTitle = label("ACTUALIZACIÓN DE MÉTRICAS");
        refreshTitle.setTextColor(COLOR_CYAN);
        refreshCard.addView(refreshTitle, matchWidthWrapHeight());
        LinearLayout autoRefreshRow = new LinearLayout(this);
        autoRefreshRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRefreshRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRefreshSwitch = new Switch(this);
        autoRefreshSwitch.setText("Actualizar métricas automáticamente");
        autoRefreshSwitch.setTextSize(14);
        autoRefreshSwitch.setTextColor(COLOR_TEXT);
        autoRefreshRow.addView(autoRefreshSwitch, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        intervalSpinner = new Spinner(this);
        String[] intervalLabels = {"5 s", "10 s", "30 s", "60 s"};
        ArrayAdapter<String> intervalAdapter = terminalSpinnerAdapter(intervalLabels);
        intervalSpinner.setAdapter(intervalAdapter);
        styleSpinner(intervalSpinner);
        intervalSpinner.setSelection(preferences.getInt("intervalIndex", 1));
        autoRefreshRow.addView(intervalSpinner, new LinearLayout.LayoutParams(
                dp(78), LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshCard.addView(autoRefreshRow, marginParams(dp(10)));
        content.addView(refreshCard, cardParams(dp(14)));

        autoRefreshSwitch.setChecked(preferences.getBoolean("autoRefresh", false));
        autoRefreshSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean("autoRefresh", isChecked).apply();
            if (isChecked) startAutoRefresh(); else stopAutoRefresh();
        });
        intervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("intervalIndex", position).apply();
                if (autoRefreshSwitch.isChecked()) startAutoRefresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the previously selected interval.
            }
        });
        content.addView(createUpdateCard(), cardParams(dp(14)));
        return scroll;
    }

    private LinearLayout createUpdateCard() {
        LinearLayout updateCard = card();
        TextView updateTitle = label("ACTUALIZACIONES");
        updateTitle.setTextColor(COLOR_CYAN);
        updateCard.addView(updateTitle, matchWidthWrapHeight());
        updateView = new TextView(this);
        updateView.setText("Versión instalada: " + CURRENT_VERSION_NAME);
        updateView.setTextColor(COLOR_MUTED);
        updateView.setTextSize(13);
        updateCard.addView(updateView, marginParams(dp(6)));
        searchUpdateButton = actionButton("[ BUSCAR ACTUALIZACIÓN ]", COLOR_MAGENTA);
        searchUpdateButton.setOnClickListener(view -> searchForUpdate());
        updateCard.addView(searchUpdateButton, marginParams(dp(10)));
        updateButton = actionButton("[ INSTALAR ACTUALIZACIÓN ]", COLOR_GREEN);
        updateButton.setVisibility(View.GONE);
        updateButton.setOnClickListener(view -> downloadAndInstallUpdate());
        updateCard.addView(updateButton, matchWidthWrapHeight());
        return updateCard;
    }

    private ScrollView screenScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        return scroll;
    }

    private LinearLayout screenContent(ScrollView scroll) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(content);
        return content;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBackground(COLOR_SURFACE, COLOR_BORDER, 14));
        card.setElevation(0);
        return card;
    }

    private Button actionButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(isBrightButton(color) ? COLOR_BACKGROUND : COLOR_TEXT);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(14), dp(4), dp(14), dp(4));
        button.setBackground(roundBackground(color,
                isBrightButton(color) ? color : COLOR_BORDER, 10));
        return button;
    }

    private boolean isBrightButton(int color) {
        return color == COLOR_CYAN || color == COLOR_GREEN
                || color == COLOR_MAGENTA || color == COLOR_AMBER;
    }

    private void styleInput(EditText input) {
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_DIM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            input.setBackgroundTintList(ColorStateList.valueOf(COLOR_CYAN));
        }
    }

    private void styleSpinner(Spinner spinner) {
        spinner.setBackground(roundBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, 8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            spinner.setPopupBackgroundDrawable(roundBackground(COLOR_SURFACE_RAISED,
                    COLOR_CYAN, 8));
        }
    }

    private ArrayAdapter<String> terminalSpinnerAdapter(String[] values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(COLOR_TEXT);
                view.setTextSize(13);
                view.setTypeface(Typeface.MONOSPACE);
                view.setPadding(dp(10), 0, dp(10), 0);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView,
                                        android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(COLOR_CYAN);
                view.setTextSize(13);
                view.setTypeface(Typeface.MONOSPACE);
                view.setPadding(dp(12), dp(10), dp(12), dp(10));
                view.setBackgroundColor(COLOR_SURFACE_RAISED);
                return view;
            }
        };
    }

    private void applyTerminalTypeface(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            int style = textView.getTypeface() != null && textView.getTypeface().isBold()
                    ? Typeface.BOLD : Typeface.NORMAL;
            textView.setTypeface(Typeface.MONOSPACE, style);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyTerminalTypeface(group.getChildAt(index));
            }
        }
    }

    private TextView navigationButton(String text) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setTextSize(14);
        tab.setGravity(Gravity.CENTER);
        tab.setMinHeight(dp(44));
        tab.setPadding(dp(4), 0, dp(4), 0);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return tab;
    }

    private void showScreen(View active, TextView activeTab, View[] screens, TextView[] tabs) {
        for (View screen : screens) screen.setVisibility(screen == active ? View.VISIBLE : View.GONE);
        for (TextView tab : tabs) {
            boolean selected = tab == activeTab;
            tab.setTextColor(selected ? COLOR_CYAN : COLOR_MUTED);
            tab.setBackground(selected
                    ? roundBackground(COLOR_SURFACE_RAISED, COLOR_CYAN, 10)
                    : roundBackground(COLOR_SURFACE, COLOR_SURFACE, 10));
        }
    }

    private GradientDrawable headerBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(0, 8, 14), Color.rgb(0, 28, 38), Color.rgb(24, 0, 33)});
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(COLOR_BACKGROUND);
            getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private GradientDrawable roundBackground(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private LinearLayout.LayoutParams weightedWidth() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private FrameLayout.LayoutParams frameMatchParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams marginParams(int bottomMargin) {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams cardParams(int bottomMargin) {
        LinearLayout.LayoutParams params = marginParams(bottomMargin);
        params.leftMargin = dp(1);
        params.rightMargin = dp(1);
        return params;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COLOR_CYAN);
        label.setTextSize(14);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return label;
    }

    private void refreshStats() {
        if (requestInProgress) {
            return;
        }

        String address = addressInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();

        if (address.isEmpty()) {
            showError("Escribe la dirección del servidor.");
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            showError("El puerto debe ser un número.");
            return;
        }

        if (port < 1 || port > 65535) {
            showError("El puerto debe estar entre 1 y 65535.");
            return;
        }

        final String endpoint;
        try {
            endpoint = buildEndpoint(address, port);
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        final String apiUsername = currentApiUsername();
        final String apiPassword = currentApiPassword();
        final String legacyApiToken = currentApiToken();
        if (!hasValidApiCredentials(apiUsername, apiPassword)
                && !hasValidApiToken(legacyApiToken)) {
            showError("Configura usuario y contrasena (minimo 12 caracteres).");
            return;
        }

        preferences.edit().putString("address", address).putString("port", portText)
                .putString("apiUsername", apiUsername).putString("apiPassword", apiPassword).apply();
        requestInProgress = true;
        refreshButton.setEnabled(false);
        statusView.setText("Conectando…");
        statusView.setTextColor(COLOR_MUTED);
        final long requestStartedAt = SystemClock.elapsedRealtime();

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                applyApiCredentials(connection, apiUsername, apiPassword, legacyApiToken);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setUseCaches(false);

                int responseCode = connection.getResponseCode();
                InputStream responseStream = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String responseBody = responseStream == null ? "" : readResponse(responseStream);

                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("El servidor respondió HTTP " + responseCode);
                }

                JSONObject json = new JSONObject(responseBody);
                long latencyMs = SystemClock.elapsedRealtime() - requestStartedAt;
                runOnUiThread(() -> {
                    requestInProgress = false;
                    refreshButton.setEnabled(true);
                    statusView.setText("Conectado · " + latencyMs + " ms · " + currentTimeLabel());
                    statusView.setTextColor(COLOR_GREEN);
                    renderStats(json, latencyMs);
                    StatsSample latestSample = StatsSample.fromJson(json, latencyMs);
                    addHistorySample(latestSample);
                    loadPersistentHistoryIfNeeded(endpoint, latestSample);
                });
            } catch (Exception exception) {
                String message = exception.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = exception.getClass().getSimpleName();
                }
                final String errorMessage = message;
                runOnUiThread(() -> {
                    requestInProgress = false;
                    refreshButton.setEnabled(true);
                    showError("No se pudo conectar: " + errorMessage);
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static String buildEndpoint(String address, int port) {
        return buildEndpoint(address, port, "/api/server/stats");
    }

    private static String buildEndpoint(String address, int port, String apiPath) {
        String normalized = address;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }

        try {
            URL parsed = new URL(normalized);
            String protocol = parsed.getProtocol().toLowerCase(Locale.ROOT);
            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IllegalArgumentException("Usa una dirección HTTP o HTTPS válida.");
            }

            String host = parsed.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("La dirección del servidor no es válida.");
            }
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }

            int actualPort = parsed.getPort() == -1 ? port : parsed.getPort();
            return protocol + "://" + host + ":" + actualPort + apiPath;
        } catch (IOException exception) {
            throw new IllegalArgumentException("La dirección del servidor no es válida.");
        }
    }

    private String currentApiUsername() {
        if (apiUsernameInput == null) {
            return preferences.getString("apiUsername", "admin").trim();
        }
        return apiUsernameInput.getText().toString().trim();
    }

    private String currentApiPassword() {
        if (apiPasswordInput == null) {
            return preferences.getString("apiPassword", "").trim();
        }
        return apiPasswordInput.getText().toString().trim();
    }

    private String currentApiToken() {
        return preferences.getString("apiToken", "").trim();
    }

    private static boolean hasValidApiCredentials(String username, String password) {
        return username != null && !username.isEmpty()
                && password != null && password.length() >= MIN_API_PASSWORD_LENGTH;
    }

    private static boolean hasValidApiToken(String token) {
        return token != null && token.length() >= MIN_API_TOKEN_LENGTH;
    }

    private static void applyApiCredentials(HttpURLConnection connection, String username, String password,
                                            String legacyToken) {
        if (hasValidApiCredentials(username, password)) {
            String rawCredentials = username + ":" + password;
            String encodedCredentials = Base64.encodeToString(
                    rawCredentials.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
            connection.setRequestProperty("Authorization", "Basic " + encodedCredentials);
        } else if (legacyToken != null && !legacyToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + legacyToken);
        }
    }

    private void sendCommand() {
        if (commandInProgress || commandInput == null) return;

        String command = commandInput.getText().toString().trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty()) {
            appendConsoleLine("[error] escribe un comando primero");
            return;
        }
        if (command.length() > 2048) {
            appendConsoleLine("[error] el comando supera los 2048 caracteres");
            return;
        }

        String address = addressInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        if (address.isEmpty()) {
            appendConsoleLine("[error] configura la direccion del servidor en Ajustes");
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            appendConsoleLine("[error] el puerto no es valido");
            return;
        }
        if (port < 1 || port > 65535) {
            appendConsoleLine("[error] el puerto debe estar entre 1 y 65535");
            return;
        }

        final String endpoint;
        try {
            endpoint = buildEndpoint(address, port, "/api/server/command");
        } catch (IllegalArgumentException error) {
            appendConsoleLine("[error] " + error.getMessage());
            return;
        }

        final String apiUsername = currentApiUsername();
        final String apiPassword = currentApiPassword();
        final String legacyApiToken = currentApiToken();
        if (!hasValidApiCredentials(apiUsername, apiPassword)
                && !hasValidApiToken(legacyApiToken)) {
            appendConsoleLine("[error] configura usuario y contrasena en Ajustes");
            return;
        }

        final String commandToSend = command;
        preferences.edit().putString("address", address).putString("port", portText)
                .putString("apiUsername", apiUsername).putString("apiPassword", apiPassword).apply();
        commandInProgress = true;
        commandSendButton.setEnabled(false);
        appendConsoleLine("> " + commandToSend);

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String commandUrl = endpoint + "?command=" + URLEncoder.encode(commandToSend, "UTF-8");
                connection = (HttpURLConnection) new URL(commandUrl).openConnection();
                connection.setRequestMethod("GET");
                applyApiCredentials(connection, apiUsername, apiPassword, legacyApiToken);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                connection.setUseCaches(false);

                int responseCode = connection.getResponseCode();
                InputStream responseStream = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String responseBody = responseStream == null ? "" : readResponse(responseStream);
                if (responseCode < 200 || responseCode >= 300) {
                    String errorCode = responseErrorCode(responseBody);
                    throw new IOException("HTTP " + responseCode
                            + (errorCode.isEmpty() ? "" : " - " + errorCode));
                }

                String output = new JSONObject(responseBody).optString("output", "").trim();
                runOnUiThread(() -> {
                    commandInProgress = false;
                    commandSendButton.setEnabled(true);
                    if (!output.isEmpty()) {
                        appendConsoleLine(output);
                    }
                    commandInput.requestFocus();
                });
            } catch (Exception error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                final String errorMessage = message;
                runOnUiThread(() -> {
                    commandInProgress = false;
                    commandSendButton.setEnabled(true);
                    appendConsoleLine("[error] " + errorMessage);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static String readResponse(InputStream stream) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private void appendConsoleLine(String text) {
        if (commandOutputView == null) return;
        boolean followTail = consoleFollowTail || isConsoleOutputAtBottom();
        int previousScrollY = consoleOutputScrollView == null
                ? 0 : consoleOutputScrollView.getScrollY();
        if (consoleTranscript.length() > 0
                && consoleTranscript.charAt(consoleTranscript.length() - 1) != '\n') {
            consoleTranscript.append('\n');
        }
        consoleTranscript.append(text == null ? "" : text);
        if (consoleTranscript.length() == 0
                || consoleTranscript.charAt(consoleTranscript.length() - 1) != '\n') {
            consoleTranscript.append('\n');
        }
        if (consoleTranscript.length() > MAX_CONSOLE_CHARS) {
            int trimUntil = consoleTranscript.length() - MAX_CONSOLE_CHARS;
            int nextLine = consoleTranscript.indexOf("\n", trimUntil);
            consoleTranscript.delete(0, nextLine >= 0 ? nextLine + 1 : trimUntil);
        }
        commandOutputView.setText(consoleTranscript.toString());
        if (consoleOutputScrollView != null) {
            consoleOutputScrollView.post(() -> {
                consoleScrollProgrammatic = true;
                try {
                    if (followTail) {
                        consoleOutputScrollView.fullScroll(View.FOCUS_DOWN);
                        consoleFollowTail = true;
                    } else {
                        int contentBottom = consoleOutputScrollView.getChildCount() == 0
                                ? 0 : consoleOutputScrollView.getChildAt(0).getBottom();
                        int viewportBottom = consoleOutputScrollView.getHeight()
                                - consoleOutputScrollView.getPaddingBottom();
                        int maxScrollY = Math.max(0, contentBottom - viewportBottom);
                        consoleOutputScrollView.scrollTo(0,
                                Math.min(previousScrollY, maxScrollY));
                        consoleFollowTail = isConsoleOutputAtBottom();
                    }
                } finally {
                    consoleScrollProgrammatic = false;
                }
            });
        }
    }

    private boolean isConsoleOutputAtBottom() {
        if (consoleOutputScrollView == null || commandOutputView == null) return true;
        int viewportHeight = consoleOutputScrollView.getHeight()
                - consoleOutputScrollView.getPaddingTop()
                - consoleOutputScrollView.getPaddingBottom();
        if (viewportHeight <= 0) return true;
        int contentBottom = consoleOutputScrollView.getChildCount() == 0
                ? commandOutputView.getBottom()
                : consoleOutputScrollView.getChildAt(0).getBottom();
        return consoleOutputScrollView.getScrollY() + viewportHeight
                >= contentBottom - dp(12);
    }

    private void startConsolePolling() {
        if (consolePolling) return;
        consolePolling = true;
        pollConsole();
    }

    private void stopConsolePolling() {
        consolePolling = false;
        if (mainHandler != null && consolePollRunnable != null) {
            mainHandler.removeCallbacks(consolePollRunnable);
        }
        consolePollRunnable = null;
    }

    private void scheduleConsolePolling() {
        if (!consolePolling || mainHandler == null) return;
        if (consolePollRunnable == null) consolePollRunnable = this::pollConsole;
        mainHandler.postDelayed(consolePollRunnable, CONSOLE_POLL_INTERVAL_MS);
    }

    private void pollConsole() {
        if (!consolePolling || consoleRequestInFlight) return;
        if (addressInput == null || portInput == null) {
            reportConsoleProblem("configura el servidor en Ajustes");
            scheduleConsolePolling();
            return;
        }

        String address = addressInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        if (address.isEmpty()) {
            reportConsoleProblem("configura la direccion del servidor en Ajustes");
            scheduleConsolePolling();
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            reportConsoleProblem("el puerto no es valido");
            scheduleConsolePolling();
            return;
        }
        if (port < 1 || port > 65535) {
            reportConsoleProblem("el puerto debe estar entre 1 y 65535");
            scheduleConsolePolling();
            return;
        }

        final String endpoint;
        try {
            endpoint = buildEndpoint(address, port, "/api/server/console");
        } catch (IllegalArgumentException error) {
            reportConsoleProblem(error.getMessage());
            scheduleConsolePolling();
            return;
        }

        final String apiUsername = currentApiUsername();
        final String apiPassword = currentApiPassword();
        final String legacyApiToken = currentApiToken();
        if (!hasValidApiCredentials(apiUsername, apiPassword)
                && !hasValidApiToken(legacyApiToken)) {
            reportConsoleProblem("configura usuario y contrasena en Ajustes");
            scheduleConsolePolling();
            return;
        }

        if (!endpoint.equals(consoleLoadedForEndpoint)) {
            consoleLoadedForEndpoint = endpoint;
            consoleCursor = 0L;
            consoleLastError = null;
            consoleTranscript.setLength(0);
            consoleFollowTail = true;
        }

        final long after = consoleCursor;
        consoleRequestInFlight = true;
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String consoleUrl = endpoint + "?after=" + after + "&limit=200";
                connection = (HttpURLConnection) new URL(consoleUrl).openConnection();
                connection.setRequestMethod("GET");
                applyApiCredentials(connection, apiUsername, apiPassword, legacyApiToken);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setUseCaches(false);

                int responseCode = connection.getResponseCode();
                InputStream responseStream = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String responseBody = responseStream == null ? "" : readResponse(responseStream);
                if (responseCode < 200 || responseCode >= 300) {
                    String errorCode = responseErrorCode(responseBody);
                    throw new IOException("HTTP " + responseCode
                            + (errorCode.isEmpty() ? "" : " - " + errorCode));
                }

                JSONObject payload = new JSONObject(responseBody);
                runOnUiThread(() -> {
                    consoleRequestInFlight = false;
                    if (!consolePolling) return;
                    if (!endpoint.equals(consoleLoadedForEndpoint)) {
                        scheduleConsolePolling();
                        return;
                    }

                    long remoteCursor = payload.optLong("cursor", consoleCursor);
                    if (remoteCursor < consoleCursor || payload.optBoolean("truncated", false)) {
                        consoleTranscript.setLength(0);
                        consoleFollowTail = true;
                    }
                    JSONArray lines = payload.optJSONArray("lines");
                    if (lines != null) {
                        StringBuilder received = new StringBuilder();
                        for (int index = 0; index < lines.length(); index++) {
                            JSONObject line = lines.optJSONObject(index);
                            if (line == null) continue;
                            if (received.length() > 0) received.append('\n');
                            received.append(line.optString("text", ""));
                        }
                        if (received.length() > 0) appendConsoleLine(received.toString());
                    }
                    consoleCursor = remoteCursor;
                    consoleLastError = null;
                    scheduleConsolePolling();
                });
            } catch (Exception error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                final String errorMessage = message;
                runOnUiThread(() -> {
                    consoleRequestInFlight = false;
                    if (consolePolling) {
                        reportConsoleProblem(errorMessage);
                        scheduleConsolePolling();
                    }
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void reportConsoleProblem(String message) {
        if (message == null || message.equals(consoleLastError)) return;
        consoleLastError = message;
        appendConsoleLine("[link] " + message);
    }

    private static String responseErrorCode(String responseBody) {
        try {
            return new JSONObject(responseBody).optString("error", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void renderEmptyStats() {
        statsContainer.removeAllViews();
        TextView empty = new TextView(this);
        empty.setText("SIN DATOS");
        empty.setTextColor(COLOR_MUTED);
        empty.setTextSize(14);
        statsContainer.addView(empty, matchWidthWrapHeight());
    }

    private void renderStats(JSONObject json, long latencyMs) {
        statsContainer.removeAllViews();

        double tps = json.optDouble("estimatedTps", 0.0);
        double mspt = json.optDouble("mspt", 0.0);
        int onlinePlayers = json.optInt("onlinePlayers", 0);
        int maxPlayers = json.optInt("maxPlayers", 0);
        int tpsColor = tps >= 19.0 ? COLOR_GREEN
                : (tps >= 15.0 ? COLOR_AMBER : COLOR_RED);
        int msptColor = mspt <= 50.0 ? COLOR_GREEN
                : (mspt <= 100.0 ? COLOR_AMBER : COLOR_RED);

        LinearLayout headline = new LinearLayout(this);
        headline.setOrientation(LinearLayout.HORIZONTAL);
        headline.addView(metricTile("TPS", formatNumber(tps, 2), tpsColor),
                metricTileParams(true));
        headline.addView(metricTile("MSPT", formatNumber(mspt, 1) + " ms", msptColor),
                metricTileParams(false));
        headline.addView(metricTile("JUGADORES", onlinePlayers + "/" + maxPlayers, COLOR_CYAN),
                metricTileParams(false));
        statsContainer.addView(headline, marginParams(dp(12)));

        double processCpu = json.optDouble("processCpuPercent", -1.0);
        double systemCpu = json.optDouble("systemCpuPercent", -1.0);
        addProgressMetric(statsContainer, "CPU de Minecraft", processCpu, formatPercent(processCpu),
                COLOR_CYAN);
        addProgressMetric(statsContainer, "CPU del equipo", systemCpu, formatPercent(systemCpu),
                COLOR_BLUE);

        long usedBytes = json.optLong("memoryUsedBytes", 0L);
        long maxBytes = json.optLong("memoryMaxBytes", 0L);
        double memoryPercent = maxBytes > 0L ? (usedBytes * 100.0 / maxBytes) : -1.0;
        addProgressMetric(statsContainer, "Memoria asignada", memoryPercent,
                formatMegabytes(usedBytes) + " / " + formatMegabytes(maxBytes),
                COLOR_GREEN);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.addView(detailCell("MINECRAFT", json.optString("minecraftVersion", "—")),
                detailCellParams(true));
        details.addView(detailCell("UPTIME", formatUptime(json.optLong("uptimeSeconds", 0L))),
                detailCellParams(false));
        details.addView(detailCell("RESPUESTA", latencyMs + " ms"), detailCellParams(false));
        statsContainer.addView(details, marginParams(dp(16)));

        TextView playersTitle = label("JUGADORES CONECTADOS");
        playersTitle.setTextColor(COLOR_CYAN);
        playersTitle.setTextSize(12);
        statsContainer.addView(playersTitle, marginParams(dp(8)));
        LinearLayout players = new LinearLayout(this);
        players.setOrientation(LinearLayout.VERTICAL);
        JSONArray playerArray = json.optJSONArray("players");
        if (playerArray == null || playerArray.length() == 0) {
            TextView none = new TextView(this);
            none.setText("Ningún jugador conectado");
            none.setTextColor(COLOR_MUTED);
            none.setTextSize(13);
            players.addView(none, matchWidthWrapHeight());
        } else {
            for (int index = 0; index < playerArray.length(); index++) {
                JSONObject player = playerArray.optJSONObject(index);
                if (player == null) continue;
                TextView playerChip = new TextView(this);
                playerChip.setText("•  " + player.optString("name", "sin nombre"));
                playerChip.setTextColor(COLOR_GREEN);
                playerChip.setTextSize(13);
                playerChip.setPadding(dp(10), dp(6), dp(10), dp(6));
                playerChip.setBackground(roundBackground(COLOR_SURFACE_RAISED,
                        COLOR_BORDER, 8));
                players.addView(playerChip, marginParams(dp(5)));
            }
        }
        statsContainer.addView(players, matchWidthWrapHeight());
    }

    private LinearLayout metricTile(String title, String value, int color) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(11), dp(10), dp(11), dp(10));
        tile.setBackground(roundBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, 10));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_MUTED);
        titleView.setTextSize(10);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(titleView, matchWidthWrapHeight());

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(color);
        valueView.setTextSize(20);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueView.setShadowLayer(dp(5), 0, 0, color);
        tile.addView(valueView, marginParams(dp(2)));

        return tile;
    }

    private void addProgressMetric(LinearLayout parent, String title, double value,
                                   String valueLabel, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(0);
        row.setMinimumWidth(0);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(0);
        heading.setMinimumWidth(0);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(13);
        heading.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = new TextView(this);
        valueView.setText(valueLabel);
        valueView.setTextColor(color);
        valueView.setTextSize(13);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(valueView, matchWidthWrapHeight());
        row.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(7));
        progressParams.topMargin = dp(3);
        progressParams.leftMargin = dp(1);
        progressParams.rightMargin = dp(1);
        row.addView(progressBar(value, color), progressParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        rowParams.bottomMargin = dp(8);
        parent.addView(row, rowParams);
    }

    private View progressBar(double value, int color) {
        return new StatsProgressView(value, color);
    }

    private final class StatsProgressView extends View {
        private final float fraction;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StatsProgressView(double value, int color) {
            super(MainActivity.this);
            fraction = Double.isFinite(value)
                    ? (float) (Math.max(0.0, Math.min(100.0, value)) / 100.0)
                    : 0.0f;
            trackPaint.setColor(COLOR_TRACK);
            glowPaint.setColor(Color.argb(72, Color.red(color), Color.green(color), Color.blue(color)));
            fillPaint.setColor(color);
            setMinimumHeight(0);
            setMinimumWidth(0);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? 0 : MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, dp(7));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float height = getHeight();
            float radius = height / 2.0f;
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), height), radius, radius, trackPaint);
            float fillWidth = getWidth() * fraction;
            if (fillWidth > 0) {
                float glowInset = dp(2);
                canvas.drawRoundRect(new RectF(0, -glowInset, fillWidth,
                        height + glowInset), radius + glowInset, radius + glowInset, glowPaint);
                canvas.drawRoundRect(new RectF(0, 0, fillWidth, height), radius, radius, fillPaint);
            }
        }
    }

    /** Keeps the live output scrollable without letting the outer screen consume the gesture. */
    private final class ConsoleOutputScrollView extends ScrollView {
        ConsoleOutputScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            ViewParent parent = getParent();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            } else if ((action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) && parent != null) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private LinearLayout detailCell(String title, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_MUTED);
        titleView.setTextSize(10);
        cell.addView(titleView, matchWidthWrapHeight());
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(COLOR_TEXT);
        valueView.setTextSize(12);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cell.addView(valueView, marginParams(dp(2)));
        return cell;
    }

    private LinearLayout.LayoutParams metricTileParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (!first) params.leftMargin = dp(5);
        return params;
    }

    private LinearLayout.LayoutParams detailCellParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (!first) params.leftMargin = dp(8);
        return params;
    }

    private static String formatPercent(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return "no disponible";
        }
        return String.format(Locale.getDefault(), "%.1f%%", value);
    }

    private static String formatNumber(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return String.format(Locale.getDefault(), "%1$." + decimals + "f", value);
    }

    private static String currentTimeLabel() {
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date());
    }

    private void addHistorySample(StatsSample sample) {
        history.add(sample);
        if (history.size() > MAX_HISTORY_SAMPLES) {
            history.remove(0);
        }
        chartView.setSamples(history);
    }

    private void loadPersistentHistoryIfNeeded(String statsEndpoint, StatsSample latestSample) {
        if (historyRequestInFlight || statsEndpoint.equals(historyLoadedForEndpoint)) {
            return;
        }
        loadHistory(statsEndpoint, latestSample);
    }

    private void loadSelectedHistory() {
        if (historyStartMs >= historyEndMs) {
            showError("La fecha inicial debe ser anterior a la fecha final.");
            return;
        }
        String address = addressInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            if (address.isEmpty()) {
                showError("Escribe la dirección del servidor.");
                return;
            }
            historyLoadedForEndpoint = null;
            loadHistory(buildEndpoint(address, port), null);
        } catch (NumberFormatException error) {
            showError("El puerto debe estar entre 1 y 65535.");
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
        }
    }

    private void loadHistory(String statsEndpoint, StatsSample latestSample) {
        if (historyRequestInFlight) return;
        final String apiUsername = currentApiUsername();
        final String apiPassword = currentApiPassword();
        final String legacyApiToken = currentApiToken();
        if (!hasValidApiCredentials(apiUsername, apiPassword)
                && !hasValidApiToken(legacyApiToken)) {
            showError("Configura usuario y contrasena (minimo 12 caracteres).");
            return;
        }
        historyRequestInFlight = true;
        loadHistoryButton.setEnabled(false);
        statusView.setText("Cargando historial…");
        String historyEndpoint = statsEndpoint.replace(
                "/api/server/stats", "/api/server/history?fromEpochMs=" + historyStartMs
                        + "&toEpochMs=" + historyEndMs + "&limit=" + MAX_HISTORY_SAMPLES);

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(historyEndpoint).openConnection();
                connection.setRequestMethod("GET");
                applyApiCredentials(connection, apiUsername, apiPassword, legacyApiToken);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                int responseCode = connection.getResponseCode();
                InputStream responseStream = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String responseBody = responseStream == null ? "" : readResponse(responseStream);
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("HTTP " + responseCode);
                }

                JSONArray samples = new JSONObject(responseBody).optJSONArray("samples");
                ArrayList<StatsSample> loadedHistory = new ArrayList<>();
                if (samples != null) {
                    for (int index = 0; index < samples.length(); index++) {
                        JSONObject sample = samples.optJSONObject(index);
                        if (sample != null) {
                            loadedHistory.add(StatsSample.fromJson(sample, 0L));
                        }
                    }
                }
                runOnUiThread(() -> replaceHistory(loadedHistory, latestSample, statsEndpoint));
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    historyRequestInFlight = false;
                    historyLoadedForEndpoint = statsEndpoint;
                    loadHistoryButton.setEnabled(true);
                    showError("No se pudo cargar el historial de ese periodo.");
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void replaceHistory(ArrayList<StatsSample> loadedHistory, StatsSample latestSample,
                                String statsEndpoint) {
        history.clear();
        int start = Math.max(0, loadedHistory.size() - MAX_HISTORY_SAMPLES);
        for (int index = start; index < loadedHistory.size(); index++) {
            history.add(loadedHistory.get(index));
        }
        if (latestSample != null && (history.isEmpty()
                || history.get(history.size() - 1).capturedAtMs < latestSample.capturedAtMs)) {
            addHistorySample(latestSample);
        } else {
            chartView.setSamples(history);
        }
        historyRequestInFlight = false;
        historyLoadedForEndpoint = statsEndpoint;
        loadHistoryButton.setEnabled(true);
        chartTitle.setText("Historial: " + history.size() + " muestras");
    }

    private void pickHistoryDateTime(boolean start) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(start ? historyStartMs : historyEndMs);
        new DatePickerDialog(this, (view, year, month, day) -> {
            calendar.set(year, month, day);
            new TimePickerDialog(this, (timeView, hour, minute) -> {
                calendar.set(Calendar.HOUR_OF_DAY, hour);
                calendar.set(Calendar.MINUTE, minute);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                if (start) {
                    historyStartMs = calendar.getTimeInMillis();
                } else {
                    historyEndMs = calendar.getTimeInMillis();
                }
                updateHistoryRangeLabels();
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateHistoryRangeLabels() {
        java.text.DateFormat format = new java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm", Locale.getDefault());
        historyStartButton.setText("Desde: " + format.format(new Date(historyStartMs)));
        historyEndButton.setText("Hasta: " + format.format(new Date(historyEndMs)));
        chartTitle.setText("Historial: " + format.format(new Date(historyStartMs))
                + " - " + format.format(new Date(historyEndMs)));
    }

    private void searchForUpdate() {
        String manifestUrl = DEFAULT_UPDATE_MANIFEST_URL;
        updateCheckedForEndpoint = null;
        checkForUpdate(manifestUrl);
    }

    private void checkForUpdate(String manifestUrl) {
        if (updateCheckInFlight) return;
        updateCheckInFlight = true;
        searchUpdateButton.setEnabled(false);
        updateButton.setVisibility(View.GONE);
        updateView.setText("Buscando actualización…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);
                int responseCode = connection.getResponseCode();
                InputStream responseStream = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String responseBody = responseStream == null ? "" : readResponse(responseStream);
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("HTTP " + responseCode);
                }
                String contentType = connection.getHeaderField("Content-Type");
                if ((contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html"))
                        || responseBody.trim().startsWith("<")) {
                    throw new IOException("Página HTML");
                }
                JSONObject update = new JSONObject(responseBody);
                int remoteVersionCode = update.optInt("versionCode", 0);
                String remoteVersionName = update.optString("versionName", "desconocida");
                String downloadEndpoint = update.optString("downloadUrl", "").trim();
                if (downloadEndpoint.isEmpty()) {
                    throw new IOException("El manifiesto no contiene downloadUrl");
                }
                runOnUiThread(() -> {
                    updateCheckInFlight = false;
                    updateCheckedForEndpoint = manifestUrl;
                    searchUpdateButton.setEnabled(true);
                    availableVersionCode = remoteVersionCode;
                    availableVersionName = remoteVersionName;
                    updateDownloadEndpoint = downloadEndpoint;
                    if (remoteVersionCode > CURRENT_VERSION_CODE) {
                        updateView.setText("Nueva versión disponible: " + remoteVersionName);
                        updateView.setTextColor(COLOR_CYAN);
                        updateButton.setVisibility(View.VISIBLE);
                        updateButton.setEnabled(true);
                    } else {
                        updateView.setText("La aplicación está actualizada (" + CURRENT_VERSION_NAME + ").");
                        updateView.setTextColor(COLOR_GREEN);
                        updateButton.setVisibility(View.GONE);
                    }
                });
            } catch (Exception ignored) {
                String message = "Página HTML".equals(ignored.getMessage())
                        ? "GitHub no devolvió el manifiesto JSON de la Release."
                        : "No se pudo consultar GitHub Releases.";
                runOnUiThread(() -> {
                    updateCheckInFlight = false;
                    updateCheckedForEndpoint = manifestUrl;
                    searchUpdateButton.setEnabled(true);
                    updateButton.setVisibility(View.GONE);
                    updateView.setText(message);
                    updateView.setTextColor(COLOR_MUTED);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void downloadAndInstallUpdate() {
        if (updateDownloadEndpoint == null || availableVersionCode <= CURRENT_VERSION_CODE) return;
        updateButton.setEnabled(false);
        updateView.setText("Descargando versión " + availableVersionName + "…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(updateDownloadEndpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("HTTP " + responseCode);
                }
                File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (directory == null) directory = getCacheDir();
                File apk = new File(directory, "modserverstats-update.apk");
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                runOnUiThread(() -> installDownloadedUpdate(apk));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    updateView.setText("No se pudo descargar la actualización.");
                    updateView.setTextColor(COLOR_RED);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void installDownloadedUpdate(File apk) {
        if (apk == null || !apk.isFile() || apk.length() == 0L) {
            updateButton.setEnabled(true);
            updateView.setText("La actualización descargada está vacía.");
            updateView.setTextColor(COLOR_RED);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingUpdateApk = apk;
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(settingsIntent, INSTALL_PERMISSION_REQUEST_CODE);
                updateView.setText("Activa «permitir instalar apps». Al volver, la instalación continuará sola.");
                updateView.setTextColor(COLOR_AMBER);
            } catch (ActivityNotFoundException error) {
                pendingUpdateApk = null;
                updateView.setText("Activa manualmente el permiso para instalar apps desconocidas.");
                updateView.setTextColor(COLOR_RED);
                updateButton.setEnabled(true);
            }
            return;
        }

        try {
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ".update.provider")
                    .path("apk")
                    .build();
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException noInstallAction) {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(uri, "application/vnd.android.package-archive");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(viewIntent);
            }
            updateView.setText("Descarga completa. Confirma la instalación de Android.");
            updateView.setTextColor(COLOR_CYAN);
        } catch (RuntimeException error) {
            updateButton.setEnabled(true);
            updateView.setText("Android no pudo abrir el instalador. Revisa el permiso de instalación.");
            updateView.setTextColor(COLOR_RED);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != INSTALL_PERMISSION_REQUEST_CODE) return;

        File apk = pendingUpdateApk;
        pendingUpdateApk = null;
        if (apk != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().canRequestPackageInstalls()) {
            installDownloadedUpdate(apk);
        } else {
            updateButton.setEnabled(true);
            updateView.setText("El permiso de instalación sigue desactivado.");
            updateView.setTextColor(COLOR_AMBER);
        }
    }

    private long selectedIntervalMillis() {
        int position = intervalSpinner == null ? 1 : intervalSpinner.getSelectedItemPosition();
        long[] intervals = {5_000L, 10_000L, 30_000L, 60_000L};
        if (position < 0 || position >= intervals.length) {
            position = 1;
        }
        return intervals[position];
    }

    private void startAutoRefresh() {
        stopAutoRefresh();
        refreshStats();
        autoRefreshRunnable = () -> {
            if (autoRefreshSwitch != null && autoRefreshSwitch.isChecked()) {
                refreshStats();
                mainHandler.postDelayed(autoRefreshRunnable, selectedIntervalMillis());
            }
        };
        mainHandler.postDelayed(autoRefreshRunnable, selectedIntervalMillis());
    }

    private void stopAutoRefresh() {
        if (mainHandler != null && autoRefreshRunnable != null) {
            mainHandler.removeCallbacks(autoRefreshRunnable);
        }
        autoRefreshRunnable = null;
    }

    private static String formatMegabytes(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String formatUptime(long seconds) {
        if (seconds <= 0) {
            return "0 s";
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (days > 0) {
            return days + " d " + hours + " h";
        }
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return minutes + " min " + remainingSeconds + " s";
        }
        return remainingSeconds + " s";
    }

    private void showError(String message) {
        statusView.setText(message);
        statusView.setTextColor(COLOR_RED);
    }

    private LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (autoRefreshSwitch != null && autoRefreshSwitch.isChecked()) {
            startAutoRefresh();
        }
        if (consoleScreenActive) {
            startConsolePolling();
        }
    }

    @Override
    protected void onStop() {
        stopAutoRefresh();
        stopConsolePolling();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopAutoRefresh();
        stopConsolePolling();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
