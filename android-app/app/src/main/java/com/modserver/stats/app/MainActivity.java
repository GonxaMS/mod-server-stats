package com.modserver.stats.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.Gravity;
import android.view.View;
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
    private static final int CURRENT_VERSION_CODE = 12;
    private static final String CURRENT_VERSION_NAME = "1.11";
    private static final String DEFAULT_UPDATE_MANIFEST_URL =
            "https://github.com/GonxaMS/mod-server-stats/releases/latest/download/latest.json";

    private EditText addressInput;
    private EditText portInput;
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
    private final ArrayList<StatsSample> history = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureSystemBars();
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
        root.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(14), dp(22), dp(18));
        header.setBackground(headerBackground());
        TextView title = new TextView(this);
        title.setText("Mod Server Stats");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, matchWidthWrapHeight());
        TextView subtitle = new TextView(this);
        subtitle.setText("Tu servidor, en una mirada");
        subtitle.setTextColor(Color.rgb(218, 232, 248));
        subtitle.setTextSize(14);
        header.addView(subtitle, matchWidthWrapHeight());
        root.addView(header, matchWidthWrapHeight());

        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(10), dp(8), dp(10), dp(8));
        navigation.setBackgroundColor(Color.WHITE);
        navigation.setElevation(dp(2));
        TextView statusTab = navigationButton("Estado");
        TextView historyTab = navigationButton("Historial");
        TextView settingsTab = navigationButton("Ajustes");
        navigation.addView(statusTab, weightedWidth());
        navigation.addView(historyTab, weightedWidth());
        navigation.addView(settingsTab, weightedWidth());
        root.addView(navigation, matchWidthWrapHeight());

        FrameLayout screens = new FrameLayout(this);
        View statusScreen = createStatusScreen();
        View historyScreen = createHistoryScreen();
        View settingsScreen = createSettingsScreen();
        screens.addView(statusScreen, frameMatchParams());
        screens.addView(historyScreen, frameMatchParams());
        screens.addView(settingsScreen, frameMatchParams());
        root.addView(screens, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            header.setPadding(dp(22), dp(14) + topInset, dp(22), dp(18));
            screens.setPadding(0, 0, 0, bottomInset);
            return insets;
        });
        root.post(root::requestApplyInsets);

        View[] allScreens = {statusScreen, historyScreen, settingsScreen};
        TextView[] allTabs = {statusTab, historyTab, settingsTab};
        statusTab.setOnClickListener(view -> showScreen(statusScreen, statusTab, allScreens, allTabs));
        historyTab.setOnClickListener(view -> showScreen(historyScreen, historyTab, allScreens, allTabs));
        settingsTab.setOnClickListener(view -> showScreen(settingsScreen, settingsTab, allScreens, allTabs));
        showScreen(statusScreen, statusTab, allScreens, allTabs);
        return root;
    }

    private View createStatusScreen() {
        ScrollView scroll = screenScroll();
        LinearLayout content = screenContent(scroll);

        TextView eyebrow = label("ESTADO DEL SERVIDOR");
        eyebrow.setTextColor(Color.rgb(38, 101, 165));
        content.addView(eyebrow, matchWidthWrapHeight());

        statusView = new TextView(this);
        statusView.setText("Sin conexión. Configura el servidor y pulsa actualizar.");
        statusView.setTextColor(Color.rgb(90, 100, 110));
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.addView(statusView, cardParams(dp(12)));

        refreshButton = actionButton("Actualizar métricas", Color.rgb(38, 101, 165));
        refreshButton.setOnClickListener(view -> refreshStats());
        content.addView(refreshButton, marginParams(dp(14)));

        LinearLayout statsCard = card();
        TextView statsTitle = label("MÉTRICAS EN TIEMPO REAL");
        statsTitle.setTextColor(Color.rgb(38, 101, 165));
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
        TextView rangeHelp = new TextView(this);
        rangeHelp.setText("Consulta cualquier fecha y hora guardada en el servidor.");
        rangeHelp.setTextColor(Color.rgb(105, 115, 125));
        rangeHelp.setTextSize(13);
        content.addView(rangeHelp, marginParams(dp(10)));

        LinearLayout rangeCard = card();
        historyStartButton = actionButton("Desde", Color.rgb(74, 91, 111));
        historyStartButton.setOnClickListener(view -> pickHistoryDateTime(true));
        rangeCard.addView(historyStartButton, matchWidthWrapHeight());
        historyEndButton = actionButton("Hasta", Color.rgb(74, 91, 111));
        historyEndButton.setOnClickListener(view -> pickHistoryDateTime(false));
        rangeCard.addView(historyEndButton, marginParams(dp(8)));
        loadHistoryButton = actionButton("Cargar este periodo", Color.rgb(38, 101, 165));
        loadHistoryButton.setOnClickListener(view -> loadSelectedHistory());
        rangeCard.addView(loadHistoryButton, marginParams(dp(10)));
        content.addView(rangeCard, cardParams(dp(14)));
        updateHistoryRangeLabels();

        Spinner chartMetricSpinner = new Spinner(this);
        String[] chartMetrics = {"TPS", "MSPT", "CPU de Minecraft", "CPU del equipo", "Memoria",
                "Jugadores", "Tiempo de respuesta"};
        ArrayAdapter<String> chartMetricAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, chartMetrics);
        chartMetricAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        chartMetricSpinner.setAdapter(chartMetricAdapter);
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

    private View createSettingsScreen() {
        ScrollView scroll = screenScroll();
        LinearLayout content = screenContent(scroll);
        TextView heading = label("AJUSTES DE CONEXIÓN");
        heading.setTextSize(20);
        content.addView(heading, matchWidthWrapHeight());
        TextView help = new TextView(this);
        help.setText("Estos datos se guardan solo en este teléfono.");
        help.setTextColor(Color.rgb(105, 115, 125));
        help.setTextSize(13);
        content.addView(help, marginParams(dp(10)));

        LinearLayout connectionCard = card();
        connectionCard.addView(label("Dirección del servidor"), matchWidthWrapHeight());
        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setHint("192.168.1.50 o dominio");
        addressInput.setText(preferences.getString("address", ""));
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        connectionCard.addView(addressInput, marginParams(dp(4)));
        TextView addressHelp = new TextView(this);
        addressHelp.setText("IP, dominio o localhost");
        addressHelp.setTextColor(Color.rgb(105, 115, 125));
        addressHelp.setTextSize(12);
        connectionCard.addView(addressHelp, marginParams(dp(12)));
        connectionCard.addView(label("Puerto de la API"), marginParams(dp(8)));
        portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setText(preferences.getString("port", DEFAULT_PORT));
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectionCard.addView(portInput, marginParams(dp(4)));
        TextView portHelp = new TextView(this);
        portHelp.setText("Debe coincidir con api.port del mod");
        portHelp.setTextColor(Color.rgb(105, 115, 125));
        portHelp.setTextSize(12);
        connectionCard.addView(portHelp, marginParams(dp(12)));
        content.addView(connectionCard, cardParams(dp(14)));

        LinearLayout refreshCard = card();
        TextView refreshTitle = label("ACTUALIZACIÓN DE MÉTRICAS");
        refreshTitle.setTextColor(Color.rgb(38, 101, 165));
        refreshCard.addView(refreshTitle, matchWidthWrapHeight());
        LinearLayout autoRefreshRow = new LinearLayout(this);
        autoRefreshRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRefreshRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRefreshSwitch = new Switch(this);
        autoRefreshSwitch.setText("Actualizar métricas automáticamente");
        autoRefreshSwitch.setTextSize(14);
        autoRefreshSwitch.setTextColor(Color.rgb(45, 55, 65));
        autoRefreshRow.addView(autoRefreshSwitch, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        intervalSpinner = new Spinner(this);
        String[] intervalLabels = {"5 s", "10 s", "30 s", "60 s"};
        ArrayAdapter<String> intervalAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, intervalLabels);
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalSpinner.setAdapter(intervalAdapter);
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
        updateTitle.setTextColor(Color.rgb(38, 101, 165));
        updateCard.addView(updateTitle, matchWidthWrapHeight());
        TextView sourceLabel = label("ENLACE DE ACTUALIZACIONES");
        sourceLabel.setTextColor(Color.rgb(105, 115, 125));
        sourceLabel.setTextSize(11);
        updateCard.addView(sourceLabel, marginParams(dp(10)));
        TextView updateSource = new TextView(this);
        updateSource.setText(DEFAULT_UPDATE_MANIFEST_URL);
        updateSource.setTextColor(Color.rgb(55, 65, 75));
        updateSource.setTextSize(12);
        updateSource.setTextIsSelectable(false);
        updateCard.addView(updateSource, matchWidthWrapHeight());
        TextView sourceHelp = new TextView(this);
        sourceHelp.setText("Enlace oficial integrado · se consulta solo al pulsar buscar.");
        sourceHelp.setTextColor(Color.rgb(105, 115, 125));
        sourceHelp.setTextSize(12);
        updateCard.addView(sourceHelp, marginParams(dp(5)));
        updateView = new TextView(this);
        updateView.setText("Pulsa buscar cuando quieras comprobar una versión nueva.");
        updateView.setTextColor(Color.rgb(90, 100, 110));
        updateView.setTextSize(13);
        updateCard.addView(updateView, marginParams(dp(6)));
        searchUpdateButton = actionButton("Buscar actualización", Color.rgb(232, 157, 49));
        searchUpdateButton.setOnClickListener(view -> searchForUpdate());
        updateCard.addView(searchUpdateButton, marginParams(dp(10)));
        updateButton = actionButton("Instalar actualización", Color.rgb(43, 145, 95));
        updateButton.setVisibility(View.GONE);
        updateButton.setOnClickListener(view -> downloadAndInstallUpdate());
        updateCard.addView(updateButton, matchWidthWrapHeight());
        return updateCard;
    }

    private ScrollView screenScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 247, 251));
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
        card.setBackground(roundBackground(Color.WHITE, Color.rgb(226, 232, 240), 18));
        card.setElevation(dp(2));
        return card;
    }

    private Button actionButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(14), dp(4), dp(14), dp(4));
        button.setBackground(roundBackground(color, color, 14));
        return button;
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
        return tab;
    }

    private void showScreen(View active, TextView activeTab, View[] screens, TextView[] tabs) {
        for (View screen : screens) screen.setVisibility(screen == active ? View.VISIBLE : View.GONE);
        for (TextView tab : tabs) {
            boolean selected = tab == activeTab;
            tab.setTextColor(selected ? Color.rgb(38, 101, 165) : Color.rgb(100, 112, 126));
            tab.setBackground(selected
                    ? roundBackground(Color.rgb(232, 241, 251), Color.rgb(232, 241, 251), 12)
                    : roundBackground(Color.WHITE, Color.WHITE, 12));
        }
    }

    private GradientDrawable headerBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(24, 71, 125), Color.rgb(47, 130, 173)});
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.rgb(24, 71, 125));
            getWindow().setNavigationBarColor(Color.rgb(244, 247, 251));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
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
        label.setTextColor(Color.rgb(45, 55, 65));
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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

        preferences.edit().putString("address", address).putString("port", portText).apply();
        requestInProgress = true;
        refreshButton.setEnabled(false);
        statusView.setText("Conectando…");
        statusView.setTextColor(Color.rgb(90, 100, 110));
        final long requestStartedAt = SystemClock.elapsedRealtime();

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
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
                    statusView.setTextColor(Color.rgb(35, 125, 70));
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
            return protocol + "://" + host + ":" + actualPort + "/api/server/stats";
        } catch (IOException exception) {
            throw new IllegalArgumentException("La dirección del servidor no es válida.");
        }
    }

    private static String readResponse(InputStream stream) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private void renderEmptyStats() {
        statsContainer.removeAllViews();
        TextView empty = new TextView(this);
        empty.setText("Aquí aparecerán las estadísticas del servidor.");
        empty.setTextColor(Color.rgb(105, 115, 125));
        empty.setTextSize(14);
        statsContainer.addView(empty, matchWidthWrapHeight());
    }

    private void renderStats(JSONObject json, long latencyMs) {
        statsContainer.removeAllViews();

        double tps = json.optDouble("estimatedTps", 0.0);
        double mspt = json.optDouble("mspt", 0.0);
        int onlinePlayers = json.optInt("onlinePlayers", 0);
        int maxPlayers = json.optInt("maxPlayers", 0);
        int tpsColor = tps >= 19.0 ? Color.rgb(35, 125, 70)
                : (tps >= 15.0 ? Color.rgb(190, 120, 25) : Color.rgb(190, 55, 55));
        int msptColor = mspt <= 50.0 ? Color.rgb(35, 125, 70)
                : (mspt <= 100.0 ? Color.rgb(190, 120, 25) : Color.rgb(190, 55, 55));

        LinearLayout headline = new LinearLayout(this);
        headline.setOrientation(LinearLayout.HORIZONTAL);
        headline.addView(metricTile("TPS", formatNumber(tps, 2), tpsStatus(tps), tpsColor),
                metricTileParams(true));
        headline.addView(metricTile("MSPT", formatNumber(mspt, 1) + " ms", "objetivo < 50 ms", msptColor),
                metricTileParams(false));
        headline.addView(metricTile("JUGADORES", onlinePlayers + "/" + maxPlayers, "conectados",
                        Color.rgb(38, 101, 165)), metricTileParams(false));
        statsContainer.addView(headline, marginParams(dp(12)));

        double processCpu = json.optDouble("processCpuPercent", -1.0);
        double systemCpu = json.optDouble("systemCpuPercent", -1.0);
        addProgressMetric(statsContainer, "CPU de Minecraft", processCpu, formatPercent(processCpu),
                Color.rgb(38, 101, 165));
        addProgressMetric(statsContainer, "CPU del equipo", systemCpu, formatPercent(systemCpu),
                Color.rgb(78, 117, 173));

        long usedBytes = json.optLong("memoryUsedBytes", 0L);
        long maxBytes = json.optLong("memoryMaxBytes", 0L);
        double memoryPercent = maxBytes > 0L ? (usedBytes * 100.0 / maxBytes) : -1.0;
        addProgressMetric(statsContainer, "Memoria asignada", memoryPercent,
                formatMegabytes(usedBytes) + " / " + formatMegabytes(maxBytes),
                Color.rgb(43, 145, 95));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.addView(detailCell("MINECRAFT", json.optString("minecraftVersion", "—")),
                detailCellParams(true));
        details.addView(detailCell("UPTIME", formatUptime(json.optLong("uptimeSeconds", 0L))),
                detailCellParams(false));
        details.addView(detailCell("RESPUESTA", latencyMs + " ms"), detailCellParams(false));
        statsContainer.addView(details, marginParams(dp(16)));

        TextView playersTitle = label("JUGADORES CONECTADOS");
        playersTitle.setTextColor(Color.rgb(38, 101, 165));
        playersTitle.setTextSize(12);
        statsContainer.addView(playersTitle, marginParams(dp(8)));
        LinearLayout players = new LinearLayout(this);
        players.setOrientation(LinearLayout.VERTICAL);
        JSONArray playerArray = json.optJSONArray("players");
        if (playerArray == null || playerArray.length() == 0) {
            TextView none = new TextView(this);
            none.setText("Ningún jugador conectado");
            none.setTextColor(Color.rgb(105, 115, 125));
            none.setTextSize(13);
            players.addView(none, matchWidthWrapHeight());
        } else {
            for (int index = 0; index < playerArray.length(); index++) {
                JSONObject player = playerArray.optJSONObject(index);
                if (player == null) continue;
                TextView playerChip = new TextView(this);
                playerChip.setText("•  " + player.optString("name", "sin nombre"));
                playerChip.setTextColor(Color.rgb(45, 75, 105));
                playerChip.setTextSize(13);
                playerChip.setPadding(dp(10), dp(6), dp(10), dp(6));
                playerChip.setBackground(roundBackground(Color.rgb(239, 246, 253),
                        Color.rgb(214, 229, 244), 10));
                players.addView(playerChip, marginParams(dp(5)));
            }
        }
        statsContainer.addView(players, matchWidthWrapHeight());
    }

    private LinearLayout metricTile(String title, String value, String caption, int color) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(11), dp(10), dp(11), dp(10));
        tile.setBackground(roundBackground(Color.rgb(247, 250, 253), Color.rgb(226, 234, 242), 12));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(105, 115, 125));
        titleView.setTextSize(10);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(titleView, matchWidthWrapHeight());

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(color);
        valueView.setTextSize(20);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(valueView, marginParams(dp(2)));

        TextView captionView = new TextView(this);
        captionView.setText(caption);
        captionView.setTextColor(Color.rgb(105, 115, 125));
        captionView.setTextSize(11);
        tile.addView(captionView, matchWidthWrapHeight());
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
        titleView.setTextColor(Color.rgb(55, 65, 75));
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
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StatsProgressView(double value, int color) {
            super(MainActivity.this);
            fraction = Double.isFinite(value)
                    ? (float) (Math.max(0.0, Math.min(100.0, value)) / 100.0)
                    : 0.0f;
            trackPaint.setColor(Color.rgb(232, 238, 244));
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
                canvas.drawRoundRect(new RectF(0, 0, fillWidth, height), radius, radius, fillPaint);
            }
        }
    }

    private LinearLayout detailCell(String title, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(105, 115, 125));
        titleView.setTextSize(10);
        cell.addView(titleView, matchWidthWrapHeight());
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(Color.rgb(45, 55, 65));
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

    private static String tpsStatus(double tps) {
        if (tps >= 19.0) {
            return "estable";
        }
        if (tps >= 15.0) {
            return "degradado";
        }
        return "lento";
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
                        updateView.setTextColor(Color.rgb(35, 105, 165));
                        updateButton.setVisibility(View.VISIBLE);
                        updateButton.setEnabled(true);
                    } else {
                        updateView.setText("La aplicación está actualizada (" + CURRENT_VERSION_NAME + ").");
                        updateView.setTextColor(Color.rgb(35, 125, 70));
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
                    updateView.setTextColor(Color.rgb(90, 100, 110));
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
                    updateView.setTextColor(Color.rgb(180, 45, 45));
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
            updateView.setTextColor(Color.rgb(180, 45, 45));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(settingsIntent);
                updateView.setText("Activa «permitir instalar apps» y vuelve a pulsar instalar.");
                updateView.setTextColor(Color.rgb(180, 110, 25));
            } catch (ActivityNotFoundException error) {
                updateView.setText("Activa manualmente el permiso para instalar apps desconocidas.");
                updateView.setTextColor(Color.rgb(180, 45, 45));
            }
            updateButton.setEnabled(true);
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
            updateView.setTextColor(Color.rgb(35, 105, 165));
        } catch (RuntimeException error) {
            updateButton.setEnabled(true);
            updateView.setText("Android no pudo abrir el instalador. Revisa el permiso de instalación.");
            updateView.setTextColor(Color.rgb(180, 45, 45));
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
        statusView.setTextColor(Color.rgb(180, 45, 45));
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
    }

    @Override
    protected void onStop() {
        stopAutoRefresh();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopAutoRefresh();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
