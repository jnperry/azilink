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
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

import android.util.Log;

/**
 * Represents a link to the OpenVPN session.
 * 
 * @author Jim Perry
 *
 */
public class VpnLink extends SocketHandler {
	/** Pointer to the VPN engine */
	VpnNatEngine mEngine;
	/** socket used for vpn communications */
	SocketChannel mSocket;
	/** Input and output buffers */
	ByteBuffer mInput, mOutput;
	/** Timers for ping and ping_expire */
	long mTimerKeyPing, mTimerKeyDie;
	/** Should we accept write() commands? */
	boolean mPauseOutput = false;
	/** Magic packet sequence used for OpenVPN pings */
	private int[] mPingMagick = new int[] { 0x2a, 0x18, 0x7b, 0xf3, 0x64, 0x1e, 
			0xb4, 0xcb, 0x07, 0xed, 0x2d, 0x0a, 0x98, 0x1f, 0xc7, 0x48 };
	/** Magic packet sequence used for OpenVPN configurations */
	private int[] mConfigMagick = new int[] { 0x28, 0x7f, 0x34, 0x6b, 0xd4, 0xef, 0x7a, 0x81,
			  0x2d, 0x56, 0xb8, 0xd3, 0xaf, 0xc5, 0x45, 0x9c };
	
	/** How often should we send OpenVPN pings? (ms) */
	final static int sPingTime = 10000;			
	/** If a ping isn't received within this time limit, then we die */
	final static int sDieTime = 30000;			// 30 seconds

	/**
	 * Transmit the openvpn configuration to the remote host.
	 */
	void respondWithConfig() {
		if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::respondWithConfig");
		String response = "V4,dev-type tun,link-mtu 1502,tun-mtu 1500,proto TCPv4_SERVER,ifconfig 192.168.56.2 192.168.56.1";
		CharBuffer cb = CharBuffer.wrap(response);
		Charset cs = Charset.forName("ISO-8859-1");
		ByteBuffer bb = ByteBuffer.allocate(response.length()*2 + mConfigMagick.length + 1 );
		for( int i=0 ; i < mConfigMagick.length ; i++ ) {
			bb.put( (byte) mConfigMagick[i] );
		}
		bb.put((byte)1);							// Command byte -- 1 = response
		cs.newEncoder().encode(cb, bb, true);		// Add the string
		bb.put((byte)0);							// NULL terminate
		write( bb.array(), (short) bb.position() );
	}
	
	/**
	 * Send an OpenVPN ping packet
	 */
	TimerCallback mPingCallback = new TimerCallback() {
		public void onTimer() {
			if( !mSocket.isConnected() ) return;
			byte[] ping = new byte[ mPingMagick.length ];
			for( int i=0 ; i < mPingMagick.length ; i++ ) {
				ping[i] = (byte) mPingMagick[i];
			}
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::write_ping" );
			setPingTimer();
			write( ping, (short) ping.length );
		}		
	};
	
	/**
	 * Teardown the OpenVPN connection due to ping timeout.
	 */
	TimerCallback mDieCallback = new TimerCallback() {
		public void onTimer() {
			if(mEngine.mPinger) {
				Log.e("AziLink", "Did not receive a ping for 30 seconds from OpenVPN!  Assuming dead link..");
				mEngine.lostServerLink( VpnLink.this );
			} else {
				// Lazy soln: just requeue the die request
				setDieTimer();
			}
		}
	};
	
	/**
	 * Reset the ping timer
	 */
	void setPingTimer() {
		mTimerKeyPing = mEngine.mTimers.changeTimer( mTimerKeyPing, sPingTime, mPingCallback );
	}
	
	/**
	 * Reset the die timer
	 */
	void setDieTimer() {
		mTimerKeyDie = mEngine.mTimers.changeTimer( mTimerKeyDie, sDieTime, mDieCallback );
	}
	
	/**
	 * Construct a new OpenVPN connection
	 * 
	 * @param engine VPN engine
	 * @param ch socket channel
	 * @throws ClosedChannelException
	 */
	VpnLink( VpnNatEngine engine, SelectableChannel ch ) throws ClosedChannelException {
		super( ch );
		mEngine = engine;
		mSocket = (SocketChannel) ch;
		mPauseOutput = false;
		
		mSocket.register( mEngine.mSelectThread.mSelector, SelectionKey.OP_READ, this );
		mInput = ByteBuffer.allocate( mEngine.mPacketSize+100 );
		mOutput = ByteBuffer.allocate( mEngine.mPacketSize+100 );
		
		setDieTimer();
		setPingTimer();
	}
	
