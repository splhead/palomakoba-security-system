package ho.palomakoba.securitysystem;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
            // init the service after a boot completed
            context.startService(new Intent(context, SensorsService.class));
        }
    }
}