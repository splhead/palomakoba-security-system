package ho.palomakoba.securitysystem;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, SensorsService.class);

        startForegroundService(intent);

        // setContentView(R.layout.activity_main);
        finish();
    }
}