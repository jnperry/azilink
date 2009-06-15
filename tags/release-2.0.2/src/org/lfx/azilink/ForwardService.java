/* AziLink: USB tethering for Android
 * Copyright (C) 2009 by James Perry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.lfx.azilink;

import java.io.IOException;

import org.lfx.azilink.net.VpnNatEngine;
import org.lfx.azilink.net.VpnNatEngineNotify;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Controls the VPN service and sends statistics back to the UI.  This service runs in a different
 * process space than the UI to protect the network code from UI crashes.
 * 
 * @author Jim Perry
 *
 */
public class ForwardService extends Service implements VpnNatEngineNotify {
	/** The actual NAT engine. */
	VpnNatEngine mEngine;					

	/** If WiFi is active when VPN link is started, then hold a WiFi lock to keep
	 * the WiFi connection active.
	 */
	WifiManager.WifiLock mWifiLock;
	
	/** Is a computer connected to the VPN? */
	boolean mActive = false;	
	
	/** Bytes received prior to the last mark point. */
	private long mBytesSavedRecv = 0;
	/** Bytes sent prior to the last mark point. */
	private long mBytesSavedSent = 0;
	/** How often should we save the byte counters to a file (ms)? */
	private static final int sPeriodicSaveTime = 30000;
	/** Handler which periodically saves the byte counters */
	Handler mHandler = new Handler();
	/** Enable debug logging. */
	final static boolean sLog = false;
	
	/**
	 * Start the VPN engine.  Called when the user clicks "start service."
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		
		if(sLog) Log.v("AziLink", "fwd::onCreate");
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        mBytesSavedSent = pref.getLong(getString(R.string.pref_key_saved_bytessent), 0);
        mBytesSavedRecv = pref.getLong(getString(R.string.pref_key_saved_bytesrecv), 0);
                
		mEngine = new VpnNatEngine( this );
		mEngine.setTMobileWorkaround(pref.getBoolean(getString(R.string.pref_key_tmobile),false));
		mEngine.setTMobileWorkaroundTimeout(Integer.parseInt(pref.getString(getString(R.string.pref_key_tmobile_ms),"1000")));
		mEngine.setPinger(pref.getBoolean(getString(R.string.pref_key_ping),true));
		try {
			mEngine.start();
		} catch (IOException e) {
			onError( e.toString() );
			return;
		}
		
		WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
		mWifiLock = wifi.createWifiLock("AziLink");
		mWifiLock.setReferenceCounted(false);
		
		// Probably don't need to hold the power lock since this is a USB service and
		// the phone never suspends while it's plugged in..
		//
		// mPower = (PowerManager) getSystemService( Context.POWER_SERVICE );
		// mPowerLock = mPower.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "AziLink" );		
	}
	
	/**
	 * Shut down the VPN engine, save byte counters, and release any WiFi locks.
	 */
	@Override
	public void onDestroy() {
		if(sLog) Log.v("AziLink", "fwd::onDestroy");
		try {
			mWifiLock.release();
			mEngine.stop();
			saveByteCounters();
		} catch (InterruptedException e) {
			onError( e.toString() );
		}				
		super.onDestroy();
	}
	
	/**
	 * Save the byte counters to disk, and zero the dynamic counters inside the VPN module.
	 * Actual usage is saved_value + dynamic_value
	 */
	public void saveByteCounters() {
		if(sLog) Log.v("AziLink", "fwd::saveByteCounters");
		mBytesSavedSent += mEngine.getBytesSent();
		mBytesSavedRecv += mEngine.getBytesRecv();		
		
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor ed = pref.edit();
		ed.putLong(getString(R.string.pref_key_saved_bytessent), mBytesSavedSent);
		ed.putLong(getString(R.string.pref_key_saved_bytesrecv), mBytesSavedRecv);
		ed.commit();        
		
		mEngine.resetCounters();
	}
		
	/**
	 * Callback from VPN module indicating VPN link is active.  Grabs the WiFi lock, and
	 * changes the service priority to foreground.
	 */
	public void onLinkEstablished() {
		Log.v("AziLink", "AziLink established connection to VPN");
		setForeground( true );
		mWifiLock.acquire();
		mActive = true;
		mPeriodicSaver.run();
		
		//mPowerLock.acquire();
	}
	
