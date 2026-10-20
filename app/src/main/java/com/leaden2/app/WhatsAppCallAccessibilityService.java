package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class WhatsAppCallAccessibilityService extends AccessibilityService {

    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isProcessing = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.packageNames = new String[] {"com.whatsapp", "com.whatsapp.w4b"};
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
        Log.i(TAG, "Servicio de accesibilidad activado.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();

        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) {
            return;
        }

        processWhatsAppCall();
    }

    private void processWhatsAppCall() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false) || isProcessing) {
            return;
        }

        String phone = prefs.getString("wa_auto_call_phone", "").replaceAll("\\D", "");
        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null || phone.isEmpty()) return;

        // 1. Si el menú desplegable está abierto o estamos en el chat del contacto
        boolean menuOpened = prefs.getBoolean("wa_call_menu_opened", false);

        if (menuOpened) {
            isProcessing = true;
            disarm();
            
            // Forzar inicio directo de llamada de WhatsApp por esquema URI
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("whatsapp://call?phone=" + phone));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.i(TAG, "Llamada iniciada por esquema URI nativo.");
            } catch (Exception e) {
                // Si falla el esquema URI, buscar el botón/opción por ID de la lista
                clickFirstMenuItem(root);
            }

            resetProcessingFlag();
            return;
        }

        // 2. Si estamos en la cabecera del chat, pulsar la opción de llamada
        List<AccessibilityNodeInfo> callButtons = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/action_voice_call");
        if (callButtons != null && !callButtons.isEmpty()) {
            AccessibilityNodeInfo btn = callButtons.get(0);
            if (btn.isEnabled()) {
                isProcessing = true;
                prefs.edit().putBoolean("wa_call_menu_opened", true).apply();
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "Icono de cabecera pulsado.");
                resetProcessingFlag();
            }
        }
    }

    private void clickFirstMenuItem(AccessibilityNodeInfo root) {
        // Buscar elementos por ID nativo de lista en menús emergentes de WhatsApp
        List<AccessibilityNodeInfo> items = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/title");
        if (items != null && !items.isEmpty()) {
            items.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "Opción 'Llamada' seleccionada mediante ID de vista.");
        }
    }

    private void disarm() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("wa_auto_call_armed", false)
                .putBoolean("wa_call_menu_opened", false)
                .apply();
    }

    private void resetProcessingFlag() {
        handler.postDelayed(() -> isProcessing = false, 3000);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.");
    }
}
