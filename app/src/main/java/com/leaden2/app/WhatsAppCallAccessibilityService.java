package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Automatización opcional y local de la interfaz de WhatsApp.
 * Solo actúa después de que LEADEN2 haya recibido un “sí” y armado el número validado.
 * Si no puede verificar el chat o identificar con claridad el control de llamada de voz,
 * no pulsa nada.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checkQueued = false;
    private boolean diagWindowShown = false;
    private boolean diagContactShown = false;
    private boolean diagButtonShown = false;
    private boolean diagMenuShown = false;
    private int loadingPickerRetries = 0;
    private View diagnosticOverlay;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            // Android espera nombres de paquete como arreglo; evitamos depender
            // de cómo se interprete la lista declarada en el XML.
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
        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) return;
        scheduleInspection(500);
    }

    /**
     * Reintenta de forma limitada mientras la orden siga armada. WhatsApp puede tardar
     * en terminar de cargar el chat y el árbol de accesibilidad no siempre está listo
     * en el primer evento de ventana.
     */
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
            diagWindowShown = false;
            diagContactShown = false;
            diagButtonShown = false;
            diagMenuShown = false;
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
        if (root != null && !diagWindowShown) {
            CharSequence rootPackage = root.getPackageName();
            showDiagnostic("Diagnóstico LEADEN2: ventana " + (rootPackage == null ? "sin paquete" : rootPackage));
            diagWindowShown = true;
        }
        if (expectedPhone.isEmpty()) {
            Log.w(TAG, "La orden está armada pero no hay número esperado; no se pulsará nada.");
            disarm("No hay número validado para la llamada.");
            return;
        }
        if (root == null) {
            Log.d(TAG, "WhatsApp aún no ofrece una ventana accesible; se reintentará.");
            scheduleInspection(900);
            return;
        }

        boolean menuOpened = prefs.getBoolean("wa_call_menu_opened", false);

        // A veces la ventana activa es solo el diálogo de progreso "Buscando...",
        // mientras el chat real permanece en otra ventana accesible de WhatsApp.
        // Preferimos la ventana que realmente contiene el contacto validado.
        if (!menuOpened && isLoadingSendPicker(root)) {
            AccessibilityNodeInfo contactRoot = findExpectedContactRootAcrossWindows(expectedName, expectedPhone);
            if (contactRoot != null) {
                root = contactRoot;
                loadingPickerRetries = 0;
                Log.i(TAG, "Se encontró el chat validado en otra ventana accesible de WhatsApp; se usará esa ventana en lugar del diálogo de carga.");
            } else if (loadingPickerRetries < 35) {
                loadingPickerRetries++;
                Log.i(TAG, "WhatsApp sigue mostrando Enviar a.../Buscando... y aún no hay una ventana accesible con el contacto; espera " + loadingPickerRetries + "/35.");
                scheduleInspection(700);
                return;
            } else {
                Log.w(TAG, "WhatsApp mantuvo la ventana de carga y no expuso una ventana de chat verificable.");
                prefs.edit().putBoolean("wa_auto_call_armed", false)
                        .putBoolean("wa_call_menu_opened", false).apply();
                showContactDiagnosticAcrossWindows(root, expectedName, expectedPhone);
                return;
            }
        } else if (!menuOpened && !isLoadingSendPicker(root)) {
            loadingPickerRetries = 0;
        }

        if (menuOpened) {
            // No mostrar un panel apenas se pulsa el icono: eso tapaba el chat antes
            // de que WhatsApp terminara de presentar su menú. Esperamos la opción real.
            AccessibilityNodeInfo optionNode = findVoiceCallMenuOptionAcrossWindows(root);
            if (optionNode == null) {
                if (loadingPickerRetries < 20) {
                    loadingPickerRetries++;
                    Log.i(TAG, "Menú solicitado; la opción exacta de llamada de voz aún no es accesible. Reintento " + loadingPickerRetries + "/20.");
                    scheduleInspection(650);
                    return;
                }
                prefs.edit().putBoolean("wa_auto_call_armed", false)
                        .putBoolean("wa_call_menu_opened", false).apply();
                diagMenuShown = true;
                Log.w(TAG, "No se encontró una opción accesible inequívoca de llamada de voz; no se pulsará ninguna opción.");
                showMenuDiagnosticAcrossWindows(root);
                return;
            }

            boolean clicked = clickSelfOrClickableParent(optionNode);
            // Desarmar siempre después de un intento para impedir llamadas repetidas.
            prefs.edit().putBoolean("wa_auto_call_armed", false)
                    .putBoolean("wa_call_menu_opened", false).apply();
            Log.i(TAG, clicked
                    ? "Se seleccionó la opción textual exacta de llamada de voz para el contacto previamente validado."
                    : "Se identificó la opción de llamada de voz, pero Android rechazó la acción.");
            showDiagnostic(clicked
                    ? "LEADEN2: se seleccionó «Llamada». Comprueba que WhatsApp inició la llamada."
                    : "LEADEN2: Android no aceptó la selección de «Llamada»; no se repetirá.");
            return;
        }

        if (!containsExpectedContact(root, expectedName, expectedPhone)) {
            if (!diagContactShown) {
                diagContactShown = true;
                // Esta compilación es exclusivamente diagnóstica: detener la automatización
                // para que cerrar el panel no provoque un clic accidental en WhatsApp.
                prefs.edit().putBoolean("wa_auto_call_armed", false)
                        .putBoolean("wa_call_menu_opened", false).apply();
                Log.w(TAG, "No se pudo verificar el contacto; se detiene la automatización y se muestra el árbol accesible.");
                showContactDiagnosticAcrossWindows(root, expectedName, expectedPhone);
            }
            return;
        }
        if (!diagContactShown) {
            showDiagnostic("Diagnóstico LEADEN2: contacto verificado.");
            diagContactShown = true;
        }

        AccessibilityNodeInfo callNode = findHeaderCallControl(root);
        if (callNode == null) {
            if (!diagButtonShown) {
                showDiagnostic("LEADEN2: contacto verificado; esperando el icono de llamadas de WhatsApp.");
                diagButtonShown = true;
            }
            Log.d(TAG, "El contacto coincide, pero aún no se identifica el icono de llamadas de la cabecera; se reintentará sin pulsar.");
            scheduleInspection(700);
            return;
        }

        // Primera etapa: abrir el menú de WhatsApp. La orden sigue armada para la segunda etapa.
        boolean clicked = clickSelfOrClickableParent(callNode);
        if (clicked) {
            loadingPickerRetries = 0;
            prefs.edit().putBoolean("wa_call_menu_opened", true).apply();
            showDiagnostic("LEADEN2: menú de llamadas solicitado; buscaré la opción «Llamada».");
            Log.i(TAG, "Se pulsó una vez el icono de llamadas de la cabecera; se buscará la opción de voz del menú.");
            scheduleInspection(700);
        } else {
            prefs.edit().putBoolean("wa_auto_call_armed", false)
                    .putBoolean("wa_call_menu_opened", false).apply();
            showDiagnostic("LEADEN2: Android rechazó abrir el menú de llamadas; no se repetirá.");
            Log.w(TAG, "Android rechazó el toque al icono de llamadas; operación desarmada para evitar repeticiones.");
        }
    }

    /** Busca el chat validado en cualquier ventana accesible de WhatsApp, no solo la activa. */
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
            Log.w(TAG, "No fue posible localizar el chat validado en ventanas secundarias.", e);
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

    /**
     * WhatsApp no mantiene IDs de recursos estables entre versiones. En vez de exigir
     * que el ID contenga "title" (lo que puede impedir siempre la llamada), verifica
     * una coincidencia exacta del nombre o número en la franja superior de la pantalla.
     * Esto evita aceptar un mensaje del historial como prueba del contacto.
     */
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
                Log.i(TAG, "Contacto verificado mediante coincidencia exacta en la cabecera visible.");
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

    /** Encuentra únicamente el control de llamadas de la cabecera, no una opción del menú. */
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

    /**
     * Busca la opción de voz en la ventana activa y en las ventanas accesibles de WhatsApp.
     * Esto contempla menús emergentes que no pertenecen al árbol de la cabecera del chat.
     */
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
            Log.w(TAG, "No fue posible inspeccionar todas las ventanas accesibles de WhatsApp.", e);
        }
        return null;
    }

    /** Busca texto visible exacto del menú; excluye videollamada y enlace de llamada. */
    private AccessibilityNodeInfo findVoiceCallMenuOption(AccessibilityNodeInfo root) {
        if (root == null) return null;
        return findVoiceCallMenuOptionRecursive(root);
    }

    private boolean isExactVoiceCallLabel(String value) {
        return value.equals("llamada") || value.equals("llamada de voz")
                || value.equals("llamar") || value.equals("voice call")
                || value.equals("call") || value.equals("start voice call");
    }

    private AccessibilityNodeInfo findVoiceCallMenuOptionRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : normalize(node.getText().toString());
        String desc = node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
        boolean exactVoiceOption = isExactVoiceCallLabel(text) || isExactVoiceCallLabel(desc);
        boolean forbidden = text.contains("video") || text.contains("videollamada")
                || text.contains("enlace") || text.contains("link")
                || desc.contains("video") || desc.contains("videollamada")
                || desc.contains("enlace") || desc.contains("link");
        if (node.isVisibleToUser() && node.isEnabled() && exactVoiceOption && !forbidden) {
            Log.i(TAG, "Opción de menú de llamada localizada mediante texto exacto: " + text);
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findVoiceCallMenuOptionRecursive(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickSelfOrClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return false;
    }

    private void showDiagnostic(String message) {
        Log.i(TAG, message);
        handler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    /**
     * Diagnóstico persistente de solo lectura cuando falla la verificación del contacto.
     * Registra el árbol completo expuesto por Android en la ventana activa y las demás
     * ventanas de WhatsApp. Esta compilación desarma la orden antes de mostrar el panel.
     */
    private void showContactDiagnosticAcrossWindows(AccessibilityNodeInfo activeRoot,
                                                     String expectedName, String expectedPhone) {
        StringBuilder report = new StringBuilder();
        report.append("LEADEN2 — DIAGNÓSTICO PERSISTENTE DE CONTACTO\n\n");
        report.append("Contacto esperado: ").append(expectedName).append("\n");
        report.append("Número esperado: ").append(expectedPhone).append("\n");
        report.append("Paquete del evento/ventana activa: ");
        CharSequence activePackage = activeRoot == null ? null : activeRoot.getPackageName();
        report.append(activePackage == null ? "sin raíz accesible" : activePackage).append("\n");
        report.append("Automatización detenida: no se pulsará ningún botón.\n\n");
        report.append("ÁRBOL DE LA VENTANA ACTIVA:\n");
        int[] count = new int[]{0};
        if (activeRoot != null) {
            appendMenuNodes(activeRoot, report, count, 0);
        } else {
            report.append("Android no entregó raíz accesible para la ventana activa.\n");
        }
        try {
            int windowIndex = 0;
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot == null) continue;
                CharSequence pkg = windowRoot.getPackageName();
                if (pkg != null && !"com.whatsapp".contentEquals(pkg)
                        && !"com.whatsapp.w4b".contentEquals(pkg)) continue;
                report.append("\nVENTANA WHATSAPP ").append(++windowIndex).append(": ")
                        .append(pkg == null ? "paquete desconocido" : pkg)
                        .append(" | tipo=").append(window.getType())
                        .append(" | capa=").append(window.getLayer())
                        .append(" | activa=").append(window.isActive())
                        .append(" | enfocada=").append(window.isFocused()).append("\n");
                appendMenuNodes(windowRoot, report, count, 0);
                if (count[0] >= 100) {
                    report.append("\nInforme limitado a 100 elementos para mantenerlo legible.\n");
                    break;
                }
            }
        } catch (Exception e) {
            report.append("No se pudieron inspeccionar todas las ventanas: ")
                    .append(e.getClass().getSimpleName()).append("\n");
            Log.w(TAG, "Error leyendo ventanas para diagnóstico del contacto.", e);
        }
        if (count[0] == 0) {
            report.append("Android no expuso nodos con texto, descripción, ID o controles pulsables.\n");
        }
        report.append("\nEsta versión solo diagnostica. Cierra el panel para volver a WhatsApp; no iniciará una llamada.");
        String content = report.toString();
        Log.i(TAG, content);
        handler.post(() -> showDiagnosticOverlay(content));
    }

    /**
     * Diagnóstico de solo lectura del menú de llamadas. Recopila los nodos de la
     * ventana activa y de las ventanas accesibles de WhatsApp, sin pulsar controles.
     */
    private void showMenuDiagnosticAcrossWindows(AccessibilityNodeInfo activeRoot) {
        StringBuilder report = new StringBuilder();
        report.append("LEADEN2 — DIAGNÓSTICO DEL MENÚ DE LLAMADAS\n\n");
        report.append("Se esperaba encontrar la opción exacta «Llamada» o «Llamada de voz».\n");
        report.append("No se ha pulsado ninguna opción.\n\nELEMENTOS ACCESIBLES DETECTADOS:\n");
        int[] count = new int[]{0};
        if (activeRoot != null) {
            CharSequence pkg = activeRoot.getPackageName();
            report.append("VENTANA ACTIVA: ").append(pkg == null ? "paquete desconocido" : pkg).append("\n");
            appendMenuNodes(activeRoot, report, count, 0);
        } else {
            report.append("VENTANA ACTIVA: sin raíz accesible\n");
        }
        try {
            int windowIndex = 0;
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot == null) continue;
                CharSequence pkg = windowRoot.getPackageName();
                if (pkg != null && !"com.whatsapp".contentEquals(pkg)
                        && !"com.whatsapp.w4b".contentEquals(pkg)) continue;
                report.append("\nVENTANA ACCESIBLE ").append(++windowIndex).append(": ")
                        .append(pkg == null ? "paquete desconocido" : pkg).append("\n");
                appendMenuNodes(windowRoot, report, count, 0);
                if (count[0] >= 100) break;
            }
        } catch (Exception e) {
            report.append("No se pudieron inspeccionar todas las ventanas: ")
                    .append(e.getClass().getSimpleName()).append("\n");
            Log.w(TAG, "Error leyendo ventanas para diagnóstico del menú.", e);
        }
        if (count[0] == 0) {
            report.append("Android no expuso nodos con texto, descripción, ID o controles pulsables.\n");
        }
        report.append("\nCierra este panel para volver al menú de WhatsApp. El diagnóstico no realiza llamadas.");
        String content = report.toString();
        Log.i(TAG, content);
        handler.post(() -> showDiagnosticOverlay(content));
    }

    private void appendMenuNodes(AccessibilityNodeInfo node, StringBuilder report, int[] count, int depth) {
        if (node == null || depth > 35 || count[0] >= 100) return;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        boolean clickable = node.isClickable();
        if (!text.isEmpty() || !desc.isEmpty() || !id.isEmpty() || clickable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            report.append("• ").append(node.getClassName() == null ? "vista" : node.getClassName())
                    .append(" [").append(bounds.left).append(',').append(bounds.top).append('-')
                    .append(bounds.right).append(',').append(bounds.bottom).append("]\n");
            if (!text.isEmpty()) report.append("  texto: ").append(shorten(text, 180)).append("\n");
            if (!desc.isEmpty()) report.append("  descripción: ").append(shorten(desc, 180)).append("\n");
            if (!id.isEmpty()) report.append("  ID: ").append(id).append("\n");
            report.append("  clicable=").append(clickable).append(", habilitado=")
                    .append(node.isEnabled()).append(", visible=").append(node.isVisibleToUser()).append("\n\n");
            count[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            appendMenuNodes(node.getChild(i), report, count, depth + 1);
            if (count[0] >= 100) return;
        }
    }

    /**
     * Diagnóstico visible persistente: evita depender de un Toast que desaparece.
     * Muestra los textos, descripciones, IDs y posiciones que Android expone en la
     * cabecera de WhatsApp. No realiza ninguna acción de llamada.
     */
    private void showHeaderDiagnostic(AccessibilityNodeInfo root, String expectedName, String expectedPhone) {
        StringBuilder report = new StringBuilder();
        report.append("LEADEN2 — DIAGNÓSTICO DE WHATSAPP\n\n");
        report.append("Contacto esperado: ").append(expectedName).append("\n");
        report.append("Número esperado: ").append(expectedPhone).append("\n");
        CharSequence pkg = root == null ? null : root.getPackageName();
        report.append("Paquete de ventana: ").append(pkg == null ? "desconocido" : pkg).append("\n\n");
        report.append("ELEMENTOS ACCESIBLES EN LA PARTE SUPERIOR:\n");
        int height = getResources().getDisplayMetrics().heightPixels;
        int[] count = new int[]{0};
        appendHeaderNodes(root, height, report, count, 0);
        if (count[0] == 0) {
            report.append("Android no expuso texto ni controles accesibles en el 40% superior de esta ventana.\n");
        }
        report.append("\nNo se ha pulsado ningún botón. Cierra este panel para volver a WhatsApp.");
        final String content = report.toString();
        Log.i(TAG, content);
        handler.post(() -> showDiagnosticOverlay(content));
    }

    private void appendHeaderNodes(AccessibilityNodeInfo node, int screenHeight,
                                   StringBuilder report, int[] count, int depth) {
        if (node == null || depth > 30 || count[0] >= 45) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean topArea = bounds.top >= 0 && bounds.top < (int)(screenHeight * 0.40f)
                && bounds.bottom > bounds.top;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        if (topArea && (!text.isEmpty() || !desc.isEmpty() || !id.isEmpty())) {
            report.append("• ").append(node.getClassName() == null ? "vista" : node.getClassName())
                    .append(" [").append(bounds.left).append(',').append(bounds.top).append('-')
                    .append(bounds.right).append(',').append(bounds.bottom).append("]\n");
            if (!text.isEmpty()) report.append("  texto: ").append(shorten(text, 180)).append("\n");
            if (!desc.isEmpty()) report.append("  descripción: ").append(shorten(desc, 180)).append("\n");
            if (!id.isEmpty()) report.append("  ID: ").append(id).append("\n");
            report.append("  clicable=").append(node.isClickable()).append(", habilitado=")
                    .append(node.isEnabled()).append("\n\n");
            count[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            appendHeaderNodes(node.getChild(i), screenHeight, report, count, depth + 1);
            if (count[0] >= 45) break;
        }
    }

    private String shorten(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private void showDiagnosticOverlay(String report) {
        if (diagnosticOverlay != null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(28, 24, 28, 20);
            panel.setBackgroundColor(Color.rgb(250, 250, 250));

            TextView title = new TextView(this);
            title.setText("Diagnóstico LEADEN2");
            title.setTextSize(20);
            title.setTextColor(Color.BLACK);
            title.setPadding(0, 0, 0, 14);
            panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

            ScrollView scroll = new ScrollView(this);
            TextView body = new TextView(this);
            body.setText(report);
            body.setTextSize(14);
            body.setTextColor(Color.rgb(25, 25, 25));
            body.setTextIsSelectable(true);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            panel.addView(scroll, scrollParams);

            Button close = new Button(this);
            close.setText("Cerrar y volver a WhatsApp");
            close.setOnClickListener(v -> {
                try { wm.removeView(panel); } catch (Exception ignored) { }
                diagnosticOverlay = null;
            });
            panel.addView(close, new LinearLayout.LayoutParams(-1, -2));

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (int)(getResources().getDisplayMetrics().heightPixels * 0.78f),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            wm.addView(panel, params);
            diagnosticOverlay = panel;
        } catch (Exception e) {
            Log.e(TAG, "No se pudo mostrar el panel de diagnóstico persistente.", e);
            Toast.makeText(getApplicationContext(), "No se pudo abrir el panel de diagnóstico; consulta los registros del sistema.", Toast.LENGTH_LONG).show();
        }
    }

    private void disarm(String reason) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wa_auto_call_armed", false).apply();
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
