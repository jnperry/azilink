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

import android.util.Log;

/**
 * Engine driving all active TCP links.
 * 
 * @author Jim Perry
 *
 */
public class TcpEngine implements TcpDriverPacketSink {
	/** HashMap containing all active connections, indexed by ip/port */
	HashMap< TcpKey, TcpDriver > mNat = new HashMap<TcpKey, TcpDriver>();
	/** Pointer to the VPN engine */
	VpnNatEngine mEngine;
	/** Stores addresses of connections that were accepted, so we can automatically accept them again
	 * without waiting for the TMobile timeout.  Entries last 30 seconds.
	 */
	TmAccept mTM = new TmAccept();
	
	TcpEngine( VpnNatEngine e ) {
		mEngine = e;
	}
	
	/**
	 * Close all active TCP connections (via RST, not FIN)
	 */
	public void closeAll() {
		if(VpnNatEngine.sLog) Log.v("AziLink","closeAll");
		synchronized(mEngine.mSelectThread) {
			while( !mNat.isEmpty() ) {
				mNat.values().iterator().next().destroy();					
			}
		}
	}
	
	/**
	 * Parse a new packet received over the VPN.  Figure out which tcp link it belongs to, and dispatch.
	 * @param d tcp packet
	 */
	void readRawPacket( byte[] d ) {
		ByteBuffer bb = ByteBuffer.wrap( d );
		int headerLength = (((int) bb.get(0)) & 0x0F) * 4;
		
		if( d.length < headerLength + 20 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Packet under minimum TCP length" );
			return;
		}
		
		TcpPacket pkt = new TcpPacket( d );
		TcpKey nk = pkt.getAddresses();
		
		TcpDriver te = mNat.get( nk );
		if( te == null && pkt.isConnectRequest() ) {			
			try {
				if(VpnNatEngine.sLog) Log.v("AziLink","Engine::read forming new TCP link");
				TcpToNio cb = new TcpToNio(this, mEngine.mSelectThread.mSelector);
				te = new TcpDriverImpl(cb, mEngine.mTimers, this);
				cb.setDriver(te);
				mNat.put( nk, te );
				te.newPacket(pkt);
			} catch( IOException e ) {}			
		} else if( te != null ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Engine::pass packet");
			te.newPacket(pkt);
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Issuing a reset for an unknown TCP connection" );
			TcpPacket tp = new TcpPacket( nk, pkt.getAck(), pkt.getSeq() + pkt.getDataLength(), 1 );
			tp.setResetFlag();
			tp.complete();
			mEngine.vpnWrite( tp.mRaw.array(), (short) tp.mPacketLength );							
		}
	}

	/**
	 * Callback from TcpDriver indicating that it wants to send a packet to the VPN
	 */
	public void write(TcpPacket pkt) {
		mEngine.vpnWrite( pkt.mRaw.array(), (short) pkt.mPacketLength );
	}
}
