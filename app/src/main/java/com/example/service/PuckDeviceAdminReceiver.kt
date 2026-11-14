package com.example.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.util.PuckPermissionManager

class PuckDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        PuckPermissionManager.onDeviceAdminEnabled(context)
        Toast.makeText(context, "Lock Screen permission granted", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        PuckPermissionManager.onDeviceAdminDisabled(context)
        Toast.makeText(context, "Lock Screen permission revoked", Toast.LENGTH_SHORT).show()
    }
}
