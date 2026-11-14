package org.telegram.porcodio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Riceve l'esito dell'installazione (PackageInstaller): chiede la conferma di sistema quando serve e segnala gli errori. */
public class PcInstallReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.telegram.porcodio.INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Throwable t) {
                    PcUpdater.onInstallResult(PackageInstaller.STATUS_FAILURE, String.valueOf(t.getMessage()));
                }
            }
            return;
        }
        PcUpdater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
