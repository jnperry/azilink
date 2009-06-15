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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;

import android.util.Log;

/**
 * Engine that holds all active UDP links
 * 
 * @author Jim Perry
 *
 */
public class UdpEngine {
	/**
	 * Map of all connections, indexed by src/dest ip/port
	 */
	HashMap< UdpKey, UdpDriver > mNat = new HashMap<UdpKey, UdpDriver>();
	/**
	 * Pointer to the NAT engine
	 */
	VpnNatEngine mEngine;
	
	UdpEngine( VpnNatEngine e ) {
		mEngine = e;
	}
	
	/**
	 * Teardown a specific UDP connection
	 * @param nt connection to remove
	 */
	public void close(UdpDriver nt) {
		try {
			nt.mChannel.close();
		} catch (IOException e) {
		}
		mEngine.mTimers.killTimer(nt.mTimerKey,nt);
		mNat.remove( nt.mAddr );
	}	
	
	/**
	 * Teardown all UDP connections
	 */
	public void closeAll() {
		//if(VpnNatEngine.sLog) Log.v("AziLink", "Close ALL" );
		Iterator<UdpDriver> i = mNat.values().iterator();
		while( i.hasNext() ) {
			UdpDriver u = i.next();
			try {
				u.mChannel.close();
			} catch (IOException e) {
			}
			i.remove();			
		}
	}
	
	/**
	 * Handle a new packet received from the VPN (dispatch to the UdpDriver)
	 * @param d vpn packet
	 */
	void readRawPacket( byte[] d ) {
		ByteBuffer bb = ByteBuffer.wrap( d );
		int headerLength = (((int) bb.get(0)) & 0x0F) * 4;
		
		int protocol = ((int) bb.get( 9 )) & 0xFF;
		boolean isIcmp = protocol == 1;
		
		if( d.length < headerLength + 8 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Packet under minimum UDP length" );
			return;
		}
		
		UdpPacket pkt;
		if( isIcmp ) {
			IcmpPacket ip = new IcmpPacket(d);
			if(ip.getType() != IcmpPacket.TYPE_ICMP_ECHO_REQUEST ||
					ip.getCode() != IcmpPacket.PROTO_ICMP_ECHO_REQUEST) return;
			if(VpnNatEngine.sLog) Log.v("AziLink","Translate ICMP -> UDP");
			UdpKey nk = new UdpKey();
			IcmpKey ik = ip.getAddresses();
			nk.mDestIp = ik.mSrcIp;
			nk.mSrcIp = ik.mDestIp;
			nk.mDestPort = ip.getSequence() ^ ip.getId();
			nk.mSrcPort = 7;
			pkt = new UdpPacket( nk );		// this will reverse the host/port
			pkt.setData(ip.mRaw.array(), ip.mPacketLength);
		} else {
			pkt = new UdpPacket( d );			
		}
		UdpKey nk = pkt.getAddresses();		
		
		UdpDriver te = mNat.get( nk );
		if( te == null ) {			
			try {
				te = new UdpDriver( mEngine, nk, pkt, isIcmp );
				mNat.put( te.mAddr, te );
				te.readRawPacket( pkt );
			} catch( IOException e ) {}			
		} else {
			te.readRawPacket( pkt );
		}
	}
}
