package com.dmarc.cordovacall;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.pushwoosh.internal.utils.PWLog;
import com.pushwoosh.notification.NotificationServiceExtension;
import com.pushwoosh.notification.PushMessage;

public class PushwooshNotificationServiceExtension extends NotificationServiceExtension {
	private boolean showForegroundPush;

	public PushwooshNotificationServiceExtension() {
		try {
			String packageName = getApplicationContext().getPackageName();
			ApplicationInfo ai = getApplicationContext().getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);

			if (ai.metaData != null) {
				showForegroundPush = ai.metaData.getBoolean("PW_BROADCAST_PUSH", false) || ai.metaData.getBoolean("com.pushwoosh.foreground_push", false);
			}
		} catch (Exception e) {
			PWLog.error(CordovaCall.TAG, "Failed to read AndroidManifest metaData", e);
		}

		PWLog.debug(CordovaCall.TAG, "showForegroundPush = " + showForegroundPush);
	}

	@Override
	protected boolean onMessageReceived(final PushMessage pushMessage) {
		String message = pushMessage.toJson().toString();
		boolean result = true;
		CordovaCall.messageReceived(message);
		if(CordovaCall.isKilled()){
			result = super.onMessageReceived(pushMessage);
		}
		return (!showForegroundPush && isAppOnForeground()) || result;
	}

	@Override
	protected void onMessageOpened(PushMessage pushMessage) {
		CordovaCall.openPush(pushMessage.toJson().toString());
	}
}
