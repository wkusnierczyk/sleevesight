package io.github.wkusnierczyk.sleevesight.app.session

import android.service.notification.NotificationListenerService

/**
 * Declared only to obtain the session permission: while the user grants this listener
 * notification access, the app may read the players' media sessions. It ignores the
 * notifications themselves (architecture, *Session layer*).
 */
class SleevesightListenerService : NotificationListenerService()