	/**
	 * Test whether this packet contains an openvpn magic packet.  Surely there is a better way...
	 * 
	 * @param d packet
	 * @param magick magic sequence
	 * @return whether it's a magic packet
	 */
	private boolean comparePacket( byte[] d, int[] magick ) {
		if( d.length < magick.length ) return false;
		for( int i=0 ; i < magick.length ; i++ ) {
			if( (d[i]&0xFF) != magick[i] ) return false;
		}
		return true;
	}
	
	/**
	 * Read new data from the OpenVPN link.  Extract any packets and transmit them individually.
	 * OpenVPN packet format: [2 byte length] [packet data].
	 * If [packet data] starts with an openvpn magic byte sequence, then handle that separately.
	 * @param k selection key
	 */
	public void onRead( SelectionKey k ) throws IOException {
		if( mSocket.read( mInput ) <= 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::onread lost" );
			mEngine.lostServerLink( this );
			return;
		}
		
		setDieTimer();
		
		mInput.flip();
		mInput.order( ByteOrder.BIG_ENDIAN );
		
		while( mInput.remaining() >= 2 ) {
			mInput.mark();
			// first 2 bytes are the packet length
			int packetLength = ((int) mInput.getShort()) & 0xFFFF;
			if( packetLength > mInput.remaining() ) {
				if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::onread packetlength wrong" );
				mInput.reset();
				break;
			}			
			byte[] packet = new byte[ packetLength ];
			mInput.get( packet );
			if( comparePacket( packet, mPingMagick ) ) {
				if(VpnNatEngine.sLog) Log.v("AziLink", "Ping packet" );
				setDieTimer();
				continue;
			}
			if( comparePacket( packet, mConfigMagick ) ) {				
				if( packet.length == mConfigMagick.length+1 &&
						packet[mConfigMagick.length] == 0 ) {
					// The last byte is the command -- zero is configuration request
					respondWithConfig();
				} else {
					if(VpnNatEngine.sLog) Log.v("AziLink", "Config packet (unknown type)" );
				}
				continue;
			}
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::onread upload packet of length " + packetLength );
			mEngine.vpnRead( packet );
		}
		mInput.compact();
		
		if( mInput.remaining() == 0 ) {
			// And the buffer is full?  This packet is ridiculously long!
			if(VpnNatEngine.sLog) Log.v("AziLink", "Ridiculously long VPN packet was received; killing the connection");
			mEngine.lostServerLink( this );
			return;
		}
	}
	
	/**
	 * Is a write going to be accepted?
	 * 
	 * @return whether we can accept data
	 */
	public boolean isWriteOk() {
		return mOutput.position() == 0;
	}

	/**
	 * Transmit a packet to the host.  If the transmit buffer is full, then the entire packet will be
	 * dropped.  This function will never partially transmit a buffer.
	 * 
	 * @param d packet
	 * @param length length of packet
	 */
	public void write( byte[] d, short length ) {
		if( mPauseOutput ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "write dropping packet in vpnlink" );
			return;
		}
		setPingTimer();
		mOutput.order( ByteOrder.BIG_ENDIAN );
		mOutput.putShort( (short)(length) );
		mOutput.put( d, 0, length );
		mOutput.flip();
		try {
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::write transmit " + length + " packet" );
			if( mSocket.write( mOutput ) < 0 ) {
				mEngine.lostServerLink( this );
				if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::write failed write" );
				return;
			}
		} catch (IOException e) {
			mEngine.lostServerLink( this );
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::write failed write exception" );
			return;
		}
		if( mOutput.remaining() == 0 ) {
			mOutput.clear();
			return;
		}
		if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::write has exceeded buffer -- pausing output" );
		mPauseOutput = true;
		// Kernel buffer is full -> have some remaining data.
		try {
			mSocket.register( mEngine.mSelectThread.mSelector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, this );
		} catch (ClosedChannelException e) {
			mEngine.lostServerLink( this );
		}		
	}
	
	/**
	 * NIO indicates that a write will succeed.  If anything's in the overflow buffer, then transmit it to the host
	 */
	public void onWrite( SelectionKey k ) throws IOException {
		setPingTimer();
		
		if( mSocket.write( mOutput ) < 0 ) {
			mEngine.lostServerLink( this );
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::onwrite lost" );
			return;
		}
		if( mOutput.remaining() == 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::onwrite cancelled write pause" );
			mOutput.clear();
			mPauseOutput = false;
			k.interestOps( SelectionKey.OP_READ );
		}
	}
	
	/**
	 * Kill the VPN link
	 */
	void close() {
		try {
			if(VpnNatEngine.sLog) Log.v("AziLink", "vpnlink::close" );
			mEngine.mTimers.killTimer(mTimerKeyPing, mPingCallback);
			mEngine.mTimers.killTimer(mTimerKeyDie, mDieCallback);
			mChannel.close();
		} catch (IOException e) {			
		}
	}
}
