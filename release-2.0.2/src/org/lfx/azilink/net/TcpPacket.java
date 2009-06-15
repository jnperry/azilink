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
 * Represents a single TCP packet.
 * 
 * @author Jim Perry
 *
 */
public class TcpPacket {
	/**
	 * Build a new TCP packet with some preloaded values.
	 * 
	 * @param nk src/dest ip/port (REVERSED!)
	 * @param seq packet sequence number
	 * @param ack packet acknowledge number
	 * @param window window size remaining
	 */
	TcpPacket( TcpKey nk, long seq, long ack, int window ) {
		mRaw = ByteBuffer.allocate( 1500 );
		setBlank(nk,seq,ack,window);
	}
	
	// Reset a packet to be reused
	/**
	 * Reuse an old packet.  Clears out the data and sets new preloaded values.
	 * 
	 * @param nk src/dest ip/port (REVERSED!)
	 * @param seq packet sequence number
	 * @param ack packet acknowledge number
	 * @param window window size remaining	  
	 * 
	 */
	void setBlank( TcpKey nk, long seq, long ack, int window ) {
		mTcpOffset = 20;
		mDataOffset = mTcpOffset + 20;
		mPacketLength = mDataOffset;
		byte[] raw = mRaw.array();
		for(int i=0 ; i<40 ; i++) {
			raw[i] = 0;
		}		
		raw[0] = 0x45;
		raw[8] = (byte) 0xFF;		// ttl
		raw[9] = 0x06;		// tcp proto
		mRaw.putInt( 12, nk.mDestIp );		// src
		mRaw.putInt( 16, nk.mSrcIp );		// dest
		mRaw.putShort( mTcpOffset, (short) nk.mDestPort );
		mRaw.putShort( mTcpOffset+2, (short) nk.mSrcPort );
		mRaw.putInt( mTcpOffset+4, (int) seq );
		mRaw.putInt( mTcpOffset+8, (int) ack );
		raw[20+12] = 0x50;  // data offset
		raw[20+13] = 0x10;  // ack flag
		mRaw.putShort( mTcpOffset+14, (short) window );
	}
	
	/**
	 * Get packet's window size
	 * @return window size
	 */
	int getWindowSize() { 
		return ((int) mRaw.getShort( mTcpOffset+14 )) & 0xFFFF; 
	}
	
	/**
	 * Set the window size
	 * @param sz window size
	 */
	void setWindowSize( int sz ) { 
		mRaw.putShort( mTcpOffset+14, (short) sz );
	}
	
	/** Is this a reset packet? */
	boolean isReset() { return (getFlags() & 4) != 0; }
	/** Is the FIN flag set? */
	boolean isFin() { return (getFlags() & 1) != 0; }
	/** Is the ACK flag set? */
	boolean isAck() { return (getFlags() & 16) != 0; }
	
