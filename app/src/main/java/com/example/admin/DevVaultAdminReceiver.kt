package com.example.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DevVaultAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "DevVault Device Admin & Uninstall Protection Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Immediate device lock to prevent unauthorized or bypassed deactivation
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.lockNow()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        return "SECURITY ALERT: DevVault Uninstall Protection is active. To safely deactivate or uninstall, please open DevVault and authenticate via Settings."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "DevVault Uninstall Protection Deactivated", Toast.LENGTH_SHORT).show()
    }
}
