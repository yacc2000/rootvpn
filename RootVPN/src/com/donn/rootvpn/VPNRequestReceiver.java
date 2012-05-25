package com.donn.rootvpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class VPNRequestReceiver extends BroadcastReceiver {

	public static final String ON_INTENT = "com.donn.rootvpn.ON";
	public static final String CONNECTED_INTENT = "com.donn.rootvpn.CONNECTED";
	public static final String OFF_INTENT = "com.donn.rootvpn.OFF";
	public static final String DISCONNECTED_INTENT = "com.donn.rootvpn.DISCONNECTED";
	private Context appContext;
	
	@Override
	public void onReceive(Context context, Intent intent) {
		appContext = context;
		
		log("Received Intent: " + intent.toString());

		if (intent.getAction().equals(ON_INTENT)) {
			System.out.println("Got the ON intent");
		}
		else if (intent.getAction().equals(OFF_INTENT)) {
			System.out.println("Got the OFF intent");
		}
		
		intent.setClass(context, RootVPNService.class);
        context.startService(intent);
	}
	
	private void log(String stringToLog) {
		Log.d((String) appContext.getText(R.string.app_name), 
				appContext.getText(R.string.app_name) + ": " + stringToLog);
	}
	
	

}
