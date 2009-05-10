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
import java.util.BitSet;

import android.util.Log;

/**
 * Represents a single TCP connection.  This class translates between a byte stream from the VPN (representing
 * raw packets) and a byte stream over a socket (representing the actual data stream).
 * 
 * @author Jim Perry
 *
 */
public class TcpDriverImpl implements TcpDriver {
	/** src/dest IP/port for this connection */
	TcpKey mAddr;
	/** pointer to callbacks (see TcpToNio) */
	TcpDriverCallback mCallback;
	/** Timer key used for TCP retransmits */
	long mTimerRetransmitKey;
	/** Timer key used to destroy this link */
	long mTimerDestroyKey;
	/** Number of retransmissions since last ACK */
	int mRetries;				
	/** Timer queue we can add our timeouts into */
	TimerQueue mTimer;
	/** Callback used to send packets through the VPN */
	TcpDriverPacketSink mHost;
	
	// Input buffer:
	/** Sequence # that buffer starts at (this is the only absolute #) */
	long mInSeq = 0;			
	/** Sequence # of FIN packet (-2 if none seen yet) (relative) */
	int mInFinSeq = -2;			 
	/** Is the first packet in the buffer a SYN packet? (data will be ignored) */
	boolean mInSyn = false;		
	/** Partially assembled buffer for the TCP stream */
	ByteBuffer mInBuffer;		
	/** Bitmap indicating which bytes of mInBuffer are currently valid */
	BitSet mInValid;		
	
	// Output buffer:
	// Might need to add FIN to buffer when compacting!
	/** Sequence # that buffer starts at (only absolute #) */
	long mOutSeq = 0;			
	/** The last incoming packet that we acknowledged.  Used to detect some resets */
	long mOutSeqLastAck = 0;	 
	/** Sequence # of FIN packet (-2 if none seen yet) (relative) */
	int mOutFinSeq = -2;		
	/** Is the first packet in the buffer a SYN packet? (data will be ignored) */
	boolean mOutSyn = false;
	/** Next byte to transmit */
	int mOutNextXmit=0;			 
	/** Output data (limit() is set by the host's advertised window size) */
	ByteBuffer mOutBuffer;
	
	// Configuration:
	/** Advertised window size over TCP segment */
	static final int mWindowSize = 32*1024;		 
	/** Time between retransmissions */
	static final int mTimeRetransmit = 2000;	 
	/** Maximum number of retries */
	static final int mMaxRetries = 6;			 
	/** Max time to try to connect to foreign host (repeated SYN will restart timer) */
	static final int mTimeConnect = 20000;		
	/** Max time in established state with no communication before delete from NAT table */
	static final int mTimeIdle = 1800000;		
	/** How long after close to keep the link around */
	static final int mDeadTime = 5000;		
	
	/** Is bind complete and are sequence numbers synchronized? */
	boolean mBindComplete = false;	
	/** Has bind been started? */
	boolean mBindStarted = false;		
	
	/**
	 * Construct a new TCP connection.
	 * 
	 * @param callback TcpToNio pointer
	 * @param timer select thread's timer queue
	 * @param host where to send VPN packets
	 */
	TcpDriverImpl(TcpDriverCallback callback, TimerQueue timer, TcpDriverPacketSink host) {
		mCallback = callback;
		mTimer = timer;
		mInBuffer = ByteBuffer.allocate(mWindowSize);
		mInValid = new BitSet(mWindowSize);
		mOutBuffer = ByteBuffer.allocate(mWindowSize);
		mHost = host;
	}

	/**
	 * Cleanly close the connection.  This will not be immediate, since the TCP buffer will need
	 * to be flushed and the FIN handshake will need to occur.
	 */
	public void close() {
		if( !mBindComplete ) destroy();
		if( mOutFinSeq == -2 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::close" );
			mOutFinSeq = mOutBuffer.position();
			if( mOutBuffer.hasRemaining() ) mOutBuffer.put((byte)0);
			xmit();
		}
	}

