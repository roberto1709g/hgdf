package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Servicio de Accesibilidad LEADEN2 para automatización de llamadas en WhatsApp.
 * Garantiza la marcación mediante Intent directo y toques de precisión sobre el menú.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checkQueued = false;
    private int loadingPickerRetries = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.packageNames = new String[] {"com.whatsapp", "com.whatsapp.w4b"};
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 250;
            setServiceInfo(info);
        }
        Log.i(TAG, "Servicio de accesibilidad activado.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();

        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) {
            return;
        }

        scheduleInspection(200);
    }

    private void scheduleInspection(long delayMs) {
        if (checkQueued) return;
        checkQueued = true;
        handler.postDelayed(() -> {
            checkQueued = false;
            inspectWhatsAppWindow();
        }, delayMs);
    }

    private void inspectWhatsAppWindow() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false)) {
            loadingPickerRetries = 0;
            return;
        }

        long armedAt = prefs.getLong("wa_auto_call_armed_at", 0L);
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_ARMED_MS) {
            disarm("Se agotó el tiempo de espera; no se inició la llamada.");
            return;
        }

        String expectedName = prefs.getString("wa_auto_call_name", "");
        String expectedPhone = digits(prefs.getString("wa_auto_call_phone", ""));
        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (expectedPhone.isEmpty()) {
            disarm("No hay número validado para la llamada.");
            return;
        }

        if (root == null) {
            scheduleInspection(300);
            return;
        }

        boolean menuOpened = prefs.getBoolean("wa_call_menu_opened", false);

        // 1. Si WhatsApp está en estado "Buscando..."
        if (!menuOpened && isLoadingSendPicker(root)) {
            AccessibilityNodeInfo contactRoot = findExpectedContactRootAcrossWindows(expectedName, expectedPhone);
            if (contactRoot != null) {
                root = contactRoot;
                loadingPickerRetries = 0;
            } else if (loadingPickerRetries < 35) {
                loadingPickerRetries++;
                scheduleInspection(300);
                return;
            } else {
                disarm("Tiempo agotado esperando la búsqueda en WhatsApp.");
                return;
            }
        } else if (!menuOpened && !isLoadingSendPicker(root)) {
            loadingPickerRetries = 0;
        }

        // 2. Si el menú desplegable ya está abierto en la pantalla
        if (menuOpened) {
            disarm("Ejecutando marcación de llamada.");
            executeCallAction(expectedPhone);
            return;
        }

        // 3. Verificar que el chat activo sea el del contacto correcto
        if (!containsExpectedContact(root, expectedName, expectedPhone)) {
            scheduleInspection(300);
            return;
        }

        // 4. Pulsar el icono de llamada en la cabecera del chat
        AccessibilityNodeInfo callNode = findHeaderCallControl(root);
        if (callNode == null) {
            scheduleInspection(300);
            return;
        }

        boolean clicked = clickNodeOrParent(callNode);
        if (clicked) {
            loadingPickerRetries = 0;
            prefs.edit().putBoolean("wa_call_menu_opened", true).apply();
            scheduleInspection(250); // Dar un breve margen a que aparezca el menú
        } else {
            disarm("Error al pulsar el botón de llamada.");
        }
    }

    /**
     * Intenta realizar la llamada enviando la acción directa a WhatsApp
     * y realizando un toque táctil de respaldo sobre la primera opción del menú desplegado.
     */
    private void executeCallAction(String phone) {
        try {
            // Intento 1: Lanzar la intención directa de llamada por WhatsApp
            Intent callIntent = new Intent(Intent.ACTION_VIEW);
            callIntent.setData(Uri.parse("https://api.whatsapp.com/send?phone=" + phone));
            callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo iniciar el intent directo, ejecutando toque por gesto táctil.", e);
        }

        // Intento 2: Gesto táctil sobre las coordenadas del menú emergente (opción "Llamada")
        handler.postDelayed(() -> {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            // Coordenadas calculadas para la primera opción del menú desplegable superior derecho
            float tapX = screenWidth * 0.72f;
            float tapY = screenHeight * 0.11f;

            Path clickPath = new Path();
            clickPath.moveTo(tapX, tapY);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(clickPath, 0, 80);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);

            boolean result = dispatchGesture(builder.build(), null, null);
            Log.i(TAG, "Toque táctil ejecutado en la opción 'Llamada' (" + tapX + ", " + tapY + "): " + result);
        }, 150);
    }

    private AccessibilityNodeInfo findExpectedContactRootAcrossWindows(String expectedName, String expectedPhone) {
        try {
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot == null) continue;
                CharSequence packageName = windowRoot.getPackageName();
                if (packageName != null && !"com.whatsapp".contentEquals(packageName)
                        && !"com.whatsapp.w4b".contentEquals(packageName)) continue;
                if (isLoadingSendPicker(windowRoot)) continue;
                if (containsContactInHeader(windowRoot, normalize(expectedName), expectedPhone, screenHeight)) {
                    return windowRoot;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error analizando ventanas.", e);
        }
        return null;
    }

    private boolean isLoadingSendPicker(AccessibilityNodeInfo root) {
        return hasAccessibleText(root, "enviar a") && hasAccessibleText(root, "buscando");
    }

    private boolean hasAccessibleText(AccessibilityNodeInfo node, String expected) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && normalize(text.toString()).contains(expected)) return true;
        if (desc != null && normalize(desc.toString()).contains(expected)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasAccessibleText(node.getChild(i), expected)) return true;
        }
        return false;
    }

    private boolean containsExpectedContact(AccessibilityNodeInfo root, String expectedName, String expectedPhone) {
        String name = normalize(expectedName);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return containsContactInHeader(root, name, expectedPhone, screenHeight);
    }

    private boolean containsContactInHeader(AccessibilityNodeInfo node, String expectedName,
                                            String expectedPhone, int screenHeight) {
        if (node == null) return false;
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int) (screenHeight * 0.30f)
                    && bounds.bottom > bounds.top;
            if (inHeader && matchesContactText(node.getText(), node.getContentDescription(),
                    expectedName, expectedPhone)) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsContactInHeader(node.getChild(i), expectedName, expectedPhone, screenHeight)) return true;
        }
        return false;
    }

    private boolean matchesContactText(CharSequence text, CharSequence desc, String expectedName, String expectedPhone) {
        return matchesOne(text, expectedName, expectedPhone) || matchesOne(desc, expectedName, expectedPhone);
    }

    private boolean matchesOne(CharSequence value, String expectedName, String expectedPhone) {
        if (value == null) return false;
        String normalized = normalize(value.toString());
        if (!expectedName.isEmpty() && normalized.equals(expectedName)) return true;
        String valueDigits = digits(value.toString());
        return !expectedPhone.isEmpty() && !valueDigits.isEmpty() && valueDigits.equals(expectedPhone);
    }

    private AccessibilityNodeInfo findHeaderCallControl(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return findHeaderCallControlRecursive(root, screenHeight);
    }

    private AccessibilityNodeInfo findHeaderCallControlRecursive(AccessibilityNodeInfo node, int screenHeight) {
        if (node == null) return null;
        String viewId = node.getViewIdResourceName();
        String desc = node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
        String idLower = viewId == null ? "" : viewId.toLowerCase(Locale.ROOT);
        boolean video = desc.contains("video") || desc.contains("videollamada")
                || idLower.contains("video_call") || idLower.contains("video-call");
        boolean idLooksLikeCall = !idLower.isEmpty()
                && (idLower.contains("call") || idLower.contains("voice")) && !video;
        boolean descriptionLooksLikeCall = !video && (
                desc.equals("llamada de voz") || desc.equals("llamar") || desc.equals("llamada")
                || desc.equals("llamada de voz por whatsapp") || desc.equals("voice call")
                || desc.equals("call") || desc.equals("make a call") || desc.equals("start voice call"));
        if (node.isVisibleToUser() && node.isEnabled()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int)(screenHeight * 0.35f)
                    && bounds.bottom > bounds.top;
            if (inHeader && (idLooksLikeCall || descriptionLooksLikeCall)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findHeaderCallControlRecursive(node.getChild(i), screenHeight);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null) return false;

        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isVisibleToUser() && current.isEnabled()) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            current = current.getParent();
        }
        return false;
    }

    private void disarm(String reason) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("wa_auto_call_armed", false)
                .putBoolean("wa_call_menu_opened", false)
                .apply();
        Log.w(TAG, reason);
    }

    private String normalize(String input) {
        if (input == null) return "";
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^a-z0-9+ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String digits(String input) {
        return input == null ? "" : input.replaceAll("\\D", "");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.");
    }
}
