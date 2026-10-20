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
 * Servicio de Accesibilidad LEADEN2 - Ejecución directa e incondicional por secuencia táctil.
 * Inyecta toques coordinados sobre la cabecera y el menú para bypass de bloqueos de interfaz.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isExecuting = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.packageNames = new String[] {"com.whatsapp", "com.whatsapp.w4b"};
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 150;
            setServiceInfo(info);
        }
        Log.i(TAG, "Servicio de accesibilidad activado en modo directo.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();

        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false) || isExecuting) {
            return;
        }

        long armedAt = prefs.getLong("wa_auto_call_armed_at", 0L);
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_ARMED_MS) {
            disarm("Tiempo de espera agotado.");
            return;
        }

        executeDirectCallSequence();
    }

    /**
     * Dispara la secuencia de toques incondicionales:
     * 1. Presiona el icono de llamada en la cabecera superior derecha.
     * 2. Presiona la primera opción ("Llamada") en el desplegable.
     */
    private void executeDirectCallSequence() {
        isExecuting = true;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        // Coordenadas calculadas para la cabecera (botón de llamada)
        float headerX = screenWidth * 0.78f;
        float headerY = screenHeight * 0.065f;

        // Coordenadas calculadas para el primer ítem del menú emergente ("Llamada")
        float menuOptionX = screenWidth * 0.72f;
        float menuOptionY = screenHeight * 0.12f;

        Log.i(TAG, "Paso 1: Ejecutando toque en cabecera (" + headerX + ", " + headerY + ")");
        dispatchTapGesture(headerX, headerY);

        // Espera de 250ms para permitir la animación de despliegue del menú
        handler.postDelayed(() -> {
            Log.i(TAG, "Paso 2: Ejecutando toque en menú ('Llamada') (" + menuOptionX + ", " + menuOptionY + ")");
            dispatchTapGesture(menuOptionX, menuOptionY);

            // Reintento de respaldo sobre el menú
            handler.postDelayed(() -> {
                dispatchTapGesture(menuOptionX, menuOptionY);
                disarm("Secuencia de llamada completada.");
                isExecuting = false;
            }, 200);
        }, 250);
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
        Log.w(TAG, reason);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.");
    }
}