	/**
	 * Callback from VPN module indicating VPN link has been lost.  Releases WiFi lock,
	 * removes the periodic byte counter saver, and indicates whether the NAT module should
	 * terminate all connections.
	 * 
	 * @return whether the NAT module should close all connections
	 */
	public boolean onLinkLost() {
		Log.v("AziLink", "AziLink lost connection to VPN");
		mWifiLock.release();
		setForeground( false );
		mActive = false;
		mPeriodicSaver.run();
		
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
		return pref.getBoolean(getString(R.string.pref_key_autodisconnect),true);
        //mPowerLock.release();
	}
	
	/**
	 * Periodically saves the byte counters to disk.
	 */
	Runnable mPeriodicSaver = new Runnable() {
		public void run() {
			saveByteCounters();
			if( mActive ) {
				mHandler.removeCallbacks(this);
				mHandler.postDelayed(this, sPeriodicSaveTime);
			}
		}		
	};
	
	/**
	 * Display an error to the user.
	 * @param error Error message
	 */
	public void onError( String error ) {
		Log.e("AziLink","***** AZILINK CRASH ******: "+error);
		NotificationManager nm = (NotificationManager) getSystemService( NOTIFICATION_SERVICE );
		Notification n = new Notification(R.drawable.icon, getText(R.string.error_desc), System.currentTimeMillis() );
		PendingIntent contentIntent = PendingIntent.getActivity(ForwardService.this, 0, 
				new Intent(ForwardService.this, MainActivity.class), 0);
		n.setLatestEventInfo(ForwardService.this, getText(R.string.error_label), getText(R.string.error_desc), contentIntent);
		nm.notify(R.string.error_label, n); 
	}

	@Override
	public IBinder onBind(Intent intent) {
		if(sLog) Log.v("AziLink", "fwd::onBind");
		return mBinder;
	}

	/**
	 * Implements the interface for communication between the service and the UI module.
	 */
	private final IAziLinkInformation.Stub mBinder = new IAziLinkInformation.Stub() {

		/**
		 * Retrieves statistics about the VPN link and returns them to the UI.
		 * @return link statistics
		 */
		public LinkStatistics getStatistics() throws RemoteException {
			if(sLog) Log.v("AziLink", "fwd::getStatistics");
			LinkStatistics ls = new LinkStatistics();
			ls.mBytesRecv = mEngine.getBytesRecv() + mBytesSavedRecv;
			ls.mBytesSent = mEngine.getBytesSent() + mBytesSavedSent;
			ls.mBytesTotal = ls.mBytesRecv + ls.mBytesSent;
			ls.mTcpConnections = mEngine.getTcpSize();
			ls.mUdpConnections = mEngine.getUdpSize();
			if( mActive ) {
				ls.mStatus = getString(R.string.status_active);
			} else {
				ls.mStatus = getString(R.string.status_listen);
			}
			return ls;
		}

		/**
		 * Zeros all the byte counters.
		 */
		public void resetCounters() throws RemoteException {
			mBytesSavedSent = 0;
			mBytesSavedRecv = 0;		
			if(sLog) Log.v("AziLink", "fwd::reset");
			
			SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(ForwardService.this);
			SharedPreferences.Editor ed = pref.edit();
			ed.putLong(getString(R.string.pref_key_saved_bytessent), mBytesSavedSent);
			ed.putLong(getString(R.string.pref_key_saved_bytesrecv), mBytesSavedRecv);
			ed.commit();        
			
			mEngine.resetCounters();
		}

		/**
		 * Dynamically activates the T-Mobile workaround.
		 * @param active whether the workaround is active.
		 */
		public void setTMworkaround(boolean active) throws RemoteException {
			if(sLog) Log.v("AziLink", "fwd::setTM");
			mEngine.setTMobileWorkaround(active);
		}

		/**
		 * Dynamically changes the timeout for the T-Mobile workaround.
		 * @param ms how long to stall connections.
		 */
		public void setTMworkaroundTimeout(int ms) throws RemoteException {
			if(sLog) Log.v("AziLink", "fwd::setTM");
			mEngine.setTMobileWorkaroundTimeout(ms);
		}
		
		/**
		 * Dynamically change whether a VPN link will be terminated when
		 * the connection has been lost.
		 * @param active whether ping timeouts are active.
		 */
		public void setPinger(boolean active) throws RemoteException {
			mEngine.setPinger(active);
		}
	
	};

}
