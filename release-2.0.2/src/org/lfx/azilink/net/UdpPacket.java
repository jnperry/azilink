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
 * Represents a single UDP packet
 * 
 * @author Jim Perry
 *
 */
public class UdpPacket {
	/**
	 * Builds an empty packet with preloaded addresses (REVERSED!)
	 * @param nk addresses to preload (reversed)
	 */
	UdpPacket( UdpKey nk ) {
		mRaw = ByteBuffer.allocate( 3000 );
		mUdpOffset = 20;
		mDataOffset = mUdpOffset + 8;
		mPacketLength = mDataOffset;
		
		mRaw.putShort( 0, (short) 0x4500 );	// len=20
		mRaw.putInt( 4, 0 );		// no flags, no fragment, no id
		mRaw.putShort( 8, (short) 0xFF11 );	// 255 TTL, UDP
		mRaw.putInt( 12, nk.mDestIp );		// src
		mRaw.putInt( 16, nk.mSrcIp );		// dest
		mRaw.putShort( mUdpOffset, (short) nk.mDestPort );
		mRaw.putShort( mUdpOffset+2, (short) nk.mSrcPort );		
	}
	
	/**
	 * Complete a UDP packet (fill in length and compute checksums)
	 */
	void complete() {
		mRaw.putShort( 2, (short) mPacketLength );
		mRaw.putShort( 10, (short) 0 );				// zero header checksum for calculation
		mRaw.putShort( mUdpOffset+4, (short) (mPacketLength-mUdpOffset) );
		mRaw.putShort( mUdpOffset+6, (short) 0 );	// zero udp checksum for calculation
		
		byte[] buf = mRaw.array();
		
		// ** IP header **
		{
			int leftSum = 0;
			int rightSum = 0;
			int sum = 0;
			
			int len = mUdpOffset;
			
			for( int i=0 ; i<len ; i+=2 ) {
				leftSum += buf[i] & 0xFF;
				rightSum += buf[i+1] & 0xFF;			
			}
			sum = (leftSum << 8) + rightSum;
			
			while( sum>>>16 != 0 ) {
				sum = (sum & 0xFFFF) + (sum >>> 16);
			}
			sum = ~sum;
			mRaw.putShort( 10, (short) sum );
		}
		
		// *** UDP
		{
			int leftSum = 0;
			int rightSum = 0;
			int sum = 0;
			
			int len = mPacketLength-1;
			
			for( int i=mUdpOffset ; i<len ; i+=2 ) {
				leftSum += buf[i] & 0xFF;
				rightSum += buf[i+1] & 0xFF;			
			}
			if( (mPacketLength&1) != 0) {
				leftSum += buf[ mPacketLength-1 ] & 0xFF;
			}
			sum = (leftSum << 8) + rightSum;
									
			UdpKey nk = getAddresses();
			sum += (nk.mSrcIp>>>16)&0xFFFF;
			sum += (nk.mSrcIp)&0xFFFF;
			sum += (nk.mDestIp>>>16)&0xFFFF;
			sum += (nk.mDestIp)&0xFFFF;
			sum += mPacketLength - mUdpOffset;
			sum += getProtocol();
			while( (sum>>>16) != 0 ) {
				sum = (sum & 0xFFFF) + (sum >>> 16);
			}
			sum = ~sum;
			mRaw.putShort( mUdpOffset+6, (short) sum );
		}
	}		
	
	/**
	 * Import an existing packet (wraps the provided buffer, so don't alter it)
	 * @param pkt packet to import (do not alter until UdpPacket is destroyed)
	 */
	UdpPacket( byte[] pkt ) {
		mRaw = ByteBuffer.wrap( pkt );
		mUdpOffset = (((int) mRaw.get(0)) & 0x0F) * 4;
		mDataOffset = mUdpOffset + 8;			
		mPacketLength = ((int) mRaw.getShort( 2 )) & 0xFFFF;
		if( mPacketLength > pkt.length ) mPacketLength = pkt.length;
	}
	
	/**
	 * Get protocol of packet
	 * @return protocol
	 */
	int getProtocol() {
		return mRaw.get( 9 ) & 0xFF;
	}
	
	/**
	 * Get the addresses used by this packet
	 * @return addresses
	 */
	UdpKey getAddresses() {
		UdpKey nk = new UdpKey();
		nk.mSrcIp = mRaw.getInt( 12 );
		nk.mDestIp = mRaw.getInt( 16 );
		nk.mSrcPort = ((int) mRaw.getShort( mUdpOffset )) & 0xFFFF;
		nk.mDestPort = ((int) mRaw.getShort( mUdpOffset+2 )) & 0xFFFF;
		return nk;
	}
	
	/**
	 * Set the packet payload
	 * 
	 * @param info payload
	 * @param len payload length
	 */
	public void setData( byte[] info, int len ) {
		System.arraycopy( info, 0, mRaw.array(), mDataOffset, len );
		mPacketLength = mDataOffset + len;
	}
		
	/**
	 * Get the payload length
	 * @return payload length
	 */
	public int getDataLength() {
		return mPacketLength - mDataOffset;
	}
	
	/**
	 * Get the packet payload
	 * @return payload
	 */
	public byte[] getData() {
		byte[] dd = new byte[ getDataLength() ];
		System.arraycopy( mRaw.array(), mDataOffset, dd, 0, dd.length );
		return dd;
	}
	
	int mUdpOffset;
	int mDataOffset;
	int mPacketLength;
	ByteBuffer mRaw;
}