	/**
	 * Immediately teardown the link.  If a FIN hasn't occured yet, then a RST will be immediately issued.
	 */
	public void destroy() {
		if(mInFinSeq != -1 || mOutFinSeq != -1 || getMaxInLength() != 0 || mOutBuffer.position() != 0 ) {
			// close was not clean, so transmit a RST packet
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::destroy (reset mode)" );
			TcpPacket tp = makePacket();
			tp.setResetFlag();
			tp.complete();
			mHost.write(tp);
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::destroy (no reset packet)" );
		}
		mTimer.killTimer(mTimerDestroyKey, mTimerDestroyCallback);
		mTimer.killTimer(mTimerRetransmitKey, mTimerRetransmitCallback);
		mCallback.onDestroy();
	}

	public int getReadAvailableSize() {
		if( mInFinSeq == -1 ) return 0;
		if( mInSyn ) return 0;
		
		int len = getMaxInLength();
		if( mInFinSeq != -2 && len >= mInFinSeq ) {
			return mInFinSeq-1;
		}
		return len;
	}

	public int getWriteAvailableSize() {
		if( mOutSyn ) return 0;
		if( mOutFinSeq != -2 ) return 0;
		if( !mBindComplete ) return 0;
		return mOutBuffer.remaining();		
	}

	/**
	 * New TCP packet received from the VPN link.
	 * @param pkt the received tcp packet
	 */
	public void newPacket(TcpPacket pkt) {
		try {
			if(!mBindComplete) {
				// We're still in the process of connecting.  The only valid packet in this state is
				// another SYN packet (a retransmission).
				if(!pkt.isConnectRequest()) {
					if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::newPacket received non-SYN while in bind mode" );
					destroy();		// Until bind is complete, only SYN is valid
				}
				if( !mBindStarted ) {
					// This is the first SYN packet, so begin a new connection.
					if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::newPacket - begin BIND" );
					mAddr = pkt.getAddresses();
					
					int destIp = mAddr.mDestIp;
					
					if( mAddr.mDestPort == 53 && mAddr.mDestIp == 0xC0A83801 ) {	// 0xC0A83801 = 192.168.56.1
						// Redirect 192.168.56.1:53 to the actual dns server
						if(VpnNatEngine.sLog) Log.v("AziLink", "Redirecting DNS TCP link");
						destIp = VpnNatEngine.getDnsIp();						
					}
					
					byte[] addr = new byte[4];
					addr[0] = (byte)(destIp >> 24);
					addr[1] = (byte)(destIp >> 16);
					addr[2] = (byte)(destIp >> 8);
					addr[3] = (byte)(destIp >> 0);
					
					mBindStarted = true;
					mInSeq = pkt.getSeq();
					mInBuffer.put(0, (byte) 0);
					mInValid.set(0);
					mInSyn = true;
					setDestroyTimer(mTimeConnect);
					// Ask NIO to begin connect()
					mCallback.onBeginBind(new InetSocketAddress(InetAddress.getByAddress( addr ), mAddr.mDestPort));					
				} else {
					// The host retransmitted the SYN because we're taking too long.  Don't need to do anything.
					if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::newPacket - renew BIND" );
					// Update timer
					setDestroyTimer(mTimeConnect);
				}
				return;
			}
			// Packet is for an established connection.
			
			// Check whether the packet is within the expected window
			int seq = (int)(pkt.getSeq() - mInSeq);
			// Accept seq == limit because they're valid ACK packets
			if( seq < 0 || seq > mInBuffer.limit() ) {
				// Sequence is out of bounds.  Send an ACK packet with what we expect.
				// However, a RST is acceptable if SEQ is equal to whatever our last acknowledgement was.
				if(pkt.isReset() && pkt.getSeq() == mOutSeqLastAck ) {
					if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet RESET");
					destroy();
					return;
				}
				
				if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::packet Seq out of bounds - saw " + seq + " with limit " + mInBuffer.limit() );
				TcpPacket tp = makePacket();
				tp.complete();
				mHost.write(tp);
				return;
			}
			// Copy as much data as will fit in the window
			int len = Math.min(pkt.getDataLength(), mInBuffer.limit() - seq);
			boolean newData = false;
			if( len > 0 ) {
				mInBuffer.position(seq);
				pkt.getData(mInBuffer);
				mInValid.set(seq, seq+len);
				if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::packet imports " + len + " bytes of data from host");
				newData = true;
				setDestroyTimer(mTimeIdle);
			}
			if(pkt.isReset() ) {
				if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet RESET");
				destroy();
				return;
			}
			
			boolean wasFull = !mOutBuffer.hasRemaining();
			
			// Process the ACK portion
			if(pkt.isAck()) {
				int ack = (int)(pkt.getAck() - mOutSeq);				
				if( ack > 0 && ack <= mOutBuffer.position() ) {
					// ACK is reasonable, so eliminate whatever data it refers to
					if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::Packet before seq=" + mOutSeq + ", pos=" + mOutBuffer.position() + ", fin=" + mOutFinSeq + ",ACK=" + ack );
					if( mOutSyn ) wasFull = true;
					mOutSyn = false;
					mOutSeq = (mOutSeq + ack) & 0xFFFFFFFF;
					mOutBuffer.flip();
					mOutBuffer.position(ack);
					mOutBuffer.compact();					
					mOutNextXmit -= ack;
					mOutNextXmit = Math.min( Math.max( mOutNextXmit, 0 ), mOutBuffer.position() );
					if( mOutFinSeq != -2 ) mOutFinSeq -= ack;
					if( mOutBuffer.hasRemaining() && mOutBuffer.position() == mOutFinSeq ) {
						// close() couldn't fit the FIN into the buffer, so do it now
						mOutBuffer.put((byte)0);
					}
					if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::Packet after seq=" + mOutSeq + ", pos=" + mOutBuffer.position() + ", fin=" + mOutFinSeq );
					
					// Restart timer
					mRetries = 0;
					if( mOutBuffer.position() != 0 ) {						
						setRetransmitTimer(mTimeRetransmit);
					} else {
						setRetransmitTimer(0);
					}
				} else if( ack == 0 ) {
					if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::Packet ack does not advance");
				} else {
					if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::Packet rejected ack seq=" + mOutSeq + ", pos=" + mOutBuffer.position() + ", fin=" + mOutFinSeq + ",ACK=" + ack );
				}
			} else {
				if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::Packet has no ack flag");
			}
			// If it's a FIN packet, process it
			if(pkt.isFin()) {
				int fseq = seq + pkt.getDataLength();
				if( fseq < mInBuffer.limit() ) {
					if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet recording FIN at relative sequence " + fseq );
					mInFinSeq = seq + pkt.getDataLength();
					mInValid.set(mInFinSeq);
				} else {
					if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet has incoming FIN but no buffer room" );
				}
			}
			// How do we dispatch the FIN flag?  The callbacks will only pick it up
			// if they're still active
			if( mInFinSeq == 0 ) {
				if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet will act on the FIN immediately");
				mInFinSeq = -1;
				mInValid.clear();
				mInSeq = (mInSeq+1) & 0xFFFFFFFF;
				
				// Send an acknowledge packet
				TcpPacket tp = makePacket();
				tp.complete();
				mHost.write(tp);
				
				if( mOutFinSeq == -2 ) {
					if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet has no output FIN, so calling onClosed");
					mCallback.onClosed();
					return;
				} else {
					if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::packet output FIN is at " + mOutFinSeq);
				}
			}

			// Complete any FIN business
			if( mOutFinSeq == -1 && mInFinSeq == -1 ) {
				if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::Packet detects FIN sequence complete");
				setRetransmitTimer(0);
				setDestroyTimer(mDeadTime);
				return;
			}
			if( mOutFinSeq == 0 ) {
				if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::Packet completing the sending FIN" );
				TcpPacket tp = makePacket();
				tp.setFinFlag();
				tp.complete();
				mHost.write(tp);
			}

			// Process the window size information
			int ws = pkt.getWindowSize();
			mOutBuffer.limit(Math.min(ws, mOutBuffer.capacity()));
			if( mOutFinSeq >= mOutBuffer.limit() ) mOutFinSeq = -2;
			
			if( newData ) {
				mCallback.onNewDataAvailable();
			}
			if( wasFull && mOutBuffer.hasRemaining() && mOutFinSeq == -2 ) {
				mCallback.onRequestMoreData();
			}
			
		} catch(IOException err) {
			if(VpnNatEngine.sLog) Log.v("AziLink","IO exception in newPacket");
			destroy();
		}
	}
	
