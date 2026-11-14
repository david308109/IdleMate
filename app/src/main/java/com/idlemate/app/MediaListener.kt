package com.idlemate.app

import android.service.notification.NotificationListenerService

/** 空殼服務：使用者授予「通知存取權」後，本 App 才能直接控制其他 App 的播放器。 */
class MediaListener : NotificationListenerService()
