package com.dmarc.cordovacall;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.Manifest;
import android.telecom.Connection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import android.graphics.drawable.Icon;
import android.media.AudioManager;

import com.pushwoosh.Pushwoosh;
import com.pushwoosh.internal.utils.PWLog;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import android.provider.Settings;
import android.webkit.JavascriptInterface;

import com.pushwoosh.GDPRManager;
import com.pushwoosh.badge.PushwooshBadge;
import com.pushwoosh.exception.GetTagsException;
import com.pushwoosh.exception.PushwooshException;
import com.pushwoosh.exception.RegisterForPushNotificationsException;
import com.pushwoosh.exception.UnregisterForPushNotificationException;
import com.pushwoosh.function.Callback;
import com.pushwoosh.function.Result;
import com.pushwoosh.inapp.PushwooshInApp;
import com.pushwoosh.inbox.ui.presentation.view.activity.InboxActivity;
import com.pushwoosh.internal.platform.utils.GeneralUtils;
import com.pushwoosh.notification.LocalNotification;
import com.pushwoosh.notification.LocalNotificationReceiver;
import com.pushwoosh.notification.PushMessage;
import com.pushwoosh.notification.PushwooshNotificationSettings;
import com.pushwoosh.notification.SoundType;
import com.pushwoosh.notification.VibrateType;
import com.pushwoosh.tags.Tags;
import com.pushwoosh.tags.TagsBundle;


import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION;

public class CordovaCall extends CordovaPlugin {

    public static String TAG = "CordovaCall";
    public static final int CALL_PHONE_REQ_CODE = 0;
    public static final int REAL_PHONE_CALL = 1;
    private int permissionCounter = 0;
    private String pendingAction;
    private TelecomManager tm;
    private PhoneAccountHandle handle;
    private PhoneAccount phoneAccount;
    private CallbackContext callbackContext;
    private String appName;
    private String from;
    private String to;
    private String realCallTo;
    private static HashMap<String, ArrayList<CallbackContext>> callbackContextMap = new HashMap<String, ArrayList<CallbackContext>>();
    private static CordovaInterface cordovaInterface;
    private static CordovaWebView cordovaWebView;
    private static Icon icon;
    private static CordovaCall instance;

	private static final Object sStartPushLock = new Object();
	private static String sStartPushData;
	private static String sReceivedPushData;
	private static AtomicBoolean sAppReady = new AtomicBoolean();
	private static CordovaCall sInstance;

    //there are 2
	private final HashMap<String, CallbackContext> callbackIds = new HashMap<String, CallbackContext>();

    public static HashMap<String, ArrayList<CallbackContext>> getCallbackContexts() {
        return callbackContextMap;
    }

    public static CordovaInterface getCordova() {
        return cordovaInterface;
    }

    public static CordovaWebView getWebView() { 
        return cordovaWebView; 
    }

    public static Icon getIcon() {
        return icon;
    }

