package com.majnioui.tv.prayer;

/**
 * The pill and banner alarms, on a receiver no other app can call (not exported). PrayerAlerts itself stays
 * exported for boot, update and clock broadcasts and ignores an alert sent to it.
 */
public class AlertReceiver extends PrayerAlerts {
}
