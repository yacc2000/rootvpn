package com.donn.rootvpn;

import java.util.StringTokenizer;

import com.donn.rootvpn.ShellCommand.CommandResult;

//TODO: Add notification in bar when VPN connected

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.widget.RemoteViews;

public class RootVPNService extends Service {

	private ShellCommand cmd = new ShellCommand();
	private static String preVPNDNSServer;
	private static boolean isConnected = false;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		L.log(this, "Service started");

		RootVPNTask task = new RootVPNTask(this, intent);
		task.execute((Void[]) null);

		this.stopService(intent);

		return START_STICKY;
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

			L.log(this, "Started service thread");

			ComponentName thisWidget = new ComponentName(context, RootVPNWidget.class);
			AppWidgetManager manager = AppWidgetManager.getInstance(context);
			RemoteViews updateViews = new RemoteViews(context.getPackageName(), R.layout.widget_data);

			Intent defineIntent = null;

			if (intent != null) {
				String intentAction = intent.getAction();

				if (intentAction != null) {
					if (intent.getAction().equals(VPNRequestReceiver.ON_INTENT)) {

						L.log(this, "Got the : " + VPNRequestReceiver.ON_INTENT + " intent");

						try {
							initialActions();
						}
						catch (VPNException e) {
							L.err(this, "VPNException received, terminating RootVPNService");
							L.err(e.getSource(), e.getMessage());
							//If we couldn't perform the initial actions, terminate thread
							return null;
						}
						
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.wait);
						manager.updateAppWidget(thisWidget, updateViews);

						if (isConnected == false) {

							L.log(this, "VPN is not connected, connecting now");

							if (turnOnVPN()) {
								L.log(this, "VPN was turned on. Setting next action to OFF");
								sendBroadcast(new Intent(VPNRequestReceiver.CONNECTED_INTENT));
								defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
							}
							else {
								L.log(this, "VPN failed to turn on. Setting next action to ON");
								updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
								defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
							}
						}
						else {
							L.log(this, "VPN is already connected. Setting next action to OFF");
							sendBroadcast(new Intent(VPNRequestReceiver.CONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
						}
					}
					else if (intent.getAction().equals(VPNRequestReceiver.OFF_INTENT)) {
						L.log(this, "Got the : " + VPNRequestReceiver.OFF_INTENT + " intent");
						
						try {
							initialActions();
						}
						catch (VPNException e) {
							L.err(this, "VPNException received, terminating RootVPNService");
							L.err(e.getSource(), e.getMessage());
							//If we couldn't perform the initial actions, terminate thread
							return null;
						}

						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.wait);
						manager.updateAppWidget(thisWidget, updateViews);

						if (turnOffVPN()) {
							L.log(this, "VPN was turned off. Setting next action to ON");
							sendBroadcast(new Intent(VPNRequestReceiver.DISCONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
						}
						else {
							// TODO: Probably never reach this code, no false
							// value returns from turnOffVPN
							updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
							defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
						}
					}
					else if (intent.getAction().equals(VPNRequestReceiver.CONNECTED_INTENT)) {
						L.log(this, "Got the : " + intent.getAction() + " intent. Setting connected=true");
						L.log(this, "Set next action to OFF");
						isConnected = true;
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.connected);
						defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
					}
					else if (intent.getAction().equals(VPNRequestReceiver.DISCONNECTED_INTENT)) {
						L.log(this, "Got the : " + intent.getAction() + " intent. Setting connected=false");
						L.log(this, "Set next action to ON");
						isConnected = false;
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
						defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
					}
				}
				else {
					L.log(this, "Intent action was null, setting next action to ON");
					updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
					defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
				}
			}
			else {
				L.log(this, "Intent itself was null, setting next action to ON");
				updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
				defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
			}

			defineIntent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
			PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, defineIntent, 0);
			updateViews.setOnClickPendingIntent(R.id.widget, pendingIntent);

			manager.updateAppWidget(thisWidget, updateViews);

			L.log(this, "Completed service thread");