	int getMaxInLength() {
		int len = mInValid.nextClearBit(0);
		if( len == -1 ) return mInValid.size();
		return len;
	}
	
	/**
	 * Construct a new packet for transmission to the host. 
	 * @return a new packet with most parameters already set
	 */
	TcpPacket makePacket() {
		int len = getMaxInLength();
		mOutSeqLastAck = mInSeq+len;
		return new TcpPacket( mAddr, mOutSeq + mOutNextXmit, mInSeq+len, mInBuffer.limit() - len );		
	}
	
	/**
	 * Transmit as many packets as possible to the VPN.
	 */
	public void xmit() {
		if( mOutBuffer.position() - mOutNextXmit <= 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::xmit nothing to send - pos " + mOutBuffer.position() + " xmit - " + mOutNextXmit );
			return;
		}
		
		TcpPacket tp = makePacket();
		
		if( mOutSyn ) {
			// If SYN packet, only output the SYN+ACK
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::xmit sending SYN+ACK" );
			tp.setSynFlag();
			tp.complete();
			mHost.write(tp);
			return;
		}
		// If the only packet is FIN then output it alone
		if( mOutFinSeq == 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::xmit sending FIN" );
			tp.setFinFlag();
			tp.complete();
			mHost.write(tp);
			return;
		}		
		// Otherwise, output everything (except the FIN packet)
		ByteBuffer data = mOutBuffer.asReadOnlyBuffer();
		data.flip();
		data.position(mOutNextXmit);
		
		// Exclude the FIN (if any)
		if( mOutFinSeq != -2 ) data.limit(Math.min(mOutFinSeq, data.limit()));
		
		// Output everything we've got
		while( data.hasRemaining() ) {			
			tp.setData(data);
			if( !data.hasRemaining() ) tp.setPshFlag();
			tp.complete();
			if(VpnNatEngine.sLog) Log.v("AziLink", "Tcp::xmit seq=" + mInSeq + ", off=" + mOutNextXmit + ", len=" + tp.getDataLength() );
			mHost.write(tp);
			mOutNextXmit += tp.getDataLength();
			tp.setSeq(mOutSeq + mOutNextXmit);
		}
	}