    public static CordovaCall getInstance() {
        return instance;
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        cordovaInterface = cordova;
        cordovaWebView = webView;
        super.initialize(cordova, webView);
        appName = getApplicationName(this.cordova.getActivity().getApplicationContext());
        handle = new PhoneAccountHandle(new ComponentName(this.cordova.getActivity().getApplicationContext(),MyConnectionService.class),appName);
        tm = (TelecomManager)this.cordova.getActivity().getApplicationContext().getSystemService(this.cordova.getActivity().getApplicationContext().TELECOM_SERVICE);
        if(android.os.Build.VERSION.SDK_INT >= 26) {
          phoneAccount = new PhoneAccount.Builder(handle, appName)
                  .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                  .build();
          tm.registerPhoneAccount(phoneAccount);
        }
        if(android.os.Build.VERSION.SDK_INT >= 23) {
          phoneAccount = new PhoneAccount.Builder(handle, appName)
                   .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                   .build();
          tm.registerPhoneAccount(phoneAccount);          
        }
        callbackContextMap.put("answer",new ArrayList<CallbackContext>());
        callbackContextMap.put("reject",new ArrayList<CallbackContext>());
        callbackContextMap.put("hangup",new ArrayList<CallbackContext>());
        callbackContextMap.put("sendCall",new ArrayList<CallbackContext>());
        callbackContextMap.put("receiveCall",new ArrayList<CallbackContext>());

        instance = this;
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        this.checkCallPermission();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        PWLog.debug(TAG, "Plugin Method Called: " + action);

        if (action.equals("receiveCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn != null) {
                if(conn.getState() == Connection.STATE_ACTIVE) {
                    this.callbackContext.error("You can't receive a call right now because you're already in a call");
                } else {
                    this.callbackContext.error("You can't receive a call right now");
                }
            } else {
                from = args.getString(0);
                permissionCounter = 2;
                pendingAction = "receiveCall";
                this.checkCallPermission();
            }
            return true;
        } else if (action.equals("sendCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn != null) {
                if(conn.getState() == Connection.STATE_ACTIVE) {
                    this.callbackContext.error("You can't make a call right now because you're already in a call");
                } else if(conn.getState() == Connection.STATE_DIALING) {
                    this.callbackContext.error("You can't make a call right now because you're already trying to make a call");
                } else {
                    this.callbackContext.error("You can't make a call right now");
                }
            } else {
                to = args.getString(0);
                permissionCounter = 2;
                pendingAction = "sendCall";
                this.checkCallPermission();
                /*cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        getCallPhonePermission();
                    }
                });*/
            }
            return true;
        } else if (action.equals("connectCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn == null) {
                this.callbackContext.error("No call exists for you to connect");
            } else if(conn.getState() == Connection.STATE_ACTIVE) {
                this.callbackContext.error("Your call is already connected");
            } else {
                conn.setActive();
                Intent intent = new Intent(this.cordova.getActivity().getApplicationContext(), this.cordova.getActivity().getClass());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                this.cordova.getActivity().getApplicationContext().startActivity(intent);
                this.callbackContext.success("Call connected successfully");
            }
            return true;
        } else if (action.equals("endCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn == null) {
                this.callbackContext.error("No call exists for you to end");
            } else {
                DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
                conn.setDisconnected(cause);
                conn.destroy();
                MyConnectionService.deinitConnection();
                ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
                for (final CallbackContext cbContext : callbackContexts) {
                    cordova.getThreadPool().execute(new Runnable() {
                        public void run() {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, "hangup event called successfully");
                            result.setKeepCallback(true);
                            cbContext.sendPluginResult(result);
                        }
                    });
                }
                this.callbackContext.success("Call ended successfully");
            }
            return true;
        } else if (action.equals("registerEvent")) {
            String eventType = args.getString(0);
            ArrayList<CallbackContext> callbackContextList = callbackContextMap.get(eventType);
            callbackContextList.add(this.callbackContext);
            return true;
        } else if (action.equals("setAppName")) {
            String appName = args.getString(0);
            handle = new PhoneAccountHandle(new ComponentName(this.cordova.getActivity().getApplicationContext(),MyConnectionService.class),appName);
            if(android.os.Build.VERSION.SDK_INT >= 26) {
              phoneAccount = new PhoneAccount.Builder(handle, appName)
                  .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                  .build();
              tm.registerPhoneAccount(phoneAccount);
            }
            if(android.os.Build.VERSION.SDK_INT >= 23) {
              phoneAccount = new PhoneAccount.Builder(handle, appName)
                   .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                   .build();
              tm.registerPhoneAccount(phoneAccount);
            }
            this.callbackContext.success("App Name Changed Successfully");
            return true;
        } else if (action.equals("setIcon")) {
            String iconName = args.getString(0);
            int iconId = this.cordova.getActivity().getApplicationContext().getResources().getIdentifier(iconName, "drawable", this.cordova.getActivity().getPackageName());
            if(iconId != 0) {
                icon = Icon.createWithResource(this.cordova.getActivity(), iconId);
                this.callbackContext.success("Icon Changed Successfully");
            } else {
                this.callbackContext.error("This icon does not exist. Make sure to add it to the res/drawable folder the right way.");
            }
            return true;
        } else if (action.equals("mute")) {
            this.mute();
            this.callbackContext.success("Muted Successfully");
            return true;
        } else if (action.equals("unmute")) {
            this.unmute();
            this.callbackContext.success("Unmuted Successfully");
            return true;
        } else if (action.equals("speakerOn")) {
            this.speakerOn();
            this.callbackContext.success("Speakerphone is on");
            return true;
        } else if (action.equals("speakerOff")) {
            this.speakerOff();
            this.callbackContext.success("Speakerphone is off");
            return true;
        } else if (action.equals("callNumber")) {
            realCallTo = args.getString(0);
            if(realCallTo != null) {
              cordova.getThreadPool().execute(new Runnable() {
                  public void run() {
                      callNumberPhonePermission();
                  }
              });
              this.callbackContext.success("Call Successful");
            } else {
              this.callbackContext.error("Call Failed. You need to enter a phone number.");
            }
            return true;
        }else if(action.equals("onDeviceReady")) {
            return onDeviceReady(args,callbackContext);
        }else if(action.equals("registerDevice")) {
            return registerDevice(args,callbackContext);
        }else if(action.equals("unregisterDevice")) {
            return unregisterDevice(args,callbackContext);
        }else if(action.equals("setTags")) {
            return setTags(args,callbackContext);
        }else if(action.equals("getTags")) {
            return getTags(args,callbackContext);
        }else if(action.equals("getPushToken")) {
            return getPushToken(args,callbackContext);
        }else if(action.equals("getPushwooshHWID")) {
            return getPushwooshHWID(args,callbackContext);
        }else if(action.equals("createLocalNotification")) {
            return createLocalNotification(args,callbackContext);
        }else if(action.equals("getLaunchNotification")) {
            return getLaunchNotification(args,callbackContext);
        }else if(action.equals("setSoundType")) {
            return setSoundType(args,callbackContext);
        }else if(action.equals("setVibrateType")) {
            return setVibrateType(args,callbackContext);
        }else if(action.equals("setLightScreenOnNotification")) {
            return setLightScreenOnNotification(args,callbackContext);
        }else if(action.equals("setEnableLED")) {
            return setEnableLED(args,callbackContext);
        }else if(action.equals("setColorLED")) {
            return setColorLED(args,callbackContext);
        }else if(action.equals("getPushHistory")) {
            return getPushHistory(args,callbackContext);
        }else if(action.equals("addJavaScriptInterface")) {
            return addJavaScriptInterface(args,callbackContext);
        }else if(action.equals("setCommunicationEnabled")) {
            return setCommunicationEnabled(args,callbackContext);
        }else if(action.equals("removeAllDeviceData")) {
            return removeAllDeviceData(args,callbackContext);
        }else if(action.equals("getRemoteNotificationStatus")) {
            return getRemoteNotificationStatus(args,callbackContext);
        }else if(action.equals("postEvent")) {
            return postEvent(args,callbackContext);
        }else if(action.equals("setUserId")) {
            return setUserId(args,callbackContext);
        }else if(action.equals("addToApplicationIconBadgeNumber")) {
            return addToApplicationIconBadgeNumber(args,callbackContext);
        }else if(action.equals("setApplicationIconBadgeNumber")) {
            return setApplicationIconBadgeNumber(args,callbackContext);
        }else if(action.equals("clearLocalNotification")){
            LocalNotificationReceiver.cancelAll();
            return true;
        }else if(action.equals("clearLaunchNotification")){
            Pushwoosh.getInstance().clearLaunchNotification();
            return true;
        }else if(action.equals("setMultiNotificationMode")){
            PushwooshNotificationSettings.setMultiNotificationMode(true);
            return true;
        }else if(action.equals("setSingleNotificationMode")){
            PushwooshNotificationSettings.setMultiNotificationMode(false);
            return true;
        }else if(action.equals("clearPushHistory")){
            Pushwoosh.getInstance().clearPushHistory();
            return true;
        }else if(action.equals("clearNotificationCenter")){
            NotificationManagerCompat.from(cordova.getActivity()).cancelAll();
            return true;
        }else if(action.equals("getApplicationIconBadgeNumber")){
            Integer badgeNumber  = PushwooshBadge.getBadgeNumber();
            callbackContext.success(badgeNumber);
            return true;
        }else if(action.equals("presentInboxUI")){
            if (args.length() > 0)
                InboxUiStyleManager.setStyle(this.cordova.getActivity().getApplicationContext(), args.optJSONObject(0));
            this.cordova.getActivity().startActivity(new Intent(this.cordova.getActivity(), InboxActivity.class));
            return true;
        }else if(action.equals("showGDPRConsentUI")){
            GDPRManager.getInstance().showGDPRConsentUI();
            return true;
        }else if(action.equals("showGDPRDeletionUI")){
            GDPRManager.getInstance().showGDPRDeletionUI();
            return true;
        }else if(action.equals("isDeviceDataRemoved")){
            boolean removed = GDPRManager.getInstance().isDeviceDataRemoved();
            callbackContext.success(removed ? 1 : 0);
            return true;
        }else if(action.equals("isCommunicationEnabled")){
            boolean enabled = GDPRManager.getInstance().isCommunicationEnabled();
            callbackContext.success(enabled ? 1 : 0);
            return true;
        }else if(action.equals("isAvailableGDPR")){
            boolean isAvailableGDPR = GDPRManager.getInstance().isAvailable();
            callbackContext.success(isAvailableGDPR ? 1 : 0);
            return true;
        }
        return false;
    }

    private void checkCallPermission() {
        if(permissionCounter >= 1) {
            PhoneAccount currentPhoneAccount = tm.getPhoneAccount(handle);
            if(currentPhoneAccount.isEnabled()) {
                if(pendingAction == "receiveCall") {
                    this.receiveCall();
                } else if(pendingAction == "sendCall") {
                    this.sendCall();
                }
            } else {
                if(permissionCounter == 2) {
                    Intent phoneIntent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
                    phoneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    this.cordova.getActivity().getApplicationContext().startActivity(phoneIntent);
                } else {
                    this.callbackContext.error("You need to accept phone account permissions in order to send and receive calls");
                }
            }
        }
        permissionCounter--;
    }

    private void receiveCall() {
        Bundle callInfo = new Bundle();
        callInfo.putString("from",from);
        tm.addNewIncomingCall(handle, callInfo);
        permissionCounter = 0;
        this.callbackContext.success("Incoming call successful");
    }

    private void sendCall() {
        Uri uri = Uri.fromParts("tel", to, null);
        Bundle callInfoBundle = new Bundle();
        callInfoBundle.putString("to",to);
        Bundle callInfo = new Bundle();
        callInfo.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,callInfoBundle);
        callInfo.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
        callInfo.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, true);
        tm.placeCall(uri, callInfo);
        permissionCounter = 0;
        this.callbackContext.success("Outgoing call successful");
    }

    private void mute() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMicrophoneMute(true);
    }

    private void unmute() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMicrophoneMute(false);
    }

    private void speakerOn() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);
    }

    private void speakerOff() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(false);
    }

    public static String getApplicationName(Context context) {
      ApplicationInfo applicationInfo = context.getApplicationInfo();
      int stringId = applicationInfo.labelRes;
      return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    protected void getCallPhonePermission() {
        cordova.requestPermission(this, CALL_PHONE_REQ_CODE, Manifest.permission.CALL_PHONE);
    }

    protected void callNumberPhonePermission() {
        cordova.requestPermission(this, REAL_PHONE_CALL, Manifest.permission.CALL_PHONE);
    }

    private void callNumber() {
        try {
          Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", realCallTo, null));
          this.cordova.getActivity().getApplicationContext().startActivity(intent);
        } catch(Exception e) {
          this.callbackContext.error("Call Failed");
        }
        this.callbackContext.success("Call Successful");
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException
    {
        for(int r:grantResults)
        {
            if(r == PackageManager.PERMISSION_DENIED)
            {
                this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "CALL_PHONE Permission Denied"));
                return;
            }
        }
        switch(requestCode)
        {
            case CALL_PHONE_REQ_CODE:
                this.sendCall();
                break;
            case REAL_PHONE_CALL:
                this.callNumber();
                break;
        }
    }
    //PushWoosh

    private final Handler handler = new Handler(Looper.getMainLooper());

	public CordovaCall () {
		sInstance = this;
		sAppReady.set(false);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		PWLog.noise("OnDestroy");
		sAppReady.set(false);
	}

	private JSONObject getPushFromIntent(Intent intent) {
		if (null == intent)
			return null;

		if (intent.hasExtra(Pushwoosh.PUSH_RECEIVE_EVENT)) {
			String pushString = intent.getExtras().getString(Pushwoosh.PUSH_RECEIVE_EVENT);
			JSONObject pushObject = null;
			try {
				pushObject = new JSONObject(pushString);
			} catch (JSONException e) {
				PWLog.error(TAG, "Failed to parse push notification", e);
			}

			return pushObject;
		}

		return null;
	}

	private boolean onDeviceReady(JSONArray data, CallbackContext callbackContext) {
		JSONObject params = null;
		try {
			params = data.getJSONObject(0);
		} catch (JSONException e) {
			PWLog.error(TAG, "No parameters has been passed to onDeviceReady function. Did you follow the guide correctly?", e);
			return false;
		}

		try {

			if (sStartPushData != null) {
				doOnPushOpened(sStartPushData.toString());
			}

			String appid = null;
			if (params.has("appid")) {
				appid = params.getString("appid");
			} else {
				appid = params.getString("pw_appid");
			}

			Pushwoosh.getInstance().setAppId(appid);
			Pushwoosh.getInstance().setSenderId(params.getString("projectid"));


			synchronized (sStartPushLock) {
				if (sReceivedPushData != null) {
					doOnPushReceived(sReceivedPushData);
				}

				if (sStartPushData != null) {
					doOnPushOpened(sStartPushData);
				}
			}

			sAppReady.set(true);
		} catch (Exception e) {
			PWLog.error(TAG, "Missing pw_appid parameter. Did you follow the guide correctly?", e);
			return false;
		}
		registerDevice(null,callbackContext);
		Activity activity = cordova.getActivity();
		if(SDK_INT >= 29 && !Settings.canDrawOverlays(activity)){
			Intent intent = new Intent(ACTION_MANAGE_OVERLAY_PERMISSION);
			activity.startActivity(intent);
		}
		return true;
	}
	private boolean registerDevice(JSONArray data, CallbackContext callbackContext) {
		try {
			callbackIds.put("registerDevice", callbackContext);
			Pushwoosh.getInstance().registerForPushNotifications(new Callback<String, RegisterForPushNotificationsException>() {
				@Override
				public void process(@NonNull final Result<String, RegisterForPushNotificationsException> result) {
					if (result.isSuccess()) {
						doOnRegistered(result.getData());
					} else if (result.getException() != null) {
						doOnRegisteredError(result.getException().getMessage());
					}
				}
			});
		} catch (java.lang.RuntimeException e) {
			callbackIds.remove("registerDevice");
			PWLog.error(TAG, "registering for push notifications failed", e);

			callbackContext.error(e.getMessage());
		}

		return true;
	}

	private boolean unregisterDevice(JSONArray data, CallbackContext callbackContext) {
		callbackIds.put("unregisterDevice", callbackContext);

		try {
			Pushwoosh.getInstance().unregisterForPushNotifications(new Callback<String, UnregisterForPushNotificationException>() {
				@Override
				public void process(@NonNull final Result<String, UnregisterForPushNotificationException> result) {
					if (result.isSuccess()) {
						doOnUnregistered(result.getData());
					} else if (result.getException() != null) {
						doOnUnregisteredError(result.getException().getMessage());
					}
				}
			});
		} catch (Exception e) {
			callbackIds.remove("unregisterDevice");
			callbackContext.error(e.getMessage());
		}

		return true;
	}

	private boolean setTags(JSONArray data, final CallbackContext callbackContext) {
		JSONObject params;
		try {
			params = data.getJSONObject(0);
		} catch (JSONException e) {
			PWLog.error(TAG, "No tags information passed (missing parameters)", e);
			return false;
		}
		callbackIds.put("setTags", callbackContext);

		Pushwoosh.getInstance().sendTags(Tags.fromJson(params), new Callback<Void, PushwooshException>() {
			@Override
			public void process(@NonNull final Result<Void, PushwooshException> result) {
				CallbackContext callback = callbackIds.get("setTags");
				if (callback == null) {
					return;
				}

				if(result.isSuccess()){
					callback.success(new JSONObject());
				} else if(result.getException()!=null){
					callback.error(result.getException().getMessage());
				}

				callbackIds.remove("setTags");
			}
		});

		return true;
	}

	private boolean getTags(JSONArray data, final CallbackContext callbackContext) {
		callbackIds.put("getTags", callbackContext);

		Pushwoosh.getInstance().getTags(new Callback<TagsBundle, GetTagsException>() {
			@Override
			public void process(@NonNull final Result<TagsBundle, GetTagsException> result) {
				CallbackContext callback = callbackIds.get("getTags");
				if (callback == null)
					return;

				if(result.isSuccess()) {
					callback.success(result.getData().toJson());
				} else {
					callback.error(result.getException().getMessage());
				}
				callbackIds.remove("getTags");
			}
		});
		return true;
	}

	private boolean getPushToken(JSONArray data, final CallbackContext callbackContext) {
		callbackContext.success(Pushwoosh.getInstance().getPushToken());
		return true;
	}

    private boolean getPushwooshHWID(JSONArray data, final CallbackContext callbackContext) {
		callbackContext.success(Pushwoosh.getInstance().getHwid());
		return true;
	}

    private boolean createLocalNotification(JSONArray data, final CallbackContext callbackContext)
	{
		JSONObject params = null;
		try
		{
			params = data.getJSONObject(0);
		}
		catch (JSONException e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
			return false;
		}

		try
		{
			//config params: {msg:"message", seconds:30, userData:"optional"}
			String message = params.getString("msg");
			int seconds = params.getInt("seconds");
			if (message == null) {
				return false;
			}

			String userData = params.getString("userData");

			Bundle extras = new Bundle();
			if (userData != null) {
				extras.putString("u", userData);
			}

			LocalNotification notification = new LocalNotification.Builder()
					.setMessage(message)
					.setDelay(seconds)
					.setExtras(extras)
					.build();
			Pushwoosh.getInstance().scheduleLocalNotification(notification);
		}
		catch (JSONException e)
		{
			PWLog.error(TAG, "Not correct parameters passed (missing parameters)", e);
			return false;
		}

		return true;
	}

	private boolean getLaunchNotification(JSONArray data, final CallbackContext callbackContext)
	{
		PushMessage launchNotification = Pushwoosh.getInstance().getLaunchNotification();
		if (launchNotification == null) {
			callbackContext.success((String) null);
		} else {
			callbackContext.success(launchNotification.toJson().toString());
		}
		return true;
	}

	private boolean setSoundType(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			Integer type = (Integer) data.get(0);
			if (type == null)
				return false;

			PushwooshNotificationSettings.setSoundNotificationType(SoundType.fromInt(type));
		}
		catch (Exception e)
		{
			PWLog.error(TAG, "No sound parameters passed (missing parameters)", e);
			return false;
		}

		return true;
	}

	private boolean setVibrateType(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			Integer type = (Integer) data.get(0);
			if (type == null)
				return false;

			PushwooshNotificationSettings.setVibrateNotificationType(VibrateType.fromInt(type));
		}
		catch (Exception e)
		{
			PWLog.error(TAG, "No vibration parameters passed (missing parameters)", e);
			return false;
		}

		return true;
	}

	private boolean setLightScreenOnNotification(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			boolean type = (boolean) data.getBoolean(0);
			PushwooshNotificationSettings.setLightScreenOnNotification(type);
		}
		catch (Exception e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
			return false;
		}

		return true;
	}

	private boolean setEnableLED(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			boolean type = (boolean) data.getBoolean(0);
			PushwooshNotificationSettings.setEnableLED(type);
		}
		catch (Exception e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
			return false;
		}

		return true;
	}

	private boolean setColorLED(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			String colorString = (String) data.get(0);
			if (colorString == null)
				return false;

			int colorLed = GeneralUtils.parseColor(colorString);
			PushwooshNotificationSettings.setColorLED(colorLed);
		}
		catch (Exception e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
			return false;
		}

		return true;
	}

	private boolean getPushHistory(JSONArray data, final CallbackContext callbackContext)
	{
		List<PushMessage> pushMessageHistory = Pushwoosh.getInstance().getPushHistory();
		List<String> pushHistory = new ArrayList<String>();

		for (PushMessage pushMessage: pushMessageHistory){
			pushHistory.add(pushMessage.toJson().toString());
		}
		callbackContext.success(new JSONArray(pushHistory));
		return true;
	}

	private boolean setApplicationIconBadgeNumber(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			Integer badgeNumber = data.getJSONObject(0).getInt("badge");
			PushwooshBadge.setBadgeNumber(badgeNumber);
		}
		catch (JSONException e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
			return false;
		}
		return true;
	}

	private boolean addToApplicationIconBadgeNumber(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			Integer badgeNumber = data.getJSONObject(0).getInt("badge");
			PushwooshBadge.addBadgeNumber(badgeNumber);
		}
		catch (JSONException e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
			return false;
		}
		return true;
	}

	private boolean setUserId(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			String userId = data.getString(0);
			PushwooshInApp.getInstance().setUserId(userId);
		}
		catch (JSONException e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
		}
		return true;
	}

	private boolean postEvent(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			String event = data.getString(0);
			JSONObject attributes = data.getJSONObject(1);
			PushwooshInApp.getInstance().postEvent(event, Tags.fromJson(attributes));
		}
		catch (JSONException e)
		{
			PWLog.error(TAG, "No parameters passed (missing parameters)", e);
		}
		return true;
	}

	private boolean getRemoteNotificationStatus(JSONArray data, final CallbackContext callbackContext)
	{
		try
		{
			String enabled = PushwooshNotificationSettings.areNotificationsEnabled() ? "1" : "0";
			JSONObject result = new JSONObject();
			result.put("enabled", enabled);
			callbackContext.success(result);
		}
		catch (Exception e)
		{
			callbackContext.error(e.getMessage());
		}

		return true;
	}


	public boolean removeAllDeviceData(JSONArray data, final CallbackContext callbackContext){
		GDPRManager.getInstance().removeAllDeviceData(new Callback<Void, PushwooshException>() {
			@Override
			public void process(@NonNull Result<Void, PushwooshException> result) {
				if(result.isSuccess()){
					callbackContext.success();
				}else {
					callbackContext.error(result.getException().getMessage());
				}
			}
		});
		return true;
	}
	public boolean setCommunicationEnabled(JSONArray data, final CallbackContext callbackContext){
		try {
			boolean enable = data.getBoolean(0);
			GDPRManager.getInstance().setCommunicationEnabled(enable, new Callback<Void, PushwooshException>() {
				@Override
				public void process(@NonNull Result<Void, PushwooshException> result) {
					if(result.isSuccess()){
						callbackContext.success();
					}else {
						callbackContext.error(result.getException().getMessage());
					}
				}
			});
			return true;
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return false;
	}

	private void doOnRegistered(String registrationId)
	{
		CallbackContext callback = callbackIds.get("registerDevice");
		if (callback == null)
			return;

		callback.success(registrationId);

		callbackIds.remove("registerDevice");
	}

	private void doOnRegisteredError(String errorId)
	{
		CallbackContext callback = callbackIds.get("registerDevice");
		if (callback == null)
			return;

		callback.error(errorId);
		callbackIds.remove("registerDevice");
	}

	private void doOnUnregistered(String registrationId)
	{
		CallbackContext callback = callbackIds.get("unregisterDevice");
		if (callback == null)
			return;

		callback.success(registrationId);
		callbackIds.remove("unregisterDevice");
	}

	private void doOnUnregisteredError(String errorId)
	{
		CallbackContext callback = callbackIds.get("unregisterDevice");
		if (callback == null)
			return;

		callback.error(errorId);
		callbackIds.remove("unregisterDevice");
	}

	private void doOnPushOpened(String notification)
	{
		PWLog.debug(TAG, "push opened: " + notification);

		String jsStatement = String.format("cordova.require(\"cordova-plugin-callkit.PushNotification\").notificationCallback(%s);", convertNotification(notification));
		evalJs(jsStatement);
		sStartPushData = null;
	}

	public void doOnPushReceived(String notification)
	{
		//Make call
		CordovaCall cordovaCall = (CordovaCall)this.webView.getPluginManager().getPlugin("CordovaCall");
		try {
			JSONObject caller = new JSONObject(notification).optJSONObject("userdata").optJSONObject("Caller");
			cordovaCall.execute("receiveCall",new JSONArray("[\""+caller.optString("Username")+"\"]"),new CallbackContext("0",webView));
		} catch (JSONException e) {
			e.printStackTrace();
		}

		PWLog.debug(TAG, "push received: " + notification);

		String jsStatement = String.format("cordova.require(\"cordova-plugin-callkit.PushNotification\").pushReceivedCallback(%s);", convertNotification(notification));
		evalJs(jsStatement);

		sReceivedPushData = null;
	}

	private String convertNotification(String notification)
	{
		JSONObject unifiedNotification = new JSONObject();

		try
		{
			JSONObject notificationJson = new JSONObject(notification);
			String pushMessage = notificationJson.optString("title");
			Boolean foreground = notificationJson.optBoolean("foreground");
			Boolean onStart = notificationJson.optBoolean("onStart");
			JSONObject userData = notificationJson.optJSONObject("userdata");


			unifiedNotification.put("android", notificationJson);
			unifiedNotification.put("message", pushMessage);
			unifiedNotification.put("foreground", foreground);
			unifiedNotification.put("onStart", onStart);
			unifiedNotification.put("userdata", userData);
		}
		catch (JSONException e) {
			PWLog.error(TAG, "push message parsing failed", e);
		}

		String result = unifiedNotification.toString();

		// wrap special characters
		result = result.replace("%", "%\"+\"");

		return result;
	}

	private void evalJs(String statement)
	{
		final String url = "javascript:" + statement;

		handler.post(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					webView.loadUrl(url);
				}
				catch (Exception e)
				{
					PWLog.exception(e);
				}
			}
		});
	}


	static void openPush(String pushData) {
		try {
			synchronized (sStartPushLock) {
				sStartPushData = pushData;
				if (sAppReady.get() && sInstance != null) {
					sInstance.doOnPushOpened(pushData);
				}
			}
		} catch (Exception e) {
			// React Native is highly unstable
			PWLog.exception(e);
		}
	}

	static void messageReceived(String pushData) {
		try {
			synchronized (sStartPushLock) {
				sReceivedPushData = pushData;
				if (sAppReady.get() && sInstance != null) {
					sInstance.doOnPushReceived(pushData);
				}
			}
		} catch (Exception e) {
			// React Native is highly unstable
			PWLog.exception(e);
		}
	}


	public class JavascriptInterfaceCordova {
		@JavascriptInterface
		public void callFunction(String functionName) {
			String url = String.format("%s();", functionName);
			evalJs(url);
		}

		@JavascriptInterface
		public void callFunction(String functionName, String args) {
			String url;
			if (args == null || args.isEmpty()) {
				url = String.format("%s();", functionName);
			} else {
				url = String.format("%s(%s);", functionName, args);
			}
			evalJs(url);
		}
	}

	private boolean addJavaScriptInterface(JSONArray data, final CallbackContext callbackContext) {
		try {
			String name = data.getString(0);
			PushwooshInApp.getInstance().addJavascriptInterface(new JavascriptInterfaceCordova(), name);
		} catch (JSONException e) {
			PWLog.error(TAG, "No parameters has been passed to addJavaScriptInterface function. Did you follow the guide correctly?", e);
			return false;
		}

		return true;
	}
}
