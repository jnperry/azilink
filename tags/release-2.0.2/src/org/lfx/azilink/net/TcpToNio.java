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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import android.util.Log;

/**
 * This class translates between TcpDriver and NIO. 
 * @author Jim Perry
 *
 */
public class TcpToNio extends SocketHandler implements TcpDriverCallback, TimerCallback {
	/** socket for the NIO link */
	SocketChannel mChannel;
	/** select() in SelectThread */
	Selector mSelect;
	/** input buffer (NIO) */
	ByteBuffer mInBuffer = ByteBuffer.allocate( 16 * 1024 );
	/** output buffer (NIO) */
	ByteBuffer mOutBuffer = ByteBuffer.allocate( 16 * 1024 );
	/** Current socket state */
	State mState = State.STATE_NONE;
	/** TCP driver for this connection */
	TcpDriver mTCP;
	/** Selection key for this connection */
	SelectionKey mKey;
	/** TCP engine for all connections */
	TcpEngine mEngine;
	/** Transfer statistics for all links */
	TransferStatistics mStats;
	/** Close the link when the final byte is transmitted? */
	boolean mCloseWhenDoneXmit = false;
	/** Timer key for T-Mobile workaround */
	long mTimerKey;
	/** Where are we connection to? */
	InetSocketAddress mAddr;
	
	enum State {
		/** Socket is not connected */
		STATE_NONE,
		/** Socket is in the process of connecting */
		STATE_CONNECTING,
		/** Connection was accepted, but maybe it's T-Mobile's fake link */
		STATE_CONNECT_MAYBE,
		/** Connection is established */
		STATE_CONNECTED
	};
	
	/**
	 * Construct a new link between the tcp driver and a socket.  Cannot use class until setDriver has been called.
	 * 
	 * @param engine TCP engine
	 * @param select select in SelectThread
	 * @throws IOException
	 */
	TcpToNio(TcpEngine engine, Selector select) throws IOException {
		super( SocketChannel.open() );
		mEngine = engine;
		mStats = mEngine.mEngine;
		mChannel = (SocketChannel) super.mChannel;
		mChannel.configureBlocking(false);
		mChannel.socket().setTcpNoDelay(true);
		mSelect = select;
		mKey = mChannel.register( mSelect, SelectionKey.OP_CONNECT, this );
		mKey.interestOps(0);
	}
	
	/**
	 * Set the TCP driver this connection is linked with
	 * @param tcp tcp driver
	 */
	public void setDriver(TcpDriver tcp) {
		mTCP = tcp;
	}

	/**
	 * TCP driver just received a SYN packet for this link.  Begin the connect process.
	 * @param address where to connect
	 */
	public void onBeginBind(InetSocketAddress address) throws IOException {
		if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onBeginBind");
		if( mState != State.STATE_NONE ) throw new IOException( "onBeginBind in invalid state" );
		mState = State.STATE_CONNECTING;
		mAddr = address;
		mKey = mChannel.register( mSelect, SelectionKey.OP_CONNECT, this );
		mChannel.connect(address);		
	}
	
	/**
	 * NIO just completed the connection.
	 * @param k selection key
	 */
	public void onConnect( SelectionKey k ) throws IOException {
		if( mState != State.STATE_CONNECTING ) throw new IOException( "onConnect in invalid state" );
		boolean success = false;
		mKey.interestOps( SelectionKey.OP_READ );
		
		try {
			success = mChannel.finishConnect();
		} catch (IOException e) {
		}
		if( success ) {
			boolean doWorkaround = false;
			if( mEngine.mEngine.mTMobileWorkaround ) {
				// Has this connection been accepted in the past?  If so, bypass the workaround.
				doWorkaround = !mEngine.mTM.check(mAddr);
			}
			if( !doWorkaround ) {
				// TMobile wrokaround is not active -> immediately accept
				mState = State.STATE_CONNECTED;
				if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onConnect " + success);
				mTCP.onBindComplete(success);
			} else {
				// TMobile workaround active -> delay the accept for nn seconds or until data received
				mState = State.STATE_CONNECT_MAYBE;
				if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onConnect tmobile workaround" );
				mTimerKey = mEngine.mEngine.mTimers.addTimer(mEngine.mEngine.mTMobileWorkaroundTimeout, this);
				return;
			}
		} else {			
			// Connection failed -> report to tcp driver
			mState = State.STATE_NONE;
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onConnect " + success);
			mTCP.onBindComplete(success);
		}
		
	}
	