			return null;
		}

		private void requestRoot() throws VPNException {
			L.log(this, "Requesting root...");

			CommandResult result = cmd.su.runWaitFor("date");

			if (result.success()) {
				L.log(this, "Requesting root successful!");
			}
			else {
				throw new VPNException(this, "Requesting root failed: " + result.stderr + " " + result.stdout);
			}
		}

		private boolean turnOnVPN() {
			L.log(this, "Turning on VPN");
			
			try {
				String currentNetworkInterface = getCurrentNetworkInterface();

				startMtpdService(currentNetworkInterface);
				waitForMtpdServiceStart();

				setupVPNRoutingTables();
				setPreVPNDNSServer();
				String pppDNSServer = getPPPDNSServer();
				setDNS(pppDNSServer);

				L.log(this, "VPN connection started: success, returning true.");
				return true;
			}
			catch (VPNException e) {
				L.err(this, "VPNException received, terminating RootVPNService");
				L.err(e.getSource(), e.getMessage());
				//If we fail to connect for whatever reason, try to clean up by turning VPN off
				turnOffVPN();
				return false;
			}
		}

		private boolean turnOffVPN() {
			L.log(this, "Turning off VPN");
			
			try {
				killMtpdService();
				resetDNS();

				L.log(this, "VPN connection stopped: success, returning true.");
				return true;
			}
			catch (VPNException e) {
				L.err(this, "VPNException received, terminating RootVPNService");
				L.err(e.getSource(), e.getMessage());
				return false;
			}

		}
		
		private void initialActions() throws VPNException {
			requestRoot();
			
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
			vpnServer = preferences.getString(getString(R.string.pref_vpnserver), "none");
			vpnPort = preferences.getString(getString(R.string.pref_vpnport), "1723");
			vpnUser = preferences.getString(getString(R.string.pref_username), "vpn");
			vpnPassword = preferences.getString(getString(R.string.pref_password), "password");
		}

		private String getCurrentNetworkInterface() throws VPNException {
			L.log(this, "Getting current network interface");

			String resultString = "";

			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'ip route | grep default'");

			if (result.success()) {

				resultString = result.stdout;
				StringTokenizer tokenizer = new StringTokenizer(resultString, " ");

				int tokens = 0;
				while (tokens < 5) {
					resultString = tokenizer.nextToken();
					tokens++;
				}
				L.log(this, "got current network interface: " + resultString);
			}
			else {
				throw new VPNException(this, "Failed to grep for current network interface " + result.stdout + " "
						+ result.stderr);
			}

			return resultString;
		}

		private void startMtpdService(String currentNetworkInterface) {
			L.log(this, "Starting mtpd...");

			cmd.su.run("mtpd " + currentNetworkInterface + " pptp " + vpnServer + " " + vpnPort + " name " + vpnUser
					+ " password " + vpnPassword + " linkname vpn " + "refuse-eap nodefaultroute usepeerdns idle "
					+ "1800 mtu 1400 mru 1400 +mppe");

			L.log(this, "mtpd was started...");
		}

		private void waitForMtpdServiceStart() throws VPNException {
			L.log(this, "Waiting for mtpd initialization...");

			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp0'");
			String resultString = result.stdout;
			
			int count = 0;
			for (; count < 30 && (resultString == null || !resultString.contains("UP")); count++) {
				// wait until mtpd is running
				sleep(1);
				L.log(this, "Still waiting for mtpd to initialize...");
				result = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp0'");
				resultString = result.stdout;
			}
			if (result.success() && count < 30) {
				L.log(this, "mtpd initialized successfully after " + count + " seconds");
			}
			else {
				throw new VPNException(this, "mtpd failed to initialize after " + count + " seconds " + result.stderr
						+ " " + result.stdout);
			}
		}

		private void setupVPNRoutingTables() throws VPNException {
			L.log(this, "Setting up VPN routing tables");

			CommandResult resultOne = cmd.su.runWaitFor("ip route add 0.0.0.0/1 dev ppp0");
			CommandResult resultTwo = cmd.su.runWaitFor("ip route add 128.0.0.0/1 dev ppp0");

			if (resultOne.success() && resultTwo.success()) {
				L.log(this, "Successfully setup VPN routing tables");
			}
			else {
				throw new VPNException(this, "VPN Routing table setup fail: " + resultOne.stderr + " "
						+ resultOne.stdout + " " + resultTwo.stderr + " " + resultTwo.stdout);
			}
		}

		private void setPreVPNDNSServer() throws VPNException {
			L.log(this, "Getting DNS server system was using before VPN");

			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'getprop net.dns1'");
			String resultString = result.stdout;
			if (result.success()) {
				L.log(this, "Got pre-VPN DNS server from ip route: " + resultString);

				StringTokenizer tokenizer = new StringTokenizer(resultString, " ");
				String dnsServer = tokenizer.nextToken();

				L.log(this, "Parsed pre-VPN DNS server from ip route: " + dnsServer);

				preVPNDNSServer = dnsServer;
			}
			else {
				throw new VPNException(this, "Unable to get pre-VPN DNS server from ip route: " + result.stderr + " "
						+ result.stdout);
			}
		}

		private String getPPPDNSServer() throws VPNException {
			L.log(this, "Getting DNS server to use for VPN server's IP");

			String dnsServer = "";
			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'ip route | grep \"dev ppp0  proto kernel\"'");
			String resultString = result.stdout;

			if (result.success()) {
				L.log(this, "Got DNS server from ip route: " + resultString);

				StringTokenizer tokenizer = new StringTokenizer(resultString, " ");
				dnsServer = tokenizer.nextToken();

				L.log(this, "Parsed DNS server from ip route: " + dnsServer);
			}
			else {
				throw new VPNException(this, "Unable to get DNS server from ip route: " + result.stderr + " "
						+ result.stdout);
			}

			return dnsServer;
		}

		private void setDNS(String dnsValue) throws VPNException {
			L.log(this, "Setting DNS server to: " + dnsValue);

			CommandResult result = cmd.su.runWaitFor("setprop net.dns1 " + dnsValue);

			if (result.success()) {
				L.log(this, "Successfully set DNS server from ip route: " + dnsValue);
				incrementDNSChangeValue();
			}
			else {
				throw new VPNException(this, "Unable to set DNS server in properties: " + result.stderr + " "
						+ result.stdout);
			}
		}

		private void incrementDNSChangeValue() throws VPNException {
			L.log(this, "Getting DNS increment value");
			CommandResult resultOne = cmd.su.runWaitFor("getprop net.dnschange");
			String resultOneString = resultOne.stdout;

			if (resultOne.success()) {
				L.log(this, "Success in get dnschange value: " + resultOne.stdout);

				Integer intValue = Integer.parseInt(resultOneString);
				int dnsChange = intValue.intValue();
				dnsChange++;

				L.log(this, "Setting DNS increment value to: " + dnsChange);
				CommandResult resultTwo = cmd.su.runWaitFor("setprop net.dnschange " + dnsChange);

				if (resultTwo.success()) {
					L.log(this, "Success in set dnschange value: " + dnsChange);
				}
				else {
					throw new VPNException(this, "Unable to set dnschange in properties: " + resultTwo.stderr + " "
							+ resultTwo.stdout);
				}
			}
			else {
				throw new VPNException(this, "Unable to get dnschange in properties: " + resultOne.stderr + " "
						+ resultOne.stdout);
			}
		}

		private void killMtpdService() throws VPNException {
			L.log(this, "Killing mtpd service");
			
			CommandResult resultOne = cmd.su.runWaitFor("pidof mtpd");
		
			if (resultOne.success()) {
				if (resultOne.stdout != null) {
					L.log(this, "Killing mtpd: pid=" + resultOne.stdout);
					CommandResult resultTwo = cmd.su.runWaitFor("kill -9 " + resultOne.stdout);
					L.log(this, "Killed mtpd");
					
					if (resultTwo.success()) {
						L.log(this, "VPN connection terminated: success, returning true.");
					}
					else {
						throw new VPNException(this, "Unable to kill mtpd: " + resultTwo.stderr + " "
								+ resultTwo.stdout);
					}
				}
				else {
					// If no process to kill exists, VPN is already off, that's fine.
					L.log(this, "No process found to kill for mtpd.");
					L.log(this, "VPN connection terminated: fail, returning false.");
				}
			}
			else {
				// If no process to kill exists, VPN is already off, that's fine.
				L.log(this, "pidof mtpd error: No process found to kill for mtpd.");
				L.log(this, "VPN connection terminated: fail, returning false.");
			}
		}

		private void resetDNS() throws VPNException {
			setDNS(preVPNDNSServer);
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
