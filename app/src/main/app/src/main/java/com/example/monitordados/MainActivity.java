package com.example.monitordados;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PERMISSOES = 101;
    private static final int REQ_VPN = 102;

    // Instância global do WebView que continua rodando em 2º plano mesmo com a tela fechada
    public static WebView webViewGlobal = null;
    public static MainActivity instanciaAtiva = null;

    private static volatile double ultimaVelocidadeMbps = 0.0;
    private static volatile int ultimaLatenciaMs = 0;
    private static long ultimoRxBytesTotal = 0;
    private static long ultimoTxBytesTotal = 0;
    private static long ultimoTempoLeitura = 0;
    private static final Map<Integer, Long> cacheTrafegoUid = new HashMap<>();

    // WebView customizado que ignora o bloqueio de tela do Android para não pausar o WebSocket
    public static class WebViewSempreAtivo extends WebView {
        public WebViewSempreAtivo(Context context) {
            super(context);
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            // Mantém o motor JavaScript e WebSocket sempre em estado visível/ativo
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    public static synchronized void garantirMotorWebAtivo(final Context context) {
        final Context appCtx = context.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    garantirMotorWebAtivo(appCtx);
                }
            });
            return;
        }

        if (webViewGlobal == null) {
            webViewGlobal = new WebViewSempreAtivo(appCtx);
            WebSettings settings = webViewGlobal.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setGeolocationEnabled(true);
            settings.setAllowFileAccess(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                settings.setMediaPlaybackRequiresUserGesture(false);
            }

            webViewGlobal.setWebViewClient(new WebViewClient());
            webViewGlobal.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                    callback.invoke(origin, true, false);
                }
            });

            webViewGlobal.addJavascriptInterface(new PonteMonitorDados(appCtx), "AndroidMonitor");
            webViewGlobal.loadUrl("file:///android_asset/index.html");
        }

        webViewGlobal.onResume();
        webViewGlobal.resumeTimers();
    }

    public static void iniciarServico24h(Context context) {
        try {
            Intent srv = new Intent(context, MonitorSempreAtivoService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(srv);
            } else {
                context.startService(srv);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instanciaAtiva = this;

        // Inicia o serviço 24h e garante que o WebView único esteja rodando
        iniciarServico24h(this);
        garantirMotorWebAtivo(this);

        if (webViewGlobal.getParent() != null) {
            ((ViewGroup) webViewGlobal.getParent()).removeView(webViewGlobal);
        }
        setContentView(webViewGlobal);

        solicitarPermissoesIniciais();
    }

    @Override
    protected void onResume() {
        super.onResume();
        instanciaAtiva = this;
        if (webViewGlobal != null) {
            webViewGlobal.onResume();
            webViewGlobal.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Impede que o Android pause o envio de dados quando o app vai para o fundo
        if (webViewGlobal != null) {
            webViewGlobal.onResume();
            webViewGlobal.resumeTimers();
        }
    }

    @Override
    public void onBackPressed() {
        // Apenas minimiza o app em vez de fechar a janela
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (webViewGlobal != null && webViewGlobal.getParent() != null) {
            ((ViewGroup) webViewGlobal.getParent()).removeView(webViewGlobal);
        }
        if (instanciaAtiva == this) {
            instanciaAtiva = null;
        }
        super.onDestroy();
    }

    private void solicitarPermissoesIniciais() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissoes = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissoes.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                permissoes.add(Manifest.permission.READ_PHONE_STATE);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    permissoes.add("android.permission.POST_NOTIFICATIONS");
                }
            }
            if (!permissoes.isEmpty()) {
                requestPermissions(permissoes.toArray(new String[0]), REQ_PERMISSOES);
            }

            // Solicita rodar sem restrição de economia de bateria do Android
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intentBat = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intentBat.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intentBat);
                }
            } catch (Exception ignored) {}
        }

        // Solicita autorização inicial da chave de Firewall VPN
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQ_VPN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            if (FirewallVpnService.bloqueioGeralAtivo || !FirewallVpnService.listaAppsBloqueados.isEmpty()) {
                Intent serviceIntent = new Intent(this, FirewallVpnService.class);
                serviceIntent.setAction("ATUALIZAR");
                startService(serviceIntent);
            }
        }
    }

    private static long calcularInicioCiclo(int diaRenovacao) {
        Calendar cal = Calendar.getInstance();
        int diaAtual = cal.get(Calendar.DAY_OF_MONTH);
        int diaAjustado = Math.max(1, Math.min(28, diaRenovacao));

        if (diaAtual < diaAjustado) {
            cal.add(Calendar.MONTH, -1);
        }
        cal.set(Calendar.DAY_OF_MONTH, diaAjustado);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // =========================================================================
    // 1. RECEIVER DE BOOT (INICIA SOZINHO QUANDO O CELULAR LIGA)
    // =========================================================================
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            iniciarServico24h(context);
        }
    }

    // =========================================================================
    // 2. SERVIÇO 24H EM SEGUNDO PLANO (MANTÉM ONLINE NA CENTRAL SEM ABRIR O APP)
    // =========================================================================
    public static class MonitorSempreAtivoService extends Service {
        private static final String CANAL_ID = "monitor_dados_24h";
        private PowerManager.WakeLock wakeLock;

        @Override
        public void onCreate() {
            super.onCreate();
            iniciarNotificacaoDiscreta();
            ativarWakeLock();
            garantirMotorWebAtivo(getApplicationContext());
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            iniciarNotificacaoDiscreta();
            garantirMotorWebAtivo(getApplicationContext());
            return START_STICKY;
        }

        private void iniciarNotificacaoDiscreta() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                    NotificationChannel channel = new NotificationChannel(
                            CANAL_ID,
                            "Monitoramento de Dados em 2º Plano",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }

                Intent openIntent = new Intent(this, MainActivity.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, flags);

                Notification.Builder builder;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder = new Notification.Builder(this, CANAL_ID);
                } else {
                    builder = new Notification.Builder(this);
                }

                Notification notif = builder
                        .setContentTitle("Monitor de Dados Ativo")
                        .setContentText("Sincronizado em tempo real")
                        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .build();

                startForeground(1001, notif);
            } catch (Exception ignored) {}
        }

        private void ativarWakeLock() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonitorDados::SempreAtivoLock");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            // Se o usuário deslizar para fechar nos apps recentes, o serviço continua rodando
            iniciarServico24h(getApplicationContext());
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) {}
            }
            iniciarServico24h(getApplicationContext());
            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }

    // =========================================================================
    // 3. SERVIÇO DE FIREWALL VIA VPN LOCAL (CORTA INTERNET DOS APPS BLOQUEADOS)
    // =========================================================================
    public static class FirewallVpnService extends VpnService {
        private static ParcelFileDescriptor vpnInterface = null;
        public static volatile boolean bloqueioGeralAtivo = false;
        public static final List<String> listaAppsBloqueados = new ArrayList<>();

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && "PARAR".equals(intent.getAction())) {
                fecharTunel();
                stopSelf();
                return START_NOT_STICKY;
            }
            aplicarRegrasVpn();
            return START_STICKY;
        }

        private synchronized void aplicarRegrasVpn() {
            fecharTunel();

            if (!bloqueioGeralAtivo && listaAppsBloqueados.isEmpty()) {
                stopSelf();
                return;
            }

            try {
                Builder builder = new Builder();
                builder.setSession("Firewall Central Monitor");
                builder.setMtu(1500);

                builder.addAddress("10.111.222.1", 32);
                builder.addRoute("0.0.0.0", 0);
                builder.addDnsServer("10.111.222.2");

                try {
                    builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
                    builder.addRoute("::", 0);
                } catch (Exception ignored) {}

                if (bloqueioGeralAtivo) {
                    try {
                        builder.addDisallowedApplication(getPackageName());
                    } catch (Exception ignored) {}
                } else {
                    int adicionados = 0;
                    for (String pkg : listaAppsBloqueados) {
                        if (pkg != null && !pkg.trim().isEmpty() && !pkg.equals(getPackageName())) {
                            try {
                                builder.addAllowedApplication(pkg.trim());
                                adicionados++;
                            } catch (Exception ignored) {}
                        }
                    }
                    if (adicionados == 0) {
                        stopSelf();
                        return;
                    }
                }

                vpnInterface = builder.establish();
            } catch (Exception e) {
                fecharTunel();
            }
        }

        private synchronized void fecharTunel() {
            if (vpnInterface != null) {
                try {
                    vpnInterface.close();
                } catch (IOException ignored) {}
                vpnInterface = null;
            }
        }

        @Override
        public void onDestroy() {
            fecharTunel();
            super.onDestroy();
        }
    }

    // =========================================================================
    // 4. PONTE JAVASCRIPT <-> ANDROID NATIVO
    // =========================================================================
    public static class PonteMonitorDados {
        private final Context context;

        public PonteMonitorDados(Context context) {
            this.context = context.getApplicationContext();
        }

        @JavascriptInterface
        public boolean temPermissaoUso() {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        @JavascriptInterface
        public void solicitarPermissaoUso() {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }

        @JavascriptInterface
        public boolean temPermissaoVpn() {
            return VpnService.prepare(context) == null;
        }

        @JavascriptInterface
        public void solicitarPermissaoVpn() {
            if (instanciaAtiva != null) {
                Intent vpnIntent = VpnService.prepare(instanciaAtiva);
                if (vpnIntent != null) {
                    instanciaAtiva.startActivityForResult(vpnIntent, REQ_VPN);
                }
            }
        }

        @JavascriptInterface
        public void sincronizarFirewallVpn(boolean bloquearGeral, String pacotesJsonArray) {
            FirewallVpnService.bloqueioGeralAtivo = bloquearGeral;
            synchronized (FirewallVpnService.listaAppsBloqueados) {
                FirewallVpnService.listaAppsBloqueados.clear();
                if (pacotesJsonArray != null && !pacotesJsonArray.isEmpty()) {
                    try {
                        JSONArray arr = new JSONArray(pacotesJsonArray);
                        for (int i = 0; i < arr.length(); i++) {
                            String pkg = arr.optString(i, "");
                            if (!pkg.isEmpty()) {
                                FirewallVpnService.listaAppsBloqueados.add(pkg);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            boolean precisaAtivar = bloquearGeral || !FirewallVpnService.listaAppsBloqueados.isEmpty();

            if (!precisaAtivar) {
                Intent stopIntent = new Intent(context, FirewallVpnService.class);
                stopIntent.setAction("PARAR");
                context.startService(stopIntent);
                return;
            }

            Intent prepareIntent = VpnService.prepare(context);
            if (prepareIntent != null) {
                if (instanciaAtiva != null) {
                    instanciaAtiva.startActivityForResult(prepareIntent, REQ_VPN);
                }
            } else {
                Intent startIntent = new Intent(context, FirewallVpnService.class);
                startIntent.setAction("ATUALIZAR");
                context.startService(startIntent);
            }
        }

        @JavascriptInterface
        public double obterConsumoTotalMesGB(int diaRenovacao) {
            long inicio = calcularInicioCiclo(diaRenovacao);
            long agora = System.currentTimeMillis();
            long totalBytes = 0;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && temPermissaoUso()) {
                    NetworkStatsManager nsm = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
                    if (nsm != null) {
                        NetworkStats.Bucket bucket = nsm.querySummaryForDevice(
                                ConnectivityManager.TYPE_MOBILE,
                                null,
                                inicio,
                                agora
                        );
                        if (bucket != null) {
                            totalBytes = bucket.getRxBytes() + bucket.getTxBytes();
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (totalBytes <= 0) {
                long rx = TrafficStats.getMobileRxBytes();
                long tx = TrafficStats.getMobileTxBytes();
                if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED) {
                    totalBytes = rx + tx;
                }
            }

            return totalBytes / (1024.0 * 1024.0 * 1024.0);
        }

        @JavascriptInterface
        public String obterTipoRede() {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return "Desconectado";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network net = cm.getActiveNetwork();
                    if (net == null) return "Dados Desligados";
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps == null) return "Dados Desligados";

                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return "Wi-Fi Ativo";
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return obterGeracaoCelular();
                    }
                }
            } catch (Exception ignored) {}
            return "Dados Móveis";
        }

        private String obterGeracaoCelular() {
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    String operadora = tm.getNetworkOperatorName();
                    String sufixoOp = (operadora != null && !operadora.isEmpty()) ? " (" + operadora + ")" : "";
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                            int tipo = tm.getDataNetworkType();
                            if (tipo == 20) return "5G Ativo" + sufixoOp;
                            if (tipo == TelephonyManager.NETWORK_TYPE_LTE) return "4G LTE" + sufixoOp;
                            if (tipo == TelephonyManager.NETWORK_TYPE_HSPAP || tipo == TelephonyManager.NETWORK_TYPE_UMTS) {
                                return "3G" + sufixoOp;
                            }
                        }
                    }
                    return "4G/5G Móvel" + sufixoOp;
                }
            } catch (Exception ignored) {}
            return "4G/5G Móvel";
        }

        @JavascriptInterface
        public String obterInfoRedeEGeoJSON() {
            JSONObject json = new JSONObject();
            try {
                String redeAtiva = obterTipoRede();
                String operadora = "Não identificada";
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getNetworkOperatorName() != null && !tm.getNetworkOperatorName().isEmpty()) {
                    operadora = tm.getNetworkOperatorName();
                }

                double lat = 0.0;
                double lon = 0.0;
                String provedorGeo = "Aguardando GPS";

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                    LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null) {
                        Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (loc == null) {
                            loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        }
                        if (loc != null) {
                            lat = loc.getLatitude();
                            lon = loc.getLongitude();
                            provedorGeo = "GPS (" + loc.getProvider().toUpperCase(Locale.ROOT) + ")";
                        }
                    }
                }

                json.put("redeAtiva", redeAtiva);
                json.put("operadora", operadora);
                json.put("latitude", lat);
                json.put("longitude", lon);
                json.put("provedorGeo", provedorGeo);
            } catch (Exception ignored) {}
            return json.toString();
        }

        @JavascriptInterface
        public String obterAppsInstaladosJSON(int diaRenovacao) {
            JSONArray listaApps = new JSONArray();
            try {
                PackageManager pm = context.getPackageManager();
                long inicio = calcularInicioCiclo(diaRenovacao);
                long agora = System.currentTimeMillis();
                String redeAtual = obterTipoRede();

                Map<Integer, Long> bytesMobilePorUid = new HashMap<>();
                Map<Integer, Long> bytesWifiPorUid = new HashMap<>();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && temPermissaoUso()) {
                    NetworkStatsManager nsm = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
                    if (nsm != null) {
                        try {
                            NetworkStats statsMobile = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, inicio, agora);
                            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                            while (statsMobile != null && statsMobile.hasNextBucket()) {
                                statsMobile.getNextBucket(bucket);
                                int uid = bucket.getUid();
                                long b = bucket.getRxBytes() + bucket.getTxBytes();
                                long ant = bytesMobilePorUid.containsKey(uid) ? bytesMobilePorUid.get(uid) : 0L;
                                bytesMobilePorUid.put(uid, ant + b);
                            }
                            if (statsMobile != null) statsMobile.close();
                        } catch (Exception ignored) {}

                        try {
                            NetworkStats statsWifi = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, inicio, agora);
                            NetworkStats.Bucket bucketW = new NetworkStats.Bucket();
                            while (statsWifi != null && statsWifi.hasNextBucket()) {
                                statsWifi.getNextBucket(bucketW);
                                int uid = bucketW.getUid();
                                long b = bucketW.getRxBytes() + bucketW.getTxBytes();
                                long antW = bytesWifiPorUid.containsKey(uid) ? bytesWifiPorUid.get(uid) : 0L;
                                bytesWifiPorUid.put(uid, antW + b);
                            }
                            if (statsWifi != null) statsWifi.close();
                        } catch (Exception ignored) {}
                    }
                }

                List<PackageInfo> pacotes = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
                List<JSONObject> tempObjs = new ArrayList<>();

                for (PackageInfo pkg : pacotes) {
                    ApplicationInfo appInfo = pkg.applicationInfo;
                    if (appInfo == null) continue;

                    boolean ehAppUsuario = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                            || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                            || pm.getLaunchIntentForPackage(pkg.packageName) != null;
                    if (!ehAppUsuario) continue;

                    int uid = appInfo.uid;
                    String nomeApp = pm.getApplicationLabel(appInfo).toString();

                    long mobileBytes = bytesMobilePorUid.containsKey(uid) ? bytesMobilePorUid.get(uid) : 0L;
                    long wifiBytes = bytesWifiPorUid.containsKey(uid) ? bytesWifiPorUid.get(uid) : 0L;

                    long trfAtual = 0;
                    long rxUid = TrafficStats.getUidRxBytes(uid);
                    long txUid = TrafficStats.getUidTxBytes(uid);
                    if (rxUid > 0 || txUid > 0) {
                        trfAtual = rxUid + txUid;
                        if (mobileBytes == 0 && wifiBytes == 0) {
                            mobileBytes = trfAtual;
                        }
                    }

                    long ultimoTrf = cacheTrafegoUid.containsKey(uid) ? cacheTrafegoUid.get(uid) : trfAtual;
                    boolean transmitindoAgora = (trfAtual > ultimoTrf);
                    cacheTrafegoUid.put(uid, trfAtual);

                    boolean solicitaGeo = false;
                    boolean geoPermitida = false;
                    if (pkg.requestedPermissions != null) {
                        for (String perm : pkg.requestedPermissions) {
                            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(perm) ||
                                    Manifest.permission.ACCESS_COARSE_LOCATION.equals(perm)) {
                                solicitaGeo = true;
                                break;
                            }
                        }
                    }
                    if (solicitaGeo) {
                        geoPermitida = (pm.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, pkg.packageName) == PackageManager.PERMISSION_GRANTED)
                                || (pm.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, pkg.packageName) == PackageManager.PERMISSION_GRANTED);
                    }

                    double totalMB = (mobileBytes + wifiBytes) / (1024.0 * 1024.0);
                    JSONObject obj = new JSONObject();
                    obj.put("nome", nomeApp);
                    obj.put("pacote", pkg.packageName);
                    obj.put("totalMB", Math.round(totalMB * 100.0) / 100.0);
                    obj.put("redeAtiva", transmitindoAgora ? "Transmitindo (" + redeAtual + ")" : "Conectado (" + redeAtual + ")");
                    obj.put("emTransmissao", transmitindoAgora);
                    obj.put("usaGeo", solicitaGeo);
                    obj.put("geoAtiva", geoPermitida);

                    tempObjs.add(obj);
                }

                Collections.sort(tempObjs, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject a, JSONObject b) {
                        return Double.compare(b.optDouble("totalMB", 0), a.optDouble("totalMB", 0));
                    }
                });

                int limite = Math.min(tempObjs.size(), 40);
                for (int i = 0; i < limite; i++) {
                    listaApps.put(tempObjs.get(i));
                }
            } catch (Exception ignored) {}
            return listaApps.toString();
        }

        @JavascriptInterface
        public double obterVelocidadeMbps() {
            long agora = System.currentTimeMillis();
            long rxAtual = TrafficStats.getTotalRxBytes();
            long txAtual = TrafficStats.getTotalTxBytes();

            if (ultimoTempoLeitura > 0 && (agora - ultimoTempoLeitura) > 500) {
                long deltaBytes = (rxAtual - ultimoRxBytesTotal) + (txAtual - ultimoTxBytesTotal);
                double segundos = (agora - ultimoTempoLeitura) / 1000.0;
                if (deltaBytes >= 0 && segundos > 0) {
                    double mbpsInstantaneo = (deltaBytes * 8.0) / (segundos * 1000000.0);
                    if (mbpsInstantaneo > 0.05) {
                        ultimaVelocidadeMbps = mbpsInstantaneo;
                    }
                }
            }
            ultimoRxBytesTotal = rxAtual;
            ultimoTxBytesTotal = txAtual;
            ultimoTempoLeitura = agora;
            return Math.round(ultimaVelocidadeMbps * 100.0) / 100.0;
        }

        @JavascriptInterface
        public int obterLatenciaMs() {
            return ultimaLatenciaMs;
        }

        @JavascriptInterface
        public void dispararMedicaoEmSegundoPlano() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        long t0 = System.currentTimeMillis();
                        HttpURLConnection conn = (HttpURLConnection) new URL("https://www.google.com/generate_204").openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setRequestMethod("HEAD");
                        conn.getResponseCode();
                        long t1 = System.currentTimeMillis();
                        ultimaLatenciaMs = (int) (t1 - t0);
                        conn.disconnect();
                    } catch (Exception e) {
                        ultimaLatenciaMs = 0;
                    }
                }
            }).start();
        }
    }
}
