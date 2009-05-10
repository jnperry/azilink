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

import java.nio.ByteBuffer;

/**
 * Represents a single ICMP packet.
 * 
 * @author Jim Perry
 *
 */
public class IcmpPacket {
	/** Create an empty packet with provided IP addresses.  Since this is used to create a reply packet,
	 * the src and dest addresses are reversed during construction.
	 * 
	 * @param nk src/dest addresses (inverted)
	 */
	IcmpPacket( IcmpKey nk ) {
		mRaw = ByteBuffer.allocate( 1500 );
		mIcmpOffset = 20;
		mDataOffset = mIcmpOffset + 8;
		mPacketLength = mDataOffset;
		
		mRaw.putShort( 0, (short) 0x4500 );	// len=20
		mRaw.putInt( 4, 0 );		// no flags, no fragment, no id
		mRaw.putShort( 8, (short) 0xFF01 );	// 255 TTL, ICMP
		mRaw.putInt( 12, nk.mDestIp );		// src
		mRaw.putInt( 16, nk.mSrcIp );		// dest
	}
	
	/** Swap src/dest IP addresses */
	void swapHosts() {
		int src = mRaw.getInt(12);
		mRaw.putInt(12, mRaw.getInt(16));
		mRaw.putInt(16, src);
	}
	
	/** Prepare to send packet.  Store size and compute checksums. */
	void complete() {
		mRaw.putShort( 2, (short) mPacketLength );
		mRaw.putShort( 10, (short) 0 );				// zero header checksum for calculation
		mRaw.putShort( mIcmpOffset+2, (short) 0 );	// zero udp checksum for calculation
		
		// ** IP header **
		int sum = 0;
		for( int i=0 ; i<mIcmpOffset ; i+=2 ) {
			sum += ((int) mRaw.getShort( i )) & 0xFFFF;
		}
		while( sum>>>16 != 0 ) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		sum = ~sum;
		mRaw.putShort( 10, (short) sum );
		
		// ** ICMP **
		sum = 0;
		// -1 is so we don't scan a final odd byte
		for( int i=mIcmpOffset ; i<mPacketLength-1 ; i+=2 ) {
			sum += ((int) mRaw.getShort( i )) & 0xFFFF;
		}
		if( mPacketLength%2 != 0 ) {
			int val = ((int) mRaw.get( mPacketLength-1 ))&0xFF;
			sum += (val << 8);
		}
		while( (sum>>>16) != 0 ) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		sum = ~sum;
		mRaw.putShort( mIcmpOffset+2, (short) sum );
	}
	
	/** Load a provided ICMP packet.  Do not alter the passed array until the IcmpPacket is destroyed!
	 * 
	 * @param pkt ICMP source packet
	 */
	IcmpPacket( byte[] pkt ) {
		mRaw = ByteBuffer.wrap( pkt );
		mIcmpOffset = (((int) mRaw.get(0)) & 0x0F) * 4;
		mDataOffset = mIcmpOffset + 8;			
		mPacketLength = ((int) mRaw.getShort( 2 )) & 0xFFFF;
		if( mPacketLength > pkt.length ) mPacketLength = pkt.length;
	}
	
	/** Returns the ICMP protocol 
	 * @return protocol
	 */
	int getProtocol() {
		return mRaw.get( 9 ) & 0xFF;
	}
	
	/** 
	 * Retrieve the IP addresses this packet refers to.
	 * @return src/dest addresses
	 */
	IcmpKey getAddresses() {
		IcmpKey nk = new IcmpKey();
		nk.mSrcIp = mRaw.getInt( 12 );
		nk.mDestIp = mRaw.getInt( 16 );
		return nk;
	}
	
	/**
	 * Set the payload of this ICMP packet.
	 * 
	 * @param info payload for packet
	 * @param len length of payload (if less than the array size)
	 */
	public void setData( byte[] info, int len ) {
		int maxlen = Math.min(len, 1500-mDataOffset);
		System.arraycopy( info, 0, mRaw.array(), mDataOffset, maxlen );
		mPacketLength = mDataOffset + maxlen;
	}

	/**
	 * Get the length of the payload.
	 * @return payload length
	 */
	public int getDataLength() {
		return mPacketLength - mDataOffset;
	}
	
	/**
	 * Get a copy of the packet's payload (can be modified).
	 * @return payload
	 */
	public byte[] getData() {
		byte[] dd = new byte[ getDataLength() ];
		System.arraycopy( mRaw.array(), mDataOffset, dd, 0, dd.length );
		return dd;
	}
	
	/**
	 * Set the ICMP type.
	 * @param v ICMP type
	 */
	public void setType(int v) {
		mRaw.put(mIcmpOffset, (byte)v);		
	}
	
	/**
	 * Set the ICMP code.
	 * @param v ICMP code
	 */
	public void setCode(int v) {
		mRaw.put(mIcmpOffset+1, (byte)v);
	}
	
	/**
	 * Set the ICMP ID
	 * @param v ICMP ID
	 */
	public void setId( int v) {
		mRaw.putShort(mIcmpOffset+4, (short)v);
	}
	
	/**
	 * Set the packet sequence number.
	 * @param v sequence number
	 */
	public void setSequence( int v ) {
		mRaw.putShort(mIcmpOffset+6, (short)v);
	}
	
	/**
	 * Get the ICMP type.
	 * @return icmp type
	 */
	public int getType() {
		return mRaw.get(mIcmpOffset) & 0xFF;
	}
	
	/**
	 * Get the ICMP code
	 * @return icmp code
	 */
	public int getCode() {
		return mRaw.get(mIcmpOffset+1) & 0xFF;
	}
	
	/**
	 * Get the ICMP ID
	 * @return icmp id
	 */
	public int getId() {
		return mRaw.getShort(mIcmpOffset+4) & 0xFFFF;
	}
	
	/**
	 * Get the packet sequence number.
	 * @return packet sequence number
	 */
	public int getSequence() {
		return mRaw.getShort(mIcmpOffset+6) & 0xFFFF;
	}
	
	static final int TYPE_ICMP_ECHO_REPLY = 0;
	static final int PROTO_ICMP_ECHO_REPLY = 0;
	
	static final int TYPE_ICMP_UNREACHABLE = 3;
	static final int PROTO_ICMP_UNREACHABLE_PORT = 3;

	static final int TYPE_ICMP_ECHO_REQUEST = 8;
	static final int PROTO_ICMP_ECHO_REQUEST = 0;
	
	/** Offset to the ICMP header */
	int mIcmpOffset;
	/** Offset to the payload */
	int mDataOffset;
	/** Total packet length */
	int mPacketLength;
	/** Buffer containing the raw packet */
	ByteBuffer mRaw;
}
