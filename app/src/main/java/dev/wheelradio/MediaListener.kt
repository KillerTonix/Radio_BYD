package dev.wheelradio

import android.service.notification.NotificationListenerService

/**
 * Exists only so Android lets us see other apps' media sessions (e.g. the car's Bluetooth music).
 * The user grants it once in notification-access settings, or over ADB:
 *   adb shell cmd notification allow_listener dev.wheelradio/dev.wheelradio.MediaListener
 */
class MediaListener : NotificationListenerService()
