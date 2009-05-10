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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

import android.os.SystemClock;

/**
 * Queue of timers that the select thread will automatically dispatch.  When a timer expires, the OnTimer
 * callback will be called.
 * 
 * @author Jim Perry
 *
 */
public class TimerQueue {
	/**
	 * Map of all timers, indexed by the expiration time.
	 */
	TreeMap< Long, LinkedList< TimerCallback > > mTimers = new TreeMap< Long, LinkedList< TimerCallback > >();  
	
	/**
	 * Add a timer to the queue.
	 * 
	 * @param ms ms until timer expires.
	 * @param cb callback to issue when timer expires
	 * @return key that can be used to quickly alter the timer
	 */
	long addTimer( int ms, TimerCallback cb ) {
		long fullTime = ms + SystemClock.elapsedRealtime();
		LinkedList< TimerCallback > entries = mTimers.get( fullTime );
		if( entries == null ) {
			entries = new LinkedList< TimerCallback >();
			entries.add( cb );
			mTimers.put( fullTime, entries );
		} else {
			entries.add( cb );
		}
		return fullTime;
	}
	
	/**
	 * Change when a timer entry expires.
	 * 
	 * @param oldKey key for old timer
	 * @param ms ms until timer expires
	 * @param cb callback for this timer
	 * @return new key for the timer
	 */
	long changeTimer( long oldKey, int ms, TimerCallback cb ) {
		killTimer( oldKey, cb );
		return addTimer( ms, cb );
	}
	
	/**
	 * Remove a timer from the queue.
	 * 
	 * @param key key for the timer
	 * @param cb callback that was associated w/ this timer
	 */
	void killTimer( long key, TimerCallback cb ) {
		LinkedList< TimerCallback > entries = mTimers.get( key );
		if( entries == null ) return;		
		Iterator< TimerCallback > i = entries.iterator();
		while( i.hasNext() ) {
			TimerCallback tcb = i.next();
			if( tcb == cb ) {
				i.remove();
				if( entries.isEmpty() ) {
					mTimers.remove( key );
				}
				return;
			}
		}
	}
	
	/**
	 * Run all timers that have expired.
	 */
	void runTimers() {
		// Read the timers off into a linked list first since the callback could start a new timer and 
		// we don't want to recurse.
		LinkedList< TimerCallback > cb = new LinkedList< TimerCallback >();
		Iterator<Map.Entry<Long, LinkedList<TimerCallback>>> i = mTimers.entrySet().iterator();
		long now = SystemClock.elapsedRealtime();
		
		while( i.hasNext() ) {
			Map.Entry<Long, LinkedList<TimerCallback>> entry = i.next();
			if( entry.getKey() > now ) break;
			Iterator< TimerCallback > j = entry.getValue().iterator();
			while( j.hasNext() ) {
				cb.add( j.next() );
			}
			i.remove();
		}
		
		Iterator< TimerCallback > k = cb.iterator();
		while( k.hasNext() ) {
			k.next().onTimer();
		}
	}
	
	/**
	 * Return the elapsedTime when the next timer will expire.
	 * @return time of next timer expiration
	 */
	long nextTimer() {
		if( mTimers.isEmpty() ) return -1;
		return mTimers.firstKey();
	}
}
