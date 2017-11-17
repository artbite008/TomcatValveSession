package org.apache.catalina.session;

/**
 * State of a session w.r.t. a queue for asynchronous persistence.
 * 
 * A session will go through the lifecyle of null until used in a request, after which it will get QUEUED if determined 
 * to have changes, then into a STORING state while being saved, then to STORED once persisted.
 * 
 * All state transitions should be done while the session is "locked" to prevent other threads from transitioning state.
 * 
 * While QUEUED, a session may still be used and itself be mutated, but should not be queued redundantly.
 * 
 * While STORING, the session should already be in a "locked" state, so any other thread attempting to 
 * queue the session (put the session in the QUEUED state) should block.
 *
 * @author jim631@sina.com
 */
public enum PersistenceQueueState {
	NONE, QUEUED, STORING, STORED;

	public boolean isValidTransition(final PersistenceQueueState toState) {
		final boolean result;
		if (toState == null) {
			// we can go back from QUEUED to null in the case where a queue.offer() fails
			result = this.equals(QUEUED);
		} else {
			// we can always go to the NEXT, or from the end to the beginning
			final int total = PersistenceQueueState.values().length;
			final int legitimateNextOrdinal = (this.ordinal() + 1) % total;
			final int toStateOrdinal = toState.ordinal();
			result = toStateOrdinal == legitimateNextOrdinal;
		}
		return result;
	}
}
