package br.com.error;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * ============================================================
 * UnCaughtException
 * ============================================================
 * <p>
 * Handler global para capturar exceções não tratadas (crashes).
 * <p>
 * Responsabilidades principais:
 * <p>
 * 1) Interceptar crashes globais da aplicação.
 * 2) Evitar crash loop (loop infinito de crash).
 * 3) Opcionalmente abrir uma Activity de erro personalizada.
 * 4) Registrar log das últimas Activities (debug).
 * 5) Persistir informações necessárias antes de finalizar o processo.
 * <p>
 * IMPORTANTE:
 * - Esta classe substitui o UncaughtExceptionHandler padrão da JVM.
 * - Após tratar o erro, o processo é encerrado manualmente.
 * - Não deve conter operações pesadas.
 * <p>
 * Uso recomendado:
 * <p>
 * new UnCaughtException.Builder(context)
 * .setMailSuport("email@empresa.com")
 * .setTrackActivitiesEnabled(true)
 * .setBackgroundModeEnabled(true)
 * .build();
 * <p>
 * ============================================================
 */
public final class UnCaughtException {

    // ===============================
    // CONSTANTES PÚBLICAS
    // ===============================

    /**
     * Intent extra contendo a stacktrace do crash
     */
    static final String EXTRA_STACK_TRACE = "EXTRA_STACK_TRACE";

    /**
     * Intent extra contendo o histórico de Activities
     */
    static final String EXTRA_ACTIVITY_LOG = "EXTRA_ACTIVITY_LOG";

    // ===============================
    // CONSTANTES INTERNAS
    // ===============================

    private static final String TAG = "UnCaught";

    /**
     * Arquivo de SharedPreferences utilizado pela lib
     */
    private static final String PREF_FILE = "uceh_preferences";

    /**
     * Chave que armazena o timestamp do último crash
     */
    private static final String KEY_LAST_CRASH = "last_crash_timestamp";

    /**
     * Limite máximo de caracteres da stacktrace
     */
    private static final int MAX_STACK_TRACE_SIZE = 131071;

    /**
     * Quantidade máxima de Activities armazenadas no log
     */
    private static final int MAX_ACTIVITIES_IN_LOG = 50;

    // ===============================
    // ESTADO GLOBAL
    // ===============================

    /**
     * Fila circular com histórico de lifecycle das Activities.
     * Usado apenas se o tracking estiver habilitado.
     */
    private static final Deque<String> activityLog = new ArrayDeque<>(MAX_ACTIVITIES_IN_LOG);

    /**
     * Referência fraca para última Activity criada.
     * Evita memory leak.
     */
    private static WeakReference<Activity> lastActivityCreated =
            new WeakReference<>(null);

    /**
     * Application global.
     * <p>
     * ⚠️ StaticFieldLeak suprimido pois usamos apenas Application,
     * que tem ciclo de vida igual ao processo.
     */
    @SuppressLint("StaticFieldLeak")
    private static Application application;

    /**
     * Indica se app está em background
     */
    private static boolean isInBackground = true;

    /**
     * Permite exibir tela de erro mesmo em background
     */
    private static boolean isBackgroundMode;

    /**
     * Liga/desliga o handler
     */
    private static boolean isUCEHEnabled;

    /**
     * Liga/desliga tracking de Activities
     */
    private static boolean isTrackActivitiesEnabled;

    /**
     * Lista de e-mails configurados
     */
    static String COMMA_SEPARATED_EMAIL_ADDRESSES;

    // ============================================================
    // CONSTRUTOR PRIVADO (PADRÃO BUILDER)
    // ============================================================

    private UnCaughtException(Builder builder) {

        application = builder.application;
        isUCEHEnabled = builder.isUCEHEnabled;
        isTrackActivitiesEnabled = builder.isTrackActivitiesEnabled;
        isBackgroundMode = builder.isBackgroundModeEnabled;
        COMMA_SEPARATED_EMAIL_ADDRESSES = builder.commaSeparatedEmailAddresses;

        installHandler();
        registerLifecycleCallbacks();
    }

    // ============================================================
    // INSTALAÇÃO DO HANDLER GLOBAL
    // ============================================================

    /**
     * Substitui o UncaughtExceptionHandler padrão.
     * <p>
     * Fluxo:
     * 1) Verifica se está habilitado
     * 2) Detecta crash loop
     * 3) Salva timestamp
     * 4) Abre DefaultActivity
     * 5) Finaliza processo
     */
    private static void installHandler() {

        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {

            if (!isUCEHEnabled) {
                if (oldHandler != null)
                    oldHandler.uncaughtException(thread, throwable);
                return;
            }

            Log.e(TAG, "App crashed", throwable);

            // Proteção contra crash loop
            if (hasCrashedRecently()) {
                Log.e(TAG, "Crash loop detected. Delegating to system.");
                if (oldHandler != null)
                    oldHandler.uncaughtException(thread, throwable);
                return;
            }

            saveCrashTimestamp();

            // Exibe tela de erro se permitido
            if (!isInBackground || isBackgroundMode) {

                Intent intent = new Intent(application, DefaultActivity.class);
                intent.putExtra(EXTRA_STACK_TRACE, getStackTraceString(throwable));

                if (isTrackActivitiesEnabled) {
                    intent.putExtra(EXTRA_ACTIVITY_LOG, buildActivityLog());
                }

                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                application.startActivity(intent);
            }

            // Fecha última activity se existir
            Activity lastActivity = lastActivityCreated.get();
            if (lastActivity != null) {
                lastActivity.finish();
                lastActivityCreated.clear();
            }

            killCurrentProcess();
        });

