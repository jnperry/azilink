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

/**
 * Key for TCP links; holds src/dest IP/port. Used to look up a connection in the hash table.
 * 
 * @author Jim Perry
 *
 */
public class TcpKey {
	@Override public boolean equals(Object k) {
		if( this == k ) return true;
		if( !(k instanceof TcpKey) ) return false;
		TcpKey rk = (TcpKey) k;
		return mSrcIp==rk.mSrcIp && mDestIp==rk.mDestIp && mSrcPort==rk.mSrcPort && mDestPort==rk.mDestPort;
	}
	@Override public int hashCode() {
		return ((mSrcPort&0xFFFF)<<16) | (mDestPort&0xFFFF);
	}
	int mSrcIp = 0;
	int mDestIp = 0;
	int mSrcPort= 0;
	int mDestPort = 0;
}
