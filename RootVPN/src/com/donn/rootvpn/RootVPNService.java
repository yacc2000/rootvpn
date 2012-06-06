package com.donn.rootvpn;

import java.util.StringTokenizer;

import com.donn.rootvpn.ShellCommand.CommandResult;

//TODO: Add notification in bar when VPN connected
//TODO: figure out routing so that when VPN connected internet still works

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;

public class RootVPNService extends Service {
	
	private ShellCommand cmd = new ShellCommand();
	
	private static boolean isConnected = false;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);
		
		RootVPNTask task = new RootVPNTask(this, intent);
		task.execute((Void[]) null);
		
		this.stopService(intent);
		
		return START_STICKY;
	}
	
	private void log(String stringToLog) {
		Log.d((String) getText(R.string.app_name), 
				getText(R.string.app_name) + ": " + stringToLog);
	}
	
	private class RootVPNTask extends AsyncTask<Void, Void, Void> {
		
		private Context context;
		private Intent intent;
		
		private String vpnServer;
		private String vpnPort;
		private String vpnUser;
		private String vpnPassword;
		
		public RootVPNTask(Context context, Intent intent) {
			this.context = context;
			this.intent = intent;
		}
		
		@Override
		protected Void doInBackground(Void... params) {
			
			requestRoot();
			
	        ComponentName thisWidget = new ComponentName(context, RootVPNWidget.class);
			AppWidgetManager manager = AppWidgetManager.getInstance(context);

			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
			vpnServer = preferences.getString(getString(R.string.pref_vpnserver), "none");
			vpnPort = preferences.getString(getString(R.string.pref_vpnport), "1723");
			vpnUser = preferences.getString(getString(R.string.pref_username), "vpn");
			vpnPassword = preferences.getString(getString(R.string.pref_password), "password");
			
	        RemoteViews updateViews = new RemoteViews(context.getPackageName(), R.layout.widget_data);

			Intent defineIntent = null;
	    	
			if (intent != null) {
				String intentAction = intent.getAction();
				
				if (intentAction != null) {
					if (intent.getAction().equals(VPNRequestReceiver.ON_INTENT)) {
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.wait);
						manager.updateAppWidget(thisWidget, updateViews);
						
						if (isConnected == false) {
							if (turnOnVPN()) {
								sendBroadcast(new Intent(VPNRequestReceiver.CONNECTED_INTENT));
								defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
							}
							else {
								updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
								defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
							}
						}
						else {
							//already connected, no need to turn VPN on again
							sendBroadcast(new Intent(VPNRequestReceiver.CONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
						}
			        }
					else if (intent.getAction().equals(VPNRequestReceiver.OFF_INTENT)) {
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.wait);
						manager.updateAppWidget(thisWidget, updateViews);
						
						if (turnOffVPN()) {
							sendBroadcast(new Intent(VPNRequestReceiver.DISCONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
						}
						else {
							//TODO: Probably never reach this code, no false value returns from turnOffVPN
							updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
							defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
						}
			        }
					else if (intent.getAction().equals(VPNRequestReceiver.CONNECTED_INTENT)) {
						isConnected = true;
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.connected);
						defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT); 
					}
					else if (intent.getAction().equals(VPNRequestReceiver.DISCONNECTED_INTENT)) {
						isConnected = false;
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
						defineIntent = new Intent(VPNRequestReceiver.ON_INTENT); 
					}
				}
				else {
					updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
		        	defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
				}
			}
			else {
				updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
	        	defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
			}
			
		 	defineIntent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
	        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, defineIntent, 0);
	        updateViews.setOnClickPendingIntent(R.id.widget, pendingIntent);

			manager.updateAppWidget(thisWidget, updateViews);
			
			return null;
		}
		
		private void requestRoot() {
			log("Requesting root...");
			cmd.su.runWaitFor("ls");
			log("Requesting root successful!");
		}
		
		private boolean turnOnVPN() {
			ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo ni = cm.getActiveNetworkInfo();
			NetworkInfo[] nia = cm.getAllNetworkInfo();
			int netpreference = cm.getNetworkPreference();
			//cm.requestRouteToHost(ConnectivityManager.TYPE_ETHERNET, 1 );
			//cm.setNetworkPreference(1);

			log("Starting mtpd...");
			
			//TODO: may also be on wifi (wlan0)
			cmd.su.run("mtpd rmnet0 pptp " + 
					vpnServer + " " + vpnPort + 
					" name " + vpnUser + 
					" password " + vpnPassword + 
					" linkname vpn " + "refuse-eap nodefaultroute usepeerdns idle "
					+ "1800 mtu 1400 mru 1400 +mppe");
			
			log("mtpd was started...");
			
			int count = 0;
			String result = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp0'").stdout;
			while (count < 30 && (result == null || !result.contains("UP"))) {
				//wait until mtpd is running
				sleep(1);
				log("Waiting for mtpd to initialize...");
				result = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp0'").stdout;
				count++;
			}

			if (count < 30) {
				int exitValues = 0;
				
				log("route 1st command being issued...");
				CommandResult r = cmd.su.runWaitFor("ip route add 0.0.0.0/1 dev ppp0");
				log("route 1st execution code: " + r.exit_value);
				
				exitValues = exitValues + r.exit_value;
				
				log("route 2nd command being issued...");
				r = cmd.su.runWaitFor("ip route add 128.0.0.0/1 dev ppp0");
				log("route 2nd execution code: " + r.exit_value);

				exitValues = exitValues + r.exit_value;
				
				log("getting DNS server to use for VPN server's IP"); 
				//r = cmd.su.runWaitFor("ip route | grep 'dev ppp0  proto kernel'");
				r = cmd.su.runWaitFor("/system/bin/sh -c 'ip route | grep \"dev ppp0  proto kernel\"'");
				result = r.stdout;
				log("got DNS server from ip route: " + result);
				StringTokenizer tokenizer = new StringTokenizer(result, " ");
				String dnsServer = tokenizer.nextToken();

				exitValues = exitValues + r.exit_value;
				
				log("setting DNS server to VPN server's IP");
				r = cmd.su.runWaitFor("setprop net.dns1 " + dnsServer);
				log("got DNS server from ip route: " + r.exit_value);
				
				exitValues = exitValues + r.exit_value;

				if (exitValues == 0) {
					log("VPN connection started: success, returning true.");
					return true;
				}
				else {
					log("VPN connection started: fail, returning false.");
					return false;
				}
			}
			else {
				log("VPN connection failed to initialize after 30 seconds.");
				return false;
			}
		}
		
		private boolean turnOffVPN() {
			ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo ni = cm.getActiveNetworkInfo();
			NetworkInfo[] nia = cm.getAllNetworkInfo();
			int netpreference = cm.getNetworkPreference();
			//cm.requestRouteToHost(ConnectivityManager.TYPE_ETHERNET, 1 );
			//cm.setNetworkPreference(1);
			
			int exitValues = 0;
			
			CommandResult r = cmd.su.runWaitFor("pidof mtpd");
			log("pidof execution code: " + r.exit_value);
			
			if (r.stdout != null) {
				log("Killing mtpd: pid=" + r.stdout);
				r = cmd.su.runWaitFor("kill -9 " + r.stdout);
				log("Killed mtpd");
				log("kill -9 execution code: " + r.exit_value);
				log("VPN connection terminated: success, returning true.");
			}
			else {
				//If no process to kill exists, VPN is already off, that's fine.
				log("No process found to kill for mtpd.");
				log("VPN connection terminated: fail, returning false.");
			}
			
			//TODO: (or Wifi value)
			log("getting mobile DNS server to use for DNS ");
			r = cmd.su.runWaitFor("getprop net.rmnet0.dns1");
			String result = r.stdout;
			log("got DNS server from system properties: " + result);

			exitValues = exitValues + r.exit_value;
			
			log("setting DNS server mobile DNS server");
			r = cmd.su.runWaitFor("setprop net.dns1 " + result);
			log("set net.dns1 propery success: " + r.exit_value);
			
			exitValues = exitValues + r.exit_value;
			
			if (exitValues == 0) {
				log("VPN connection stopped: success, returning true.");
				return true;
			}
			else {
				log("VPN connection stopped: fail, returning false.");
				return false;
			}
		}
		
		private void sleep(int seconds) {
			int millis = seconds * 1000;

			try {
				Thread.sleep(millis);
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

}
