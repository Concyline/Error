package br.com.error;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DefaultActivity extends AppCompatActivity {

    private String strCurrentErrorLog;
    private DateFormat dateFormat;

    /**
     * Arquivo de SharedPreferences utilizado pela lib
     */
    private static final String PREF_FILE = "uceh_preferences";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_default);

        dateFormat = new SimpleDateFormat("dd-MM-yyyy_HH:mm:ss", Locale.getDefault());

        findViewById(R.id.button_close_app).setOnClickListener(v -> {
            UnCaughtException.closeApplication(DefaultActivity.this);
        });

        findViewById(R.id.button_copy_error_log).setOnClickListener(v -> {
            copyErrorToClipboard();
        });

        findViewById(R.id.button_share_error_log).setOnClickListener(v -> {
            shareErrorLog();
        });

        findViewById(R.id.button_save_error_log).setOnClickListener(v -> {
            saveErrorLogToFile();
        });

        findViewById(R.id.button_share_email_log).setOnClickListener(v -> {
            shareEmailLog();
        });

        findViewById(R.id.button_view_error_log).setOnClickListener(v -> {

            LayoutInflater inflater = LayoutInflater.from(this);
            View view = inflater.inflate(R.layout.dialog_error_log, null);

            TextView txtErrorLog = view.findViewById(R.id.txtErrorLog);
            txtErrorLog.setText(getAllErrorDetailsFromIntent(this, getIntent()));

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Erros encontrados")
                    .setView(view)
                    .setPositiveButton("Copiar Log & Fechar", (dialog1, which) -> {
                        copyErrorToClipboard();
                        dialog1.dismiss();
                    })
                    .setNeutralButton("Fechar", (dialog2, which) -> {
                        dialog2.dismiss();
                    })
                    .create();

            dialog.show();
        });
    }

    private String getActivityLogFromIntent(Intent intent) {
        return intent.getStringExtra(UnCaughtException.EXTRA_ACTIVITY_LOG);
    }

    private String getStackTraceFromIntent(Intent intent) {
        return intent.getStringExtra(UnCaughtException.EXTRA_STACK_TRACE);
    }

    private void saveErrorLogToFile() {

        File baseDir = new File(getExternalFilesDir(null), "UnCaughtException");

        // 1️⃣ Criar diretório se não existir
        if (!baseDir.exists()) {
            boolean dirCreated = baseDir.mkdirs();

            if (!dirCreated) {
                showMessage("Falha ao criar diretório de logs.");
                return;
            }
        }

        Date currentDate = new Date();
        String fileName = "Error_" + dateFormat.format(currentDate) + ".txt";
        File logFile = new File(baseDir, fileName);

        String errorLog = getAllErrorDetailsFromIntent(this, getIntent());

        // 2️⃣ Escrever arquivo com try-with-resources
        try (FileOutputStream fos = new FileOutputStream(logFile)) {

            fos.write(errorLog.getBytes(StandardCharsets.UTF_8));
            fos.flush(); // força escrita

            showMessage("Arquivo salvo com sucesso em:\n\n" + logFile.getAbsolutePath());

        } catch (IOException e) {
            showMessage("Erro ao salvar arquivo:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showMessage(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Log de Erro")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void shareEmailLog() {

        String errorInformation = getAllErrorDetailsFromIntent(this, getIntent());


        SharedPreferences preferences = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);

        String json = preferences.getString("mails", "[]");

        String[] emails = new Gson().fromJson(json, String[].class);

        if (emails == null || emails.length == 0) {
            Toast.makeText(this, "Nenhum e-mail configurado.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:")); // Garante apenas apps de email
        intent.putExtra(Intent.EXTRA_EMAIL, emails);
        intent.putExtra(Intent.EXTRA_SUBJECT, "Application Crash Report");
        intent.putExtra(Intent.EXTRA_TEXT, errorInformation);

        try {
            startActivity(Intent.createChooser(intent, "Enviar relatório por e-mail"));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Nenhum aplicativo de e-mail encontrado.", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareErrorLog() {

        String errorLog = getAllErrorDetailsFromIntent(this, getIntent());

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Crash Report");
        shareIntent.putExtra(Intent.EXTRA_TEXT, errorLog);

        startActivity(Intent.createChooser(shareIntent, "Compartilhar relatório de erro"));
    }

    private void copyErrorToClipboard() {
        String errorInformation = getAllErrorDetailsFromIntent(DefaultActivity.this, getIntent());
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("View Error Log", errorInformation);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(DefaultActivity.this, "Error Log Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private String getAllErrorDetailsFromIntent(Context context, Intent intent) {

        if (!TextUtils.isEmpty(strCurrentErrorLog)) {
            return strCurrentErrorLog;
        }

        final String NL = "\n";
        final String SEP = "======================================================";

        StringBuilder errorReport = new StringBuilder();

        // DEVICE INFO
        errorReport.append(NL).append("DEVICE INFO").append(NL);
        errorReport.append(SEP).append(NL);
        errorReport.append("Locale : ").append(Locale.getDefault()).append(NL);
        errorReport.append("Brand : ").append(Build.BRAND).append(NL);
        errorReport.append("Board : ").append(Build.BOARD).append(NL);
        errorReport.append("Device : ").append(Build.DEVICE).append(NL);
        errorReport.append("Model : ").append(Build.MODEL).append(NL);
        errorReport.append("Manufacturer : ").append(Build.MANUFACTURER).append(NL);
        errorReport.append("Product : ").append(Build.PRODUCT).append(NL);
        errorReport.append("SDK : ").append(Build.VERSION.SDK_INT).append(NL);
        errorReport.append("Android Version : ").append(Build.VERSION.RELEASE).append(NL);
        errorReport.append("Host : ").append(Build.HOST).append(NL);
        errorReport.append("Build ID : ").append(Build.ID).append(NL);
        errorReport.append("Build Type : ").append(Build.TYPE).append(NL);

        try {
            StatFs stat = getStatFs();

            long totalBytes = stat.getTotalBytes();
            double totalMB = totalBytes / (1024.0 * 1024.0);

            errorReport.append("Total Memory : ").append(String.format(Locale.US, "%.2f MB", totalMB)).append(NL);

            long avaliableBytes = stat.getAvailableBytes();
            double avaliableMB = avaliableBytes / (1024.0 * 1024.0);

            errorReport.append("Available Memory : ").append(String.format(Locale.US, "%.2f MB", avaliableMB)).append(NL);

        } catch (Exception ignored) {
        }

        // APP INFO
        errorReport.append(NL).append("APP INFO").append(NL);
        errorReport.append(SEP).append(NL);

        try {
            PackageInfo pi = getPackageInfoCompat(context);

            errorReport.append("Version : ").append(pi.versionName).append(NL);
            errorReport.append("Package : ").append(pi.packageName).append(NL);

        } catch (Exception e) {
            Log.e("CustomExceptionHandler", "Error", e);
            errorReport.append("Version : Not available").append(NL);
        }

        Date currentDate = new Date();

        String firstInstallTime = getFirstInstallTimeAsString(context, dateFormat);
        if (!TextUtils.isEmpty(firstInstallTime)) {
            errorReport.append("Installed On : ").append(firstInstallTime).append(NL);
        }

        String lastUpdateTime = getLastUpdateTimeAsString(context, dateFormat);
        if (!TextUtils.isEmpty(lastUpdateTime)) {
            errorReport.append("Updated On : ").append(lastUpdateTime).append(NL);
        }

        errorReport.append("Current Date : ").append(dateFormat.format(currentDate)).append(NL);

        // ERROR LOG
        errorReport.append(NL).append("ERROR LOG").append(NL);
        errorReport.append(SEP).append(NL);
        errorReport.append(getStackTraceFromIntent(intent)).append(NL);

        String activityLog = getActivityLogFromIntent(intent);
        if (!TextUtils.isEmpty(activityLog)) {
            errorReport.append(NL).append("USER ACTIVITIES").append(NL);
            errorReport.append(SEP).append(NL);
            errorReport.append(activityLog).append(NL);
        }

        errorReport.append(NL).append(SEP).append(NL);
        errorReport.append("END OF LOG").append(NL);
        errorReport.append(SEP).append(NL);

        strCurrentErrorLog = errorReport.toString();
        return strCurrentErrorLog;
    }

    private PackageInfo getPackageInfoCompat(Context context) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getPackageInfo(
                    context.getPackageName(),
                    PackageManager.PackageInfoFlags.of(0)
            );
        } else {
            return pm.getPackageInfo(context.getPackageName(), 0);
        }
    }

    private StatFs getStatFs() {
        File path = Environment.getDataDirectory();
        return new StatFs(path.getPath());
    }

    private String getFirstInstallTimeAsString(Context context, DateFormat dateFormat) {
        long firstInstallTime;
        try {
            firstInstallTime = getPackageInfoCompat(context).firstInstallTime;
            return dateFormat.format(new Date(firstInstallTime));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private String getLastUpdateTimeAsString(Context context, DateFormat dateFormat) {
        long lastUpdateTime;
        try {
            lastUpdateTime = getPackageInfoCompat(context).lastUpdateTime;
            return dateFormat.format(new Date(lastUpdateTime));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}