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

import java.text.DecimalFormat;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.util.Log;

/**
 * Implements the main UI.  PreferenceActive handles most of the actual work.
 * @author Jim Perry
 *
 */
public class MainActivity extends PreferenceActivity {
	/** Is the service active? */
	private CheckBoxPreference mActive;
	/** Status of the service, for example "Waiting for connection". */
	private Preference mStatus;
	/** # bytes received since counter reset */
	private Preference mBytesRecv;
	/** # bytes sent since counter reset */
	private Preference mBytesSent;
	/** # bytes total since counter reset */
	private Preference mBytesTotal;
	/** # of TCP connections in the NAT table */
	private Preference mTcpConnections;
	/** # of entries in the NAT table */
	private Preference mNatSize;
	/** Timeout for the T-Mobile workaround (ms). */
	private EditTextPreference mTM;
	/** Formatting for all the byte counters */
	private DecimalFormat mFormat = new DecimalFormat("###,###,###,###,###");
	/** Debug logging active? */
	private static boolean sLog = false;
	
	/** Interface to the NAT process */
	IAziLinkInformation mService = null;
	/** How often should we update the link statistics? */
	private static final int sUpdatePeriod = 5000;
	
	/**
	 * Setup the basic UI.
	 */
	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(sLog) Log.v("AziLink","app::oncreate");
                
        addPreferencesFromResource(R.xml.preferences);
       
        // Get convenience pointers to all the important preferences.
        mActive = (CheckBoxPreference) findPreference(getString(R.string.pref_key_active));
        mStatus 		= findPreference(getString(R.string.pref_key_status));
        mBytesRecv 		= findPreference(getString(R.string.pref_key_bytesrecv));
        mBytesSent 		= findPreference(getString(R.string.pref_key_bytessent));
        mBytesTotal 	= findPreference(getString(R.string.pref_key_bytestotal));
        mTcpConnections = findPreference(getString(R.string.pref_key_tcpconn));
        mNatSize 		= findPreference(getString(R.string.pref_key_natsize));
        mTM = (EditTextPreference) findPreference(getString(R.string.pref_key_tmobile_ms));
                
        // Activate/deactivate service
        mActive.setOnPreferenceChangeListener(mActiveListen);
        
        // Reset the statistics
        Preference resetStats = findPreference(getString(R.string.pref_key_reset));        
		resetStats.setOnPreferenceClickListener(mResetHandler);
		
		// About dialog
		Preference about = findPreference(getString(R.string.pref_key_about));
		about.setOnPreferenceClickListener(mAboutHandler);
	
		// T-Mobile workarounds
		findPreference(getString(R.string.pref_key_tmobile)).setOnPreferenceChangeListener(mChangeTM);
		mTM.setOnPreferenceChangeListener(mChangeTMtimeout);
		mTM.setSummary(mTM.getText());
		
