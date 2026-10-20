package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Servicio de Accesibilidad LEADEN2 - Ejecución por sondeo activo (polling).
 * Dispara la secuencia táctil de llamada por intervalos periódicos cuando WhatsApp está en primer plano.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isPolling = false;
    private int pollAttempts = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.packageNames = new String[] {"com.whatsapp", "com.whatsapp.w4b"};
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        Log.i(TAG, "Servicio de accesibilidad activado con sondeo activo.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();

        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) {
            return;
        }

        startPollingIfArmed();
    }

    private void startPollingIfArmed() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false) || isPolling) {
            return;
        }

        long armedAt = prefs.getLong("wa_auto_call_armed_at", 0L);
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_ARMED_MS) {
            disarm("Tiempo de espera agotado.");
            return;
        }

        isPolling = true;
        pollAttempts = 0;
        executePollingLoop();
    }

    private void executePollingLoop() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false)) {
            isPolling = false;
            return;
        }

        pollAttempts++;
        if (pollAttempts > 15) { // Limite de reintentos
            disarm("Se alcanzó el límite de intentos sin respuesta.");
            isPolling = false;
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        // Coordenadas fijas de la cabecera (botón de llamada)
        float headerX = screenWidth * 0.78f;
        float headerY = screenHeight * 0.065f;

        // Coordenadas fijas de la primera opción del menú ("Llamada")
        float menuOptionX = screenWidth * 0.72f;
        float menuOptionY = screenHeight * 0.12f;

        Log.i(TAG, "Intento " + pollAttempts + ": Inyectando gesto en la cabecera (" + headerX + ", " + headerY + ")");
        dispatchTapGesture(headerX, headerY);

        // Disparo secundario sobre la opción del menú tras 200ms
        handler.postDelayed(() -> {
            Log.i(TAG, "Inyectando gesto en opción 'Llamada' (" + menuOptionX + ", " + menuOptionY + ")");
            dispatchTapGesture(menuOptionX, menuOptionY);
        }, 200);

        // Programar siguiente ciclo de sondeo en 800ms
        handler.postDelayed(this::executePollingLoop, 800);
    }

    private void dispatchTapGesture(float x, float y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(clickPath, 0, 60);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        dispatchGesture(builder.build(), null, null);
    }

    private void disarm(String reason) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("wa_auto_call_armed", false)
                .putBoolean("wa_call_menu_opened", false)
                .apply();
        isPolling = false;
        Log.w(TAG, reason);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.");
    }
}