	/**
	 * New data from NIO, so pass it to the tcp driver
	 * @param k selection key
	 */
	public void onRead( SelectionKey k ) {
		k.interestOps(k.interestOps() & ~SelectionKey.OP_READ);
		if( mState == State.STATE_CONNECT_MAYBE ) {
			// Is there actually any data?
			mInBuffer.limit(1);
			try {
				int len = mChannel.read(mInBuffer);
				if( len > 0 ) {
					mStats.addBytes(len,0);
				}
			} catch (IOException e) {}
			if( mInBuffer.position() == 0 ) {
				// 0 byte read indicates that the connection was lost
				mState = State.STATE_NONE;
				if(VpnNatEngine.sLog) Log.v("AziLink","TMobile failed onRead");
				mEngine.mEngine.mTimers.killTimer(mTimerKey, this);
				mTCP.onBindComplete(false);
				return;
			} else {
				// New data means exit T-Mobile workaround state
				mEngine.mTM.mark(mAddr);
				mState = State.STATE_CONNECTED;
				if(VpnNatEngine.sLog) Log.v("AziLink","TMobile passwd onRead");
				mKey.interestOps( SelectionKey.OP_READ );
				mEngine.mEngine.mTimers.killTimer(mTimerKey, this);
				mTCP.onBindComplete(true);
			}
		}
		if( mState != State.STATE_CONNECTED ) return;
		
		mInBuffer.limit(mInBuffer.capacity());
		
		int maxLen = Math.min( mTCP.getWriteAvailableSize() - mInBuffer.position(), mInBuffer.remaining() );
		if( maxLen <= 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Nio::onRead is returning because max read length is 0" );
			return;
		}
		
		mInBuffer.limit( maxLen );
		
		int bytesRead = -1;
		try {
			bytesRead = mChannel.read(mInBuffer);
		} catch (IOException e) {}	
		if( bytesRead < 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onRead lost");
			mState = State.STATE_NONE;
			mTCP.close();
			return;
		}
		mStats.addBytes(bytesRead,0);
		if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onRead pass to driver - maxLen="+maxLen+", bytesRead="+bytesRead+"inpos="+mInBuffer.position());
		mInBuffer.flip();
		mTCP.write( mInBuffer );
		mInBuffer.clear();
		
		if( mTCP.getWriteAvailableSize() != 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onRead remains on");
			k.interestOps(k.interestOps() | SelectionKey.OP_READ);
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onRead disabled - buffer full");
		}
	}
	
	/**
	 * The NIO link can now accept more data.  Transmit anything in our outbound buffer.  If the outbound
	 * buffer is empty, then ask the TCP driver for something. 
	 * @param k selection key
	 */
	public void onWrite( SelectionKey k ) {
		k.interestOps(k.interestOps() & ~SelectionKey.OP_WRITE);
		if( mState != State.STATE_CONNECTED ) return;		
		if( mOutBuffer.position() == 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onWrite called but no pending data");
			return;
		}
		mOutBuffer.flip();
		int bytesWritten = -1;
		try {
			bytesWritten = mChannel.write(mOutBuffer);
		} catch (IOException e) {}
		if( bytesWritten < 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onWrite failed");
			mState = State.STATE_NONE;
			mTCP.destroy();
			return;
		}
		mStats.addBytes(0,bytesWritten);
		mOutBuffer.compact();
		if( mOutBuffer.position() != 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onWrite has more data, staying on");
			k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
		} else if( mTCP.getReadAvailableSize() != 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onWrite recursing into onNewDataAvailable");
			onNewDataAvailable();
		} else if( mCloseWhenDoneXmit ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onWrite closing because xmit is finished");
			mState = State.STATE_NONE;
			mTCP.close();
		}
	}

	/**
	 * TCP driver received end of stream.  Close the link once our output buffer has been finished.
	 */
	public void onClosed() {
		if( mState != State.STATE_CONNECTED ) return;
		
		mCloseWhenDoneXmit = true;
		if( mOutBuffer.position() == 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onClosed is closing immediately since no buffer");
			mState = State.STATE_NONE;
			mTCP.close();			
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onClosed is pending");
		}
	}

	/**
	 * Immediately teardown this link.
	 */
	public void onDestroy() {
		if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onDestroy");
		try {
			mChannel.close();
		} catch (IOException e) {}
		mState = State.STATE_NONE;
		mEngine.mNat.remove(mTCP.getKey());
	}

	/**
	 * New data is available from the tcp driver.  If there's room, accept and transmit it.
	 */
	public void onNewDataAvailable() {
		if( mState != State.STATE_CONNECTED ) {
			if( mTCP.getReadAvailableSize() != 0 ) {
				if(VpnNatEngine.sLog) Log.v("AziLink","onNewData in unconnected state with data -> destroy tcp");
				mTCP.destroy();
			}
			return;	
		}
		if( mOutBuffer.remaining() == 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onNewData, but no data");
			return;
		}
		mTCP.read(mOutBuffer);
		int bytesWritten = -1;
		mOutBuffer.flip();
		try {
			bytesWritten = mChannel.write(mOutBuffer);
		} catch( IOException err ) {}
		if( bytesWritten < 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onNewData failed");
			mTCP.destroy();
			return;
		}
		mStats.addBytes(0,bytesWritten);
		mOutBuffer.compact();
		if( mOutBuffer.position() != 0 ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Nio::onNewData could not write everything");
			mKey.interestOps(mKey.interestOps() | SelectionKey.OP_WRITE);
		} else {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Nio::onNewData wrote all " + bytesWritten + " bytes");
		}
	}

	/**
	 * Receive buffer has room, so re-enable the OP_READ key for select()
	 */
	public void onRequestMoreData() {
		if(VpnNatEngine.sLog) Log.v("AziLink","onRequestMoreData called - turning on OP_READ");
		mKey.interestOps(mKey.interestOps() | SelectionKey.OP_READ );
	}

	/**
	 * Used for the T-Mobile workaround.  If the connection is still active after the timeout, then
	 * it must be a real connection.
	 */
	public void onTimer() {
		if( mState == State.STATE_CONNECT_MAYBE ) {
			if(VpnNatEngine.sLog) Log.v("AziLink","Complete TMOBILE");
			mEngine.mTM.mark(mAddr);
			mState = State.STATE_CONNECTED;
			mTCP.onBindComplete(true);
		}
	}

}
