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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

import android.util.Log;

/**
 * Driver for a single UDP connection.
 * 
 * @author Jim Perry
 *
 */
public class UdpDriver extends SocketHandler implements TimerCallback {
	/** NIO socket for this link */
	DatagramChannel mChannel;
	/** src/dest ip/port */
	UdpKey mAddr = new UdpKey();
	/** VPN engine */
	VpnNatEngine mEngine;
	/** Timer key for the connection expiration */
	long mTimerKey;
	/** Last received packet. Used to generate ICMP unreachable and for ICMP ping translation. */
	UdpPacket mLastPacket;
	/** Is this an ICMP <-> UDP translated packet? */
	boolean mIcmp;
	/** Statistics reporter */
	TransferStatistics mStats;
	
	/** Time to keep an idle UDP connection in the table (ms) */
	static final int mTimeIdle = 120000;
	/** Time to keep an idle DNS UDP connection in the table (ms) */
	static final int mTimeIdleDNS = 12000;
	/** Time to keep an ICMP<->UDP connection in the table (ms) */
	static final int mTimeIdleIcmp = 12000;
	
	/**
	 * Construct a new UDP link
	 * 
	 * @param engine pointer to the vpn engine
	 * @param nk addresses of both endpoints
	 * @param pkt the first udp packet
	 * @param icmp is this an icmp-in-udp packet?
	 * @throws IOException
	 */
	public UdpDriver(VpnNatEngine engine, UdpKey nk, UdpPacket pkt, boolean icmp) throws IOException {
		super( DatagramChannel.open() );
		mChannel = (DatagramChannel) super.mChannel;
		mEngine = engine;
		mStats = mEngine;
		mChannel.configureBlocking(false);
		mIcmp = icmp;
		mAddr = nk;
	
		int destIp = mAddr.mDestIp;
		if( mAddr.mDestPort == 53 && mAddr.mDestIp == 0xC0A83801 ) {	// 192.168.56.1
			// Redirect 192.168.56.1:53 to the actual dns server
			if(VpnNatEngine.sLog) Log.v("AziLink", "Redirecting DNS packet");
			destIp = VpnNatEngine.getDnsIp();			
		}
		
		byte[] addr = new byte[4];
		addr[0] = (byte)(destIp >> 24);
		addr[1] = (byte)(destIp >> 16);
		addr[2] = (byte)(destIp >> 8);
		addr[3] = (byte)(destIp >> 0);
				
		if(VpnNatEngine.sLog) Log.v("AziLink", "Connect to foreign host (udp) " + InetAddress.getByAddress(addr).getHostAddress() + ":" + nk.mDestPort );		
		mChannel.connect( new InetSocketAddress( InetAddress.getByAddress( addr ), nk.mDestPort ) );
		mChannel.register( mEngine.mSelectThread.mSelector, SelectionKey.OP_READ, this );
		setTimer();
		mLastPacket = pkt;
	}
	
	/**
	 * Restart the teardown timer
	 */
	void setTimer() {
		if( mAddr.mDestPort == 53 ) {
			mTimerKey = mEngine.mTimers.changeTimer( mTimerKey, mTimeIdleDNS, this );
		} else if( mIcmp ) {
			mTimerKey = mEngine.mTimers.changeTimer( mTimerKey, mTimeIdleIcmp, this );
		} else {
			mTimerKey = mEngine.mTimers.changeTimer( mTimerKey, mTimeIdle, this );
		}
	}
	
	/**
	 * New UDP packet received from the VPN
	 * @param pkt udp packet
	 */
	public void readRawPacket(UdpPacket pkt) {
		if(VpnNatEngine.sLog) Log.v("AziLink", "UDP Host->Foreign" );
		mLastPacket = pkt;
		mEngine.mBytesSent += pkt.getDataLength();
		setTimer();
		ByteBuffer dat = ByteBuffer.allocate( pkt.getDataLength() );
		dat.put( pkt.getData(), 0, pkt.getDataLength() );
		dat.flip();
		try {
			int len = mChannel.write( dat );
			if( len > 0 ) {
				mStats.addBytes(0,len);
			}
		} catch (IOException e) {	
		}		
	}		
		
	/**
	 * New data available from the NIO link
	 * @param k selection key
	 */
	@Override public void onRead( SelectionKey k ) {
		if(VpnNatEngine.sLog) Log.v("AziLink", "UDP Foreign->Host " + mAddr.mSrcPort + " and " + mAddr.mDestPort );
		ByteBuffer dat = ByteBuffer.allocate( 1500 );
		
		if(mIcmp) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "UDP convert back to ICMP");
			try {
				int len = mChannel.read(dat);
				if( len > 0 ) mStats.addBytes(len,0);
			} catch (IOException e) {}
			IcmpPacket ip = new IcmpPacket(mLastPacket.getData());
			ip.swapHosts();
			ip.setType(IcmpPacket.TYPE_ICMP_ECHO_REPLY);
			ip.setCode(IcmpPacket.PROTO_ICMP_ECHO_REPLY);
			ip.complete();
			mEngine.vpnWrite( ip.mRaw.array(), (short) ip.mPacketLength );
			return;
		}
		setTimer();
		
		try {
			for( ;; ) {
				dat.clear();
				int rlen = mChannel.read( dat );
				if( rlen < 0 ) {
					if(VpnNatEngine.sLog) Log.v("AziLink", "UDP read len was " + rlen );
					return;
				} else if( rlen == 0 ) {
					if(VpnNatEngine.sLog) Log.v("AziLink", "UDP read len was " + rlen );
					break;
				}
				mStats.addBytes(rlen, 0);
				dat.flip();
				mEngine.mBytesRecv += dat.limit();
				
				if( !mEngine.isVpnWriteOk() ) return;		// just toss the packet
				
				UdpKey addr = mAddr;
				UdpPacket tp = new UdpPacket( addr );
				tp.setData( dat.array(), rlen );
				tp.complete();
				mEngine.vpnWrite( tp.mRaw.array(), (short) tp.mPacketLength );				
			}						
		} catch (IOException e) {
			if(VpnNatEngine.sLog) Log.v("AziLink","UDP exception, rewrite to ICMP");
			IcmpKey addr = new IcmpKey();
			addr.mSrcIp = mAddr.mSrcIp;
			addr.mDestIp = mAddr.mDestIp;
			IcmpPacket ip = new IcmpPacket( addr );
			ip.setType(IcmpPacket.TYPE_ICMP_UNREACHABLE);
			ip.setCode(IcmpPacket.PROTO_ICMP_UNREACHABLE_PORT);
			ip.setData(mLastPacket.mRaw.array(), mLastPacket.mPacketLength);
			ip.complete();
			mEngine.vpnWrite( ip.mRaw.array(), (short) ip.mPacketLength );
		}
	}

	/**
	 * Timer to teardown idle UDP links 
	 */
	public void onTimer() {
		if(VpnNatEngine.sLog) Log.v("AziLink", "UDP timeout" );
		mEngine.mUDP.close( this );		
	}
}

