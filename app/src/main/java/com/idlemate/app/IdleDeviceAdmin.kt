package com.idlemate.app

import android.app.admin.DeviceAdminReceiver

/** 只申請「鎖定螢幕」這一項權限，讓點功能後可直接關閉螢幕。 */
class IdleDeviceAdmin : DeviceAdminReceiver()
