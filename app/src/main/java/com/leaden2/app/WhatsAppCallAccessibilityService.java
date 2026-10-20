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
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Servicio de Accesibilidad LEADEN2 para automatización de llamadas en WhatsApp.
 * Cierra el teclado virtual si interfiere y realiza el toque táctil de llamada.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checkQueued = false;
    private int loadingPickerRetries = 0;
    private boolean keyboardDismissed = false;

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
            info.notificationTimeout = 200;
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
            keyboardDismissed = false;
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

        // 1. Si la opción de llamada del menú emergente ya está visible en pantalla
        AccessibilityNodeInfo callMenuOption = findVoiceCallMenuOptionAcrossWindows(root);
        if (callMenuOption != null) {
            triggerTapOnOption(callMenuOption);
            return;
        }

        // 2. Si WhatsApp se encuentra cargando/buscando el contacto
        if (isLoadingSendPicker(root)) {
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
        } else {
            loadingPickerRetries = 0;
        }

        // 3. Confirmar que estamos dentro del chat con el contacto objetivo
        if (!containsExpectedContact(root, expectedName, expectedPhone)) {
            scheduleInspection(300);
            return;
        }

        // 4. Si el cuadro de texto tiene foco (teclado desplegado), replegar el teclado
        if (!keyboardDismissed && isInputBoxFocused(root)) {
            keyboardDismissed = true;
            Log.i(TAG, "Teclado activo detectado. Ocultando teclado...");
            performGlobalAction(GLOBAL_ACTION_BACK);
            scheduleInspection(300);
            return;
        }

        // 5. Ubicar el control de llamada en la cabecera
        AccessibilityNodeInfo callNode = findHeaderCallControl(root);
        if (callNode != null) {
            boolean clicked = clickNodeOrParentContainer(callNode);
            if (clicked) {
                Log.i(TAG, "Icono de cabecera presionado. Esperando apertura del menú...");
                scheduleInspection(250);
            } else {
                Rect bounds = new Rect();
                callNode.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0) {
                    dispatchTapGesture(bounds.exactCenterX(), bounds.exactCenterY());
                    scheduleInspection(250);
                }
            }
        } else {
            // Coordenadas fijas de respaldo en el margen superior derecho (libre de teclado)
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            float callX = screenWidth * 0.78f;
            float callY = screenHeight * 0.065f;

            Log.i(TAG, "Ejecutando gesto de toque en coordenadas de la cabecera.");
            dispatchTapGesture(callX, callY);
            scheduleInspection(300);
        }
    }

    private boolean isInputBoxFocused(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isFocused() && node.isEditable()) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (isInputBoxFocused(node.getChild(i))) return true;
        }
        return false;
    }

    private void triggerTapOnOption(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (bounds.width() > 0 && bounds.height() > 0) {
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();

            disarm("Marcación de llamada iniciada exitosamente.");
            dispatchTapGesture(x, y);
        } else {
            disarm("No se obtuvieron las coordenadas de la opción.");
        }
    }

    private void dispatchTapGesture(float x, float y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(clickPath, 0, 70);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        boolean result = dispatchGesture(builder.build(), null, null);
        Log.i(TAG, "Gesto táctil enviado a (" + x + ", " + y + "): " + result);
    }

    private boolean clickNodeOrParentContainer(AccessibilityNodeInfo node) {
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
                && (idLower.contains("call") || idLower.contains("voice") || idLower.contains("phone")) && !video;
        boolean descriptionLooksLikeCall = !video && (
                desc.contains("llamada") || desc.contains("llamar") || desc.contains("voice call")
                || desc.contains("call") || desc.contains("start call"));

        if (node.isVisibleToUser() && node.isEnabled()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int)(screenHeight * 0.25f)
                    && bounds.bottom > bounds.top;
            if (inHeader && (idLooksLikeCall || descriptionLooksLikeCall)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findHeaderCallControlRecursive(node.getChild(i), screenHeight);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findVoiceCallMenuOptionAcrossWindows(AccessibilityNodeInfo activeRoot) {
        AccessibilityNodeInfo found = findVoiceCallMenuOption(activeRoot);
        if (found != null) return found;
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot == null) continue;
                CharSequence packageName = windowRoot.getPackageName();
                if (packageName != null && !"com.whatsapp".contentEquals(packageName)
                        && !"com.whatsapp.w4b".contentEquals(packageName)) continue;
                found = findVoiceCallMenuOption(windowRoot);
                if (found != null) return found;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error analizando ventanas emergentes.", e);
        }
        return null;
    }

    private AccessibilityNodeInfo findVoiceCallMenuOption(AccessibilityNodeInfo root) {
        if (root == null) return null;
        return findVoiceCallMenuOptionRecursive(root);
    }

    private boolean isExactVoiceCallLabel(String value) {
        if (value == null) return false;
        String val = normalize(value);
        return val.equals("llamada") || val.equals("llamada de voz")
                || val.equals("llamar") || val.equals("voice call")
                || val.equals("call") || val.equals("start voice call");
    }

    private AccessibilityNodeInfo findVoiceCallMenuOptionRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();

        boolean exactVoiceOption = isExactVoiceCallLabel(text) || isExactVoiceCallLabel(desc);
        String textNorm = normalize(text);
        String descNorm = normalize(desc);

        boolean forbidden = textNorm.contains("video") || textNorm.contains("videollamada")
                || textNorm.contains("enlace") || textNorm.contains("link")
                || descNorm.contains("video") || descNorm.contains("videollamada")
                || descNorm.contains("enlace") || descNorm.contains("link");

        if (node.isVisibleToUser() && node.isEnabled() && exactVoiceOption && !forbidden) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findVoiceCallMenuOptionRecursive(node.getChild(i));
            if (found != null) return found;
        }
        return null;
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
