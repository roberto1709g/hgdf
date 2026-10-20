package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Servicio de Accesibilidad LEADEN2 - Inyección por búsqueda directa de contenedor interactivo.
 */
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
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.packageNames = new String[] {"com.whatsapp", "com.whatsapp.w4b"};
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false) || isProcessing) {
            return;
        }

        isProcessing = true;
        handler.postDelayed(this::processCallSequence, 300);
    }

    private void processCallSequence() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            isProcessing = false;
            return;
        }

        // Buscar nodo por descripción de contenido en cualquier idioma
        AccessibilityNodeInfo callBtn = findCallButton(root);
        if (callBtn != null) {
            Rect bounds = new Rect();
            callBtn.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                dispatchTap(bounds.exactCenterX(), bounds.exactCenterY());
                
                // Dar toque en la primera opción del menú desplegado tras 250ms
                handler.postDelayed(() -> {
                    dispatchTap(bounds.exactCenterX(), bounds.exactCenterY() + 150);
                    disarm();
                }, 250);
                return;
            }
        }

        isProcessing = false;
    }

    private AccessibilityNodeInfo findCallButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if ((d.contains("llamada") || d.contains("llamar") || d.contains("call")) && !d.contains("video")) {
                return node;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findCallButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private void dispatchTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        dispatchGesture(builder.build(), null, null);
    }

    private void disarm() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("wa_auto_call_armed", false)
                .apply();
        isProcessing = false;
    }

    @Override
    public void onInterrupt() {}
}
