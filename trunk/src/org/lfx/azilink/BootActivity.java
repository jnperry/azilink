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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * When the phone is reboot, restart the service if it was 
 * previously running. 
 */
public class BootActivity extends BroadcastReceiver {
	/**
	 * If the service was active when the phone powered down, restart it after a reboot.
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		if(intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
			SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
			boolean needToStart = pref.getBoolean(context.getString(R.string.pref_key_active), false);
			if(needToStart) {
				context.startService(new Intent(context, ForwardService.class));
			}
		}
	}

}
