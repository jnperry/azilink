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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Set;
import android.os.SystemClock;
import android.util.Log;

/**
 * This is the main worker thread for the NAT engine.  It blocks in a standard select() loop
 * over all network connections, and also dispatches timer events.
 * 
 * @author Jim Perry
 *
 */
public class SelectThread implements Runnable {
	/** The Java NIO selector */
	Selector mSelector;
	/** Used to tell the select thread that it's time to terminate. */
	boolean mDie;
	/** Worker thread */
	Thread mThread = new Thread( this );
	/** Pointer to the actual NAT engine; used for callbacks. */
	VpnNatEngine mEngine;
	
	/**
	 * Construct the select thread.  Does not start the thread.
	 * @param engine pointer to the NAT engine
	 */
	SelectThread( VpnNatEngine engine ) {
		mEngine = engine;
	}
	
	/**
	 * Start the worker thread.
	 * @throws IOException
	 */
	void start() throws IOException {
		mSelector = SelectorProvider.provider().openSelector();
		mDie = false;
		mThread.start();
	}
	
	/**
	 * Stop the worker thread, and wait for it to terminate.
	 * @throws InterruptedException
	 */
	void stop() throws InterruptedException {
		mDie = true;
		mSelector.wakeup();					
		mThread.join();
		try {
			mSelector.close();
		} catch (IOException e) {
		}
		mSelector = null;
	}	
	
	/**
	 * Main select loop.
	 */
	public void run() {
		try {
			// Attach the debugger to the new thread and automatically breakpoint:
			//   android.os.Debug.waitForDebugger();
			
			// Setup the listener socket (VPN)
			ServerSocketChannel fdCmd = ServerSocketChannel.open();
			fdCmd.socket().setReuseAddress( true );			
			fdCmd.configureBlocking( false );
			fdCmd.socket().bind( new InetSocketAddress( InetAddress.getByAddress( new byte[] { 127,0,0,1 } ), 41927 ) );
			fdCmd.register( mSelector, SelectionKey.OP_ACCEPT, new SocketHandler( fdCmd ) {
				/**
				 * Notify the VPN engine that a new VPN connection has been made.
				 */
				public void onAccept( SelectionKey k ) {
					mEngine.acceptServerLink( (ServerSocketChannel) k.channel() );
				}
			});
						
			// Main select loop
			for( ;; ) {
				// Ask the timer class when the next timeout is due to be dispatched.
				long nextTimeout = mEngine.mTimers.nextTimer();
				
				if( nextTimeout >= 0 ) {
					// There's a pending timeout, so select() over that interval
					if(VpnNatEngine.sLog) Log.v("AziLink", "SelectThread timeout: " + (nextTimeout - SystemClock.elapsedRealtime()));
					mSelector.select( Math.max(nextTimeout - SystemClock.elapsedRealtime(), 1) );
				} else {
					// No pending timeouts, so just select() until something exciting happens.
					if(VpnNatEngine.sLog) Log.v("AziLink", "SelectThread no timeout" );
					mSelector.select();
				}
				
				// Main thread is asking is to terminate.
				if( mDie ) {
					break;
				}
				
				// If a timeout has occured, then issue callbacks to the relevent classes.
				if( SystemClock.elapsedRealtime() >= nextTimeout ) {
					mEngine.mTimers.runTimers();					
				}
				
				// Lock to prevent the UI from screwing with the selector table (does it still actually do this?)
				synchronized(this) {
					// Iterate over all connections that have some sort of activity
					Set<SelectionKey> rdy = mSelector.selectedKeys();
					Iterator<SelectionKey> i = rdy.iterator();
					while( i.hasNext() ) {
						SelectionKey k = i.next();
						i.remove();
						if( !k.isValid() ) continue;		// Key could be cancelled
						SocketHandler h = (SocketHandler) k.attachment();
						int op = k.readyOps();
						try {
							// Figure out what happens and dispatch it to the thread.  Don't change
							// the order unless you know what you're doing!
							if( (op & SelectionKey.OP_WRITE) != 0 ) h.onWrite( k );
							if( (op & SelectionKey.OP_READ) != 0 ) h.onRead( k );							
							if( (op & SelectionKey.OP_ACCEPT) != 0 ) h.onAccept( k );
							if( (op & SelectionKey.OP_CONNECT) != 0 ) h.onConnect( k );						
						} catch( IOException e ) {
							try {
								k.channel().close();
							} catch( IOException e2 ) {								
							}
						}
					}
				}
			}
		} catch (IOException e) {
			if(VpnNatEngine.sLog) Log.v("AziLink", "Terminating due to exception" );
			mEngine.selectError( e.toString() );
		}
		// Thread is terminating.  Close all connections!
		Set<SelectionKey> allKeys = mSelector.keys();
		Iterator<SelectionKey> i = allKeys.iterator();
		while( i.hasNext() ) {
			SelectionKey k = i.next();
			try {
				k.channel().close();
			} catch (IOException e ) {}
		}
	}
}
