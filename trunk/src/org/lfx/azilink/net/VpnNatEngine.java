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
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import android.util.Log;

/**
 * Main class which contains everything else.  Handles all the interop between VPN and NIO.
 *  
 * @author Jim Perry
 *
 */
public class VpnNatEngine implements TransferStatistics {
	/** Select worker thread */
	SelectThread mSelectThread = new SelectThread( this );
	/** Interface used to report VPN status changes */
	VpnNatEngineNotify mNotify;
	/** TCP NAT engine */
	TcpEngine mTCP = new TcpEngine( this );
	/** UDP NAT engine */
	UdpEngine mUDP = new UdpEngine( this );
	/** Transfer statistics for bytes sent */
	long mBytesSent = 0;
	/** Transfer statistics for bytes received */
	long mBytesRecv = 0;
	/** NIO link to the VPN (if any) */
	VpnLink mVpnLink;
	/** Maximum possible packet size from VPN (tcp/udp engines have separate limits!) */
	int mPacketSize = 8192;
	/** Queue of all timer events */
	TimerQueue mTimers = new TimerQueue();
	/** Enable debug logging? */
	static final boolean sLog = false;
	/** Enable T-Mobile workaround? */
	boolean mTMobileWorkaround = false;
	/** Enable ping timeouts for the VPN link? */
	boolean mPinger = true;
	/** Timeout for the T-Mobile workaround */
	int mTMobileWorkaroundTimeout = 1000;
	
	public VpnNatEngine( VpnNatEngineNotify notify ) {
		mNotify = notify;		
	}
	
	/**
	 * Dynamically change the T-Mobile workaround state
	 * @param active whether the workaround is active
	 */
	public void setTMobileWorkaround(boolean active) {
		mTMobileWorkaround = active;
	}
	
	/**
	 * Dynamically change the T-Mobile workaround state
	 * @param ms timeout for workaround
	 */
	public void setTMobileWorkaroundTimeout(int ms) {
		mTMobileWorkaroundTimeout = ms;
	}
	
	/**
	 * Dynamically change the ping timeout state (VPN)
	 * @param active whether ping timeouts are active
	 */
	public void setPinger(boolean active) {
		mPinger = active;
	}

	/**
	 * Figure out what IP address to redirect DNS packets to.  By default, we read
	 * net.dns1 to pull the default android DNS server.  If that call fails for some reason,
	 * we just use 4.2.2.2
	 * @return ip address of dns server
	 */
	static public int getDnsIp() {
		String ip = org.lfx.azilink.Reflection.getDNS();
		if( ip == "" ) ip = "4.2.2.2";
		try {
			Inet4Address addr = (Inet4Address) Inet4Address.getByName(ip);
			byte[] v = addr.getAddress();
			int returnv;
			returnv  = ((v[0]&0xFF) << 24);		// would it really have fucking killed them to include unsigned
			returnv |= ((v[1]&0xFF) << 16);
			returnv |= ((v[2]&0xFF) << 8);
			returnv |= (v[3]&0xFF);
			return returnv;			
		} catch (UnknownHostException e) {
			return 0x04020202;		// 4.2.2.2 is a public dns			
		}
	}
	
	/**
	 * Startup the select worker thread
	 * @throws IOException
	 */
	public void start() throws IOException {
		mVpnLink = null;
		mSelectThread.start();
	}
	
	/**
	 * Terminate the select worker thread and destroy all the NAT tables
	 * @throws InterruptedException
	 */
	public void stop() throws InterruptedException {
		mSelectThread.stop();
		mTCP.closeAll();
		mUDP.closeAll();
		mVpnLink = null;
	}
	
	/**
	 * Reset the byte counters
	 */
	public void resetCounters() {
		mBytesSent = 0;
		mBytesRecv = 0;
	}
	
	/**
	 * Get the number of bytes sent since last reset
	 * @return bytes sent
	 */
	public long getBytesSent() {
		return mBytesSent;
	}
	
	/**
	 * Get the number of bytes received since last reset
	 * @return bytes received
	 */
	public long getBytesRecv() {
		return mBytesRecv;		
	}
	
	/**
	 * Get the size of the TCP NAT table
	 * @return TCP entries
	 */
	public int getTcpSize() {
		return mTCP.mNat.size();
	}
	
	/**
	 * Get the size of the UDP NAT table
	 * @return UDP entries
	 */
	public int getUdpSize() {
		return mUDP.mNat.size();
	}
	
	/**
	 * Accept a new VPN link
	 * @param channel socket channel for vpn link
	 */
	void acceptServerLink( ServerSocketChannel channel ) {
		try {
			if( mVpnLink != null ) {
				mVpnLink.close();				
				mVpnLink = null;
			}
			SocketChannel ch = channel.accept();			
			ch.configureBlocking( false );
			ch.socket().setTcpNoDelay(true);			
			mVpnLink = new VpnLink( this, ch );
			mNotify.onLinkEstablished();
		} catch (IOException e) {
		}
	}
	
	/**
	 * Server link has been lost.  Close the link and, if enabled, close all the TCP sockets.
	 * @param link vpn link
	 */
	void lostServerLink( VpnLink link ) {		
		link.close();
		boolean closeAll = mNotify.onLinkLost();
		mVpnLink = null;
		if( closeAll ) {
			mTCP.closeAll();
		}
	}
	
	/**
	 * Transmit an error message to the user
	 * @param error error message
	 */
	void selectError( String error ) {
		mNotify.onError( error );
	}
	
	/**
	 * New incoming data from the VPN.  Transmit it to the correct protocol engine.
	 * @param d single ip packet
	 */
	void vpnRead( byte[] d ) {
		if( d.length < 20 ) {
			if(VpnNatEngine.sLog) Log.v( "AziLink", "Packet under minimum length" );
			return;
		}
		
		ByteBuffer bb = ByteBuffer.wrap( d );
		if( (bb.get(0) & 0xF0) != 0x40 ) {
			if(VpnNatEngine.sLog) Log.v( "AziLink", "Incoming packet not IPv4" );
			return;
		}
		int headerLength = (((int) bb.get(0)) & 0x0F) * 4; 
		if( headerLength < 20 ) {
			if(VpnNatEngine.sLog) Log.v( "AziLink", "Header under minimum length" );
			return;
		}		

		int protocol = ((int) bb.get( 9 )) & 0xFF;
		if( protocol == 6 ) {
			mTCP.readRawPacket( d );
		} else if( protocol == 17 ) {
			mUDP.readRawPacket( d );
		} else if( protocol == 1 ) {
			// send ICMP to UDP (will be rewritten)
			mUDP.readRawPacket(d);
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink", "IP saw unknown protocol " + protocol );
		}
	}
	
	boolean isVpnWriteOk() {
		if( mVpnLink != null ) {
			return !mVpnLink.mPauseOutput;
		} else return false;
	}
	
	void vpnWrite( byte[] d, short len ) {
		if( mVpnLink != null ) {
			mVpnLink.write( d, len );
		}
	}

	public void addBytes(int recv, int sent) {
		mBytesSent += sent;
		mBytesRecv += recv;
	}	
}
