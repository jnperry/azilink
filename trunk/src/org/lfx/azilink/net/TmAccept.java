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

package org.lfx.azilink.net;

import java.net.InetSocketAddress;
import java.util.HashMap;

import android.util.Log;

/**
 * Holds IP addresses that had recent successful connection (for the T-Mobile workaround).
 * TODO: entries aren't always removed from the list, so it could eventually grow to be very big. 
 * 
 * @author Jim Perry
 *
 */
public class TmAccept {
	/** Map of all stored addresses */
	HashMap<InetSocketAddress, Long> mTable = new HashMap<InetSocketAddress, Long>();
	
	/**
	 * Check whether a connection should bypass the TMobile workaround.
	 * 
	 * @param key address for this link
	 * @return whether we should bypass the TMobile workaround
	 */
	boolean check(InetSocketAddress key) {
		Long when = mTable.get(key);		
		
		if( when == null ) {
			Log.v("AziLink", key.toString() + " is not in the tm table");
			return false;
		}
		
		long now = System.currentTimeMillis();
		
		if( when < now ) {
			Log.v("AziLink", key.toString() + " is EXPIRE");
			mTable.remove(key);
			return false;
		}
		Log.v("AziLink", key.toString() + " is in table");
		mTable.put(key,now + 30000);
		return true;
	};
	
	/**
	 * Add a key to the timer table.
	 * @param key ip addresses
	 */
	void mark(InetSocketAddress key) {
		Log.v("AziLink", key.toString() + " add to table");
		long now = System.currentTimeMillis() + 30000;		// key lives for 30 seconds
		mTable.put(key,now);
	}
}
