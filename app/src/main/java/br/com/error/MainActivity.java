package br.com.error;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        new UnCaughtException.Builder(this)
                .setMailSuport("siacsuporteandroid@gmail.com")
                .setTrackActivitiesEnabled(true)
                .setBackgroundModeEnabled(true)
                .build();

        double a = 10 / 0;

    }
}