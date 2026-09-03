package com.example.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DevVaultAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "DevVault Device Admin Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Warning shown when user tries to disable device admin
        return "Disabling Device Admin will allow DevVault to be uninstalled, and your encrypted data might be lost if you haven't backed it up."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "DevVault Device Admin Disabled", Toast.LENGTH_SHORT).show()
    }
}