	/**
	 * NIO reports the connection is complete.  Figure out whether it was successful and act accordingly.
	 */
	public void onBindComplete(boolean success) {
		if( !success ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "onBindComplete failed" );
			// destroy will issue a RST to the host, indicating "connection refused"
			destroy();
			return;
		}
		mOutSeq = (int)(Math.random() * 4294967295.0);
		mOutSyn = true;
		mInSyn = false;
		mInSeq = (mInSeq+1) & 0xFFFFFFFF;
		mInValid = mInValid.get(1,mInValid.size()+1);
		mInBuffer.position(1);
		mInBuffer.compact();
		mBindComplete = true;
		mOutBuffer.put((byte)0);
		mRetries = 0;
		setDestroyTimer(mTimeIdle);
		setRetransmitTimer(mTimeRetransmit);
		if(VpnNatEngine.sLog) Log.v("AziLink", "onBindComplete calling tcp to xmit SYN" );
		// xmit will transmit a syn packet to the host
		xmit();
	}

	/**
	 * Returns pending data for the TCP stream. 
	 * @param data data to be sent over NIO
	 */
	public void read(ByteBuffer data) {
		if( mInSyn ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::read rejecting because SYN is still up" );
			return;
		}
		if( mInFinSeq == -1 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::read rejecting because FIN is completed" );
			return;
		}
		int len = getMaxInLength();
		if( mInFinSeq != -2 ) len --;
		mInBuffer.rewind();
		mInBuffer.limit(len);
		int maxlen = Math.min(mInBuffer.remaining(), data.remaining());
		data.put(mInBuffer.array(), mInBuffer.position(), maxlen);
		mInBuffer.position(mInBuffer.position() + maxlen);
		
		int bytesWritten = mInBuffer.position();
		mInBuffer.compact();
		mInValid = mInValid.get(bytesWritten, mInValid.size() + bytesWritten);
		mInSeq = (mInSeq + bytesWritten) & 0xFFFFFFFF;
		if( mInFinSeq != -2 ) mInFinSeq -= bytesWritten;
		
		if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::read outputs " + bytesWritten + " bytes bitmap=" + mInValid.toString());
		
		boolean wantClose = false;
		
		if( mInFinSeq == 0 ) {
			// Pseudo-send it
			if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::read reached FIN from host, so showing close");
			mInFinSeq = -1;
			mInSeq = (mInSeq+1) & 0xFFFFFFFF;
			mInValid.clear();
			wantClose = true;			
		}
		
		// Send an acknowledge packet
		TcpPacket tp = makePacket();
		tp.complete();
		mHost.write(tp);
		
		if( wantClose ) {
			mCallback.onClosed();
		}
			
		if( mInFinSeq == -1 && mOutFinSeq == -1 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::read decided to destroy the link");
			setRetransmitTimer(0);
			setDestroyTimer(mDeadTime);
		}				
	}

	/**
	 * Adds data read out of the TCP NIO stream.  May not pull the entire buffer -- check data's length
	 * after this call.
	 * @param data data received from NIO
	 */
	public void write(ByteBuffer data) {
		if( !mBindComplete ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::write aborted bind not complete");
			return;
		}
		if( mOutFinSeq != -2 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::write aborted since output buffer is closing");
			return;
		}
		int maxlen = Math.min(data.remaining(), mOutBuffer.remaining());
		mOutBuffer.put(data.array(), data.position(), maxlen);
		data.position(data.position()+maxlen);
		
		if(VpnNatEngine.sLog) Log.v("AziLink","Tcp::write added new data");
		setDestroyTimer(mTimeIdle);
		if( mOutBuffer.position() == 0 ) {
			mRetries = 0;
			setRetransmitTimer(mTimeRetransmit);
		}
		xmit();
	}

	/**
	 * Return the src/dest ip/port that this link represents
	 */
	public TcpKey getKey() {
		return mAddr;
	}
	
	/**
	 * Called internally to reset the connection teardown timer.
	 * @param ms time until teardown
	 */
	void setDestroyTimer(int ms) {
		if(VpnNatEngine.sLog) Log.v("AziLink", "Destroy timer for " + ms );
		mTimerDestroyKey = mTimer.changeTimer(mTimerDestroyKey, ms, mTimerDestroyCallback); 
	}
	
	/**
	 * Called internally to reset the retransmission timer.
	 * @param ms time until retransmit
	 */
	void setRetransmitTimer(int ms) {
		if( ms == 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Retransmit disabled");
			mTimer.killTimer(mTimerRetransmitKey, mTimerRetransmitCallback);
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink","Retransmit " + ms );
			mTimerRetransmitKey = mTimer.changeTimer(mTimerRetransmitKey, ms, mTimerRetransmitCallback);
		}
	}
	
	/**
	 * Callback issued when the timer for connection teardown has expired,
	 */
	TimerCallback mTimerDestroyCallback = new TimerCallback() {
		public void onTimer() {
			if(VpnNatEngine.sLog) Log.v("AziLink","onTimerDestroy");
			destroy();
		}
	};
	
	/**
	 * Callback issued when it's time for a retransmission.
	 */
	TimerCallback mTimerRetransmitCallback = new TimerCallback() {
		public void onTimer() {
			mRetries++;
			if( mRetries >= mMaxRetries ) {
				if(VpnNatEngine.sLog) Log.v("AziLink","onTimerRetransmit ran out of retries" );
				destroy();
			} else {
				if(VpnNatEngine.sLog) Log.v("AziLink","onTimerRetransmit is at retry count " + mRetries );
				setRetransmitTimer(mTimeRetransmit);
				mOutNextXmit = 0;
				xmit();
			}
		}
	};
}