		findPreference(getString(R.string.pref_key_ping)).setOnPreferenceChangeListener(mChangePinger);
    }
	
	/**
	 * Bind to the NAT service and begin periodically updating transfer statistics.
	 */
	@Override
	protected void onStart() {
		if(sLog) Log.v("AziLink","app::onstart");
		super.onStart();
		bindService( new Intent(this, ForwardService.class), mConnection, 0 );
		mUpdateCallback.run();		
	}
	
	/**
	 * Disconnect from the NAT service and stop updating transfer statistics.
	 */
	@Override
	protected void onStop() {
		if(sLog) Log.v("AziLink","app::onstop");
		mHandler.removeCallbacks(mUpdateCallback);
		unbindService(mConnection);
		super.onStop();
	}
		
	/**
	 * Connection to the NAT service.
	 */
	ServiceConnection mConnection = new ServiceConnection() {
		/**
		 * Service connected, so start periodically updating transfer statistics.
		 */
		public void onServiceConnected(ComponentName arg0, IBinder arg1) {
			if(sLog) Log.v("AziLink","app::serviceConnect");
			mService = IAziLinkInformation.Stub.asInterface(arg1);
			mActive.setChecked(true);
			mUpdateCallback.run();
		}

		/**
		 * Server disconnected, so stop periodically updating transfer statistics.
		 */
		public void onServiceDisconnected(ComponentName arg0) {
			if(sLog) Log.v("AziLink","app::servicedc");
			mService = null;
			mActive.setChecked(false);
			mUpdateCallback.run();
		}
	};
	
	/**
	 * Start/kill the NAT service depending upon whether the user just checked the active box. 
	 */
	OnPreferenceChangeListener mActiveListen = new Preference.OnPreferenceChangeListener() {
		public boolean onPreferenceChange(Preference preference, Object newValue) {
			Boolean active = (Boolean) newValue;
			if( !active ) {
				if(sLog) Log.v("AziLink","app::active going off (stop)");
				unbindService(mConnection);
				stopService(new Intent(MainActivity.this, ForwardService.class));
				mConnection.onServiceDisconnected(null);
				bindService( new Intent(MainActivity.this, ForwardService.class), mConnection, 0 );
			} else {
				if(sLog) Log.v("AziLink","app::active going on");
				startService(new Intent(MainActivity.this, ForwardService.class));
			}
			return true;
		}
	};
	
	/**
	 * Reset the byte counters when the user hits the reset button.
	 */
	OnPreferenceClickListener mResetHandler = new OnPreferenceClickListener() {
		public boolean onPreferenceClick(Preference preference) {
			if(mService != null) {
				try {
					mService.resetCounters();
				} catch (RemoteException e) {}
			} else {
				SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
				SharedPreferences.Editor ed = pref.edit();
				ed.putLong(getString(R.string.pref_key_saved_bytessent), 0);
				ed.putLong(getString(R.string.pref_key_saved_bytesrecv), 0);
				ed.commit();
			}
			mUpdateCallback.run();
			return true;
		}		
	};

	/**
	 * Display an about dialog when the about button is pressed.
	 */
	OnPreferenceClickListener mAboutHandler = new OnPreferenceClickListener() {
		public boolean onPreferenceClick(Preference preference) {
			startActivity( new Intent(MainActivity.this, AboutActivity.class) );
			return true;
		}		
	};
	
	/**
	 * Used to periodically update the transfer statistics.
	 */
	private final Handler mHandler = new Handler();
	
	/**
	 * Update the transfer statistics.
	 */
	private final Runnable mUpdateCallback = new Runnable() {
		public void run() {			
			// Update all the link statistics!
			LinkStatistics ls;
			if(mService != null) {
				try {
					ls = mService.getStatistics();
				} catch (RemoteException e) {
					ls = new LinkStatistics();
				}				
			} else {
				ls = new LinkStatistics();			
				
				// This is not called regularly, so it's okay to be a little slow
				SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
		        ls.mBytesSent = pref.getLong(getString(R.string.pref_key_saved_bytessent), 0);
		        ls.mBytesRecv = pref.getLong(getString(R.string.pref_key_saved_bytesrecv), 0);
			}
			
			if( ls.mStatus.length() == 0 ) {
				mStatus.setSummary(getString(R.string.status_unknown));
			} else {
				mStatus.setSummary(ls.mStatus);
			}
			mBytesRecv.setSummary(mFormat.format(ls.mBytesRecv));
			mBytesSent.setSummary(mFormat.format(ls.mBytesSent));
			mBytesTotal.setSummary(mFormat.format(ls.mBytesRecv + ls.mBytesSent));
			mTcpConnections.setSummary(mFormat.format(ls.mTcpConnections));
			mNatSize.setSummary(mFormat.format(ls.mTcpConnections + ls.mUdpConnections));
			
			if(mService != null) {
				mHandler.removeCallbacks(this);
				mHandler.postDelayed(this, sUpdatePeriod);
				if(sLog) Log.v("AziLink","service is not null; callback");
			} else {
				if(sLog) Log.v("AziLink","service is null; no callback");
			}
		}
	};
	
	/**
	 * When the T-Mobile workaround option is changed, immediately notify the NAT service.
	 */
	private OnPreferenceChangeListener mChangeTM = new OnPreferenceChangeListener() {
		public boolean onPreferenceChange(Preference preference, Object newValue) {
			if(mService != null) {
				try {
					mService.setTMworkaround((Boolean) newValue);
				} catch (RemoteException e) {}
			}
			return true;
		}		
	};
	
	/**
	 * When the NAT ping option is changed, immediately notify the NAT service.
	 */
	private OnPreferenceChangeListener mChangePinger = new OnPreferenceChangeListener() {
		public boolean onPreferenceChange(Preference preference, Object newValue) {
			if(mService != null) {
				try {
					mService.setPinger((Boolean) newValue);
				} catch (RemoteException e) {}
			}
			return true;
		}		
	};
	
	/**
	 * When the T-Mobile workaround option is changed, immediately notify the NAT service.
	 */
	private OnPreferenceChangeListener mChangeTMtimeout = new OnPreferenceChangeListener() {
		public boolean onPreferenceChange(Preference preference, Object newValue) {
			if(mService != null) {
				try {
					mService.setTMworkaroundTimeout(Integer.parseInt((String) newValue));
				} catch (RemoteException e) {}
			}
			mTM.setSummary((String)newValue);
			return true;
		}		
	};
}
