package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Automatización opcional y local de la interfaz de WhatsApp.
 * Realiza la llamada automáticamente cuando el comando está armado desde LEADEN2.
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
        Log.i(TAG, "Servicio de accesibilidad habilitado por el usuario.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        
        // FILTRO STRICTO: Ignorar cualquier paquete que no sea WhatsApp
        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) {
            return;
        }
        
        scheduleInspection(400);
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
            prefs.edit().putBoolean("wa_call_menu_opened", false).apply();
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
            Log.w(TAG, "La orden está armada pero no hay número esperado; desarmando.");
            disarm("No hay número validado para la llamada.");
            return;
        }

        if (root == null) {
            Log.d(TAG, "WhatsApp aún no ofrece una ventana accesible; reintentando.");
            scheduleInspection(600);
            return;
        }

        boolean menuOpened = prefs.getBoolean("wa_call_menu_opened", false);

        // Si la ventana muestra "Buscando...", esperar a que cargue el contacto
        if (!menuOpened && isLoadingSendPicker(root)) {
            AccessibilityNodeInfo contactRoot = findExpectedContactRootAcrossWindows(expectedName, expectedPhone);
            if (contactRoot != null) {
                root = contactRoot;
                loadingPickerRetries = 0;
            } else if (loadingPickerRetries < 35) {
                loadingPickerRetries++;
                Log.i(TAG, "WhatsApp sigue buscando el contacto (" + loadingPickerRetries + "/35). Esperando...");
                scheduleInspection(600);
                return;
            } else {
                disarm("Tiempo agotado esperando la búsqueda en WhatsApp.");
                return;
            }
        } else if (!menuOpened && !isLoadingSendPicker(root)) {
            loadingPickerRetries = 0;
        }

        // Si ya se abrió el menú desplegable de llamada en WhatsApp
        if (menuOpened) {
            AccessibilityNodeInfo optionNode = findVoiceCallMenuOptionAcrossWindows(root);
            if (optionNode == null) {
                if (loadingPickerRetries < 20) {
                    loadingPickerRetries++;
                    scheduleInspection(500);
                    return;
                }
                disarm("No se encontró la opción de llamada de voz en el menú.");
                return;
            }

            boolean clicked = clickSelfOrClickableParent(optionNode);
            prefs.edit().putBoolean("wa_auto_call_armed", false)
                    .putBoolean("wa_call_menu_opened", false).apply();

            if (clicked) {
                Log.i(TAG, "Se realizó clic en la opción 'Llamada' de WhatsApp.");
            } else {
                Log.w(TAG, "Android no aceptó el clic en la opción de llamada.");
            }
            return;
        }

        // Si el contacto aún no aparece en la cabecera, reintentar
        if (!containsExpectedContact(root, expectedName, expectedPhone)) {
            scheduleInspection(600);
            return;
        }

        // Buscar el icono de llamada en la cabecera del chat
        AccessibilityNodeInfo callNode = findHeaderCallControl(root);
        if (callNode == null) {
            scheduleInspection(600);
            return;
        }

        // Pulsar el botón/icono de llamada
        boolean clicked = clickSelfOrClickableParent(callNode);
        if (clicked) {
            loadingPickerRetries = 0;
            prefs.edit().putBoolean("wa_call_menu_opened", true).apply();
            Log.i(TAG, "Icono de llamada pulsado; buscando opción de confirmación si aplica.");
            scheduleInspection(600);
        } else {
            disarm("Error al pulsar el botón de llamada.");
        }
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
            Log.w(TAG, "No se pudo inspeccionar ventanas secundarias.", e);
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
            Log.w(TAG, "Error inspeccionando ventanas de WhatsApp.", e);
        }
        return null;
    }

    private AccessibilityNodeInfo findVoiceCallMenuOption(AccessibilityNodeInfo root) {
        if (root == null) return null;
        return findVoiceCallMenuOptionRecursive(root);
    }

    
    private boolean isExactVoiceCallLabel(String value) {
    if (value == null) return false;
    String val = value.trim().toLowerCase(Locale.ROOT);
    return val.equals("llamada") || val.equals("llamada de voz")
            || val.equals("llamar") || val.equals("voice call")
            || val.equals("call") || val.equals("start voice call");
}

private AccessibilityNodeInfo findVoiceCallMenuOptionRecursive(AccessibilityNodeInfo node) {
    if (node == null) return null;
    
    String text = node.getText() == null ? "" : node.getText().toString();
    String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
    
    boolean exactVoiceOption = isExactVoiceCallLabel(text) || isExactVoiceCallLabel(desc);
    
    String textLower = text.toLowerCase(Locale.ROOT);
    String descLower = desc.toLowerCase(Locale.ROOT);
    boolean forbidden = textLower.contains("video") || textLower.contains("videollamada")
            || textLower.contains("enlace") || textLower.contains("link")
            || descLower.contains("video") || descLower.contains("videollamada")
            || descLower.contains("enlace") || descLower.contains("link");

    if (node.isVisibleToUser() && node.isEnabled() && exactVoiceOption && !forbidden) {
        return node;
    }

    for (int i = 0; i < node.getChildCount(); i++) {
        AccessibilityNodeInfo found = findVoiceCallMenuOptionRecursive(node.getChild(i));
        if (found != null) return found;
    }
    return null;
}

private boolean clickSelfOrClickableParent(AccessibilityNodeInfo node) {
    if (node == null) return false;

    // 1. Intentar hacer clic en el nodo o subir hasta 6 niveles para encontrar un padre clickeable
    AccessibilityNodeInfo current = node;
    for (int depth = 0; current != null && depth < 6; depth++) {
        if (current.isVisibleToUser() && current.isEnabled()) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        current = current.getParent();
    }

    // 2. Si el clic de accesibilidad estándar falla en la ventana emergente, simular un toque directo (Touch Gesture)
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.width() > 0 && bounds.height() > 0) {
        float x = bounds.exactCenterX();
        float y = bounds.exactCenterY();
        
        android.graphics.Path clickPath = new android.graphics.Path();
        clickPath.moveTo(x, y);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(clickPath, 0, 100);
        android.accessibilityservice.GestureDescription.Builder builder =
                new android.accessibilityservice.GestureDescription.Builder();
        builder.addStroke(stroke);
        
        return dispatchGesture(builder.build(), null, null);
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