	/**
	 * Complete a packet and prepare it to be sent.  Fills in the length parameters and computes
	 * the packet checksum.
	 */
	void complete() {
		mRaw.putShort( 2, (short) mPacketLength );
		mRaw.putShort( 10, (short) 0 );				// zero header checksum for calculation
		mRaw.putShort( mTcpOffset+16, (short) 0 );	// zero tcp checksum for calculation
		
		byte[] buf = mRaw.array();
		
		// using the endian translation feature of bytebuffer is a significant timesink under heavy load,
		// so just compute leftSum and rightSum separately.
		
		// ** IP header **
		{
			int leftSum = 0;
			int rightSum = 0;
			int sum = 0;
			
			int len = mTcpOffset;
			
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
		
		// ** TCP **
		{
			int leftSum = 0;
			int rightSum = 0;
			int sum = 0;
			
			int len = mPacketLength-1;
			
			for( int i=mTcpOffset ; i<len ; i+=2 ) {
				leftSum += buf[i] & 0xFF;
				rightSum += buf[i+1] & 0xFF;			
			}
			if( (mPacketLength&1) != 0) {
				leftSum += buf[ mPacketLength-1 ] & 0xFF;
			}
			// src+dest IP address
			for( int i=12 ; i<20 ; i+=2 ) {
				leftSum += buf[i] & 0xFF;
				rightSum += buf[i+1] & 0xFF;			
			}
			// protocol
			rightSum += buf[9] & 0xFF;
			
			// form complete sum
			sum = (leftSum << 8) + rightSum;
			
			// data length too
			sum += mPacketLength - mTcpOffset;
									
			while( (sum>>>16) != 0 ) {
				sum = (sum & 0xFFFF) + (sum >>> 16);
			}
			sum = ~sum;
			mRaw.putShort( mTcpOffset+16, (short) sum );
		}
	}
	
	/**
	 * Import an existing packet
	 * 
	 * @param pkt packet
	 */
	TcpPacket( byte[] pkt ) {
		mRaw = ByteBuffer.wrap( pkt );
		mTcpOffset = (((int) mRaw.get(0)) & 0x0F) * 4;
		mDataOffset = mTcpOffset + ((((int) mRaw.get( mTcpOffset + 12 )) & 0xF0) >> 2);			
		mPacketLength = ((int) mRaw.getShort( 2 )) & 0xFFFF;
		if( mPacketLength > pkt.length ) mPacketLength = pkt.length;
	}
	
	/**
	 * Retrieve packet's protocol
	 * @return protocol
	 */
	int getProtocol() {
		return mRaw.get( 9 ) & 0xFF;
	}
	
	/**
	 * Get the addresses this packet refers to
	 * @return src/dest ip/port
	 */
	TcpKey getAddresses() {
		TcpKey nk = new TcpKey();
		nk.mSrcIp = mRaw.getInt( 12 );
		nk.mDestIp = mRaw.getInt( 16 );
		nk.mSrcPort = ((int) mRaw.getShort( mTcpOffset )) & 0xFFFF;
		nk.mDestPort = ((int) mRaw.getShort( mTcpOffset+2 )) & 0xFFFF;
		return nk;
	}
	
	/**
	 * Set the packet payload (copied)
	 * @param src payload
	 */
	public void setData( ByteBuffer src ) {
		mRaw.position(mDataOffset);		
		int len = Math.min(mRaw.remaining(), src.remaining());
		int oldlimit = src.limit();
		src.limit(src.position()+len);
		mRaw.put(src);
		src.limit(oldlimit);
		mPacketLength = mRaw.position();
	}
	
	/** Set the reset flag */
	public void setResetFlag() {
		byte flag = mRaw.get( mTcpOffset + 13 );
		flag |= 0x04;
		mRaw.put( mTcpOffset+13, flag );
	}
	
	/**
	 * Get payload size
	 * @return payload size
	 */
	public int getDataLength() {
		return mPacketLength - mDataOffset;
	}
	
	/**
	 * Retrieve payload into provided buffer.
	 * @param dest payload
	 */
	public void getData(ByteBuffer dest) {
		int maxlen = Math.min(dest.remaining(), mPacketLength - mDataOffset);
		dest.put(mRaw.array(), mDataOffset, maxlen );		
	}
	
	/** Set FIN flag */
	public void setFinFlag() {
		byte flag = mRaw.get( mTcpOffset + 13 );
		flag |= 0x01;
		mRaw.put( mTcpOffset+13, flag );
	}
	
	/** Set PSH flag */
	public void setPshFlag() {
		byte flag = mRaw.get( mTcpOffset + 13 );
		flag |= 0x08;
		mRaw.put( mTcpOffset+13, flag );
	}
	
	/** Set SYN flag */
	public void setSynFlag() {
		byte flag = mRaw.get( mTcpOffset + 13 );
		flag |= 0x02;
		mRaw.put( mTcpOffset+13, flag );
	}
	
	/** Retrieve flags */
	public int getFlags() {
		return ((int) mRaw.get( mTcpOffset + 13 )) & 0xFF;
	}
	
	/** Is this a connection request?  RST=0, SYN=1, FIN=0 */
	public boolean isConnectRequest() {
		return (getFlags() & 7) == 2;
	}

	/**
	 * Get sequence number
	 * @return sequence number
	 */
	public long getSeq() {
		return ((long) mRaw.getInt( mTcpOffset + 4 )) & 0xFFFFFFFF;
	}
	
	/**
	 * Set sequence number
	 * @param seq sequence number
	 */
	public void setSeq(long seq) {
		mRaw.putInt(mTcpOffset+4, (int) seq);		
	}
	
	/**
	 * Get acknowledgement number
	 * @return ack number
	 */
	public long getAck() {
		return ((long) mRaw.getInt( mTcpOffset + 8 )) & 0xFFFFFFFF;
	}
	
	int mTcpOffset;
	int mDataOffset;
	int mPacketLength;
	ByteBuffer mRaw;
}