        Log.i(TAG, "UnCaughtException installed.");
    }

    // ============================================================
    // LIFECYCLE TRACKING
    // ============================================================

    /**
     * Registra callback global para monitorar lifecycle.
     * <p>
     * Permite:
     * - Detectar background/foreground
     * - Montar histórico de navegação
     */
    private static void registerLifecycleCallbacks() {

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {

                    final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                    int startedActivities = 0;

                    @Override
                    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {

                        if (activity.getClass() != DefaultActivity.class) {
                            lastActivityCreated = new WeakReference<>(activity);
                        }

                        if (isTrackActivitiesEnabled) {
                            addLog(dateFormat, activity, "created");
                        }
                    }

                    @Override
                    public void onActivityStarted(@NonNull Activity activity) {
                        startedActivities++;
                        isInBackground = startedActivities <= 0;
                    }

                    @Override
                    public void onActivityResumed(@NonNull Activity activity) {
                        if (isTrackActivitiesEnabled) {
                            addLog(dateFormat, activity, "resumed");
                        }
                    }

                    @Override
                    public void onActivityPaused(@NonNull Activity activity) {
                        if (isTrackActivitiesEnabled) {
                            addLog(dateFormat, activity, "paused");
                        }
                    }

                    @Override
                    public void onActivityStopped(@NonNull Activity activity) {
                        startedActivities--;
                        isInBackground = startedActivities <= 0;
                    }

                    @Override
                    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(@NonNull Activity activity) {
                    }
                });
    }

    // ============================================================
    // MÉTODOS AUXILIARES
    // ============================================================

    /**
     * Converte Throwable em String com limite de tamanho
     */
    private static String getStackTraceString(Throwable throwable) {

        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));

        String stack = sw.toString();

        if (stack.length() > MAX_STACK_TRACE_SIZE) {
            stack = stack.substring(0, MAX_STACK_TRACE_SIZE) + " [truncated]";
        }

        return stack;
    }

    /**
     * Detecta crash repetido em curto intervalo (3s)
     */
    private static boolean hasCrashedRecently() {

        SharedPreferences prefs = application.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);

        long last = prefs.getLong(KEY_LAST_CRASH, -1);
        long now = System.currentTimeMillis();

        return last != -1 && (now - last) < 3000;
    }

    /**
     * Usa commit() propositalmente.
     * <p>
     * apply() é assíncrono e pode não salvar antes do kill.
     */
    private static void saveCrashTimestamp() {
        application.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CRASH, System.currentTimeMillis())
                .commit();
    }

    /**
     * Finaliza processo manualmente
     */
    private static void killCurrentProcess() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    static void closeApplication(Activity activity) {
        activity.finish();
        killCurrentProcess();
    }

    /**
     * Monta string final do log de Activities
     */
    private static String buildActivityLog() {

        StringBuilder builder = new StringBuilder();

        for (String entry : activityLog) {
            builder.append(entry);
        }

        return builder.toString();
    }

    private static void addLog(DateFormat format, Activity activity, String event) {

        if (activityLog.size() >= MAX_ACTIVITIES_IN_LOG) {
            activityLog.poll();
        }

        activityLog.add(format.format(new Date()) + ": " + activity.getClass().getSimpleName() + " " + event + "\n");
    }

    // ============================================================
    // BUILDER
    // ============================================================

    /**
     * Builder responsável por configurar o handler.
     * <p>
     * Motivo do uso:
     * - Permite futuras extensões sem quebrar API.
     * - Mantém construção organizada.
     */
    public static class Builder {

        private final Application application;

        private boolean isUCEHEnabled = true;
        private boolean isTrackActivitiesEnabled = false;
        private boolean isBackgroundModeEnabled = true;

        private String commaSeparatedEmailAddresses;
        private String[] mails = {};

        /**
         * Recebe qualquer Context.
         * Internamente converte para Application.
         */
        public Builder(Context context) {
            this.application =
                    (Application) context.getApplicationContext();
        }

        public Builder setUCEHEnabled(boolean enabled) {
            this.isUCEHEnabled = enabled;
            return this;
        }

        public Builder setTrackActivitiesEnabled(boolean enabled) {
            this.isTrackActivitiesEnabled = enabled;
            return this;
        }

        public Builder setBackgroundModeEnabled(boolean enabled) {
            this.isBackgroundModeEnabled = enabled;
            return this;
        }

        public Builder addCommaSeparatedEmailAddresses(String emails) {
            this.commaSeparatedEmailAddresses =
                    emails != null ? emails : "";
            return this;
        }

        public Builder setMailSuport(String... mails) {
            this.mails = mails;
            return this;
        }

        /**
         * Finaliza configuração e instala handler.
         * <p>
         * Salva e-mails no SharedPreferences
         * para uso posterior pela DefaultActivity.
         */
        public UnCaughtException build() {

            Gson gson = new Gson();
            String value = gson.toJson(mails);

            SharedPreferences prefs = application.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);

            prefs.edit().putString("mails", value).apply();

            return new UnCaughtException(this);
        }
    }
}


/*


*/
