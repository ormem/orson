package app.ormem.orson

import android.app.admin.DeviceAdminReceiver

/** Lets Orson become device owner so it can lock the phone to itself. */
class AdminReceiver : DeviceAdminReceiver()
