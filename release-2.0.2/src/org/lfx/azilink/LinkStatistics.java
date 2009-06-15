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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Passes link statistics from the service module to the UI module.  Parcelable support
 * is required since the UI and service are in different processes.
 * 
 * @author Jim Perry
 *
 */
public final class LinkStatistics implements Parcelable {
	public long mBytesRecv = 0;
	public long mBytesSent = 0;
	public long mBytesTotal = 0;
	public long mTcpConnections = 0;
	public long mUdpConnections = 0;
	public String mStatus = "";

	public int describeContents() {
		return 0;
	}
	
	public static final Parcelable.Creator<LinkStatistics> CREATOR = 
			new Parcelable.Creator<LinkStatistics>() {
		
		public LinkStatistics createFromParcel(Parcel in) {
			return new LinkStatistics(in);
		}
		
		public LinkStatistics[] newArray(int size) {
			return new LinkStatistics[size];
		}
	};
	
	private LinkStatistics(Parcel in) {
		mBytesRecv = in.readLong();
		mBytesSent = in.readLong();
		mBytesTotal = in.readLong();
		mTcpConnections = in.readLong();
		mUdpConnections = in.readLong();
		mStatus = in.readString();
	}

	public LinkStatistics() {
	}

	public void writeToParcel(Parcel out, int flags) {
		out.writeLong(mBytesRecv);
		out.writeLong(mBytesSent);
		out.writeLong(mBytesTotal);
		out.writeLong(mTcpConnections);
		out.writeLong(mUdpConnections);
		out.writeString(mStatus);
	}

}
